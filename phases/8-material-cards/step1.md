# Step 1: embedding-call

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/ADR.md` — ADR-030 · ADR-118 · ADR-127 · ADR-139 · ADR-160 · ADR-161 · ADR-163 · ADR-181 · **ADR-187**(임베딩 모델 · 기준값을 실측으로 정한다)
- `docs/03-generation.md` §1.1 · §6(호출 기록)
- `docs/02-domain-data.md` §6(개념) · §8.5(`llm_calls` · `call_type` 의 `EMBEDDING`) · §8.7
- `docs/04-jobs-and-cost.md` §2.2 · §10(채팅 설정 · 프로바이더 결과 → 호출 결과 · 작업 실패 표)
- `docs/07-testing.md` §2(LLM 스텁 · 가짜 OpenAI 서버 · 실제 호출 시험)
- `docs/TEST_GUIDE.md` §5(공용 설정 · 컨텍스트를 늘리지 않는다)
- `AGENTS.md` — 금지 1 · 2 · 3 · 12 · 14 · 26
- step 0 이 만든 것(summary 참고) — 임베딩 설정 · 단가 · 시한 식
- 채팅 쪽 구현(본뜰 것): `backend/src/main/java/com/mogum/generation/ChatProvider.java` · `ChatCall.java` · `ChatResult.java` · `GenerationOutcome.java` · `generation/llm/OpenAiChatProvider.java` · `generation/call/LlmCallWriter.java` · `config/LlmConfiguration.java`
- 시험: `backend/src/test/java/com/mogum/generation/llm/FakeOpenAiServer.java` · `OpenAiChatProviderTest.java` · `OpenAiLiveIntegrationTest.java` · `generation/ChatCallIntegrationTest.java` · `support/IntegrationTestConfiguration.java`(`TestChatProvider`) · `backend/build.gradle`(`llmTest` 작업)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

강의 자료를 만들 때 카드마다 개념(이름 · 한 줄 정의)이 붙고, 서버는 그 정의를 임베딩해 사용자의 개념 사전에서 같은 개념을 찾는다(ADR-030 · ADR-161). 이 step 은 **임베딩 호출 경로**와 **기준값을 재는 실측 시험**을 만든다. 사전에 넣는 일은 step 2, 강의 자료 생성에서 부르는 일은 step 3 이다.

**계약 문서는 이 phase 전에 정해 커밋했다. 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 경계 — `com.mogum.generation`(SDK 없이)

- `EmbeddingProvider` — HTTP 요청 한 번의 경계. 채팅의 `ChatProvider` 처럼 SDK 타입 없이 스텁으로 바꿀 수 있게 둔다. 요청: 작업 ID · 작업 종류 · 설정 · **정의 글 목록**(한 번에 여럿). 응답: 입력 순서대로의 벡터 목록 · 호출 결과(`LlmCallOutcome`) · 실패(`GenerationOutcome.Failure`) · 사용량(입력 토큰).
- `EmbeddingCall` — `ChatCall` 처럼 한 번의 호출을 감싼다:
    - 트랜잭션을 쥔 채 들어오면 보내지 않고 실패로 돌려준다(금지 3).
    - 부르기 전에 호출 기록을 `EMBEDDING` 으로 열고, 끝나면 결과 · 입력 토큰 · 비용(step 0 의 단가) · 걸린 시간을 적는다(금지 14). 출력 토큰은 비운다. 원문(정의 글 · 벡터)은 기록 · 로그에 남기지 않는다.
    - **다시 시도하지 않는다** — 한 번 부르고 결과를 돌려준다. 다시 시도는 워커가 시도 전체를 다시 하는 것뿐이다(ADR-127). 이어 쓰기도 없다.
    - 응답 검사 — 벡터 수가 입력 수와 같고, 모든 벡터의 차원이 같고 0 이 아니다. 어긋나면 `INVALID_OUTPUT`.
    - 결과는 성공(벡터 목록)과 실패(`Failure`)를 담는 값으로 돌려준다 — `ChatResult` 와 같은 모양이면 그것을 따른다.
    - 예상한 실패(프로바이더 · 형식)는 실패 값으로 돌려준다. 예상 밖 예외(우리 버그)를 조용히 `PROVIDER_UNAVAILABLE` 로 바꾸지 마라 — 지금 생성 처리기의 `GenerationFailures`(04 §9.1 의 `job generate failed`)가 잡도록 던지거나, 같은 방식으로 남긴다. 어느 쪽인지 summary 에 적는다.

### 2. 어댑터 — `com.mogum.generation.llm`(SDK 는 여기서만 — 금지 2)

- `OpenAiEmbeddingProvider` — SDK 4.75.1 의 임베딩 API(`com.openai.models.embeddings.EmbeddingCreateParams` · `EmbeddingModel.TEXT_EMBEDDING_3_LARGE` · `CreateEmbeddingResponse` — 소스 JAR `~/.gradle/caches/modules-2/files-2.1/com.openai/openai-java-core/4.75.1/` 에 있다)로 정의 목록을 한 요청에 보낸다.
    - SDK 자동 재시도는 끈다(`maxRetries(0)` — 금지 12). 요청별 시간 제한은 임베딩 설정 값이다.
    - 실패 분류는 04 §10 의 채팅 표와 같다(시간 초과 → `TIMEOUT`/`PROVIDER_UNAVAILABLE` · 429 → `RATE_LIMITED` · `insufficient_quota` · 401/403/그 밖의 4xx → `REJECTED`/`PROVIDER_REJECTED` · 5xx · 연결 → `PROVIDER_ERROR` · 400 `context_length_exceeded` → `INPUT_TOO_LARGE`). 채팅 어댑터의 분류 코드를 함께 쓸 수 있으면 나눠 쓰고 중복을 만들지 마라.
    - **확인해서 적을 것** — SDK 가 보내는 `encoding_format` 기본값과 응답 벡터를 `float` 로 받는 방법(base64 를 SDK 가 푸는지)을 소스 JAR 에서 확인하고 04 §10 에 적는다.
    - HTTP 클라이언트 · 키를 채팅 어댑터와 나눠 쓸 수 있으면 하나로 둔다(같은 키 · 기준 주소). 빈 조립은 `config.LlmConfiguration` 에 둔다.

### 3. 가짜 OpenAI 서버가 검사할 것(원칙 — 실제 서비스의 검증을 그대로)

`FakeOpenAiServer` 에 임베딩 경로를 더하고, 실제 SDK 로 보낸 요청에서 아래를 검사하는 시험(`OpenAiEmbeddingProviderTest` 등)을 둔다:

- 경로(`/embeddings`) · 인증 헤더 · 모델 이름 · `input` 이 문자열 배열이고 입력 순서를 지키는지 · 요청 수가 한 번인지(500 · 429 · 시간 초과에서도 — 자동 재시도가 꺼졌는지).
- 응답의 `index` 순서가 섞여 와도 입력 순서대로 맞춰지는지(실제 API 응답은 `index` 를 싣는다).
- 위 실패 분류 각각.

### 4. 공용 테스트 스텁 — `IntegrationTestConfiguration` 의 중첩 static 클래스

- `TestEmbeddingProvider` 를 `@Primary` 빈으로 등록한다 — `TestChatProvider` 와 같은 방식. 클래스마다 `@Import` 하지 마라(TEST_GUIDE §5 · 컨텍스트가 클래스마다 새로 뜬다).
- 기본: 같은 글이면 같은 벡터를 주는 결정적 벡터. 시험이 글마다 벡터를 정할 수 있어야 한다(step 2 · 3 이 「같은 개념」 · 「다른 개념」을 만든다). 작업마다 실패를 정할 수 있고, 받은 요청과 트랜잭션 밖이었는지를 관찰할 수 있다. `reset()` 으로 비운다.

### 5. 기준값 실측 시험 — 기본 `test` 에서 빼고 `llmTest` 로만(ADR-187)

- `com.mogum.generation.llm` 에 실측 시험 하나를 더하고 `backend/build.gradle` 의 `llmTest` 에 포함한다. `OPENAI_API_KEY` 가 없거나 비었으면 건너뛴다(지금 `OpenAiLiveIntegrationTest` 와 같다). 기본 `test` 에서는 돌지 않아야 한다.
- 개념 쌍 표본 — **같은 개념 쌍 15개 이상 · 다른 개념 쌍 15개 이상**을 한국어 개발 개념의 「이름 + 한 줄 정의」로 시험 코드 안에 둔다. 판정은 정의끼리 한다(ADR-030). 섞을 것:
    - 같은 것 — 이름이 다르고 뜻이 같은 쌍(예: 「격리 수준」 · 「트랜잭션 격리 수준」), 정의를 다른 말로 쓴 쌍.
    - 다른 것 — 이름이 같거나 비슷하지만 뜻이 다른 쌍(예: 「인덱스」 DB 색인 · 배열 인덱스), 가까운 주제의 다른 개념(예: 「낙관적 락」 · 「비관적 락」, 「TCP」 · 「UDP」), 먼 개념.
- 한 번의 요청으로 모든 정의를 임베딩하고(`OpenAiEmbeddingProvider` 를 직접 — DB 는 쓰지 않는다), 쌍마다 코사인 유사도를 계산해 **사람이 읽을 보고서**를 `backend/build/reports/concept-similarity.txt` 에 쓴다: 쌍마다 「같음/다름 · 이름 둘 · 유사도」, 끝에 같은 쌍의 최솟값 · 다른 쌍의 최댓값 · 둘 사이에 걸치는 쌍. 기준값을 단언하지 마라 — 사람이 보고 정한다.
- 이 시험은 실행하지 마라(유료). 컴파일 · 건너뛰기만 확인한다.

### 6. 문서 — 구현으로 정해진 사실만

- `docs/04-jobs-and-cost.md` §10 — 임베딩 어댑터 · 실패 분류(채팅과 같음) · `encoding_format` 확인 결과 · 한 시도에 임베딩 한 번.
- `docs/07-testing.md` §2 — 가짜 서버의 임베딩 검사 · `TestEmbeddingProvider` 쓰는 법 · 실측 시험과 보고서 위치.
- `AGENTS.md` 「명령어」의 `llmTest` 줄 — 실측 시험도 돈다는 것과 보고서 위치를 덧붙인다(설명 줄만. 금지 목록은 고치지 마라).

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.generation.*' --tests 'com.mogum.config.*' --tests 'com.mogum.support.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - SDK 타입이 `generation.llm` 밖으로 새지 않는가? `generation` 을 `worker` · `config` 밖에서 참조하지 않는가?
   - 임베딩 호출마다 `llm_calls` 에 `EMBEDDING` 한 줄 · 원문 없음 · 출력 토큰 비움 · 비용이 입력 단가로 맞는가? 트랜잭션 안에서 부르면 보내지 않는가?
   - 실패에서 요청이 한 번뿐인가? 분류가 04 §10 표와 같은가?
   - 실측 시험이 기본 `test` 에 없고 `llmTest` 에만 있는가? 키가 없으면 건너뛰는가?
   - 공용 테스트 설정에 스텁을 더하며 컨텍스트 수를 늘리지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/8-material-cards/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: `EmbeddingProvider` · `EmbeddingCall` 의 시그니처와 결과 값 모양 · 예상 밖 예외를 다루는 방식 · `TestEmbeddingProvider` 의 쓰는 법(step 2 · 3 이 쓴다) · 실측 시험 클래스와 보고서 경로 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 개념 사전에 넣기 · 강의 자료 생성을 만들지 마라. 이유: step 2 · 3 의 범위다.
- 임베딩 호출 안에서 다시 시도하거나 SDK 자동 재시도를 켜지 마라. 이유: 금지 12 · ADR-127 — 시도 경계가 사라져 시한 식이 깨진다.
- 실측 시험이나 실제 OpenAI 호출을 실행하지 마라. 이유: 비용 — 사람이 phase 뒤에 돌린다(ADR-187).
- 기준값을 정하거나 설정에 넣지 마라. 이유: step 2 가 임시 값으로 두고, 실측 뒤 사람이 정한다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 호출 기록은 DB 에 남는다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
