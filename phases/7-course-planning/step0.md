# Step 0: llm-calls

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §8 머리말 · §8.1(공통 규칙 · 지울 때 `ON DELETE SET NULL` 넷) · §8.5 `llm_calls`(**이 step 의 계약이다**) · §8.6(지우는 순서 — 호출 기록은 지우지 않는다) · §8.7 `llm_calls.call_type` · `llm_calls.outcome` · `generation_jobs.type`
- `docs/03-generation.md` §6(호출 기록)
- `docs/04-jobs-and-cost.md` §2.2 · §2.3(「만드는 작업」 줄들 — 워커 · 트랜잭션 경계) · §9.1(로그)
- `docs/ERD.md` — `llm_calls` 부분
- `docs/ADR.md` — ADR-139 · ADR-156 · ADR-163
- `docs/TEST_GUIDE.md` 전체 — 특히 §1(어떤 테스트를 언제) · §4 · §5
- `AGENTS.md` — 금지 1 · 3 · 4 · 14 · 19 · 24 · 26 · 27 · 28 · 작업 방식(enum Javadoc · TDD)
- 본보기 마이그레이션: `backend/src/main/resources/db/migration/V11__create_generation_jobs.sql`(값 목록 `CHECK` · 제약 이름 · `ON DELETE SET NULL`)
- 본보기 코드: `backend/src/main/java/com/mogum/job/domain/GenerationJob.java` · `JobType.java` · `backend/src/main/java/com/mogum/worker/JobRunner.java`(`TransactionTemplate` 경계 · 네이티브 SQL 의 `updated_at`) · `backend/src/main/java/com/mogum/generation/`(지금 있는 인터페이스)
- `backend/src/test/java/com/mogum/ArchitectureTest.java` — `generation` 의 참조 규칙 · `@Transactional` 금지

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`7-course-planning`)는 LLM 을 처음 실제로 부르고, 강의 목록 · 로드맵 계획을 만드는 다섯 종류(새 코스 · 새 로드맵 · 로드맵의 코스 · 코스 다시 짜기 · 로드맵 다시 짜기)와 그 화면을 만든다. 이 step 은 **호출 기록(`llm_calls`) 테이블과 그것을 쓰는 곳**만 만든다. LLM 을 부르는 코드는 step 1 이다.

**계약 문서(02 · 03 · 04 · 05 · 06 · ADR · `AGENTS.md` · README 「정한 것」)는 이 phase 전에 정해 커밋했다(`fa5af24`). 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 마이그레이션 — `backend/src/main/resources/db/migration/V12__create_llm_calls.sql`

- `llm_calls` 를 02 §8.5 의 칸 · 타입 · 빈 값 규칙 그대로 만든다. 공통 규칙은 02 §8.1 이다 — `id bigserial` · `created_at` · `updated_at` · 값 목록 `CHECK`(`job_type` 은 `generation_jobs.type` 과 같은 여섯 값 · `call_type` 셋 · `outcome` 일곱 — 02 §8.7) · 제약 이름 `{테이블}_{칸}_{key|check|not_blank|idx}`.
- `job_id → generation_jobs` · `user_id → users` 는 둘 다 `ON DELETE SET NULL` 이다(02 §8.1 · ADR-156).
- `outcome` 이 비어 있으면 `duration_ms` 도 비어 있게 묶을지는 정해서 쓴다 — 끝나기 전 행이다.
- 색인 — `created_at`(한 달 합계) · `job_id`(02 §8.5).
- `V1` ~ `V11` 은 고치지 않는다.

### 2. 엔티티 · 값 목록 — `com.mogum.generation` 아래

- 호출 기록 엔티티(`LlmCall` — 02 §1 의 코드 이름)와 `call_type` · `outcome` 의 enum 을 둔다. 하위 패키지 이름은 정해서 summary 에 적는다(예: `generation.call`). `job_type` 은 기존 `job.domain.JobType` 을 쓴다.
- 엔티티는 `AuditedEntity` 를 상속하고(금지 28), 이름 있는 정적 팩토리로만 만든다(금지 24). 다른 도메인(작업 · 사람)은 `Long` ID 값으로 둔다 — `@ManyToOne` 을 두지 않는다(금지 27).
- enum 상수마다 한국어 Javadoc 한 줄 — 뜻은 02 §8.7 표를 그대로 따른다.
- 엔티티 · 레포지토리는 `generation` 안에 있으므로 `worker` · `config` 밖에서 참조하지 않는다(금지 1 — ArchUnit).

### 3. 호출 기록 쓰기 — `com.mogum.generation` 아래

step 1 의 LLM 클라이언트가 **호출마다** 부를 쓰기 자리를 만든다. 이름 · 모양은 예시다.

```java
/** 호출을 보내기 전에 한 줄 적고 ID 를 돌려준다. outcome · duration 은 비어 있다. */
long start(Long jobId, Long userId, JobType jobType, LlmCallType callType, String model);

/** 끝난 호출의 결과 · 사용량을 채운다. 사용량을 모르면 null 이다. */
void finish(long callId, LlmCallOutcome outcome, Integer inputTokens, Integer outputTokens,
        BigDecimal cost, int durationMs);
```

지킬 규칙:

- **트랜잭션 경계는 `TransactionTemplate` 으로 드러낸다** — `generation` 은 `@Transactional` 을 쓰지 않는다(금지 3 · ArchUnit). 호출자의 트랜잭션에 기대지 않는다 — 워커는 LLM 호출 동안 트랜잭션 밖에 있다. `start` · `finish` 는 각자 짧게 커밋한다. 이 둘 사이(LLM 호출 중)에 DB 커넥션을 쥐지 않는다.
- **작업 · 사람이 이미 지워졌어도 기록을 남긴다**(금지 14 · ADR-139) — 만드는 중에 그 결과를 지우거나(지우기 TASK) 사람이 탈퇴하면 `generation_jobs` · `users` 행이 없다. 그대로 넣으면 FK 위반으로 기록이 사라진다. **없는 쪽은 연결을 비운 채 넣는다.** 확인과 넣기 사이에 지워져 FK 위반이 나면 연결을 비운 채 한 번 더 넣는다. `job_type` 은 그대로 담는다 — 끊겨도 남는 칸이다.
- `finish` 는 이미 끊긴 행도 채운다(행은 지워지지 않는다).
- 네이티브 SQL 로 쓰면 `created_at` · `updated_at` 을 SQL 에 직접 쓴다(금지 28). JPA 로 쓰면 Auditing 이 채운다. 시각은 주입받은 `Clock` 하나로 계산한다.
- 입력 · 출력 원문 · 프롬프트를 담을 칸을 만들지 않는다(02 §8.5 — 원문은 담지 않는다).
- 합계(한 달 비용 등)를 저장하지 않는다 — 기록에서 더한다(금지 14).
- 빈은 지금 있는 조립 방식(`config` 가 `worker` · `generation` 을 조립)을 따른다. 정한 방식을 summary 에 적는다.

### 4. 테스트

- 레포지토리 · 스키마 — 칸 · `CHECK`(값 목록 셋) · `ON DELETE SET NULL` 둘(작업을 지우면 `job_id` 만 비고 `job_type` 은 남는다 · 사람을 지우면 `user_id` 만 빈다)을 실제 PostgreSQL 에서 확인한다(TEST_GUIDE 의 `@RepositoryTest`).
- 쓰기 자리 — DB 동작이 규칙이라 `@IntegrationTest`(TEST_GUIDE §1):
    - `start` 가 연결 · `job_type` · `call_type` · `model` 을 담고 `outcome` · `duration_ms` 가 비어 있다. `finish` 가 결과 · 사용량 · 비용 · 걸린 시간을 채우고 `updated_at` 이 바뀐다.
    - 작업이 없으면 `job_id` 를 비운 채 넣는다 · 사람이 없으면 `user_id` 를 비운 채 넣는다 — 예외가 나지 않는다.
    - 호출자 트랜잭션이 없을 때 동작하고, 각 쓰기가 바로 커밋된다(다른 커넥션에서 보인다).
    - 사용량이 없으면 토큰 · 비용이 비어 있다.

### 5. 문서 — 구현으로 정해진 사실만

- `docs/04-jobs-and-cost.md` §2.3 — 「만드는 작업」 줄 뒤에 호출 기록의 마이그레이션 이름 · 엔티티 · 쓰기 자리 시그니처 · 끊긴 연결 처리 방법을 한 줄로 더한다.
- `docs/02-domain-data.md` §8 머리말의 「`llm_calls` 는 강의 목록 TASK 가 … 만든다」 옆에 마이그레이션 이름을 적는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.generation.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/04-jobs-and-cost.md` §2 패키지 구조(`generation` 은 `worker` · `config` 만 참조)를 따르는가?
   - `generation` 에 `@Transactional` 이 없고, 쓰기가 `TransactionTemplate` 으로 각자 커밋되는가?
   - 작업 · 사람이 없을 때 기록이 연결을 비운 채 남는가? 원문 칸이 없는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · enum Javadoc)을 위반하지 않았는가?
3. 결과에 따라 `phases/7-course-planning/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 마이그레이션 이름 · 엔티티 · enum · 레포지토리 · 쓰기 자리의 패키지 · 클래스 · 메서드 시그니처 · 빈 조립 방식(step 1 이 쓴다) · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- LLM · OpenAI SDK · 임베딩을 부르는 코드를 만들지 마라. 이유: step 1 의 범위다.
- 호출 기록에 입력 · 출력 원문 · 프롬프트 칸을 만들지 마라. 이유: 02 §8.5 · ADR-139 — 원문은 담지 않는다.
- 호출 기록을 지우는 코드를 만들지 마라. 이유: 지우기 · 탈퇴 때도 연결만 끊고 남긴다(02 §7).
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실(호출 기록)은 DB 에서 본다.
- 계약 문서(02 · 03 · 04 · 05 · 06 의 규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
