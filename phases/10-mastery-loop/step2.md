# Step 2: material-mastery

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/03-generation.md` §1.1 · §3(강의 자료와 카드 — 「넣는 것」 · 「강의 한 줄의 개념을 사전과 맞추는 법」 · 뼈대의 「개념 설명」) · §4(프롬프트 공통 규칙 — 판정 지시 · 숫자를 넘기지 않는다) · §6
- `docs/02-domain-data.md` §5 · §6
- `docs/04-jobs-and-cost.md` §4(만드는 작업 · 실패 · 다시 시도) · §9(로그) · §10(시한 식 · `app.worker` · `app.llm` 설정 표)
- `docs/ADR.md` — ADR-031 · ADR-118 · ADR-123 · ADR-126 · ADR-127 · ADR-138 · ADR-185 · ADR-186 · ADR-187
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 1 · 3 · 4 · 10 · 12 · 14 · 15 · 26
- step 0 · 1 이 만든 것(summary 참고) — 판정 enum · `card.service` 의 읽기 전용 조회(`hasConcepts` · `judgeMatching`)
- `backend/src/main/java/com/mogum/generation/MaterialGenerator.java` · `MaterialReader.java` · `material/MaterialInput.java` · `material/MaterialFormat.java` · `EmbeddingCall.java` · `GenerationFailures.java`
- `backend/src/main/resources/prompts/material/rules.txt` · `output.txt` · `backend/src/main/resources/prompts/planning/user.txt`
- `backend/src/main/java/com/mogum/config/GenerationConfiguration.java` · `LlmConfiguration.java` · `LlmProperties.java` · `WorkerProperties.java` · `ClockConfiguration.java` · `backend/src/main/resources/application.yml`
- `backend/src/test/java/com/mogum/generation/MaterialGeneratorIntegrationTest.java` · `generation/material/MaterialFormatTest.java` · `config/LlmConfigurationTest.java`
- `backend/src/test/java/com/mogum/support/IntegrationTestConfiguration.java` — `TestEmbeddingProvider`(`vector(정의, 벡터)` 로 벡터를 정하고 `requests(jobId)` 로 임베딩 요청을 본다) · `TestChatProvider`(`requests(jobId)` 로 프롬프트를 본다)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

강의 자료를 만들 때 강의 한 줄의 다룰 개념마다 판정 지시를 프롬프트에 넣는다(03 §3). 지금은 판정 없이 모든 개념을 처음 배우는 것처럼 다룬다.

**계약 문서는 이 phase 전에 정해 커밋했다. 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 흐름 — `MaterialGenerator.generate`

```
재료 읽기(MaterialReader) → [사전이 비지 않았으면] 다룰 개념 정의 임베딩 → judgeMatching → 프롬프트 → 채팅 1번(+ 이어 쓰기) → 카드 개념 임베딩 → 결과
```

- 사전이 비었으면(`hasConcepts` 가 거짓) 임베딩을 부르지 않고 모든 개념이 기록 없음이다(03 §3).
- 다룰 개념 정의를 겹치지 않게 모아 **임베딩 한 번**으로 보낸다(`EmbeddingCall` — 호출 기록이 저절로 남는다, 금지 14). 임베딩 실패는 지금 카드 개념 임베딩 실패와 같게 그 실패를 돌려준다 — 채팅을 부르지 않는다.
- 판정 계산의 「지금」은 주입한 `Clock` 에서 읽는다(`GenerationConfiguration` 이 넘긴다). `Instant.now()` 를 쓰지 마라.
- 임베딩 · 판정 조회 · 채팅은 모두 트랜잭션 밖이다(금지 3). 조회는 `card.service` 의 읽기 전용 서비스가 자기 트랜잭션에서 하고 값만 돌려준다(금지 4).
- 예상 밖 예외는 지금처럼 `GenerationFailures.unavailable(exception)` 으로 돌려준다 — 새 `catch` 로 다른 실패 갈래로 바꾸지 마라(S23).
- 결과 저장(`save`)은 바꾸지 않는다. 맞춰 본 판정은 저장하지 않는다(03 §3 · ADR-138).

### 2. 프롬프트

- `MaterialInput` 의 강의 한 줄 개념마다 판정 지시를 함께 담는다. 프롬프트에는 **판정 지시 문구**만 싣는다 — 판정 이름(enum 값) · 유사도 · 확률 · 단단함 숫자를 싣지 마라(03 §4 · ADR-031).
- 판정 지시 문구 셋은 리소스 파일로 둔다 — `backend/src/main/resources/prompts/planning/mastery-well-known.txt` · `mastery-shaky.txt` · `mastery-no-record.txt`. step 3 · 4 의 강의 목록 · 로드맵 계획도 같은 파일을 쓴다(목적 문구 `purpose-*.txt` 를 `planning/` 에 두고 함께 쓰는 것과 같다). 뜻은 02 §5 표 — 잘 앎: 짧게 다룬다 · 흔들림: 자세히 다룬다 · 기록 없음: 처음 배우는 것으로 보고 자세히 다룬다.
- `prompts/material/rules.txt` 의 「개념 설명」 규칙에 「개념마다 받은 지시에 따라 짧게 또는 자세히」를 더한다(03 §3 뼈대 표). 어떤 개념도 판정으로 빼지 않는다(ADR-031)는 것도 적는다. 출력 형식(`output.txt`)은 바꾸지 마라.

### 3. 시한 — 금지 10

강의 자료 한 시도의 임베딩이 1번에서 2번(채팅 앞 · 뒤)이 된다(03 §3). 지금 식으로 다시 계산하면 `3 × (2 × 140초 + 2 × 20초) + 2 × 1초 + 10초 = 972초` 이고 지금 시한 `16m`(960초)이 모자란다.

- 04 §10 의 시한 식을 한 시도의 임베딩이 두 번인 모양으로 고친다 — `시한 > (R + 1) × (max_묶음((C + 1) × T호출) + 2 × T임베딩) + R × D다시시도 + T_DB`. 강의 목록 · 로드맵 계획은 임베딩이 한 번이지만 지금처럼 식 하나로 보수적으로 검사한다.
- `config.LlmConfiguration` 생성자의 검사를 같은 식으로 고친다. `app.worker.lease-duration` 을 식을 엄격히 넘는 값으로 올린다 — `17m`(1020초 > 972초).
- `LlmConfigurationTest` 의 경계 값(지금 `912s` 거부 · `913s` 허용)을 새 식의 경계(`972s` 거부 · `973s` 허용)로 고친다. 다른 시간 값(채팅 · 임베딩 시간 제한 · 정상 종료 대기)은 바꾸지 마라.

### 4. 시험

- `MaterialFormatTest` — 개념마다 판정 지시 문구가 user 데이터에 들어간다 · 판정 셋이 서로 다른 문구다 · enum 이름 · 숫자가 프롬프트 어디에도 없다.
- `MaterialGeneratorIntegrationTest`(공용 `TestEmbeddingProvider.vector` 로 다룰 개념 정의와 사전 개념의 벡터를 정한다):
    - 사전에 잘 앎인 개념(step 0 의 기준을 넘게 복습한 카드)과 같은 벡터의 다룰 개념 → 그 개념에 잘 앎 문구 · 기준 아래 개념은 기록 없음 문구 · 흔들림 개념은 흔들림 문구(채팅 요청의 프롬프트로 확인). 07 §1 「이 숙련도면 짧게」를 이 시험으로 확인한다.
    - 사전이 비었으면 이 작업의 임베딩 요청이 지금처럼 한 번(카드 개념)뿐이다 · 사전이 있으면 두 번이고 첫 번째가 채팅보다 먼저다.
    - 다룰 개념 임베딩이 실패하면 채팅 요청이 없고 작업이 그 실패로 끝난다 · 호출 기록이 남는다.
    - 임베딩 · 채팅이 트랜잭션 밖에서 불린다(`outsideTransaction`).
- `LlmConfigurationTest` — 위 3 의 경계.

### 5. 문서 — 구현으로 정해진 사실만

- `docs/04-jobs-and-cost.md` §10 — 시한 식 · 「현재:」 계산 줄 · `app.worker.lease-duration` 값 · 식 설명 문단(「임베딩을 하지 않는 강의 목록에도 …」 문장을 지금 사실에 맞춘다).
- `docs/03-generation.md` §7 「섹션 이름 · 프롬프트 문구」 줄에 판정 지시 문구 파일 경로를 더한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.generation.*' --tests 'com.mogum.config.*' --tests 'com.mogum.card.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 사전이 비면 임베딩을 부르지 않는가? 임베딩 · 조회 · 채팅이 트랜잭션 밖인가? 채팅은 한 시도에 1번(+ 이어 쓰기)인가?
   - 프롬프트에 판정 이름 · 숫자가 없는가? 개념을 빼지 않는가?
   - 시한 식 · 설정 · 기동 검사 · 04 §10 이 같은 값인가?
   - `generation` 밖에서 임베딩을 부르지 않는가? `AGENTS.md` 금지 목록 · 작업 방식(TDD · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/10-mastery-loop/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 판정 지시 문구 파일 경로(step 3 · 4 가 쓴다) · `MaterialInput` 에 더한 모양 · `MaterialGenerator` 생성자에 더한 의존 · 시한 값 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 강의 목록 · 로드맵 계획 생성기를 고치지 마라. 이유: step 3 · 4 의 범위다.
- 다룰 개념과 사전 개념의 맞춘 결과를 저장하지 마라. 이유: ADR-138 — 다룰 개념은 사전과 잇지 않는다.
- 판정 이름 · 숫자를 프롬프트에 넣지 마라. 이유: ADR-031 · 03 §4.
- 채팅 · 임베딩 시간 제한 · 다시 시도 횟수 · 정상 종료 대기를 바꾸지 마라. 이유: 이 step 은 임베딩 한 번이 는 만큼 시한만 맞춘다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 호출 기록에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
