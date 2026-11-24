# Step 0: fsrs-scheduler

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §3(학습과 복습 — 오늘 복습할 카드 · 같은 날 다시 보기 · 하루의 경계) · §8.4(`cards` 의 FSRS 칸) · §8.7(`cards.fsrs_state` · `reviews.rating`)
- `docs/ADR.md` — ADR-017 · ADR-155 · ADR-159 · ADR-164 · ADR-170 · ADR-188
- `docs/04-jobs-and-cost.md` §2(코드 구조 · 패키지 경계)
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 2 · 17 · 21 · 22 · 24 · 28
- `backend/build.gradle` — FSRS 라이브러리가 지금 `testImplementation` 이다
- `backend/src/test/java/com/mogum/learning/FsrsLibraryLearningTest.java` — 라이브러리 동작을 고정한 학습 테스트
- `backend/src/main/resources/application.yml` — `spring.jpa.properties.hibernate.type.json_format_mapper: jackson3`
- `backend/src/main/java/com/mogum/card/domain/Card.java` · `FsrsState.java` · `Rating.java` · `Review.java`
- `backend/src/main/java/com/mogum/common/time/SeoulClock.java` · `backend/src/main/java/com/mogum/config/ClockConfiguration.java` · `backend/src/test/java/com/mogum/config/ClockConfigurationTest.java`
- `backend/src/test/java/com/mogum/ArchitectureTest.java` — `OPENAI_SDK_ONLY_IN_LLM_ADAPTER` 규칙의 모양

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

복습 TASK 의 첫 step 이다. 뒤 step 이 쓸 바탕 셋을 만든다 — ① FSRS 라이브러리를 운영 코드에서 쓰게 하고 한 곳에 가둔다, ② 카드에 평가를 적용하고 평가 버튼 넷의 다음 간격을 미리 계산하는 한 곳, ③ 하루의 경계(한국 시간 새벽 4시). API · 화면은 뒤 step 의 범위다.

**계약 문서(ADR-188 · 02 §3)는 이 phase 전에 정해 커밋했다. 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. FSRS 라이브러리를 운영 의존성으로

- `backend/build.gradle` 의 `io.github.open-spaced-repetition:fsrs:1.0.0` 을 `testImplementation` 에서 `implementation` 으로 옮긴다. 버전은 1.0.0 그대로 둔다.
- 이 라이브러리는 Jackson 2(`com.fasterxml.jackson.core:jackson-databind`)를 끌고 온다 — 지금까지는 테스트 클래스패스에만 있었고 이제 운영에도 들어온다. **운영과 테스트가 같은 JSON 구현을 쓰는지 확인하고, 다르면 설정으로 고정한다**:
    - Hibernate 의 JSON 칸 — 이미 `json_format_mapper: jackson3` 로 고정했다. 그대로 지켜지는지 기존 테스트(`LessonRepositoryTest` 의 매퍼 확인)로 본다.
    - HTTP 요청 · 응답 JSON — Spring Boot 4.1.1 이 Jackson 2 와 3 이 함께 있을 때 어느 쪽 메시지 변환기를 고르는지 소스 · 실행으로 확인한다. Jackson 3 가 아니면 설정으로 Jackson 3 에 고정한다. 고른 쪽을 확인하는 테스트를 하나 둔다(예: 컨텍스트에서 쓰이는 변환기의 타입).
    - `./backend/gradlew -p backend dependencies --configuration runtimeClasspath` 로 운영 클래스패스에 새로 들어온 의존성을 보고 summary 에 적는다.

### 2. FSRS 계산 한 곳 — `card` 패키지

`card.service` 에 FSRS 계산 클래스 하나를 둔다(이름은 정한다 — Spring 의 스케줄링과 헷갈리지 않는 이름. 예: `FsrsCalculator`). 시그니처 수준:

```java
// 평가 하나를 적용한 뒤의 FSRS 칸 값 — 라이브러리 타입을 밖으로 내보내지 않는다
FsrsResult review(Card card, Rating rating, Instant reviewedAt);
// 평가 넷 각각의 다음 간격(평가했을 때의 다음 볼 때 − now)
Map<Rating, Duration> preview(Card card, Instant now);
```

- 반환 타입(`FsrsResult` 등)은 우리 record 로 둔다 — `fsrs_state` · `fsrs_step` · `stability` · `difficulty` · `due_at` · `last_reviewed_at` 여섯 칸(ADR-159). 라이브러리의 `Card` · `Rating` · `State` · `Scheduler` 가 이 클래스 밖으로 새지 않는다.
- `Card` 엔티티에 평가 결과를 반영하는 메서드를 둔다(예: `card.applyReview(FsrsResult)`) — 상태 바꾸기는 엔티티에 둔다. 서비스가 칸을 하나씩 넣지 않는다.
- **간격은 라이브러리 계산 그대로다**(금지 21 · ADR-164). 학습 단계 · 다시 배우는 단계 · 매개변수 · 목표 기억률 · 최대 간격은 라이브러리 기본값을 쓴다(1분 · 10분 / 10분 — 02 §3). 값을 바꾸지 마라.
- **흔들기(fuzz)는 켜 둔 채로, 카드마다 정해진 씨앗으로 흔든다**(ADR-188). 라이브러리 `Scheduler` 는 인스턴스 하나가 `java.util.Random` 하나를 공유해 부를 때마다 다른 값이 나온다 — 그래서 `Scheduler.builder().randomSeedNumber(int)` 로 호출마다 카드의 씨앗을 넣은 스케줄러를 만든다. 씨앗은 그 카드의 **지금 저장된 상태로만** 정한다(예: 카드 ID 와 `last_reviewed_at` 을 섞은 값) — 같은 카드 · 같은 상태면 같은 씨앗, 평가로 상태가 바뀌면 다른 씨앗. 정한 규칙은 클래스 주석 · summary · 아래 6 의 문서에 적는다(ADR-188 이 「씨앗은 복습 TASK 에서 정한다」로 남겼다).
- **불변식: `preview(card, t)` 가 돌려준 평가 X 의 간격 == `review(card, X, t)` 의 `due_at − t`** — 같은 카드 · 같은 시각이면 네 평가 모두 같다. 흔들기가 걸리는 간격(2.5일 이상)에서도 같아야 한다.
- 확인한 라이브러리 함정(1.0.0 소스) — 피하게 짠다:
    - `Card.builder().build()` 는 `cardId` 를 주지 않으면 `Instant.now()` 로 만들고 `Thread.sleep(1)` 한다. `due` 를 주지 않으면 `Instant.now()` 를 넣는다. 늘 둘 다 넣는다. `cardId` 는 `int` 이고 계산에는 쓰이지 않는다(`ReviewLog` 에만 들어간다) — 우리 `long` ID 를 넣다 넘치지 않게 정한다.
    - `reviewCard(card, rating)`(시각 없는 것)는 `Instant.now()` 를 쓴다. 늘 시각을 넘기는 오버로드를 쓴다 — 시각은 호출하는 쪽이 `Clock` 에서 받아 넘긴다.
    - `reviewCard` 는 넘긴 카드를 복사해 쓰므로 원본을 바꾸지 않는다 — `preview` 가 같은 카드로 네 번 불러도 된다.
- 우리 enum ↔ 라이브러리 enum 은 이름이 같다(`LEARNING` · `REVIEW` · `RELEARNING` / `AGAIN` · `HARD` · `GOOD` · `EASY`). 매핑은 이 클래스 안에만 둔다.
- 학습 완료 전 카드(`due_at` 이 비었다)는 계산 대상이 아니다 — 넘어오면 예상 밖(우리 버그)이므로 `IllegalArgumentException` 등으로 바로 실패한다. 조용히 기본값을 넣지 마라.

### 3. 라이브러리를 한 곳에 가둔다 — ArchUnit

`ArchitectureTest` 에 `OPENAI_SDK_ONLY_IN_LLM_ADAPTER` 와 같은 모양으로 규칙을 더한다 — `io.github.openspacedrepetition..` 은 2 의 클래스(또는 그 클래스가 있는 좁은 패키지)에서만 쓴다. 테스트 쪽 `learning.FsrsLibraryLearningTest` 는 학습 테스트라 예외다 — 규칙이 운영 클래스만 보게 하거나 그 테스트를 이름으로 뺀다. 규칙이 실제로 위반을 잡는지 기존 규칙들처럼 `archfixture` 아래 위반 픽스처로 확인한다.

### 4. 하루의 경계 — 새벽 4시

- `common.time.SeoulClock` 의 `today()` · `startOfToday()` · `startOfTomorrow()` 는 지금 **자정** 기준이고 운영 코드에서 쓰는 곳이 없다. 이 서비스의 「오늘」은 하나 — 한국 시간 새벽 4시에 바뀐다(ADR-017 · 금지 17). 세 메서드를 새벽 4시 경계로 바꾼다: `today()` 는 지금이 새벽 4시 전이면 전날, `startOfToday()` 는 그 날의 새벽 4시(Instant), `startOfTomorrow()` 는 다음 날 새벽 4시. 이름을 바꿀 필요가 있으면 바꾸고 쓰는 곳(테스트)을 함께 고친다.
- `CreditLedger` 는 `SeoulClock.ZONE` 만 쓴다 — 바꾸지 마라.
- 테스트 — 03:59:59 KST 는 전날 · 04:00:00 KST 는 그날 · 새벽 1시는 전날 · 경계 Instant 값. 서버 기본 시간대(테스트는 UTC)에 기대지 않는 것을 확인한다.

### 5. 시험

- 2 의 단위 테스트(`card.service` 의 짝 테스트 — 클래스 이름 + `Test`):
    - 새 카드(`LEARNING` · 단계 0): AGAIN → 1분 뒤 · GOOD → 10분 뒤(단계 1) · 단계 1 에서 GOOD → `REVIEW`(날 단위 · `fsrs_step` 비어 있음) · EASY → 바로 `REVIEW`.
    - `REVIEW` 카드에서 AGAIN → `RELEARNING` · 10분 뒤 → 그 뒤 GOOD → `REVIEW`.
    - `preview` == `review` 불변식 — 학습 단계 카드 · 복습 중 카드(간격이 2.5일 이상이 되게 안정도를 크게 둔 카드)에서 네 평가 모두.
    - 같은 카드 · 같은 상태로 두 번 계산하면 같은 값(씨앗이 고정됐다) · 서로 다른 카드는 다른 씨앗(흔들기가 실제로 걸리는지 — 넓은 간격의 카드 여럿에서 값이 모두 같지는 않다).
    - `last_reviewed_at` · `due_at` 이 넘긴 시각 기준이다(시스템 시계를 쓰지 않는다).
    - 학습 완료 전 카드는 바로 실패한다.
- `Card` 의 평가 반영 메서드 — 여섯 칸이 결과대로 바뀐다 · `CHECK ((fsrs_state = 'REVIEW') = (fsrs_step IS NULL))` 에 맞는 값이 저장된다(레포지토리 테스트로 저장 · 재조회 한 번).
- `SeoulClock` · ArchUnit · JSON 구현 확인 — 위 3 · 4 · 1.

### 6. 문서 — 구현으로 정해진 사실만

- `docs/04-jobs-and-cost.md` §2 — FSRS 라이브러리를 둔 곳(클래스)과 ArchUnit 규칙 한 줄, HTTP JSON 이 Jackson 3 인지(고정했으면 설정 이름).
- `docs/02-domain-data.md` §8.4 의 FSRS 라이브러리 줄 — 운영 의존성으로 옮겼다는 사실과 정한 씨앗 규칙 한 줄(규칙 문장은 고치지 마라).

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.card.*' --tests 'com.mogum.common.*' --tests 'com.mogum.config.*' --tests 'com.mogum.learning.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 라이브러리 타입이 계산 클래스 밖으로 새지 않는가? ArchUnit 이 막는가?
   - 학습 단계 · 매개변수가 라이브러리 기본값 그대로인가? 간격을 손으로 고치지 않는가(금지 21)?
   - `preview` 와 `review` 가 같은 카드 · 시각에서 같은 간격을 내는가(ADR-188)?
   - 시스템 시계(`Instant.now()`)가 계산 경로에 없는가?
   - 「오늘」이 새벽 4시 경계인가(금지 17)?
   - 운영 HTTP · Hibernate JSON 이 Jackson 3 인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/9-card-review/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 계산 클래스 이름 · 두 메서드 시그니처 · 결과 record · 엔티티 메서드 · 씨앗 규칙 · `SeoulClock` 메서드 이름과 뜻 · HTTP JSON 구현 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 · 라이브러리로 불변식을 지킬 수 없음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 오늘 복습할 카드 읽기 · 평가 · 카드 지우기 API 와 그 서비스를 만들지 마라. 이유: step 1 · 2 의 범위다.
- FSRS 학습 단계 · 매개변수 · 목표 기억률 · 최대 간격을 바꾸지 마라. 흔들기를 끄지 마라. 이유: ADR-164 · ADR-188 · 금지 21.
- 라이브러리 버전을 바꾸지 마라. 이유: 학습 테스트가 1.0.0 동작을 고정했다.
- 계산 경로에서 `Instant.now()` · 시각 없는 `reviewCard` 를 쓰지 마라. 이유: 테스트의 고정 시계와 어긋나고, 미리 계산과 평가가 달라진다.
- 서버 메모리에 공유 스케줄러 · 난수 상태를 두지 마라. 이유: 공유 난수가 바로 ADR-188 이 막은 것이고, 서버가 여럿이면 쪼개진다(금지 22).
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
