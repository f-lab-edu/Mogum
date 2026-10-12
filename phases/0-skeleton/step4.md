# Step 4: fsrs-check

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 24
- `docs/02-domain-data.md` §4(숙련도 — 라이브러리 · 학습 단계를 비운다) · §6.5(`card_states` · `reviews`) · §8.3(숙련도 SQL 의 `:factor` · `:decay`)
- `docs/ADR.md` ADR-015 · ADR-016
- `docs/PRD.md` — "🅿️ 아직 정하지 않은 값" 표의 "FSRS `R` 공식 상수 · 학습 단계 비활성화 지원 여부" 행
- `docs/07-testing.md` §1.2 의 "`R` 일치" · "FSRS 단계 없음" 행
- 이전 step 산출물: `build.gradle`

## 작업

FSRS Java 라이브러리 `io.github.open-spaced-repetition:fsrs` 를 채택할 수 있는지 확인한다. **운영 코드(`src/main`)는 만들지 않는다** — 라이브러리 동작을 고정하는 학습 테스트와 문서 기록만 한다.

### 1. 의존성

- `io.github.open-spaced-repetition:fsrs` — Maven Central 의 최신 안정판을 **정확한 버전으로 고정**한다.

### 2. 학습 테스트 — `src/test/java/com/mogum/learning/FsrsLibraryLearningTest.java`

- 기본 파라미터에 **학습 단계 · 재학습 단계를 비운** 스케줄러를 만들 수 있다
- 새 카드를 `AGAIN` · `HARD` · `GOOD` · `EASY` 로 각각 평가 → 다음 복습이 평가 시각으로부터 **1일 이상** 뒤
- 복습 상태 카드를 `AGAIN` 으로 평가(재학습) → 다음 복습이 **1일 이상** 뒤
- 라이브러리의 인출확률(`R`) 계산 결과가 공식 `R = (1 + FACTOR · t / S)^DECAY`(`t` · `S` 는 일 단위 — 02 §8.3 의 `:factor` · `:decay`)와 여러 `(S, 정수 경과일)` 조합에서 일치한다. `FACTOR` · `DECAY` 는 채택한 버전의 실제 값을 쓴다
  - **정수 경과일에서만 비교한다.** 라이브러리 `1.0.0` 은 경과일을 정수로 자르고(`ChronoUnit.DAYS`), SQL 은 실제 경과 시간(소수 일)을 쓴다 — 사용자가 소수 일 유지로 결정했다(02 §8.3 · 07 §1.2, 2026-10-12). 소수 경과일에서 둘이 같다고 단언하는 테스트는 두지 않는다. 대신 라이브러리가 같은 날 안의 경과를 정수로 자른다는 사실을 테스트 하나로 고정해, 라이브러리를 올려 이 동작이 바뀌면 깨지게 한다

> **재개 안내** — 이전 시도가 이 step 을 `blocked` 로 멈췄다(소수 경과일 `R` 불일치). 위 결정으로 풀렸다. 이전 시도의 변경(`build.gradle` 의 fsrs 의존성 · `src/test/java/com/mogum/learning/`)이 작업 트리에 남아 있다 — 처음부터 다시 만들지 말고 고쳐서 끝낸다.

### 3. 문서 기록 — 결과만 적는다. 설계는 바꾸지 않는다

- `docs/PRD.md` 🅿️ 표의 해당 행 — 채택 버전 · 학습 단계 비활성화 지원 여부 · `FACTOR` · `DECAY` 값으로 바꾸고 확정했다고 적는다
- `docs/02-domain-data.md` §4 표의 라이브러리 칸에 버전, §8.3 SQL 아래에 `:factor` · `:decay` 값
- `docs/ADR.md` ADR-016 트레이드오프의 "구현 첫 작업에서 확인한다" → 확인 결과(버전 · 지원 여부)

### 4. 저장 필드 확인

- 02 §6.5 의 `card_states` 컬럼만으로 라이브러리 `Card` 를 복원할 수 있는지 확인한다. 모자라거나 남는 필드가 있으면 **summary 에만** 적는다(문서 수정은 `7-review` 에서 한다).

### 안 될 때

학습 단계를 비울 수 없거나, 비운 상태에서 1일 미만 간격이 나오면 → `"status": "blocked"`. `blocked_reason` 에 라이브러리 버전 · 관찰한 간격 · 라이브러리가 제공하는 관련 옵션을 적고 즉시 멈춘다. 대안 선택은 사용자가 한다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 패키지 구조(`core` ← `api` · `worker` · `infra`)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/0-skeleton/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`(채택 버전 · `FACTOR` · `DECAY` · 저장 필드 확인 결과 포함)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 간격을 직접 덮어쓰거나 보정하지 마라(예: `max(1일, 간격)` · 결과 날짜 올림). 이유: 금지 목록 24 — 라이브러리의 `S` 와 실제 간격이 어긋나 숙련도 판정과 `R` 일치 테스트의 전제가 조용히 틀어진다. 안 되면 blocked 로 멈춘다.
- 라이브러리를 포크 · 복사하거나 FSRS 를 직접 구현하지 마라. 이유: 채택 여부와 대안은 사용자가 정한다.
- 운영 코드(`src/main`)를 만들지 마라. 이유: FSRS 어댑터와 `card_states` 는 `7-review` 범위다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
