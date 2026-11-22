# 04. 서버 구조 · 만드는 작업 · 비용 통제

> 기술 · 코드 구조 · 기존 구현 · 배포 · 만드는 작업의 수명 · 워커 · 서버 여러 대 · 비용 통제 · 관측.
> 전제는 하나다 — **LLM 호출은 느리고(수십 초 ~ 1분), 매번 돈이 나가고, 실패한다.** 그리고 **서버가 여러 대여도 구조가 바뀌지 않게** 한다(README 2026-11-04).
> 무엇을 부르고 무엇을 넣는지는 [03](./03-generation.md), 단위와 지우기 범위는 [02](./02-domain-data.md), 받기 · 읽기 API 는 [05](./05-api.md).

---

## 1. 기술 선택

- **스택은 기존 구현 그대로 쓴다**(ADR-146) — Java · Spring Boot · PostgreSQL(+ pgvector) · Flyway · Testcontainers · ArchUnit, 화면은 React + TypeScript(Vite). 정확한 버전은 코드가 근거다.
- **만드는 작업은 DB 에 적고 워커가 꺼낸다 — 따로 된 메시지 큐는 두지 않는다**(ADR-103). 인프라는 DB 하나다.
- **끝났을 때 서버가 밀어 주는 연결(SSE · 웹소켓)은 두지 않는다** — 화면이 작업을 다시 읽는다(ADR-143). 서버가 여럿이면 끝낸 서버와 연결을 쥔 서버가 다르다.
- **로그인 세션은 DB 에 둔다**(ADR-142).
- 프로바이더 · 모델은 TASK 에서 재 보고 고른다(README 2026-11-04).

## 2. 코드 구조와 기존 구현

### 2.1 디렉토리

지금 저장소의 모양이다. 「→」는 새 설계에서 바뀌는 곳이다(ADR-146 ~ ADR-148). 배우는 단위는 `course` · `card` 두 패키지로 나눈다 — 02 §8.3 · §8.4 의 묶음과 같다(README 2026-11-10). 그 밖의 새 도메인 패키지의 이름과 나누는 방식은 그 TASK 에서 정한다.

```
/
├── backend/                         # Spring Boot — 서버. 단일 Gradle 모듈
│   ├── config/naver/                 # 네이버 Java 포매터 · Checkstyle · import 순서 · IntelliJ 설정 · 출처 README
│   ├── src/main/java/…/mogum/
│   │   ├── config/                  # 설정 값 · 시계 · Auditing · 요청 로그 필터 · API와 워커를 한 서버에 조립 — worker · generation 의 빈 등록
│   │   ├── common/                  # api(성공 · 오류 응답) · time · logging · persistence — 그대로 쓴다
│   │   ├── auth/                    # 구글 로그인 · 세션 → 사용자 — 그대로 쓴다
│   │   ├── account/                 # 사용자 · 로그인 → 신청 · 승인 · 거절됨으로 바꾼다(ADR-147) · 크레딧도 여기서(ADR-153)
│   │   ├── course/                  # 로드맵 · 코스 · 강의 · 강의 자료 — 02 §8.3. 입력 칸 묶음도 여기(job 이 함께 쓴다)
│   │   ├── card/                    # 카드 · 카드의 개념 연결 · 복습 · 개념 사전 — 02 §8.4. 강의는 ID 값으로 가리킨다
│   │   ├── job/                     # 만드는 작업 받기 · 읽기 · 다시 시도 · 버리기(ADR-143)
│   │   ├── generation/              # 생성 · 유료 호출(LLM · 임베딩) · 호출 기록
│   │   └── worker/                  # 꺼내기 · 시한 · 실행(ADR-126 · ADR-129)
│   ├── src/main/resources/db/migration/   # Flyway — 적용한 마이그레이션은 고치지 않는다(ADR-148)
│   └── src/test/java/…              # 짝 테스트 · support(묶음 어노테이션 · 정리기 · 테스트 시계 · 요청 도우미 — TEST_GUIDE) · ArchUnit · Testcontainers · LLM 스텁
├── frontend/                        # React + TypeScript · Vite — 화면은 06 대로 새로 만든다(ADR-146)
├── deploy/                          # 운영 서버 배포 · 백업 · 절차서 — 그대로 쓴다
├── phases/ · scripts/ · .claude/ · .codex/   # 하네스
└── docs/                            # 설계 문서 — 이 묶음
```

### 2.2 패키지 경계

**기존 구현의 경계를 그대로 쓴다**(ADR-146 · ADR-149) — 도메인별 패키지 + `generation` · `worker` · `config` · `common` · `auth`.

- 유료 호출(LLM · 임베딩)은 워커에서만 부른다 — 요청 경로에서 부르지 않는다(ADR-103 · ADR-149).
- LLM 호출 중에는 DB 연결을 쥐지 않는다(ADR-149).
- 프로세스 메모리에 함께 지킬 상태(카운터 · 캐시 · 잠금)를 두지 않는다 — DB 에 둔다(ADR-133).

`7-course-planning` step 6 구현: `course.controller` · `course.service` · `course.dto`가 코스 · 로드맵 읽기와 코스 시작 · 제목 고치기를 담당한다. `job`이 이미 `course`를 참조하므로 역방향 Java 의존은 ArchUnit으로 막았다. `course.repository.JpaCourseJobRepository`는 `EntityManager` 네이티브 SQL로 `generation_jobs`에서 성공하지 않은 강의 목록 작업의 ID · 실패 여부와 코스 다시 짜기의 존재 여부만 읽는다. 진행과 다음 코스는 읽기 서비스에서 계산하고 저장하지 않는다. 코스 시작은 받기와 같은 `CreditLedger.lock`의 사용자 행 잠금 뒤에 상태와 다시 짜기를 확인한다. API 계약은 [05 §3 · §4](./05-api.md#3-읽기--화면마다-한-번)에 있다.

### 2.3 기존 구현 — 그대로 · 바꾼다 · 버린다

이미 구현한 TASK(`0-skeleton` ~ `3a-test-conventions`)를 새 설계와 맞댄 결과다. 바꾸는 방법은 각 TASK 에서 정한다.

| TASK | 그대로 쓴다(ADR-146) | 바꾼다 | 버린다 |
|---|---|---|---|
| `0-skeleton` | 스택 · 패키지 경계 · ArchUnit · Flyway · Testcontainers | — | — |
| `1-auth` | 구글 로그인 · DB 세션 · 로그인 수단 칸 | 허용 목록 → 신청 흐름 · 이름을 더한다(ADR-147) | 대기자 상태 · 대기 순번 · 약관 동의 · 역할(ADR-147) |
| `2-deploy` | 배포 · 운영 절차 — 서버 1대 · 화면 따로 | — | — |
| `2a-api-response` | 성공 · 오류 응답의 모양 — code 와 서버가 정한 문구가 있어 ADR-145 와 맞는다 | 오류 code 목록을 새 묶음(ADR-088 · ADR-116)에 맞춘다 | — |
| `2b-ops-logging` | 요청 로그 | — | — |
| `3-curriculum-model` | — | — | 커리큘럼 · 그룹 · 단계 · 강의 테이블과 엔티티(ADR-148) |
| `3a-test-conventions` | 테스트 작성 규칙과 지원 도구 · 레포지토리 `Repository<T, ID>` 상속 · ArchUnit 테스트 규칙(ADR-179 · [TEST_GUIDE](./TEST_GUIDE.md)) | — | 옛 커리큘럼 모델의 테스트 — 모델과 함께(ADR-148) |
| `frontend/` | 스택 · 로그인 확인 · 요청 도구 | 화면 자체는 06 대로 새로 만든다 | — |

- **계정**(ADR-147) — 정해 둔 이메일만 들어오던 것을 누구나 신청 · 운영자가 승인 · 거절로 바꾼다. 역할은 지운다 — 운영자는 DB 에서 직접 한다(ADR-060). `4-account` step 0에서 `V9__migrate_account_schema.sql`로 옛 상태를 옮기고 이름 · 크레딧 잔액 · 채움 기록을 넣었다. `User.apply(provider, subject, email, name, creditBalance)` · `CreditEntry.refill(user, amount)`와 `CreditEntryRepository`를 두었고, 허용 목록 설정 · 역할 · 약관 칸은 제거했다. 로그인은 기존 회원 ID 또는 `LoginResult.NotApplied(ExternalAccount)`를 반환하며 행을 만들지 않는다. step 1에서 신청 전 콜백은 구글 신원만 JDBC 세션에 담고 화면의 `/`로 보내도록 바꿨다. 요청 인증은 `AccountPrincipal`로 통일하고 권한 없이 구성한다. 「나」의 구현 경로와 칸은 [05 §3](./05-api.md#3-읽기--화면마다-한-번)에 적었다. `6-job-worker` step 1에서 `account.service.CreditLedger`의 `lock(long userId)` · `consume(long userId, long jobId, int amount)` · `restoreIfOwed(long userId, long jobId)`를 더했다. `UserRepository.findByIdForUpdate(long id)`의 JPA `PESSIMISTIC_WRITE`로 `users` 행을 잠그고, 기본 `@Transactional` 전파로 호출자의 트랜잭션에 참여한다. 잔액은 JPA 변경 감지와 Auditing으로 갱신한다. `CreditEntry.jobId`는 `Long` 값이며 `consume` · `restore` 팩토리는 크기를 양수로 받아 각각 음수 · 양수로 담는다. 같은 사용자 기록은 행 잠금 안에서 쓰므로 `created_at` 대신 증가하는 `id`로 최신 작업 기록과 마지막 채움의 앞뒤를 가른다.
- **커리큘럼 모델**(ADR-148) — 단계를 두지 않고(ADR-070) 상태(ADR-136) · 입력(ADR-137) · 강의 한 줄(ADR-020 · ADR-138)이 달라 새로 만든다. 운영에 적용한 마이그레이션은 고치지 않고 새 마이그레이션으로 지우고 만든다 — 커리큘럼을 만드는 API 가 아직 없어 운영 DB 에 커리큘럼 데이터가 없다. 패키지 이름은 GLOSSARY, 테이블은 [02 §8](./02-domain-data.md#8-테이블)을 따른다. `5-learning-model` step 0에서 `V10__create_learning_model.sql`로 옛 네 테이블을 지우고 `roadmaps` · `courses` · `lessons` · `materials` · `cards` · `card_concepts` · `reviews` · `concepts` 여덟 테이블을 만들었다. 옛 `curriculum` 패키지의 코드와 테스트도 제거했다. step 1에서 `course.domain`에 `Roadmap` · `Course` · `Lesson` · `Material`과 `RoadmapStatus` · `CourseStatus` · `Level` · `Purpose`를 두었다. 생성 팩토리는 `Roadmap.plan(long userId, LearningInput input, String title, String description)` · `Course.single(long userId, LearningInput input, String title)` · `Course.inRoadmap(Roadmap roadmap, int position, String title, String planDescription, List<PlannedConcept> planConcepts)` · `Lesson.listed(Course course, int position, String title, String description, List<PlannedConcept> concepts)` · `Material.write(Lesson lesson, String body)`다. `LearningInput`은 여섯 입력 칸을 공유하는 변경 메서드 없는 `@Embeddable`이고, `PlannedConcept(String name, String definition)`은 record다. `List<PlannedConcept>`는 `@JdbcTypeCode(SqlTypes.JSON)` · `@Column(columnDefinition = "jsonb")`로 매핑한다. Hibernate 7.4.5의 소스 JAR(`SessionFactoryOptionsBuilder` · `JacksonIntegration` · `Jackson3JsonFormatMapper`)과 Gradle 의존성 조회로 기본 매퍼가 클래스패스에 따라 Jackson 2를 먼저 고름을 확인했다. FSRS 테스트 의존성의 Jackson 2와 운영의 Jackson 3가 달라지지 않도록 `application.yml`의 `spring.jpa.properties.hibernate.type.json_format_mapper: jackson3`로 Jackson 3.1.5를 명시했다. `LessonRepositoryTest`에서 실제 선택된 매퍼를 확인하고, `CourseRepositoryTest`와 함께 PostgreSQL 저장·재조회 및 SQL 비교로 한국어 · 목록 순서 · `name`/`definition` 키를 검증했다. 네 레포지토리는 `Repository<T, Long>`만 상속하고 `save` · `findById`만 선언한다. step 2에서 `card.domain`에 `Card` · `Concept` · `CardConcept` · `Review`와 `CardStatus` · `FsrsState` · `Rating`을 두었다. 생성 팩토리는 `Card.write(long userId, long lessonId, int position, String question, String answer)` · `Concept.add(long userId, String name, String definition, float[] embedding)` · `CardConcept.link(Card card, Concept concept)` · `Review.record(Card card, Rating rating)`다. `CardConceptId(Long cardId, Long conceptId)` record를 `@EmbeddedId`로 쓰고, 같은 칸의 부모 연관관계는 읽기 전용 LAZY 매핑으로 둔다. `CardConcept`는 `Persistable<CardConceptId>`를 구현해 공용 Auditing의 `createdAt`이 비었으면 새 연결로 판단한다 — 새 복합 키 연결의 `save`가 `merge`로 중복을 덮지 않고 `persist`하도록 하며, `CardConceptRepositoryTest`에서 중복 저장의 기본 키 제약 위반을 검증했다. 네 레포지토리는 `Repository<T, ID>`만 상속하고 `<S extends T> S save(S entity)` · `Optional<T> findById(ID id)`만 선언한다. 임베딩은 `org.hibernate.orm:hibernate-vector`를 버전 없이 추가하고 `float[]`에 `@JdbcTypeCode(SqlTypes.VECTOR)` · `@Column(columnDefinition = "vector")`를 붙여 차원을 고정하지 않는다. Boot 4.1.1 BOM · Hibernate BOM과 Gradle `dependencyInsight`로 7.4.5.Final 선택을 확인했고, 해당 버전 소스 JAR의 `PGVectorTypeContributor` · `PGVectorJdbcType` · `VectorDdlType`으로 PostgreSQL 타입 등록 · 저장/조회 · 차원 처리를 확인했다. `ConceptRepositoryTest`는 `ddl-auto: validate`에서 3차원과 5차원 값을 함께 저장·재조회하고, SQL의 `vector_dims` 및 실제 칸 타입 `vector`도 확인한다. 카드 초기 FSRS 값 · 부모 LAZY · 공용 `TestClock`의 생성/수정 시각도 레포지토리 테스트로 검증했다.

- **만드는 작업** — `6-job-worker` step 0에서 `V11__create_generation_jobs.sql`로 `generation_jobs`와 `credit_entries.job_id`(작업 삭제 시 `SET NULL`)를 만들었다. 같은 대상 중복을 막는 부분 고유 색인은 [02 §8.5](./02-domain-data.md#85-만들기--호출)에 적었다. `job.domain.GenerationJob`은 `AuditedEntity`를 상속하고 사용자 · 대상은 ID 값, 입력은 기존 `LearningInput`의 `@Embedded` 매핑으로 둔다. 받기 팩토리는 `newCourse(long userId, LearningInput input)` · `newRoadmap(long userId, LearningInput input)` · `planRoadmapCourse(long userId, long courseId)` · `replanCourse(long userId, long courseId, String feedback)` · `replanRoadmap(long userId, long roadmapId, String feedback)` · `material(long userId, long lessonId)`이며 모두 `QUEUED` · `claim_count = 0`으로 시작한다. `JobType` · `JobStatus` · `JobErrorType`을 두었고, `job.repository.GenerationJobRepository`는 `Repository<GenerationJob, Long>`만 상속해 `<S extends GenerationJob> S save(S entity)` · `Optional<GenerationJob> findById(Long id)`만 선언했다. `6-job-worker` step 2에서 `GenerationJob.retry()`로 실패를 기다림으로 돌리고 오류와 끝난 때를 비운다. `job.JobIntakeHandler`는 `type()` · `check(long userId, JobRequest request)`를 나눈다. 운영 구현은 이 절의 새로 배우기 처리기부터 더했다. 받기 값은 `JobRequest(JobType type, Long targetId, LearningInput input, String feedback)`다. `job.service.JobIntakeService`의 `receive(long userId, JobRequest request)` · `retry(long userId, long jobId)`는 `JobResult(long id, JobStatus status)`를 돌려주고, `discard(long userId, long jobId)`는 반환값이 없다. 세 동작은 한 `@Transactional` 안에서 `CreditLedger.lock(userId)`를 먼저 부른다. 받기는 주인 → 처리기 → 같은 대상 → 건 수 → 작업 저장 · 크레딧 사용 순서다. 처리기가 없으면 `INVALID_REQUEST`로 거절한다. 레포지토리에 같은 대상 조회(`findSameTarget`) · 사용자별 진행 건 수(`countByUserIdAndStatusIn`) · 버리기(`delete`)를 더했다.

- **채팅 호출** — `7-course-planning` step 1: `generation.ChatProvider.send(Request): Reply`는 SDK 없는 단일 요청 인터페이스이고, `Request`의 `jobId` · `jobType`은 호출의 작업 문맥이다(프로바이더로 보내는 메시지에는 넣지 않는다). `generation.llm.OpenAiChatProvider`가 SDK 4.75.1을 사용한다. `generation.ChatCall.<T>call(JobSnapshot, ChatSettings, String system, String user, Function<String, Optional<T>>): ChatResult<T>`는 최초 호출과 이어 쓰기 · 해석 · 호출 기록을 맡는다. `ChatResult.Success<T>(value)` 또는 `ChatResult.Failed<T>(GenerationOutcome.Failure)`를 돌려준다. `config.LlmConfiguration`이 조립한다. 시작 기록 실패 때는 요청하지 않고, 종료 기록 실패는 이미 받은 결과를 버리거나 재호출하게 하지 않는다. DB 자체가 실패하면 종료 칸과 사용량을 채우지 못할 수 있다.

- **계획 형식** — `7-course-planning` step 2: `generation.plan`의 `PlanningInput` · `CoursePlanInput`(새 코스 · 로드맵의 코스 · 다시 짜기) · `RoadmapPlanInput`(새 로드맵 · 다시 짜기)은 엔티티 없는 재료이며, 종류별 재료는 sealed interface의 record로 나눈다. `CoursePlanFormat.prompt(CoursePlanInput)` · `RoadmapPlanFormat.prompt(RoadmapPlanInput)`는 리소스를 채워 `PlanPrompt(system, user)`를 반환한다. `CoursePlanFormat.parseNewCourse(String)` · `parseLessons(String)`는 `Optional<CoursePlan(title, lessons)>`(기존 코스의 title은 null), `RoadmapPlanFormat.parse(String)`는 `Optional<RoadmapPlan(title, description, courses)>`를 반환하며 `ChatCall`에 메서드 참조로 넘긴다. `LessonOutline` · `CourseOutline`은 제목 · 설명 · `List<PlannedConcept>` 값이다. DB · 빈 · 처리기는 더하지 않았다.

- **새로 배우기 처리기** — `7-course-planning` step 3: `job.NewCourseIntakeHandler` · `job.NewRoadmapIntakeHandler`와 `generation.NewCourseGenerator` · `generation.NewRoadmapGenerator`를 짝으로 더했다. 받기는 추가 대상 검사가 없고, 생성은 스냅샷을 계획 재료로 바꾸어 설정의 `planning` 채팅과 형식 해석기를 부른다. `config.GenerationConfiguration`이 조립한다. 저장은 워커가 소유권을 확인한 트랜잭션에서 기존 엔티티 팩토리로 결과와 입력을 함께 만들고 새 결과 ID를 반환한다. 새 코스는 검토 중과 강의 목록, 새 로드맵은 검토 중과 강의 없는 짜기 전 코스들이다. 운영 흐름 시험의 대역은 [07 §2](./07-testing.md#2-llm-스텁)의 LLM 어댑터뿐이다.

- **로드맵 코스 처리기** — `7-course-planning` step 4: `job.PlanRoadmapCourseIntakeHandler`와 `generation.PlanRoadmapCourseGenerator`를 짝으로 더했다. 받기 트랜잭션의 `check`에서 순서 · 앞 코스 다 마침 · 끝나지 않은 로드맵 다시 짜기를 확인하고 첫 코스면 `Roadmap.start()`로 진행 중으로 바꾼다. 뒤의 건 수 · 크레딧 거절도 같은 트랜잭션에서 이 변경을 롤백한다. `generation.RoadmapCoursePlanReader.read(courseId)`는 읽기 전용 `TransactionTemplate` 안에서 로드맵 입력(수준은 첫 코스만) · 이 코스 계획 · 바로 앞 코스 실제 강의 목록 · 뒤 코스 계획을 `CoursePlanInput.RoadmapCourse` 값으로 복사하고 트랜잭션을 닫는다. 계획과 강의는 레포지토리의 부모 ID별 순서 조회를 쓰며, 이 읽기는 다시 짜기에서도 쓸 수 있다. 생성기는 그 뒤 `planning` 채팅과 `parseLessons`를 부르고 저장 트랜잭션에서 강의와 `Course.planLessons()`의 검토 중 상태를 함께 저장한다. 제목과 계획은 그대로이고 결과 ID는 원래 코스다. `config.GenerationConfiguration`이 읽기와 생성 빈을 조립한다.

- **다시 짜기 처리기** — `7-course-planning` step 5 구현: `job.ReplanCourseIntakeHandler` · `ReplanRoadmapIntakeHandler`와 `generation.ReplanCourseGenerator` · `ReplanRoadmapGenerator`를 짝으로 조립했다. 받기는 검토 중 상태만 허용하고 기존 사용자 행 잠금 · 성공하지 않은 같은 대상 작업 반환 · 크레딧 처리를 쓴다. 생성 재료는 읽기 전용 `TransactionTemplate`에서 값으로 복사한 뒤 호출하며 로드맵 코스는 `RoadmapCoursePlanReader`를 다시 쓴다. 저장은 워커의 소유권 확인 뒤 같은 트랜잭션에서 이전 강의 또는 짜기 전 코스를 `EntityManager.remove` → `flush()` → 순서 1부터 새 자식 저장으로 교체한다. 코스 제목과 상태는 유지하고 로드맵은 `Roadmap.replan(title, description)`으로 제목 · 설명을 함께 바꾸며 검토 중 상태를 유지한다. 저장 시 검토 중이 아니거나 로드맵 코스가 짜기 전이 아니면 불변식 위반으로 예외를 낸다. 결과 ID는 원래 대상이며 생성 실패 · 버리기는 이전 결과를 건드리지 않는다.

- **호출 기록** — `7-course-planning` step 0에서 `V12__create_llm_calls.sql`과 `generation.call.domain.LlmCall` · `LlmCallType` · `LlmCallOutcome`, `generation.call.repository.LlmCallRepository`를 더했다. `generation.call.LlmCallWriter`의 `long start(Long jobId, Long userId, JobType jobType, LlmCallType callType, String model)` · `void finish(long callId, LlmCallOutcome outcome, Integer inputTokens, Integer outputTokens, BigDecimal cost, int durationMs)`는 각각 `TransactionTemplate`의 `REQUIRES_NEW`로 짧게 커밋한다. 존재하지 않는 작업 · 사용자 연결만 비우고, 확인 직후 삭제되어 FK 위반이면 롤백 후 새 트랜잭션에서 연결을 재확인해 한 번 재시도한다. `job_type`은 남기며 종료는 끊긴 행도 채운다. `outcome`과 `duration_ms`는 함께 비거나 함께 찬다. JPA 쓰기의 시각은 공용 `Clock`의 Auditing이 채우고, 빈은 `config.GenerationConfiguration.llmCallWriter`가 조립한다.

- `6-job-worker` step 3 구현: `job.controller.JobController`가 받기 · 작업 하나 읽기 · 다시 시도 · 버리기를 HTTP에 연결한다. `job.dto.JobReceiveRequest`의 칸 사이 검증은 `FieldViolation.Reason`으로 공통 오류 경로에 전달한다. `job.service.JobReadService`는 `@Transactional(readOnly = true)`에서 작업 주인을 확인하고 `course` 패키지의 레포지토리로 대상 제목과 강의의 코스 ID를 읽어 값 객체만 반환한다. 경로 · 요청/응답 칸 · 실패 묶음은 [05 §2](./05-api.md#2-만드는-작업--받기-하나--다시-읽기)에 적었다.

- `6-job-worker` step 4 구현: `generation.JobGenerator`는 `type()` · `generate(JobSnapshot)` · `save(JobSnapshot, GeneratedResult)`를 나눈다. 운영 구현은 이 절의 새로 배우기 처리기부터 더했다. `JobSnapshot`과 그 안의 `Input`은 ID · 입력 · 피드백 · 이번 `claimCount`만 담는 record이고, 결과 값은 `GeneratedResult`로 전달한다. `GenerationOutcome.Success` 또는 `Failure`의 `PROVIDER_UNAVAILABLE` · `INVALID_OUTPUT` · `PROVIDER_REJECTED` · `INPUT_TOO_LARGE`를 반환한다. `ResultRef(long id)`는 새로 만든 결과 또는 원래 대상 ID다.
- `worker.JobRunner`의 `boolean runOnce()`는 작업 하나를 집었는지 반환한다. `EntityManager` 네이티브 SQL과 `TransactionTemplate`으로 꺼내기 · 갱신 · 저장 · 실패의 경계를 두고, 만들기는 트랜잭션 밖에서 한다. 호출자 트랜잭션도 거절한다. 저장의 첫 조건부 쓰기로 소유권을 확인한 뒤 `save` · 명시적 `flush()` · 작업 성공을 함께 커밋한다. 실패 전환과 `CreditLedger.restoreIfOwed`도 한 트랜잭션이다. 시각은 주입받은 `Clock` 하나로 계산하고 비교한다. 시한 갱신은 외부에서 받은 `ScheduledExecutorService`에 한 바퀴 동안만 예약하고, 완료 전에 진행 중인 갱신을 마치고 취소한다.
- `6-job-worker` step 5 구현: `config.WorkerConfiguration`이 `worker.WorkerLifecycle`을 조립한다. `SmartLifecycle`의 자동 시작에서 워커 수만큼 플랫폼 스레드와 각각의 `JobRunner` · 갱신 스케줄러를 만든다. 빈손과 한 바퀴 밖 예외 뒤에는 설정 간격을 쉰다. 워커 수 0은 실행기 · 스레드를 만들지 않는다. 운영 처리기는 `7-course-planning` step 3의 새 코스 · 새 로드맵과 step 4의 로드맵 코스와 step 5의 코스 · 로드맵 다시 짜기 다섯 종류이며, 강의 자료는 아직 받지 않는다. **종류를 받기 시작하는 TASK 는 그 종류의 `job.JobIntakeHandler` 와 `generation.JobGenerator` 를 같은 step 에서 함께 더한다** — 받기 처리기만 있으면 `JobRunner` 가 처리기 없는 작업에서 예외를 던져 `RUNNING` 으로 남고, 시한마다 되찾아져 끝나지 않는다(크레딧도 돌아오지 않는다).
- 종료는 `SmartLifecycle`의 단계 `Integer.MAX_VALUE`에서 시작한다. 각 실행기에 새 꺼내기 금지를 먼저 알리고 빈손 대기를 깨운 뒤, 모든 워커에 같은 종료 시각을 적용한다. 진행 중 갱신은 기다리는 동안 유지한다. 한도를 넘긴 워커는 `releaseCurrent()`의 `TransactionTemplate`(반환 트랜잭션 제한 5초)에서 같은 `claim_count` · `RUNNING` 조건으로 `QUEUED` · `lease_until = NULL` · `updated_at`을 쓰며 크레딧은 건드리지 않는다. 반환은 워커별로 나란히 처리한다. 늦은 호출은 해당 시도의 시한 상실 표시와 DB 쓰기 조건으로 막는다. 실행 스레드는 데몬 플랫폼 스레드라 한도 뒤 외부 호출이 JVM 종료를 붙잡지 않는다. 각 실행기의 현재 시도와 종료 표시는 그 실행기의 수명 제어에만 쓰고, 워커 사이 작업 분배 · 소유권은 DB로만 판단한다.
- 종료 순서의 근거: 로컬 의존성 `spring-context-7.0.9-sources.jar`의 `AbstractApplicationContext.doClose()`는 `lifecycleProcessor.onClose()` 뒤에 `destroyBeans()`를 호출하며, `DefaultLifecycleProcessor.stopBeans()`는 높은 단계부터 멈추고 `stop(Runnable)`의 콜백을 기다린다. `WorkerLifecycleTest.stopsBeforeConnectionPoolDestruction`은 실제 Spring 컨텍스트를 닫아 반환 시점에 Hikari 풀이 열려 있고, 콜백 뒤 풀의 `close()`가 끝나는 순서를 검증한다. 따라서 JPA · DB 풀의 singleton 파괴 전에 워커 반환을 마친다.

## 3. 배포

- **지금은 서버 1대 · 화면은 따로**(기존 `2-deploy` — ADR-146). 운영 절차는 `deploy/README.md`.
- **서버가 몇 대인지에 기대지 않는다** — 서버를 늘리거나 배포 때 옛 서버와 새 서버가 겹쳐도 구조가 그대로다(ADR-126 · ADR-133).
- 서버가 다시 켜져도 작업이 DB 에 남아 만들던 것 · 기다리던 것을 잃지 않는다 — 쓰면서 고치는 MVP 라 배포가 잦다(ADR-103 · ADR-102).

## 4. 만드는 작업

### 4.1 흐름

**만들기를 요청받으면 서버는 만드는 작업을 DB 에 한 줄 적고 바로 응답한다. 서버 안의 워커가 작업을 하나씩 꺼내 만든다**(ADR-103). 새로 배우기(코스 · 로드맵) · 다시 짜기 · 첫 코스 · 다음 코스 · 강의 자료가 모두 같다(ADR-143). 떠나도 서버가 끝까지 만든다(ADR-006).

```
화면 ─ 만드는 작업 받기 ─▶ 서버: 한 사람이 건 수 확인 · 크레딧 씀 · 작업(기다림) 적기 ─▶ 작업 돌려줌
워커: 먼저 받은 순서대로 꺼냄(잠그고 「만드는 중」 · 시한) ─▶ 재료 준비(관련 개념 · 판정)
     ─▶ LLM 호출 1번(잘리면 이어 쓰기) · 호출 기록 ─▶ 서버 검사(형식 · 10강)
     ─▶ 저장: 아직 내 작업인가 확인 → 결과 생성 · 입력 옮겨 담기 · 상태 바꾸기 · 작업 성공
화면: 작업을 정한 간격으로 다시 읽음 ─▶ 성공하면 결과 화면으로
```

### 4.2 상태

| 상태 | 언제 | 다음 |
|---|---|---|
| 기다림 | 받았을 때 · 실패를 다시 시도할 때 · 정상적으로 끄는 서버가 돌려놓을 때(ADR-126) | 워커가 먼저 받은 순서대로 집으면 만드는 중(ADR-128 · ADR-129) |
| 만드는 중 | 워커가 집었을 때 — 시한을 적고 늘린다(ADR-126) | 성공 · 실패. 시한이 지나면 다른 워커가 가져가 처음부터 |
| 성공 | 결과가 생겼을 때 — 결과를 가리킨다(ADR-130) | 결과를 지우거나 탈퇴하면 함께 지운다(ADR-134) |
| 실패 | 자동 다시 시도로도 안 될 때(ADR-127) | 다시 시도 → 기다림 · 버리기 → 지운다(ADR-086) |

- **결과는 성공할 때 생긴다**(ADR-130) — 그 전에는 작업에만 입력과 상태가 있다. 성공할 때 입력을 결과에 옮겨 담는다(ADR-137).
- **성공한 작업도 남긴다**(ADR-134) — 기다리는 화면은 언제 돌아와도 작업을 보고 결과로 넘어간다(ADR-077). 운영자는 남은 작업으로 얼마나 걸리고 얼마나 실패하는지 본다.

### 4.3 꺼내기 — 잠그고 바꾸는 것을 한 번에

**워커가 작업을 꺼낼 때 그 작업을 잠그고 「만드는 중」으로 바꾸는 것을 한 번에 한다. 다른 워커가 잠근 작업은 건너뛴다**(ADR-129).

- 워커는 한 번에 작업 하나만 만들고, 빈손일 때만 다음 작업을 집는다(ADR-133).
- 같은 순간에 둘이 꺼내면 먼저 받은 순서가 조금 어긋날 수 있다.

### 4.4 순서 — 먼저 받은 순서대로

**차례를 기다리는 작업은 먼저 받은 순서대로 꺼낸다 — 종류나 사람을 가리지 않는다**(ADR-128). 되찾은 작업도 원래 받은 순서대로 선다. 몰리면 강의 자료를 기다리는 사람도 앞에 걸린 코스 만들기들 뒤에 선다.

### 4.5 시한 · 되찾기

**워커가 작업을 집을 때 「언제까지 내가 만든다」는 시한을 적고, 만드는 동안 늘린다. 시한이 지난 「만드는 중」 작업은 어느 서버의 워커든 다시 가져가 처음부터 만든다**(ADR-126).

- 한 시도는 호출 한 번이고 중간 결과가 없어 처음부터 다시 만든다(ADR-119 · ADR-120 · ADR-125).
- 다른 워커가 되찾아 간 뒤 늦게 끝난 옛 워커는 결과를 쓰지 않는다 — 결과를 저장할 때 아직 자기 작업인지 확인한다.
- **배포처럼 정상적으로 끌 때는 진행 중인 시도가 끝나기를 기다린다**(ADR-126) — 새 작업은 꺼내지 않고, 진행 중인 시도가 결과를 저장하기를 정한 한도까지 기다린 뒤 끈다. 한도를 넘기면 들고 있던 작업을 기다림으로 돌려놓는다. 기다리는 동안 시한은 그대로 늘린다.
    - 기다리는 한도는 Spring 의 종료 대기(`timeout-per-shutdown-phase` — 지금 180초)와 컨테이너의 강제 종료 대기(`deploy/compose.yaml` 의 `stop_grace_period` — 지금 220초)보다 짧게 둔다. 길면 기다리는 도중 강제로 죽어 시한으로 되찾는 길로 떨어진다.
- 서버가 갑자기 죽으면 시한이 지날 때까지 그 작업이 멈춘다. 되찾은 작업에 이미 나간 호출 비용은 운영자가 낸다.
- 처리하는 중에 서버를 계속 죽이는 작업은 시한이 지날 때마다 되찾아져 다시 돈다 — 꺼낸 횟수에 상한을 두지 않는다. 드물어 장치를 두지 않고, 운영자가 남은 작업의 `claim_count` 로 알아본다(ADR-102 · README 2026-11-15).

### 4.6 실패 · 다시 시도

**다시 하면 나아질 수 있는 실패만 정한 횟수까지 자동으로 처음부터 다시 시도한다. 다시 해도 같은 실패는 바로 실패로 둔다. 출력이 잘리면 잘린 곳에서 이어 쓴다**(ADR-127).

| 실패 | 예 | 워커가 하는 일 |
|---|---|---|
| 일시적 | 잠깐의 연결 오류 · 시간 초과 · 프로바이더 몰림 | 정한 횟수까지 처음부터 다시 시도 |
| 형식 검사 불합격 | 강의 목록 · 로드맵 계획의 형식 · 10강 상한(ADR-120) · 강의 자료 · 카드의 형식 | 정한 횟수까지 처음부터 다시 시도 |
| 출력이 잘림 | 출력 제한에 닿아 끝나지 않음(ADR-119) | 잘린 곳에서 이어 써 붙인다 |
| 다시 해도 같은 것 | 키 · 쿼터 소진 같은 설정 문제 · 입력이 너무 큼 | 다시 시도하지 않고 바로 실패 |

- 그래도 안 되면 실패로 두고, 기다리는 화면에 「다시 시도」 · 「버리기」를 보인다(ADR-086). 입력이 너무 크면 다시 해도 같아 「버리기」만 보인다(ADR-174). 버리면 무엇이 되는지는 [06 §6](./06-screens.md#6-기다리는-화면).
- 실패를 버리면 작업만 지운다 — 치울 결과가 없다(ADR-130).
- 이어 쓴 부분이 앞 부분과 말투 · 흐름이 어긋날 수 있다.

### 4.7 지울 때의 작업

결과를 지우거나 탈퇴하면 그 작업은 상태와 상관없이 함께 지운다(ADR-140). 만드는 중이던 워커는 결과를 저장할 때 자기 작업이 없어 쓰지 않고, 성공하지 않은 작업에 쓴 크레딧은 복구한다. 범위 전체는 [02 §7](./02-domain-data.md#7-지우기--탈퇴--함께-사라지는-범위).

## 5. 서버가 여러 대여도

**서버들이 함께 지켜야 하는 것 — 한 사람이 건 수(ADR-104) · 크레딧(ADR-131) · 잠금 · 세션(ADR-142) — 은 DB 에서 세고 잠그며, 서버 메모리에 두지 않는다. 서비스 전체에서 동시에 만드는 수(ADR-105)는 서버마다 워커 수의 합으로 묶는다**(ADR-133).

- 예: 서버 1대 · 워커 3이면 동시에 3개, 4번째는 차례를 기다린다.
- 서버를 늘리면 전체 동시 수도 는다 — 그대로 두려면 서버마다 워커 수를 다시 나눈다. 바뀌는 것은 설정 값이다.
- 정해진 때 도는 일은 여러 대가 돌아도 한 번만 일어나게 한다(§8).

## 6. 동시 실행

| 묶는 것 | 규칙 | 넘으면 | 근거 |
|---|---|---|---|
| 한 사람이 동시에 걸 수 있는 만들기 | 정한 수까지 — 만드는 중 · 차례를 기다리는 것을 모든 종류 함께 센다 | 받지 않는다 — 「만드는 중인 것이 많음」 | ADR-104 · ADR-116 |
| 서비스 전체에서 동시에 만드는 수 | 워커 수의 합 | 차례를 기다린다 — 「차례를 기다리고 있어요」 | ADR-105 |

- 한 사람이 여럿을 걸 수 있게 한 것은 기다리는 화면을 떠나 다른 강의를 공부할 수 있게 하려는 것이다 — 새 강의를 열면 강의 자료를 만들어야 한다. 그래서 서비스 전체에 걸리는 만들기는 「10명 × 정한 수」까지다.
- 한 사람이 건 수는 받을 때 DB 에서 세고, 같은 순간의 두 요청이 함께 넘지 않게 한다(ADR-133).
- `6-job-worker` step 2 구현: `application.yml`의 `app.job.max-in-progress-per-user` 한 곳에서 임시 값 **3**을 읽는다(`config.JobProperties.maxInProgressPerUser()`). 받기와 실패의 다시 시도 모두 `CreditLedger.lock`의 `users` 행 `PESSIMISTIC_WRITE` 잠금 뒤 `QUEUED` · `RUNNING`을 DB에서 센다. 같은 사람의 동시 요청은 트랜잭션이 끝날 때까지 기다려 상한을 함께 넘지 않는다. 서버 메모리의 잠금 · 카운터는 없다.
- 앞에 몇 명인지 같은 숫자는 보이지 않는다(ADR-089).

## 7. 비용 통제 — 크레딧

**사용자마다 기간 한도를 두고 크레딧으로 센다**(ADR-106 · ADR-107). 서비스 전체 상한은 따로 두지 않는다 — 「한 명 한도 × 정원 10명」으로 묶인다. 한 사람 때문에 다른 사람이 막히지 않는다.

| 규칙 | 내용 | 근거 |
|---|---|---|
| 종류마다 정한 크레딧 | 만들기마다 쓰는 토큰을 재서 정한다 — 모델 · 프롬프트가 바뀌면 다시 잰다 | ADR-107 |
| 다시 짜기도 쓴다 | 횟수 상한은 없다 | ADR-111 |
| 성공한 만들기만 크레딧이 나간다 | 받을 때 쓰고, 실패하면 복구한다 — 성공하면 그대로 둔다. 다시 시도는 다시 쓴다 | ADR-112 · ADR-166 |
| 한 달마다 채운다 | 남은 것은 넘기지 않고 한 달 몫으로 다시 맞춘다 | ADR-108 · ADR-109 |
| 채우는 날 | 첫 몫은 신청할 때 넣는다 — 승인 전에는 쓰지 못한다. 그 뒤 사람마다 승인한 날부터 30일마다 새벽 4시(하루의 경계와 같다 — ADR-017) | ADR-110 · ADR-173 |
| 한 달 몫 | 운영자의 한 달 예산 ÷ 정원 10명 — 그 몫으로 코스 몇 개도 만들 수 없으면 정원을 다시 본다 | ADR-115 |
| 보이는 곳 | 내 정보 탭에서만 — 남은 크레딧 · 다음에 채워지는 날 | ADR-113 |
| 모자라면 | 받지 않는다 — 「크레딧 부족」, 다음에 채워지는 날과 함께 | ADR-116 |

- 한 달 몫의 구현 설정은 `application.yml`의 `app.credit.monthly-amount` 한 곳이며 `config.CreditProperties.monthlyAmount`로 읽는다. 현재 임시 값은 §10대로 두고, 신청 서비스와 검증도 이 설정을 사용한다.
- 종류별 값도 `application.yml`의 `app.credit` 아래에 두고 `config.CreditProperties`로 읽는다. 받는 쪽이 종류에 맞는 값을 골라 `CreditLedger.consume`에 양수 `amount`로 넘긴다. 설정 키 · 읽는 메서드 · 임시 값은 §10에 적었다.
- **담는 모양** — 남은 양과 움직인 기록(채움 · 씀 · 복구 — ADR-166)을 함께 둔다(ADR-131). 남은 양은 `users` 의 칸이고 계정 패키지가 다룬다(ADR-153). 나중에 충전을 더하면 충전 몫과 기록 종류를 하나씩 더해 채워 주는 크레딧과 나눈다.
- 실패에 든 비용은 크레딧으로 묶이지 않는다 — 자동 다시 시도 횟수와 한 사람이 동시에 걸 수 있는 수로 묶는다(ADR-125).
- 학습과 상관없는 요청은 따로 막지 않는다 — 그 사람의 크레딧 안에서 든다(ADR-114).
- 달력 달로 보면 한 사람이 채우는 날 앞뒤로 두 번 몫을 쓸 수 있어, 운영자가 보는 한 달 비용이 「한 달 몫 × 10」을 넘을 수 있다.
- 탈퇴했다가 다시 승인받은 사람은 새 주기 · 새 크레딧이다(ADR-084) — 알고 넘어간다.

## 8. 서버가 정해진 때 하는 일

**지금까지 정한 것은 크레딧 채우기 하나다**(ADR-110 · ADR-173) — 매일 새벽 4시에 다음에 채우는 때(`credit_refill_at`)가 지난 승인된 사람을 한 달 몫으로 다시 맞추고, 그 때를 30일 뒤로 옮긴다(ADR-175). 첫 몫은 신청할 때 넣으므로 이 일이 채우지 않는다. 서버가 여러 대여도 한 번만 일어나게 한다(ADR-133).

## 9. 관측

- **호출 기록** — LLM · 임베딩을 부를 때마다 한 줄(ADR-139 — [03 §6](./03-generation.md#6-호출-기록)).
- **남은 작업** — 만들기가 얼마나 걸리고 얼마나 실패하는지(ADR-134).
- **숙련도 반영 내역** — 운영자가 DB 로 보고 튜닝한다(ADR-121).
- **사용자 화면에는 시스템 정보를 보이지 않는다** — 토큰 · 비용 · 모델 이름 · 다시 시도 횟수 · 오류 code · 대기 순서 숫자. 운영자는 DB · 로그로 본다(ADR-089).
- 요청 로그는 기존 `2b-ops-logging` 을 그대로 쓴다(ADR-146).

### 9.1 로그

**표준 출력에 평문 한 줄씩 낸다 — 무엇을 남기지 않는지는 [`AGENTS.md`](../AGENTS.md) 금지 26.** 서버의 journald 가 **14일과 500MiB 가운데 먼저 닿는 쪽**까지 보관한다. 보는 법은 [절차서 「로그 보기」](../deploy/README.md#로그-보기).

| 수준 | 언제 | 예 |
|---|---|---|
| `ERROR` | 사람이 원인을 봐야 하는 우리 쪽 실패 — 스택과 함께 | 처리되지 않은 예외(5xx) · 결과 저장 실패 · 불변식 위반 |
| `WARN` | 외부 실패 · 이상 징후 — 시스템은 계속 돈다 | 구글 토큰 교환 실패 · 외부 호출 실패 · 시한 상실 |
| `INFO` | 흐름 한 줄 | 접근 줄 · 로그인 결과(신청 전 로그인 `login result=not_applied` 는 MDC 에 `userId` 가 없다) · 기동/종료 · 작업 받기 · 끝남 |
| `DEBUG` 이하 | 운영에서 끈다 | — |

`6-job-worker` step 4의 워커 줄은 커밋 뒤 `INFO`의 `job finished status={SUCCEEDED|FAILED}`, 소유권 확인이 0행이면 한 시도에 한 번 `WARN`의 `job lease lost`, 결과 저장 트랜잭션의 예외에는 스택과 함께 `ERROR`의 `job save failed`다. 처리와 갱신 중 `jobId` · `userId`를 넣고 끝나면 지운다. 저장 예외는 원래 메시지 · cause · suppressed를 넘기지 않고 예외 타입 이름과 스택 위치만 남긴다.

`7-course-planning` step 10의 `generation.GenerationFailures`는 다섯 생성 처리기의 재료 읽기 · 프롬프트 준비 등에서 예상 밖 `RuntimeException`을 잡으면 `ERROR`의 `job generate failed`를 한 번 남기고 `PROVIDER_UNAVAILABLE`을 반환한다. 자동 다시 시도마다 한 줄씩 남을 수 있다. `jobId` · `userId`는 워커가 넣은 MDC를 그대로 쓰며, 스택에는 원래 메시지 · cause · suppressed 없이 예외 타입 이름과 위치만 싣는다. 정상 생성과 프로바이더 실패에는 이 줄을 남기지 않는다.

`NewCourseGeneratorIntegrationTest`의 `OutputCaptureExtension`이 캡처한 실제 출력:

```text
2026-11-18T14:17:15.618+09:00 ERROR jobId=1, userId=1 77732 --- [mogum] [    Test worker] c.mogum.generation.GenerationFailures    : job generate failed
```

`6-job-worker` step 5의 줄은 정상 종료 한도 뒤 조건부 반환이 커밋된 때 `INFO`의 `job released`(`jobId` · `userId`)와 한 바퀴 밖 예외의 `ERROR`인 `worker poll failed`다. 정상 종료 중 반환(`releaseCurrent`)이 실패해도 같은 줄이다(`WorkerLifecycle` 의 종료 스레드). 루프는 같은 예외 타입의 연속 실패에서 첫 번째만 스택을 남기고, 다음 실패는 같은 한 줄만 남긴다. 성공한 꺼내기(빈손 포함) 뒤에는 연속 실패 표시를 비운다. 스택에는 원래 메시지 · cause · suppressed 없이 타입 이름과 위치만 싣는다.

`WorkerLifecycleIntegrationTest` · `WorkerLifecycleTest`의 `OutputCaptureExtension`이 캡처한 실제 출력:

```text
2026-11-15T17:55:06.072+09:00  INFO jobId=42, userId=38 68829 --- [mogum] [m-worker-1-stop] com.mogum.worker.JobRunner               : job released
2026-11-15T17:55:07.928+09:00 ERROR  68829 --- [mogum] [ mogum-worker-0] com.mogum.worker.WorkerLifecycle         : worker poll failed
```

`JobRunnerIntegrationTest`의 `OutputCaptureExtension`이 캡처한 실제 출력:

```text
2026-11-15T17:44:17.508+09:00  INFO jobId=1, userId=1 57664 --- [mogum] [    Test worker] com.mogum.worker.JobRunner               : job finished status=SUCCEEDED
2026-11-15T17:44:17.938+09:00  INFO jobId=14, userId=13 57664 --- [mogum] [    Test worker] com.mogum.worker.JobRunner               : job finished status=FAILED
2026-11-15T17:44:17.509+09:00  WARN jobId=1, userId=1 57664 --- [mogum] [pool-3-thread-1] com.mogum.worker.JobRunner               : job lease lost
2026-11-15T17:44:17.589+09:00 ERROR jobId=2, userId=2 57664 --- [mogum] [    Test worker] com.mogum.worker.JobRunner               : job save failed
```

`6-job-worker` step 2의 작업 줄은 커밋 뒤 `INFO`로 남긴다. 새 작업은 `job received type={type}`, 실패에서 기다림으로 돌아간 작업은 `job retried`다. `common.logging.RequestLogFilter.JOB_ID`(`jobId`)를 줄을 쓰는 곳에서 넣고 `finally`에서 지운다. 요청의 `requestId` · `userId`는 그대로 둔다. 이미 있는 작업 반환 · 거절 · 버리기는 작업 줄을 남기지 않는다.

`JobIntakeServiceIntegrationTest`의 `OutputCaptureExtension`이 캡처한 실제 출력:

```text
2026-11-15T12:53:58.709+09:00  INFO jobId=27 37745 --- [mogum] [    Test worker] com.mogum.job.service.JobIntakeService   : job received type=NEW_COURSE
2026-11-15T12:53:58.716+09:00  INFO jobId=27, userId=33, requestId=test-job-request 37745 --- [mogum] [    Test worker] com.mogum.job.service.JobIntakeService   : job retried
```

신청 전 로그인 줄의 실제 출력(`OAuthLoginHandlerIntegrationTest`의 `OutputCaptureExtension` 캡처):

```text
2026-11-09T02:12:09.565+09:00  INFO requestId=ffca82fe-917b-4c93-8414-efed31108b51 46685 --- [mogum] [o-auto-1-exec-1] com.mogum.auth.OAuthLoginHandler         : login result=not_applied
```

MDC 키의 출력 순서는 정해지지 않았다. 시각 · 요청 ID · PID · 스레드 이름은 실행마다 달라진다.

- **MDC 키** — 줄마다 MDC 값을 모두 `키=값` 으로 싣는다. 키를 더해도 로그 패턴은 고치지 않는다. 지금 키는 `requestId` · `userId` 이고, 작업을 다루는 코드는 `jobId` 를 더한다. 키 이름은 `common.logging` 의 상수로 둔다.
- **시각** — 사람이 읽는 시각은 한국 시간(`+09:00`)이다. 저장 시각 · DB 세션은 UTC 그대로다.
- **구현 사실은 코드가 출처다** — 요청 ID · 접근 줄(`common.logging.RequestLogFilter`), 로그인 결과의 원인 분류(`auth.OAuthLoginHandler`), 로그 패턴(`application.yml`), 보관 설정(`deploy/journald/`). 옛 문서의 상세는 git 기록(`main` `7646433` 의 04 §9.2)에 있다.
- 새 로그 줄을 정한 TASK 는 그 줄(수준 · 언제 · 키)을 이 절에 더한다.
- 깨진 그림은 기록하지 않는다 — 운영자는 사용자의 말이나 품질 점검으로 안다(ADR-117).

## 10. 아직 정하지 않은 값

같은 개념 판정은 `app.concept.same-threshold`(`config.ConceptProperties.sameThreshold()`)로 설정한다. `0.80`은 임시 값 — 실측 전이며, 0 초과 1 이하가 아니면 기동을 거부한다. 강의 자료 TASK의 phase 뒤 · 병합 전에 `llmTest`로 실제 개념 쌍을 재서 바꾼다(ADR-187).

| 값 | 근거 |
|---|---|
| 한 사람이 동시에 걸 수 있는 수 — `app.job.max-in-progress-per-user` 임시 값 3(`JobProperties.maxInProgressPerUser()`). `6-job-worker` step 2에서 사용자 행 잠금으로 동시 요청을 직렬화했다(§6). 실제 운영 값은 재 보고 정한다 | ADR-104 · ADR-133 |
| 서버마다 워커 수 · 빈손/예외 뒤 쉬는 간격 · 정상 종료 대기는 아래 임시 설정. 배포는 기존 `deploy/deploy.sh`의 Compose `up -d --no-build`로 옛 컨테이너를 끄고 새 컨테이너를 켠다. 겹치는 배포 장치는 없다 | ADR-105 · ADR-126 · ADR-133 |
| 시한 · 늘리는 간격은 아래 임시 설정. 각 워커의 `ScheduledExecutorService.scheduleWithFixedDelay`로 만드는 동안과 정상 종료 대기 중에 갱신한다. 시한 식과 강의 목록 TASK에서 정한 호출 값은 아래에 적었다 | ADR-126 |
| 자동 다시 시도는 아래 임시 설정. 이어 쓰기 횟수 · 간격 · 프로바이더 오류 분류는 아래 step 1 구현 값이다(갈래 자체는 ADR-163) | ADR-127 |
| 꺼내기는 PostgreSQL CTE 하나에서 `SELECT … ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1` → `UPDATE … RETURNING id`. `QUEUED` 또는 같은 `Clock`의 현재 시각보다 `lease_until`이 작은 `RUNNING` 하나를 집고 `claim_count + 1` · 시한 · 시작/수정 시각을 바꾼다 | ADR-129 |
| 종류별 크레딧 값 · 한 달 몫 — 한 달 몫은 계정 TASK 가 설정 한 곳에 임시 값 100 으로 두고, 홈 · 크레딧 TASK 에서 M1 · M2 동안 쌓인 호출 기록(`llm_calls`)으로 정한다(README 2026-11-17 — 처음에는 강의 목록 TASK 였다). 종류별 값은 `6-job-worker` step 1에서 아래 설정에 모두 임시 값 1로 두었다 | ADR-107 · ADR-115 |
| 크레딧 채우기를 여러 서버에서 한 번만 일어나게 하는 방법 · 새벽 4시에 서버가 꺼져 있었을 때 따라잡는 방법 | ADR-133 · ADR-173 |
| 패키지를 코스 · 로드맵 등으로 나누는 방식 — 테이블은 02 §8 에서 정했다 | ADR-148 |

워커의 임시 값은 `application.yml`의 `app.worker` 한 곳이며 `config.WorkerProperties`로 읽는다(`6-job-worker` step 4 · 5). 테스트는 생성자로 짧은 간격 또는 0의 다시 시도 간격을 전달한다. 자동 다시 시도 횟수는 첫 호출 **뒤에 더 부르는 횟수**다.

| 설정 키 | 읽는 메서드 | 임시 값 |
|---|---|---|
| `app.worker.lease-duration` | `leaseDuration()` | `16m` |
| `app.worker.renewal-interval` | `renewalInterval()` | `30s` |
| `app.worker.max-retries` | `maxRetries()` | `2`(최대 호출 3번) |
| `app.worker.retry-delay` | `retryDelay()` | `1s` |
| `app.worker.count` | `count()` | `2`(0이면 API만 실행) |
| `app.worker.poll-interval` | `pollInterval()` | `1s` |
| `app.worker.shutdown-wait` | `shutdownWait()` | `150s` |

시한은 **한 번 꺼낸 시도의 최장 시간보다 길어야 한다**. `R`은 추가 자동 다시 시도 횟수, `C`는 호출마다 허용한 이어 쓰기 횟수, `T호출`은 채팅 호출 시간 제한, `T임베딩`은 한 시도에서 개념 정의를 한 번에 보내는 임베딩 호출 한 번의 시간 제한, `D다시시도`는 자동 다시 시도 사이 간격이다. 이어 쓰기 사이 간격은 0초다. `T_DB`는 재료 읽기 · 시한 처리 · 결과/실패 쓰기를 포함한 DB 처리의 여유다.

```text
시한 > (R + 1) × (max_묶음((C + 1) × T호출) + T임베딩) + R × D다시시도 + T_DB
현재: 960초 > 3 × (2 × 140초 + 20초) + 2 × 1초 + 10초 = 912초
```

`8-material-cards` step 0에서 강의 자료 채팅 `T호출=140초`, `C=1`과 임베딩 `T임베딩=20초`의 임시 값을 더했다(ADR-186 · ADR-187). 강의 목록 채팅은 50초 · 이어 쓰기 1번이며, 임베딩을 하지 않는 강의 목록에도 임베딩 시간을 더해 식 하나로 보수적으로 검사한다. `T_DB=10초`는 재료 읽기 · 호출 기록 · 시한 처리 · 결과/실패 쓰기에 남기는 예산이며 실측 상한은 아니다. 운영 프롬프트와 DB 부하로 다시 잰다. `config.LlmConfiguration`의 생성자는 모든 채팅 묶음 중 `(C + 1) × T호출`이 가장 큰 값에 임베딩 시간을 더해 엄격한 부등식을 검사하며, 각 채팅 호출 시간 제한보다 정상 종료 대기(150초)가 길어야 한다. 깨지면 빈 생성 실패로 기동을 거부한다.

채팅 · 임베딩 설정은 `application.yml`의 `app.llm` 한 곳이고 `config.LlmProperties` record로 읽는다. 생성자는 `planning` · `material` · `embedding`의 누락, 채팅 설정의 유효성, 임베딩 모델이 빈 값인지와 시간 제한이 양수인지를 검사해 설정 바인딩 실패로 기동을 거부한다. `GenerationConfiguration`은 `LlmProperties.planning()`으로 계획 묶음을 받아 다섯 생성 처리기에 넘긴다. `material()`과 `embedding()`은 아직 생성 처리기에 연결하지 않았다. step 1의 임베딩 호출 경계에는 SDK 없는 `generation.EmbeddingSettings(model, timeout)`을 넘긴다. 임베딩 설정 바인딩은 별도 `LlmProperties.Embedding(model, timeout)` record이며 채팅의 출력 상한 · 이어 쓰기 설정은 쓰지 않는다.

| 설정 키 | 값 |
|---|---|
| `app.llm.openai.api-key` | `${OPENAI_API_KEY:}` — 비어 있으면 기동 거부 |
| `app.llm.openai.base-url` | 빈 문자열이면 SDK 기본 주소. 가짜 HTTP 서버에서 바꾼다 |
| `app.llm.chats.planning.model` | `gpt-5.6-terra` |
| `app.llm.chats.planning.max-completion-tokens` | 출력 상한 — [03 §7](./03-generation.md#7-아직-정하지-않은-값) |
| `app.llm.chats.planning.timeout` | `50s` |
| `app.llm.chats.planning.max-continuations` | `1` |
| `app.llm.chats.material.model` | `gpt-5.6-terra` |
| `app.llm.chats.material.max-completion-tokens` | `10000` — 임시 값(ADR-186) |
| `app.llm.chats.material.timeout` | `140s` — 임시 값 |
| `app.llm.chats.material.max-continuations` | `1` |
| `app.llm.embedding.model` | `text-embedding-3-large` |
| `app.llm.embedding.timeout` | `20s` — 임시 값 |
| `app.llm.database-allowance` | `10s` |
| `app.llm.prices[gpt-5.6-terra].input` | `2.00` USD / 1M 토큰 |
| `app.llm.prices[gpt-5.6-terra].cached-input` | `0.20` USD / 1M 토큰 |
| `app.llm.prices[gpt-5.6-terra].output` | `12.00` USD / 1M 토큰 |
| `app.llm.prices[text-embedding-3-large].input` | `0.13` USD / 1M 토큰(ADR-187) |
| `app.llm.prices[text-embedding-3-large].cached-input` | `0` — 캐시 입력 과금 없음 |
| `app.llm.prices[text-embedding-3-large].output` | `0` — 출력 없음 |

모든 채팅 · 임베딩 모델에 단가가 있어야 기동한다. 임베딩 단가는 기존 `TokenPrices(input, cachedInput, output)`에 입력 `0.13` · 캐시 입력과 출력 `0`으로 담는다. 임베딩 사용량은 입력 토큰과 출력 `0`을 넘기면 같은 비용 식으로 입력 비용만 계산된다. 비용은 `(입력 − 캐시 입력) × 입력 단가 + 캐시 입력 × 캐시 단가 + 출력 × 출력 단가`를 1,000,000으로 나눈 USD다. 사용량이 없으면 비용도 비우고, 캐시 정보만 없으면 캐시 입력을 0으로 계산한다. SDK 자동 재시도는 `maxRetries(0)`이며, `RequestOptions`로 요청별 시간 제한을 건다. `CompletionUsage.PromptTokensDetails.cachedTokens()`, `ChatCompletionCreateParams.Builder.addAssistantMessage(String)`, `OpenAIServiceException.statusCode()/code()`, `OpenAIIoException` · `OpenAIInvalidDataException`과 그 `InterruptedIOException` 원인을 4.75.1 소스 JAR로 확인했다. `JsonHandler`가 본문 읽기 시간 초과를 데이터 예외로 감싸므로 원인 타입으로도 검사한다. SDK `OkHttpClient`는 `retryOnConnectionFailure(false)`도 적용한다.

| 프로바이더 결과 | 호출 결과 | 작업 실패 |
|---|---|---|
| `stop` | `SUCCEEDED`, 해석 실패는 `INVALID_OUTPUT` | 해석 실패면 `INVALID_OUTPUT` |
| `length` | `TRUNCATED` | 이어 쓰기 한도 뒤에도 잘리면 `INVALID_OUTPUT` |
| 그 밖의 종료 · 빈 내용 · 응답 해석 오류 | `INVALID_OUTPUT` | `INVALID_OUTPUT` |
| 시간 초과 | `TIMEOUT` | `PROVIDER_UNAVAILABLE` |
| 429 (`insufficient_quota` 제외) | `RATE_LIMITED` | `PROVIDER_UNAVAILABLE` |
| 429 `insufficient_quota` | `REJECTED` | `PROVIDER_REJECTED` |
| 5xx · 연결 오류 | `PROVIDER_ERROR` | `PROVIDER_UNAVAILABLE` |
| 401 · 403 · 그 밖의 4xx | `REJECTED` | `PROVIDER_REJECTED` |
| 400 `context_length_exceeded` | `REJECTED` | `INPUT_TOO_LARGE` |

`8-material-cards` step 1의 `generation.EmbeddingProvider.send(Request)`는 작업 ID · 종류 · `EmbeddingSettings` · 정의 글 목록을 받아 입력 순서의 `List<List<Float>>` · 호출 결과 · 작업 실패 · 입력 토큰을 돌려준다. `EmbeddingCall.call(JobSnapshot, EmbeddingSettings, List<String>)`은 `EmbeddingResult.Success(value)` 또는 `Failed(failure)`를 돌려준다. 호출 전 `EMBEDDING` 기록을 커밋하고, 트랜잭션 밖에서 한 번 호출한 뒤 결과 · 입력 토큰 · 입력 단가 비용 · 시간을 기록한다. 출력 토큰은 비워 둔다. 벡터 수가 입력 수와 다르거나 차원이 0 · 불일치이면 `INVALID_OUTPUT`이며, 확인한 사용량은 그대로 기록한다. 한 시도에서 카드 개념 정의를 한 번에 보내는 임베딩은 한 번이고, 임베딩 안의 다시 시도 · 이어 쓰기는 없다. 호출자가 트랜잭션을 쥐면 전송과 기록을 시작하지 않는다. 예상 밖 예외는 호출 기록을 마칠 수 있으면 `PROVIDER_ERROR`로 마친 뒤 다시 던져 기존 생성 처리기의 `GenerationFailures`가 원문 없는 `job generate failed`로 기록하게 한다. 기록 저장의 예상 밖 예외도 던진다.

`generation.llm.OpenAiEmbeddingProvider`는 채팅 어댑터가 소유한 같은 `OpenAIClient`(키 · 기준 주소 · HTTP 풀 · `maxRetries(0)`)를 쓰고, 빈 조립은 `config.LlmConfiguration`에 둔다. `RequestOptions`의 시간 제한은 요청의 임베딩 설정이다. HTTP · 시간 초과 · 연결 실패 분류는 위 채팅 표와 같고 `OpenAiFailures`로 공유한다. SDK 데이터/JSON 형식 오류는 `INVALID_OUTPUT`으로 돌리며, 그 밖의 예외는 숨기지 않는다. 응답 `index`로 순서를 복원하고 중복 · 범위 밖 · 누락 index는 형식 실패다. 정의 글 · 벡터 · 프로바이더 원문은 DB와 로그에 넣지 않는다.

SDK **4.75.1 소스 JAR**의 `EmbeddingCreateParams.Body.Builder`는 `encodingFormat`을 `BASE64`로 초기화한다. 따라서 이 어댑터가 실제 보내는 `encoding_format`은 `base64`다. [공식 API 문서](https://developers.openai.com/api/reference/resources/embeddings/methods/create)의 HTTP 기본값 `float`와 SDK가 넣는 기본값은 구별한다. 같은 JAR의 `Embedding.embedding()` → `EmbeddingValue.asFloats()`는 base64 바이트를 little-endian IEEE 754 32비트 float로 풀어 `List<Float>`를 반환하며, float 배열 응답도 그대로 받는다. 가짜 서버가 실제 SDK 요청의 기본값과 두 응답 형식을 확인한다.

실제 유료 호출은 실행하지 않았다. 가짜 서버가 요청 형식 · 요청 수 · 이어 쓰기와 오류 분류를 확인한다([07 §2](./07-testing.md#2-llm-스텁)).

현재 계산할 수 있는 제약은 `WorkerProperties`의 생성자에서 검사해 설정 바인딩 실패로 기동을 거부한다: 시한 · 갱신/쉬는 간격 · 종료 대기는 양수, `갱신 간격 < 시한`, 워커 수 · 추가 다시 시도 횟수/간격은 0 이상, `종료 대기 < 170초`다. Spring 단계 대기는 180초로 반환 트랜잭션 제한 5초를 위한 여유를 10초 이상 남기고, 로컬 · 운영 컨테이너 대기는 220초다. 워커는 Tomcat 정상 종료보다 먼저, 따로 멈춘다 — 실제 종료 시간은 `종료 대기(150초) + 반환(최대 5초) + Tomcat 정상 종료`의 합이다. 컨테이너의 220초에서 워커 대기와 반환을 빼면 Tomcat에 65초가 남는다. 지금 API 요청은 짧아 여유가 있지만, 단계별 제한 180초 전부를 Tomcat이 쓰는 경우까지 보장하는 값은 아니다. 종료 대기 상한 170초는 `WorkerProperties`에 상수로 있으니 Spring 단계 대기를 바꾸면 함께 본다. `WorkerPropertiesTest`는 상한의 엄격한 부등식과 실제 Spring 단계 대기 180초를, `DeployFilesTest`는 두 Compose 파일의 220초가 Spring 단계 대기보다 길고 워커 대기 · 반환 뒤 Tomcat 여유가 남는지를 검사한다.

2026-11-23 로컬 Gradle 캐시의 Spring Boot **4.1.1** · Spring Context **7.0.9** 소스 JAR로 종료 순서를 확인했다. `WorkerLifecycle.getPhase()`는 `Integer.MAX_VALUE`(2,147,483,647), `WebServerGracefulShutdownLifecycle.getPhase()`는 `WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE` = `SmartLifecycle.DEFAULT_PHASE - 1024`(2,147,482,623), 서블릿의 `WebServerStartStopLifecycle.getPhase()`는 `START_STOP_LIFECYCLE_PHASE` = 위 값 − 1024(2,147,481,599)다. `DefaultLifecycleProcessor.stopBeans()`는 단계를 내림차순으로 처리하고 `LifecycleGroup.stop()`에서 해당 단계의 콜백을 기다린다. 웹 정상 종료 단계가 호출하는 `TomcatWebServer.shutDownGracefully()` → `GracefulShutdown.doShutdown()`에서 비로소 커넥터를 pause하고 수신 소켓을 닫는다. 따라서 **워커 종료 대기 중 Tomcat은 계속 요청을 받을 수 있다** — 소스의 단계 순서로 확인한 결과이며, 실제 종료 중 HTTP 응답을 보내는 실험은 수행하지 않았다. `AbstractApplicationContext.doClose()`도 `lifecycleProcessor.onClose()` 뒤에 `destroyBeans()`를 호출하므로 이 대기 동안 singleton과 DB 풀은 아직 파괴되지 않는다.

기본 통합 테스트는 공용 `IntegrationTestConfiguration.OAuthInitializer`가 `app.worker.count=0`을 적용한다. 별도 앱 컨텍스트를 늘리지 않고, 워커 시험이 `runOnce()` 또는 짧은 설정으로 직접 만든 `WorkerLifecycle`의 시작 · 종료를 제어한다.

종류별 임시 값(`application.yml` · `config.CreditProperties`):

| 종류 | 설정 키 | 읽는 메서드 | 임시 값 |
|---|---|---|---|
| `NEW_COURSE` | `app.credit.new-course` | `newCourse()` | 1 |
| `NEW_ROADMAP` | `app.credit.new-roadmap` | `newRoadmap()` | 1 |
| `PLAN_ROADMAP_COURSE` | `app.credit.plan-roadmap-course` | `planRoadmapCourse()` | 1 |
| `REPLAN_COURSE` | `app.credit.replan-course` | `replanCourse()` | 1 |
| `REPLAN_ROADMAP` | `app.credit.replan-roadmap` | `replanRoadmap()` | 1 |
| `MATERIAL` | `app.credit.material` | `material()` | 1 |

확정 값은 홈 · 크레딧 TASK 에서 호출 기록으로 정한다(README 2026-11-17). 그때까지 쓰는 사람은 운영자뿐이라 임시 값으로 둔다.
