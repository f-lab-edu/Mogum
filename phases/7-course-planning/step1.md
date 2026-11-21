# Step 1: openai-client

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/ADR.md` — **ADR-181(프로바이더 · 모델 · SDK — 이 step 의 계약이다)** · ADR-118 · ADR-119 · ADR-125 · ADR-126 · ADR-127 · ADR-139 · ADR-163
- `docs/03-generation.md` §1.1(한 시도의 채팅 호출은 1번 + 이어 쓰기) · §4(출력 제한) · §6(호출 기록) · §7
- `docs/04-jobs-and-cost.md` §2.2 · §4.5(시한) · §4.6(실패 · 다시 시도 — 실패 표) · §9.1(로그) · §10(시한 식 · 워커 설정 표)
- `docs/02-domain-data.md` §8.5 `llm_calls` · §8.7 `llm_calls.outcome` · `generation_jobs.error_type`
- `docs/07-testing.md` §2(LLM 스텁)
- `docs/TEST_GUIDE.md` 전체
- `AGENTS.md` — 금지 1 · 2 · 3 · 4 · 10 · 12 · 13 · 14 · 15 · 26 · 명령어
- step 0 이 만든 호출 기록 쓰기 자리(`com.mogum.generation` 아래 — step 0 summary 참고)
- `backend/src/main/java/com/mogum/generation/GenerationOutcome.java` · `JobGenerator.java` · `JobSnapshot.java` · `backend/src/main/java/com/mogum/worker/JobRunner.java`(자동 다시 시도는 워커가 한다) · `backend/src/main/java/com/mogum/config/WorkerProperties.java` · `WorkerConfiguration.java`
- 기동 거부 본보기: `backend/src/main/java/com/mogum/auth/SecurityConfiguration.java`(`Assert.hasText(... "GOOGLE_CLIENT_ID must be set")`) · `backend/build.gradle`(`fakeGoogleCredentials` · `AppImageTest` 제외 · `imageTest` 작업) · `backend/src/test/java/com/mogum/AppImageTest.java`
- 배포 env: `.env.example` · `compose.yaml` · `deploy/mogum.env.example` · `deploy/compose.yaml` · `deploy/README.md` · 그 불변식 시험 `backend/src/test/java/com/mogum/deploy/DeployFilesTest.java`
- `backend/src/test/java/com/mogum/ArchitectureTest.java` — `com.openai` 는 `generation.llm` 에서만

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

강의 목록 · 로드맵 계획을 OpenAI `gpt-5.6-terra` 로 만든다(ADR-181). 이 step 은 **채팅 호출 하나를 보내고, 잘리면 이어 쓰고, 실패를 갈래로 나누고, 호출마다 호출 기록을 남기는 클라이언트**를 만든다. 무엇을 보내는지(프롬프트)는 step 2, 종류별 처리기는 step 3 부터다.

**계약 문서는 이 phase 전에 정해 커밋했다(`fa5af24`). 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 의존성

- `backend/build.gradle` 에 `com.openai:openai-java:4.75.1` 을 `implementation` 으로 더한다. **버전을 올리지 마라** — 옛 1단계 실측에서 아래 설정을 이 버전으로 확인했다(ADR-181). Maven Central 최신은 4.106.0 이지만 그 사이 변경은 확인하지 않았다.
- 실측에서 쓴 사용법(이 버전 기준): `OpenAIOkHttpClient.builder().apiKey(key).maxRetries(0).timeout(...)` · 필요하면 `.baseUrl(...)`, 요청은 `ChatCompletionCreateParams.builder().model(...).maxCompletionTokens(...).reasoningEffort(ReasoningEffort.NONE).addSystemMessage(...).addUserMessage(...)`, 응답은 `client.chat().completions().create(params)` 의 `choices().get(0).finishReason()`(`"stop"` · `"length"` …) · `message().content()` · `usage()`(`promptTokens()` · `completionTokens()`). 이 밖의 API(캐시된 입력 토큰 · 예외 타입 · 요청별 시간 제한 · 어시스턴트 메시지 넣기)는 **이 버전의 소스 JAR 로 확인해서 쓰고**, 확인한 클래스 이름을 summary 에 적는다.

### 2. 두 층으로 나눈다

이름은 예시다. 지킬 것은 경계다.

- **프로바이더 어댑터** — `com.mogum.generation.llm` 안. SDK 를 쓰는 유일한 곳이다(금지 2 · ArchUnit). HTTP 요청 **한 번**을 보내고, 결과를 SDK 타입이 아닌 값으로 돌려준다: 끝남(텍스트) · 잘림(텍스트) · 실패(아래 갈래) + 사용량(입력 · 캐시된 입력 · 출력 토큰 — 모르면 비움). **어댑터 앞에 인터페이스를 두어** step 3 이 테스트에서 이 자리를 LLM 스텁으로 바꿀 수 있게 한다.
- **채팅 호출** — `com.mogum.generation` 안, 프로바이더와 무관. 한 시도의 채팅 호출 1번 + 이어 쓰기를 맡는다.
    - 호출마다(첫 호출 `CHAT` · 이어 쓰기 `CONTINUATION`) step 0 의 쓰기 자리로 **보내기 전에 `start`, 끝나면 `finish`** 한다. 모델 이름 · 토큰 · 비용 · 걸린 시간을 담는다.
    - 잘리면(`length`) 처음부터 다시 요청하지 않고 **잘린 곳에서 이어 쓴다**(금지 13). OpenAI 는 답변 앞부분을 미리 채워 이어 받는 방식이 없으므로, 지금까지의 대화에 받은 앞부분을 어시스턴트 메시지로 넣고 아래 뜻의 사용자 메시지를 더해 새로 요청한 뒤 받은 것을 이어 붙인다(옛 실측 방식):
      > 방금 쓴 답이 출력 길이 제한에 걸려 중간에 끊겼다. 이미 쓴 부분은 반복하지 말고, 끊긴 곳의 바로 다음 글자부터 이어서 끝까지 써라. 머리말 · 설명 · 코드펜스 없이 이어지는 부분만 출력한다.
    - 이어 쓰기는 정한 횟수(`C`)까지다. 그래도 잘리면 그 시도는 `INVALID_OUTPUT` 실패다(02 §8.7 — 이어 써도 끝나지 않았다).
    - **형식 검사는 이 층이 받아 결과에 넣는다** — 호출하는 쪽이 넘긴 해석 함수(예: `Function<String, Optional<T>>`)로 이어 붙인 텍스트를 해석한다. 해석하지 못하면 **마지막 호출의 호출 기록 결과를 `INVALID_OUTPUT`** 으로 남기고 그 시도는 `INVALID_OUTPUT` 실패다. 해석한 값은 그대로 돌려준다.
    - 돌려주는 값은 성공(해석한 값) 또는 `GenerationOutcome.Failure` 넷 중 하나다. **프로바이더 · 연결 · 해석 문제로 예외를 던지지 않는다** — 지금 `JobRunner` 는 `generate` 가 던진 예외를 실패로 바꾸지 못해 작업이 「만드는 중」에 남고 시한마다 다시 돈다(돈이 계속 나간다).
    - 다시 시도는 여기서 하지 않는다 — 워커가 정한 횟수만큼 `generate` 를 다시 부른다(금지 12 · `JobRunner`).

### 3. 실패 갈래 — 호출 결과 일곱 → 작업 실패 넷

02 §8.7 · 04 §4.6 을 따른다. 아래 표가 처음 값이다. SDK 가 실제로 내는 예외 · 상태 코드 · 오류 `code` 를 소스 JAR 로 확인해 맞추고, 표와 다르게 해야 하면 04 §10 에 적는다.

| 프로바이더 결과 | 호출 결과(`llm_calls.outcome`) | 작업 실패(`GenerationOutcome.Failure`) |
|---|---|---|
| `finish_reason = stop` | `SUCCEEDED`(해석 실패면 `INVALID_OUTPUT`) | — / `INVALID_OUTPUT` |
| `finish_reason = length` | `TRUNCATED` → 이어 쓴다 | 횟수를 넘기면 `INVALID_OUTPUT` |
| 그 밖의 `finish_reason` · 빈 내용 | `INVALID_OUTPUT` | `INVALID_OUTPUT` |
| 시간 초과 | `TIMEOUT` | `PROVIDER_UNAVAILABLE` |
| 429 몰림 | `RATE_LIMITED` | `PROVIDER_UNAVAILABLE` |
| 429 쿼터 소진(`insufficient_quota`) | `REJECTED` | `PROVIDER_REJECTED` |
| 5xx · 연결 오류 | `PROVIDER_ERROR` | `PROVIDER_UNAVAILABLE` |
| 401 · 403 · 그 밖의 4xx(모델 없음 등) | `REJECTED` | `PROVIDER_REJECTED` |
| 400 입력이 너무 큼(`context_length_exceeded`) | `REJECTED` | `INPUT_TOO_LARGE` |

- `PROVIDER_REJECTED` · `INPUT_TOO_LARGE` 는 워커가 다시 시도하지 않는다(이미 `JobRunner` 의 동작).
- 입력이 너무 크면 자르거나 요약하지 않는다(금지 13).

### 4. 설정 — `application.yml` 한 곳

- `app.llm.openai.api-key: ${OPENAI_API_KEY:}` — **비어 있으면 기동을 거부한다**(README 2026-11-17 · `SecurityConfiguration` 의 구글 키와 같은 방식). 테스트는 `backend/build.gradle` 의 가짜 자격처럼 가짜 값을 넣는다 — 실제 키를 읽거나 출력하지 마라(`AGENTS.md` — `.env` · API 키).
- 기본 주소를 바꿀 수 있는 칸(가짜 서버용)을 둔다 — 운영 기본값은 SDK 기본 주소다.
- 강의 목록 · 로드맵 계획 호출의 설정 묶음: 모델 `gpt-5.6-terra` · 출력 상한 **4000** 토큰 · 호출 시간 제한 **50s** · 이어 쓰기 횟수 `C` = **1**(임시 값) — ADR-181 의 처음 값. 강의 자료 TASK 가 다른 묶음을 더할 수 있는 모양(종류 · 용도별 이름 있는 묶음)으로 둔다. 쓰지 않는 묶음을 미리 만들지 마라.
- 단가(USD · 1M 토큰당, ADR-181 — 2026-11-17 조회): `gpt-5.6-terra` 입력 2.00 · 캐시된 입력 0.20 · 출력 12.00. 비용 = `(입력 − 캐시된 입력) × 입력 단가 + 캐시된 입력 × 캐시 단가 + 출력 × 출력 단가` ÷ 1,000,000. 사용량이 없으면 비용도 비운다. 단가가 없는 모델이면 기동을 거부한다.
- 설정 묶음 · 단가는 `config` 의 `@ConfigurationProperties` record 로 읽는다(기존 `WorkerProperties` 처럼 생성자에서 검사).

### 5. 시한 식을 기동 검사로 — 금지 10

04 §10 의 식을 이제 계산할 수 있다:

```text
시한 > (R + 1) × ((C + 1) × T호출 + C × D이어쓰기) + R × D다시시도 + T_DB
```

- 지금 값(`R` = 2 · `D다시시도` = 1s · `T호출` = 50s · `C` = 1 · `D이어쓰기` = 0)이면 300s 를 넘어 **지금 시한 `5m` 이 식을 깨뜨린다.** `T_DB` 여유를 정하고(04 §10 의 정의대로) 시한을 식을 만족하는 값으로 올린다. 갱신 간격은 그대로 둔다.
- 식을 `config` 의 기동 검사로 둔다 — 워커 설정과 모든 채팅 설정 묶음 중 가장 긴 시도로 계산하고, 깨지면 설정 바인딩 실패처럼 기동을 거부한다. 값 하나를 올려 식이 깨지는 것을 서버가 막는다.
- 정상 종료 대기(`100s`)가 호출 시간 제한보다 길다는 것 등 04 §10 의 기존 검사는 그대로 둔다.

### 6. 실제 호출 시험 — 기본 실행에서 뺀다

- 실제 OpenAI 를 부르는 시험 한 벌을 두되 `./gradlew test` 에서 뺀다(07 §2 · `AGENTS.md` — 비용). `imageTest` 처럼 따로 돌리는 Gradle 작업(예: `llmTest`)으로 둔다. 키가 없으면 건너뛴다(실패가 아니다). 짧은 요청 한 번을 보내 끝남 · 사용량 · 호출 기록이 남는지 본다. **이 step 에서 실행하지 마라** — 키가 없고 돈이 든다. 사람이 phase 뒤에 돌린다.
- `AGENTS.md` 「명령어」 블록에 그 작업 한 줄을 더한다(사실 갱신).

### 7. 배포 · 로컬 env

- 기동에 키가 필요하므로 키를 넣는 곳을 모두 찾아 더한다 — `.env.example` · `compose.yaml` 의 주석 · `deploy/mogum.env.example` · `deploy/compose.yaml`(다른 필수 값처럼 `:?` 로) · `AppImageTest`(가짜 값) · `DeployFilesTest` 의 불변식. 값은 넣지 마라.
- `deploy/README.md` 의 env 설명 · 처음 배포 · 갱신 절차에 `OPENAI_API_KEY` 를 넣고, 사람이 할 일 — OpenAI 대시보드에서 키 발급 · 월 사용 한도 설정 — 을 적는다.

### 8. 테스트

- **가짜 OpenAI HTTP 서버**(테스트 안에서 띄운다 — JDK 의 `HttpServer` 등. 기본 주소 칸으로 연결)로 어댑터와 채팅 호출을 시험한다. **가짜는 실제 서버가 보는 것을 검사한다**(`/harness` 원칙 12):
    - 경로 · `Authorization: Bearer {키}` · `model` · `max_completion_tokens`(구식 `max_tokens` 가 아니다) · `reasoning_effort: "none"` · 메시지 역할 순서 · 이어 쓰기 요청에 앞부분 어시스턴트 메시지와 이어 쓰기 지시가 들어가는지.
    - **요청 수** — 500 · 429 · 시간 초과에서 HTTP 요청이 정확히 한 번이다(SDK 자동 재시도 꺼짐 — 금지 12).
- 갈래마다(위 표의 모든 줄) 호출 결과 · 작업 실패 · 호출 기록(줄 수 · `call_type` · `outcome` · 토큰 · 비용)을 확인한다. 이어 쓰기 성공 · 이어 써도 끝나지 않음 · 해석 실패를 포함한다.
- 채팅 호출 중에 트랜잭션 · DB 커넥션을 쥐지 않는다(호출 기록 쓰기 사이에). 예외를 던지지 않는다.
- 비용 계산 — 캐시된 입력이 있을 때 · 사용량이 없을 때.
- 기동 검사 — 키가 비면 기동 거부 · 시한 식이 깨지면 기동 거부 · 단가 없는 모델이면 기동 거부. 실제 `application.yml` 값이 식을 만족한다.
- **원문이 로그 · 예외 메시지에 남지 않는다** — 프롬프트 · 응답에 표식 문자열을 넣고 `OutputCaptureExtension` 으로 출력에 없음을 확인한다(금지 26).

### 9. 문서 — 구현으로 정해진 사실만

- `docs/04-jobs-and-cost.md` §10 — 워커 설정 표의 시한 새 값 · 시한 식에 대입한 값과 `T_DB` · 기동 검사 · 채팅 설정 키(모델 · 출력 상한 · 시간 제한 · 이어 쓰기 횟수 · 단가) · 실패 갈래 표가 위와 달라졌다면 그 사실. §2.3 — 어댑터 · 채팅 호출의 클래스 · 시그니처 한 줄.
- `docs/03-generation.md` §7 — 「출력 제한」 행에 강의 목록 · 로드맵 계획 값(4000)을 적는다.
- `docs/07-testing.md` §2 — 가짜 OpenAI 서버 · 실제 호출 시험 작업 이름.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.generation.*' --tests 'com.mogum.config.*' --tests 'com.mogum.deploy.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - SDK 타입이 `generation.llm` 밖으로 나가지 않는가(금지 2)? 어댑터 앞에 스텁으로 바꿀 인터페이스가 있는가?
   - SDK 자동 재시도가 꺼져 있고, 가짜 서버가 요청 수로 그것을 확인하는가?
   - 호출마다 호출 기록이 보내기 전에 생기고 끝나면 채워지는가? 형식 검사 실패가 마지막 호출의 결과로 남는가?
   - 채팅 호출이 예외를 던지지 않고, 호출 중 DB 커넥션을 쥐지 않는가?
   - 키 · 시한 식 · 단가의 기동 검사가 있는가? 실제 키를 읽지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/7-course-planning/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 어댑터 인터페이스 · 채팅 호출의 패키지 · 클래스 · 메서드 시그니처(step 2 · 3 이 쓴다) · 설정 키와 값 · 새 시한 값과 `T_DB` · 확인한 SDK 클래스 이름 · 실제 호출 시험 작업 이름 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (SDK 동작이 이 step 과 다름 · 계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 실제 OpenAI 를 부르지 마라 · `.env` 나 키를 읽거나 출력하지 마라. 이유: 비용 · 비밀(`AGENTS.md`).
- `openai-java` 버전을 바꾸지 마라. 이유: 4.75.1 이 실측으로 확인한 버전이다(ADR-181).
- 클라이언트 안에서 다시 시도하지 마라. 이유: 다시 시도 경계는 워커의 시도 단위다(금지 12 · ADR-127).
- 프롬프트 문구 · 강의 목록 출력 형식 · 종류별 처리기를 만들지 마라. 이유: step 2 · 3 의 범위다.
- 임베딩 클라이언트를 만들지 마라. 이유: 임베딩 모델은 강의 자료 TASK 에서 고른다(ADR-181).
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 호출 결과는 호출 기록(DB)에 남는다 — DB 에 남는 사실은 DB 에서 본다. 줄마다 14일 · 500MiB 보관을 나눠 쓴다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
