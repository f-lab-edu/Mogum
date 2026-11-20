# Step 13: sonar-fixes

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `backend/src/main/java/com/mogum/generation/ChatCall.java` 와 그 테스트(`ChatCallTest` · `ChatCallIntegrationTest`)
- `backend/src/main/java/com/mogum/generation/TokenPrices.java` 와 그 테스트
- `docs/03-generation.md` §6 · `docs/02-domain-data.md` §8.7 `llm_calls.outcome` — 해석 실패가 호출 기록의 `INVALID_OUTPUT` 이 된다
- `AGENTS.md` — 금지 14 · 작업 방식(TDD)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

PR #32 의 SonarCloud 품질 게이트가 새 코드의 신뢰성 등급 C(필요 A 이상)로 실패했다. Sonar 가 잡은 이슈는 둘이다. 둘 다 동작을 바꾸지 않고 고친다.

## 작업

### 1. `ChatCall.java:69` — `java:S2789`(MAJOR) 「Optional 을 null 과 비교하지 마라」

지금 `parsed = parser.apply(...)` 뒤에 `if (parsed == null || parsed.isEmpty())` 로 해석 함수가 `null` 을 돌려준 경우까지 막는다. `Optional` 변수를 `null` 과 비교하지 않도록 바꾼다 — 예: 해석 함수의 반환을 `Optional.ofNullable(…)` 로 받아 펼친 뒤 `isEmpty()` 만 본다.

- **동작은 그대로다** — 해석 함수가 빈 값 · `null` 을 돌려주거나 예외를 던지면 마지막 호출의 기록 결과는 `INVALID_OUTPUT` 이고 그 시도는 `INVALID_OUTPUT` 실패다. 해석 예외의 메시지를 남기지 않는 것도 그대로다(금지 26).
- 해석 함수가 `null` 을 돌려주는 경우를 시험으로 확인한다(없으면 더한다).

### 2. `TokenPrices.java:18` — `java:S2184`(MINOR) 「int 빼기 결과를 long 으로 넘기기 전에 넘침」

`usage.inputTokens() - cached` 를 `int` 로 뺀 뒤 `BigDecimal.valueOf(long)` 에 넘긴다. 빼기 전에 한쪽을 `long` 으로 바꾼다. 계산 결과는 그대로다 — 지금 비용 시험이 그대로 통과한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.generation.*'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `Optional` 을 `null` 과 비교하는 코드가 `ChatCall` 에 남지 않았는가? 해석 실패의 호출 기록 · 작업 실패가 그대로인가?
   - `TokenPrices` 의 빼기가 `long` 으로 이루어지는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/7-course-planning/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 고친 두 곳 · 더한 시험 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 이 두 곳 밖의 코드를 고치지 마라. 이유: Sonar 가 짚은 두 이슈만 고치는 step 이다. 특히 선언의 줄 배치를 바꾸지 마라 — 줄 배치 정리는 별도 PR #33 에 있다.
- 실패 갈래 · 다시 시도 · 비용 계산식을 바꾸지 마라. 이유: 동작 변경이 없는 수정이다.
- 문서(`docs/`)와 화면(`frontend/`)을 고치지 마라. 이유: 바뀌는 사실이 없다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
