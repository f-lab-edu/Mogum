# Step 0: llm-timing

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/ADR.md` — ADR-126(시한 · 정상 종료) · ADR-127 · ADR-181 · **ADR-186**(강의 자료 + 카드 모델 · 임시 값 · 종료 대기를 늘린다) · **ADR-187**(임베딩 모델)
- `docs/04-jobs-and-cost.md` §4.5(시한 · 되찾기 · 정상 종료) · §10(워커 · 채팅 설정 표 · 시한 식 · 종료 대기 문단)
- `docs/03-generation.md` §7
- `AGENTS.md` — 금지 10 · 12 · 22
- 설정 코드: `backend/src/main/java/com/mogum/config/LlmProperties.java` · `LlmConfiguration.java` · `WorkerProperties.java` · `GenerationConfiguration.java` · `backend/src/main/resources/application.yml`(`spring.lifecycle.timeout-per-shutdown-phase` · `app.llm` · `app.worker`)
- 배포 파일: `deploy/compose.yaml` · 저장소 루트 `compose.yaml`(`stop_grace_period`) · `deploy/README.md`(종료 대기를 적은 줄)
- 시험: `backend/src/test/java/com/mogum/config/LlmPropertiesTest.java` · `LlmConfigurationTest.java` · `WorkerPropertiesTest.java` · `backend/src/test/java/com/mogum/deploy/DeployFilesTest.java`
- 워커 수명: `backend/src/main/java/com/mogum/worker/WorkerLifecycle.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

강의 자료 TASK(`8-material-cards`)의 첫 step 이다. 이 phase 는 강의를 처음 열 때 강의 자료 + 카드를 LLM 호출 한 번으로 만들고(ADR-119), 카드 개념의 정의를 임베딩해 개념 사전에 넣는다(ADR-048 · ADR-187). 강의 자료 호출은 강의 목록보다 훨씬 길다 — 이 step 은 **설정 · 시간 값만** 먼저 맞춘다. 호출 코드는 뒤 step 이 쓴다.

**계약 문서는 이 phase 전에 정해 커밋했다(ADR-185 ~ ADR-187 · 03 §3 · §7 · 02 §9 · 06 §15.1 · README 2026-11-23). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 채팅 설정 묶음 `material` 과 임베딩 설정

- `app.llm.chats.material` 묶음을 더한다 — ADR-186 의 임시 값: 모델 `gpt-5.6-terra` · 출력 상한 `10000` · 시간 제한 `140s` · 이어 쓰기 `1`. `planning` 처럼 빠지면 기동을 거부한다. `LlmProperties` 에 `material()` 을 더한다.
- 임베딩 설정을 더한다 — 모델 `text-embedding-3-large` · 호출 시간 제한 임시 값 `20s`. 키 이름 · record 모양은 지금 `app.llm` 의 짜임에 맞춰 정한다(예: `app.llm.embedding.model` · `timeout`). 빠지면 기동을 거부한다.
- 단가 — `text-embedding-3-large` 는 **입력 $0.13 / 1M 토큰**뿐이다(2026-11-23 developers.openai.com/api/docs/pricing 조회 · ADR-187). 지금 `TokenPrices`(입력 · 캐시 입력 · 출력)에 어떻게 담을지 정하고, 모든 채팅 · 임베딩 모델에 단가가 있어야 기동하게 한다. 비용 식은 출력 · 캐시가 없을 때 입력만으로 맞게 나와야 한다 — `TokenPricesTest` 에 그 경우를 더한다.
- `ChatSettings` 의 검사 규칙을 임베딩 설정에 그대로 쓸 수 없으면 따로 둔다 — 시간 제한 양수 · 모델 비어 있지 않음.
- 이 묶음들은 step 1(임베딩) · step 3(강의 자료 생성)이 쓴다. 이 step 에서 생성 처리기에 연결하지 마라.

### 2. 정상 종료 대기를 늘린다 — 「종료 대기 > 모든 채팅 호출 시간 제한」을 지킨다

지금 관계: 워커 종료 대기 100초 < 120초 상한(`WorkerProperties` 상수 — Spring 종료 단계 130초에서 반환 여유 10초) · Spring `timeout-per-shutdown-phase` 130초 · 컨테이너 `stop_grace_period` 150초. 강의 자료 호출(140초)을 기다리려면 셋을 함께 늘린다. 값:

| 값 | 지금 | 바꿀 값 |
|---|---|---|
| `app.worker.shutdown-wait` | 100s | `150s` |
| `WorkerProperties` 의 종료 대기 상한(Spring 단계 − 반환 여유 10초) | 120초 | `170초` |
| `spring.lifecycle.timeout-per-shutdown-phase` | 130s | `180s` |
| `stop_grace_period`(`deploy/compose.yaml` · 루트 `compose.yaml`) | 150s | `220s` |

- 컨테이너 대기는 「워커 단계(종료 대기 + 반환 최대 5초) + Tomcat 정상 종료」가 들어오게 Spring 단계 대기보다 넉넉히 길어야 한다 — 위 값이 지금 시험의 관계식을 만족하는지 시험을 고쳐 확인한다. 관계식을 시험이 상수로 들고 있으면(`WorkerPropertiesTest` 의 120 · 130, `DeployFilesTest` 의 150) 새 값에 맞춘다 — **관계를 검사하는 시험을 지우지 마라.**
- `LlmConfiguration` 의 「종료 대기 > 모든 채팅 호출 시간 제한」 검사는 그대로 둔다 — 이번 값(150 > 140)으로 통과해야 한다.
- **확인해서 적을 것** — 워커가 종료 대기로 기다리는 동안 Tomcat 이 계속 요청에 응답하는지. Spring Boot 4.1.1 의 `SmartLifecycle` 단계(워커 수명 빈의 단계 · 웹 서버 정상 종료 · 웹 서버 정지 빈의 단계)를 소스 JAR(`~/.gradle/caches` 의 `spring-boot-*-sources.jar` · `spring-context-*-sources.jar`)에서 읽어 확인하고, 확인한 클래스 · 단계 값을 04 §10 에 적는다. 확인하지 못하면 「확인하지 못했다」로 적는다 — 추측으로 단정하지 마라.

### 3. 시한을 다시 계산한다(금지 10)

- 한 시도는 이제 채팅 호출(최대 `(C + 1) × T호출`)에 더해 **임베딩 호출 한 번**(step 1 · 3 — 한 시도에 강의 하나의 개념 정의를 한 번에 보낸다)을 할 수 있다. `LlmConfiguration` 의 시한 검사 식에 임베딩 시간 제한을 더한다. 식: `시한 > (R + 1) × (max_묶음((C + 1) × T호출) + T임베딩) + R × D다시시도 + T_DB`. 임베딩을 하지 않는 강의 목록에도 더해 보수적으로 둔다(식 하나).
- 새 값으로: `3 × (2 × 140 + 20) + 2 × 1 + 10 = 912초` → `app.worker.lease-duration` 을 `16m`(960초)로 올린다. 엄격한 부등식이 깨지면 기동을 거부하는 시험이 있어야 한다.
- 갱신 간격(30초)은 그대로다.

### 4. 문서 — 구현으로 정해진 사실만

- `docs/04-jobs-and-cost.md` §10 — 워커 임시 값 표(종료 대기 · 시한) · 시한 식과 「현재:」 계산 줄 · 채팅 설정 표(`material` 묶음 · 임베딩 설정 · 임베딩 단가) · 종료 대기 문단(170초 상한 · 180초 · 220초 · 2번의 확인 결과) · `LlmProperties` 가 검사하는 묶음 목록. §4.5 의 「지금 130초 · 150초」.
- `deploy/README.md` — 지금 설정을 설명하는 줄(「종료 대기는 150초로 Spring 의 130초보다 길다」 등)을 새 값으로. **지난 검증 기록(날짜가 붙은 `[x]` 항목)의 숫자는 그때의 사실이라 고치지 마라.**
- `docs/03-generation.md` §7 · `docs/ADR.md` 는 고치지 마라(계약).

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.config.*' --tests 'com.mogum.deploy.*' --tests 'com.mogum.generation.TokenPricesTest' --tests 'com.mogum.worker.*'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 종료 대기 150 > 모든 채팅 호출 시간 제한(140 · 50)인가? 종료 대기 < 170 상한 < Spring 180 < 컨테이너 220 의 관계를 시험이 검사하는가?
   - 시한 식에 임베딩 시간 제한이 들어갔고 960초 > 912초를 검사하는가?
   - 임베딩 · `material` 설정이 빠지면 기동을 거부하는가? 모든 모델에 단가가 있어야 기동하는가?
   - 04 §10 에 웹 서버가 종료 대기 중에도 응답하는지 확인한 근거(또는 「확인하지 못했다」)가 있는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/8-material-cards/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: `LlmProperties` 의 `material()` · 임베딩 설정의 키와 접근 메서드 이름 · 단가를 담은 방법 · 바꾼 시간 값 · 시한 식 · 웹 서버 응답 확인 결과
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 임베딩 호출 · 강의 자료 생성 코드를 만들지 마라. 이유: step 1 · 3 의 범위다.
- 「종료 대기 > 모든 채팅 호출 시간 제한」 검사를 빼거나 강의 자료 묶음만 예외로 두지 마라. 이유: ADR-186 — 배포 중에 만들던 강의 자료가 끝나기를 기다린다는 사용자 결정이다.
- 실제 OpenAI 를 부르지 마라. 이유: 비용.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
