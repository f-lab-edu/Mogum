# Step 0: mastery-judgment

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §4(카드 — 코스 지워짐 · 사용자가 지운 카드) · §5(숙련도 — 저장하지 않고 계산한다 · 계산) · §8.4(`cards` · `card_concepts` · `reviews` · `concepts`) · §8.7(`CardStatus`)
- `docs/07-testing.md` §1(숙련도 판정 — 같은 복습 기록이면 언제나 같은 판정)
- `docs/ADR.md` — ADR-028 · ADR-031 · ADR-034 · ADR-054 · ADR-155 · ADR-162 · ADR-165
- `docs/GLOSSARY.md` — 숙련도(`Mastery`) · 판정
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 7 · 21 · 22 · 작업 방식(TDD · enum Javadoc)
- `backend/src/main/java/com/mogum/card/` 전체 — 특히 `service/FsrsCalculator.java`(FSRS 라이브러리를 쓰는 유일한 곳) · `domain/Card.java` · `repository/`
- `backend/src/test/java/com/mogum/ArchitectureTest.java`(`FSRS_ONLY_IN_CALCULATOR` · `card` 가 `course` 를 참조하지 않는 규칙)
- `backend/src/test/java/com/mogum/card/` 의 픽스처(`CardFixtures` · `ConceptFixtures` · `CardConceptFixtures` · `ReviewFixtures`)와 `card/service/FsrsCalculatorTest.java`
- FSRS 라이브러리 소스 `io.github.openspacedrepetition.Scheduler#getCardRetrievability(Card, Instant)`(Gradle 캐시의 `fsrs-1.0.0-sources.jar`)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`10-mastery-loop`)는 복습 결과(숙련도)가 다음 강의 목록 · 강의 자료를 바꾸는 고리를 만든다. 이 step 은 그 바탕인 **개념마다의 판정 계산**만 만든다 — 생성 흐름에 넘기는 것은 뒤 step 이다.

**계약 문서는 이 phase 전에 정해 커밋했다(02 §5 · ADR-162). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 판정 enum — `card.domain`

- 판정 셋을 담는 enum 을 `card.domain` 에 둔다 — 잘 앎 · 흔들림 · 기록 없음(GLOSSARY 「판정」 · ADR-031). 코드 이름은 GLOSSARY 에 없으므로 정하고 GLOSSARY 「판정」 줄의 코드 칸에 적는다.
- 상수마다 한국어 Javadoc 한 줄 — 02 §5 표의 「하는 일」(짧게 · 자세히 · 처음 배우는 것으로)을 그대로 따른다.

### 2. 떠올릴 확률 — `FsrsCalculator`

- `FsrsCalculator` 에 카드 하나의 지금 떠올릴 확률을 돌려주는 메서드를 더한다 — 라이브러리의 `Scheduler#getCardRetrievability(card, now)` 를 **그대로** 부른다(ADR-162 · 금지 21). 공식을 다시 쓰지 마라. 라이브러리는 이 클래스 밖에서 쓰지 않는다(`FSRS_ONLY_IN_CALCULATOR`).
- 라이브러리 기본값 스케줄러를 쓴다. 이 계산은 흔들기(fuzz)와 상관없다 — 확인해서 테스트로 고정한다.

### 3. 개념의 판정 — `card.service`

```java
// 이름 · 패키지 안 위치는 정해도 된다. 시그니처의 뜻은 지킨다.
Map<Long, Judgment> judge(long userId, Collection<Long> conceptIds, Instant now);
```

- 받은 개념 ID 마다 판정 하나를 돌려준다(받은 ID 가 모두 키로 있다). 그 사용자의 개념이 아니거나 없는 ID 는 기록 없음이다.
- **센다** — 그 개념에 붙은 그 사용자의 카드 가운데 **복습 기록이 하나라도 있는 것**. 상태와 상관없다 — `COURSE_DELETED` 도 같은 무게로 센다(ADR-165 · ADR-054). 학습 완료만 하고 복습하지 않은 카드는 세지 않는다. 사용자가 지운 카드는 행이 없으니 저절로 빠진다(ADR-155). 「복습 기록이 있다」를 `reviews` 행의 존재로 볼지 `cards.last_reviewed_at` 으로 볼지는 두 칸이 언제 함께 채워지는지 코드에서 확인하고 정한다 — 정한 근거를 summary 에 적는다.
- **카드마다** — 떠올릴 확률(2 의 메서드) · 단단함(`stability`, 일).
- **개념마다** — 센 카드가 없으면 기록 없음. 떠올릴 확률 평균이 **0.9 이상**이고 단단함 평균이 **21일 이상**이면 잘 앎. 나머지는 흔들림(02 §5 · ADR-162). 경계값은 「이상」이다.
- 카드 하나에 개념이 여럿이면 그 카드는 붙은 개념 모두의 평균에 들어간다(ADR-033).
- 0.9 · 21 은 이 계산 클래스의 이름 있는 상수 한 곳에 둔다 — 설정으로 빼지 않는다(쓰면서 고칠 처음 값 — ADR-162).
- 「지금」은 호출자가 넘긴다 — 이 메서드 안에서 시계를 읽지 마라. 같은 기록 · 같은 시각이면 언제나 같은 판정이어야 한다(07 §1 · ADR-031).
- 읽기 전용 트랜잭션이다. 아무것도 저장하지 않는다(금지 7 — 숙련도는 저장하지 않는다).
- 카드 · 개념을 읽는 쿼리는 `card.repository` 에 둔다. 개념 ID 묶음 하나에 한 번(또는 몇 번)의 쿼리로 읽는다 — 개념마다 쿼리를 따로 보내지 마라. 이유: 뒤 step 이 개념 20개쯤을 한 번에 판정한다.
- `card` 는 `course` 를 참조하지 않는다(ArchUnit). 이 계산은 강의 · 코스를 볼 필요가 없다.

### 4. 시험

- `FsrsCalculatorTest` — 떠올릴 확률이 라이브러리 `getCardRetrievability` 값과 같다 · 복습 직후 1에 가깝다 · 시간이 지나면 줄어든다.
- 계산 단위 시험(Fake 또는 순수 계산 분리 — TEST_GUIDE 를 따른다) — 경계: 확률 평균 0.9 · 단단함 평균 21일이면 잘 앎, 하나라도 모자라면 흔들림 · 센 카드 없으면 기록 없음 · 여러 카드의 평균 · 「이 숙련도면 짧게」(잘 앎)를 시험 이름으로 드러낸다(07 §1).
- 통합(`@IntegrationTest` — 실제 DB):
    - `COURSE_DELETED` 카드가 세어진다 · 복습하지 않은 카드는 세지 않는다 · 남의 카드 · 남의 개념은 판정에 들어가지 않는다(남의 개념 ID 는 기록 없음).
    - 개념이 둘 붙은 카드가 두 개념 모두에 들어간다.
    - **같은 기록 · 같은 시각으로 두 번 부르면 같은 판정**이고, 부른 뒤 카드 · 복습 기록 · `updated_at` 이 그대로다.

### 5. 문서 — 구현으로 정해진 사실만

- `docs/02-domain-data.md` §5 「계산」 아래에 구현 줄 하나 — 클래스 · 메서드 이름, 「복습 기록이 있다」를 무엇으로 보는지, 상수의 자리.
- `docs/GLOSSARY.md` 「판정」 줄의 코드 칸.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.card.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 떠올릴 확률이 라이브러리 계산 그대로이고, 라이브러리가 `FsrsCalculator` 밖에 없는가?
   - 코스 지워짐 포함 · 복습 안 한 카드 제외 · 0.9 / 21 「이상」 이 02 §5 그대로인가?
   - 아무것도 저장하지 않고, 시각을 인자로 받는가? `card` 가 `course` 를 참조하지 않는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · enum Javadoc · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/10-mastery-loop/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 판정 enum 이름과 상수 · 판정 메서드의 클래스 · 시그니처 · 「복습 기록이 있다」의 기준과 근거 · `FsrsCalculator` 에 더한 메서드 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 판정을 생성 흐름(`generation`) · 프롬프트에 넘기지 마라. 이유: step 2 ~ 4 의 범위다.
- 개념 사전 검색(유사도 쿼리)을 만들지 마라. 이유: step 1 의 범위다.
- 숙련도 · 판정을 테이블 · 칸 · 캐시에 저장하지 마라. 이유: 금지 7 · 22 — 조회 때 계산한다.
- 떠올릴 확률 공식을 직접 쓰지 마라. 이유: 금지 21 · ADR-162 — 복습 일정과 숙련도가 같은 라이브러리 공식이어야 한다.
- 0.9 · 21 을 설정(`application.yml`)으로 빼지 마라. 이유: 처음 값을 한 곳에 두면 충분하다 — 요청받지 않은 설정 가능성이다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
