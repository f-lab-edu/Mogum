# Step 0: job-schema

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §8 머리말 · §8.1(공통 규칙 · 입력 칸 묶음 · 길이 상한) · §8.2(`credit_entries.job_id`) · §8.5(`generation_jobs` — **이 step 의 계약이다**) · §8.7(`generation_jobs.status` · `type` · `error_type` · `level` · `purpose`) · §9(같은 대상 막는 방법)
- `docs/04-jobs-and-cost.md` §4(만드는 작업 — 흐름 · 상태 · 꺼내기 · 순서 · 시한) · §2.3(「커리큘럼 모델」 줄 — 지금 엔티티 · 매핑 사실)
- `docs/ERD.md` — `generation_jobs` 부분
- `docs/ADR.md` — ADR-126 · ADR-128 · ADR-130 · ADR-134 · ADR-137 · ADR-157 · ADR-177
- `docs/TEST_GUIDE.md` 전체 — 특히 §4(픽스처 · 레포지토리 인터페이스) · §5(DB 격리)
- `docs/README.md` 「정한 것」 2026-11-15 세 줄 — 이 phase 의 범위
- `AGENTS.md` — 금지 6 · 9 · 11 · 24 · 27 · 28 · 작업 방식(enum Javadoc · TDD)
- 본보기 마이그레이션: `backend/src/main/resources/db/migration/V10__create_learning_model.sql`(입력 칸 묶음의 `CHECK` · 제약 이름) · `V9__migrate_account_schema.sql`
- 본보기 코드: `backend/src/main/java/com/mogum/course/domain/Course.java` · `LearningInput.java` · `Level.java` · `Purpose.java` · `course/repository/CourseRepository.java`
- 본보기 테스트: `backend/src/test/java/com/mogum/course/repository/CourseSchemaRepositoryTest.java` · `CourseRepositoryTest.java` · `course/domain/CourseFixtures.java` · `CourseTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase 는 만드는 작업(`generation_jobs`)을 받고 · 워커가 꺼내 처리하고 · 실패하면 크레딧을 돌려주는 **공통 틀**을 만든다. 종류마다 다른 일(받을 때 대상 확인 · 만들기 · 결과 저장)은 처리기 자리만 두고 뒤 TASK 가 채운다(README 2026-11-15). 이 step 은 테이블과 엔티티만 만든다.

**계약 문서(02 §8 · ERD · ADR · `AGENTS.md` 금지 목록 · README 「정한 것」)는 이 phase 전에 정해 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 마이그레이션 — `backend/src/main/resources/db/migration/V11__create_generation_jobs.sql`

- `generation_jobs` 를 02 §8.5 의 칸 · 타입 · 빈 값 규칙 그대로 만든다. 공통 규칙은 02 §8.1 이다 — `id bigserial` · `created_at` · `updated_at` · 빈 글 금지 · 길이 상한(`topic` 100 · `level_other` · `purpose_other` 50 · `memo` · `feedback` 500) · 값 목록 `CHECK` · 제약 이름 `{테이블}_{칸}_{key|check|not_blank|idx}`.
- 빈 값 규칙을 모두 `CHECK` 로 묶는다:
    - 입력 칸 묶음은 `NEW_*` 일 때만 있다. `NEW_*` 는 `topic` · `level` · `purpose` 가 차 있다. `level_other` · `purpose_other` 는 `OTHER` 일 때만 있다 — `V10` 의 `roadmaps` · `courses` 와 같은 모양으로 쓴다.
    - `feedback` 은 `REPLAN_*` 일 때만 있다(`REPLAN_*` 에 반드시 있는지는 02 가 정하지 않았다 — 비어도 되게 둔다).
    - 대상 칸 둘(ADR-177): `num_nonnulls(course_id, roadmap_id, lesson_id) <= 1` · `type` 마다 02 §8.5 표의 칸만 찬다. 대상이 있는 만들기는 그 칸이 늘 차 있고, `NEW_*` 는 `SUCCEEDED` 일 때만 찬다.
    - `claim_count >= 0`(기본 0) · `lease_until` 은 `RUNNING` 일 때만 · `error_type` 은 `FAILED` 일 때만.
- 연결(FK) — `user_id → users` · `course_id → courses` · `roadmap_id → roadmaps` · `lesson_id → lessons`. 모두 기본 동작(`NO ACTION`)이다(02 §8.1).
- 색인 — `(status, created_at)` · `(user_id, status)`(02 §8.5). 필요하면 대상 칸 색인을 더한다.
- **같은 대상에 같은 만들기가 둘 생기지 않게 막는 방법을 정해 만든다**(ADR-157 · 02 §9) — `MATERIAL` 은 `lesson_id` 마다 하나(상태와 상관없이), `PLAN_ROADMAP_COURSE` 는 `course_id` 마다 하나, `REPLAN_*` 는 대상마다 성공하지 않은 것 하나. DB 가 서버 여러 대에서도 지키는 방법(예: 부분 고유 색인)으로 둔다. 서버 메모리 잠금은 쓰지 않는다(금지 11 · 22). 두 번째 요청이 「이미 있는 작업을 돌려준다」는 동작은 step 2 가 만든다 — 이 step 은 DB 가 둘째 행을 거절하는 것까지다.
- `credit_entries` 에 `job_id bigint` · `generation_jobs` 로의 연결 `ON DELETE SET NULL` 을 더한다(02 §8.1 · §8.2). 복구 조건(그 작업의 마지막 기록)을 찾는 데 쓰므로 `job_id` 색인을 둔다.
- `V1` ~ `V10` 은 고치지 않는다.

### 2. enum · 엔티티 — `com.mogum.job.domain`

- `JobType` · `JobStatus` · `JobErrorType` — 02 §8.5 · §8.7 의 값 그대로. 상수마다 02 표의 이름 · 뜻으로 한국어 Javadoc 한 줄(`AGENTS.md` 작업 방식).
- `GenerationJob` — `AuditedEntity` 상속 · `@Getter` · `@NoArgsConstructor(access = PROTECTED)`(금지 24 · 28).
    - 사용자 · 대상은 다른 도메인이라 ID 값(`Long userId` · `Long courseId` · `Long roadmapId` · `Long lessonId`)으로 둔다(금지 27).
    - 입력 칸 묶음은 `course.domain.LearningInput` 을 그대로 `@Embedded` 로 쓴다(04 §2.1 — job 이 함께 쓴다).
    - **받을 때 만드는 팩토리를 종류마다 하나** 둔다 — 예: `newCourse(long userId, LearningInput input)` · `newRoadmap(long userId, LearningInput input)` · `planRoadmapCourse(long userId, long courseId)` · `replanCourse(long userId, long courseId, String feedback)` · `replanRoadmap(long userId, long roadmapId, String feedback)` · `material(long userId, long lessonId)`. 처음 상태는 팩토리 안에서 `QUEUED` · `claim_count` 0 으로 고정한다.
    - 상태를 바꾸는 메서드(꺼내기 · 성공 · 실패 · 다시 시도)는 두지 않는다 — 워커의 쓰기는 `claim_count` 조건이 붙은 네이티브 SQL 이라(금지 9) 쓰는 step(2 · 4)이 그 경로를 정한다. 엔티티 메서드가 필요하면 그 step 이 더한다.
- `job.repository.GenerationJobRepository` — `Repository<GenerationJob, Long>` 만 상속하고 `save` · `findById` 만 둔다(TEST_GUIDE §4). 조회는 쓰는 step 이 더한다.

### 3. 테스트

- **픽스처** — `backend/src/test/java/com/mogum/job/domain/GenerationJobFixtures.java`. 팩토리를 불러 저장하지 않은 엔티티를 돌려준다. 뒤 step 이 상태를 바꾼 행(`RUNNING` · `FAILED` · `SUCCEEDED`)을 만들 수 있게, 상태를 바꾸는 도우미가 필요하면 테스트 쪽 SQL 로 둔다 — 운영 코드에 테스트용 상태 변경 메서드를 두지 마라.
- **단위**(`job/domain/GenerationJobTest`) — 팩토리마다 채운 값을 getter 로 모두 확인한다(같은 타입 `long` 인자가 이어진다 — 금지 24).
- **레포지토리**(`@RepositoryTest` — `job/repository/GenerationJobSchemaRepositoryTest` · `GenerationJobRepositoryTest`)
    - 팩토리 여섯 종류가 저장되고 다시 읽힌다(입력 칸 묶음 · 대상 ID).
    - `CHECK` 마다 어긋난 행이 거절된다 — 대상 칸 둘 · `NEW_*` 가 `SUCCEEDED` 전에 대상을 가짐 · 입력이 `NEW_*` 밖에 있음 · `feedback` 이 `REPLAN_*` 밖 · `lease_until` 이 `RUNNING` 밖 · `error_type` 이 `FAILED` 밖 · 길이 상한 · 빈 글 · 값 목록. 제약 이름까지 확인한다(`CourseSchemaRepositoryTest` 처럼).
    - 같은 대상 막기 — 같은 강의의 `MATERIAL` 둘째 · 같은 코스의 `PLAN_ROADMAP_COURSE` 둘째 · 같은 대상의 성공하지 않은 `REPLAN_*` 둘째가 거절되고, 성공한 `REPLAN_*` 가 있으면 새 것이 들어간다. 다른 대상 · 다른 종류는 들어간다.
    - `credit_entries.job_id` 가 작업을 지우면 비워진다(`ON DELETE SET NULL`). 대상(코스 · 강의)을 작업보다 먼저 지우면 FK 가 막는다.
    - `created_at` · `updated_at` 이 공용 `TestClock` 의 시각이다.
- 정리기(`support/DatabaseCleaner`)는 카탈로그에서 테이블을 읽으므로 고치지 않는다 — 새 테이블이 비워지는지 기존 테스트로 확인만 한다.

### 4. 문서 — 구현으로 정해진 사실만

- `docs/02-domain-data.md` §8.5 의 「막는 방법(고유 색인 · 잠금)은 TASK 에서 정한다(§9)」 자리에 정한 방법 한 줄(색인 이름 포함)을 적고, §9 의 그 줄을 지운다.
- `docs/04-jobs-and-cost.md` §2.3 에 「만드는 작업」 줄을 더해 `V11` · 엔티티 · 팩토리 시그니처 · 레포지토리를 적는다.
- `docs/ERD.md` 가 02 §8.5 와 다르면 맞춘다(`credit_entries.job_id` 연결 등).
- 규칙 문장은 고치지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.job.*' --tests 'com.mogum.account.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 칸 · 빈 값 · `CHECK` · 색인이 02 §8.5 와 같은가? 같은 대상 막기가 DB 에 있는가?
   - 엔티티가 금지 24 · 27 · 28 을 따르는가? 사용자 · 대상이 ID 값인가? 처음 상태가 팩토리 안에 고정됐는가?
   - enum 상수마다 한국어 Javadoc 이 02 §8.7 과 같은가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/6-job-worker/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 마이그레이션 파일 · 같은 대상 막는 방법과 색인 이름 · 엔티티 · 팩토리 시그니처 · 레포지토리 메서드 · 픽스처 이름과 시그니처(상태를 바꾼 행을 만드는 도우미 포함 — 뒤 step 이 쓴다) · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `llm_calls` 를 만들지 마라. 이유: 강의 목록 TASK 가 그 테이블을 쓰는 코드와 함께 만든다(02 §8 머리말).
- 서비스 · 컨트롤러 · 워커 · 크레딧 쓰기/복구를 만들지 마라. 이유: step 1 ~ 5 의 범위다.
- `CreditEntry` 엔티티에 `jobId` 를 매핑하지 마라. 이유: step 1 이 쓰기 · 복구 팩토리와 함께 매핑한다(`ddl-auto: validate` 는 매핑하지 않은 칸을 막지 않는다).
- 같은 대상 막기를 서버 메모리 잠금 · 애플리케이션 검사만으로 하지 마라. 이유: 서버가 여러 대면 깨진다(금지 11 · 22).
- 테스트 전용 `@Entity` · `Repository` 를 `com.mogum` 아래에 두지 마라. 이유: 모든 컨텍스트 테스트에 섞인다(TEST_GUIDE §5).
- 계약 문서(02 의 규칙 문장 · ERD 의 관계 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
