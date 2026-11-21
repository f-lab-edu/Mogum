# Step 10: generation-fixes

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/04-jobs-and-cost.md` §4.6(실패 · 다시 시도) · §9.1(로그 — 수준 · 지금 있는 작업 줄 · `job save failed` 의 안전한 스택) · §10
- `docs/05-api.md` §1.2 — **`REPLAN_IN_PROGRESS` 줄(이 step 의 계약이다 — 브랜치 리뷰에서 `ROADMAP_REPLAN_IN_PROGRESS` 를 바꿨다)**
- `docs/ADR.md` — ADR-127 · ADR-163 · ADR-182
- `AGENTS.md` — 금지 3 · 12 · 25 · 26 · 작업 방식(TDD · enum Javadoc)
- 이 phase 의 앞 step 이 만든 것: `backend/src/main/java/com/mogum/generation/NewCourseGenerator.java` · `NewRoadmapGenerator.java` · `PlanRoadmapCourseGenerator.java` · `ReplanCourseGenerator.java` · `ReplanRoadmapGenerator.java` · `ChatCall.java` · `generation/plan/PlanJson.java` · `config/GenerationConfiguration.java` · `config/LlmProperties.java` · `config/LlmConfiguration.java` · `common/api/ErrorCode.java` · `course/service/CourseCommandService.java` · `job/PlanRoadmapCourseIntakeHandler.java` 와 각 테스트
- 로그 본보기: `backend/src/main/java/com/mogum/worker/JobRunner.java` 의 `job save failed`(원래 메시지 · cause · suppressed 없이 타입 이름과 스택 위치만) · 그 테스트 `backend/src/test/java/com/mogum/worker/JobRunnerIntegrationTest.java`(`OutputCaptureExtension`)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

phase 브랜치 리뷰(`/review`)에서 나온 수정 넷이다. 모두 동작의 큰 틀은 그대로 두고 빈틈만 고친다.

**계약 문서(05 §1.2 의 오류 code 이름 포함)는 이 step 전에 고쳐 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 생성 처리기의 예상 밖 예외를 로그로 남긴다

지금 생성 처리기 다섯(`NewCourseGenerator:42` · `NewRoadmapGenerator:42` · `PlanRoadmapCourseGenerator:36` · `ReplanCourseGenerator:73` · `ReplanRoadmapGenerator:65`)의 `generate` 가 `catch (RuntimeException ignored)` 로 재료 읽기 · 프롬프트 리소스 누락 같은 **우리 버그까지** `PROVIDER_UNAVAILABLE` 로 바꾸고 아무 흔적도 남기지 않는다. 운영자는 버그를 프로바이더 장애로 본다.

- 돌려주는 실패 갈래는 그대로 `PROVIDER_UNAVAILABLE` 이다(일시적인 DB 오류도 여기로 온다 — 다시 시도가 맞다). `generate` 는 계속 예외를 던지지 않는다 — 던지면 작업이 「만드는 중」에 남아 시한마다 다시 돈다.
- 잡을 때 **`ERROR` 한 줄**을 남긴다 — 메시지 `job generate failed`. `JobRunner` 의 `job save failed` 와 같은 방식으로 **원래 메시지 · cause · suppressed 를 넘기지 않고 예외 타입 이름과 스택 위치만** 싣는다(금지 26 — 예외 메시지에 입력 · 출력 원문이 섞일 수 있다). `jobId` · `userId` 는 워커가 이미 MDC 에 넣었다 — 따로 넣지 않는다.
- 다섯 곳에 같은 코드를 복사하지 말고 한 곳에 둔다(방법은 정한다).
- `ChatCall` 안의 `catch` 들(호출 기록 쓰기 실패 · 프로바이더 예외 · 해석 예외)은 이번 범위가 아니다 — 프로바이더 예외는 호출 기록에 `PROVIDER_ERROR` 로 남고, 해석 실패는 형식 불합격이다.
- 한 작업에서 다시 시도마다 한 줄씩(최대 정한 횟수 + 1) 찍히는 것은 받아들인다 — 1초마다 반복되는 루프가 아니다.

### 2. 채팅 설정 `planning` 이 없으면 기동을 거부한다

`config/GenerationConfiguration.java:23` 등은 `llmProperties.chats().get("planning")` 이 `null` 이어도 그대로 생성 처리기에 넘긴다. 그러면 모든 작업이 조용히 실패한다. 조립할 때(또는 `LlmProperties` 검사에서) 없으면 설정 바인딩 실패처럼 **기동을 거부**한다. 이름 `planning` 을 문자열로 여러 번 쓰지 말고 한 곳에 둔다.

### 3. `PlanJson` — 닫히지 않은 괄호 하나에 정상 답을 버리지 않는다

`generation/plan/PlanJson.java:27` — 앞뒤 설명 문장 속 괄호가 닫히지 않으면(`endOfValue` 가 -1) 이미 계획 JSON 을 찾았어도 `Optional.empty()` 를 돌려준다. 예: 뒤 설명 「(참고: {」 · 앞 설명 「[참고 아래 JSON」. 정상 답이 형식 불합격이 되어 다시 시도 비용이 든다.

- 닫히지 않은 괄호는 **그 자리만 건너뛰고 다음 글자부터 계속 찾는다.**
- 계획 JSON 이 둘이면 불합격인 지금 규칙은 그대로다.
- 위 두 예와 「닫히지 않은 괄호만 있고 계획 JSON 이 없음」을 테스트로 더한다.

### 4. 오류 code 이름 — `ROADMAP_REPLAN_IN_PROGRESS` → `REPLAN_IN_PROGRESS`

코스의 「이대로 시작」(ADR-182)도 이 code 를 쓰므로 이름을 코스 · 로드맵 공통으로 바꾼다(05 §1.2). 상태 코드(409) · 문구(「다시 짜는 중에는 시작할 수 없어요」)는 그대로다. `ErrorCode` 의 Javadoc 을 두 경우에 맞게 고치고, 쓰는 곳(`CourseCommandService` · `PlanRoadmapCourseIntakeHandler`)과 테스트를 모두 바꾼다. 화면(`frontend/`)은 이 code 로 분기하지 않는다 — 확인만 한다.

### 5. 테스트

- 1 — 생성 처리기마다(또는 공통 자리 하나와 처리기 연결 하나씩) 재료 준비에서 예외가 나면 `PROVIDER_UNAVAILABLE` 을 돌려주고 `ERROR` 줄 `job generate failed` 가 찍힌다. 예외 메시지에 넣은 **표식 문자열이 출력에 없다**(`OutputCaptureExtension`). 정상 흐름 · 프로바이더 실패에서는 이 줄이 찍히지 않는다.
- 2 — `planning` 묶음이 없는 설정으로 컨텍스트가 뜨지 않는다.
- 3 — 위 세 경우.
- 4 — 바뀐 이름으로 기존 시험이 그대로 통과한다(HTTP 응답의 `code` 포함).

### 6. 문서 — 구현으로 정해진 사실만

- `docs/04-jobs-and-cost.md` §9.1 — `job generate failed` 줄(수준 · 언제 · 키 · 스택에 싣는 것)을 지금 있는 작업 줄들 옆에 적는다. 테스트가 실제로 찍은 줄을 옮긴다 — 키 순서는 정해지지 않았다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.generation.*' --tests 'com.mogum.config.*' --tests 'com.mogum.course.*' --tests 'com.mogum.job.*' --tests 'com.mogum.common.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 생성 처리기가 여전히 예외를 던지지 않고, 예상 밖 예외에 `job generate failed` 한 줄을 원문 없이 남기는가? 같은 코드가 다섯 곳에 복사되지 않았는가?
   - `planning` 설정이 없으면 기동을 거부하는가?
   - `PlanJson` 이 닫히지 않은 괄호를 건너뛰고, 계획 JSON 둘이면 여전히 불합격인가?
   - `ROADMAP_REPLAN_IN_PROGRESS` 가 코드 · 테스트 어디에도 남지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/7-course-planning/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 로그를 남기는 자리 · 기동 검사 위치 · 바뀐 code · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 실패 갈래(`PROVIDER_UNAVAILABLE`)나 다시 시도 규칙을 바꾸지 마라. 이유: 이 step 은 관측만 더한다(ADR-127 · ADR-163).
- `ChatCall` 의 비용 계산 · 단가 처리를 바꾸지 마라. 이유: 단가는 기동 검사가 지킨다(`LlmProperties`) — 브랜치 리뷰에서 고치지 않기로 했다.
- 로그 줄에 예외 메시지 · cause · suppressed · 입력 · 프롬프트 · 응답 원문을 싣지 마라. 이유: 금지 26.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 화면 코드(`frontend/`)를 고치지 마라. 이유: 화면 수정은 step 11 이다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: 이 step 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
