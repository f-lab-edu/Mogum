# Step 1: course-entities

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §1 · §2(결과와 상태 — 생성 흐름) · §8.1(입력 칸 묶음) · §8.3(**이 step 의 계약이다**) · §8.7(`roadmaps.status` · `courses.status` · `level` · `purpose`)
- `docs/GLOSSARY.md` — 「배우는 단위」(`Roadmap` · `Course` · `Lesson` · `Material` · 다룰 개념 `PlannedConcept`) · 「만들기와 고치기」의 「주제 · 지금 수준 · 목적 · 메모」(`LearningInput`)
- `docs/ADR.md` — ADR-130 · ADR-132 · ADR-136 · ADR-137 · ADR-138
- `docs/04-jobs-and-cost.md` §2.1 · §2.3(「커리큘럼 모델」 줄)
- `docs/TEST_GUIDE.md` 전체 — 특히 §4(픽스처 · 레포지토리 인터페이스)
- `AGENTS.md` — 금지 6 · 7 · 24 · 27 · 28 · 작업 방식(enum Javadoc · TDD)
- step 0 이 만든 것: `backend/src/main/resources/db/migration/V10__create_learning_model.sql` · `backend/src/test/java/com/mogum/course/repository/CourseSchemaRepositoryTest.java`
- 본보기 코드: `backend/src/main/java/com/mogum/account/domain/User.java` · `CreditEntry.java` · `UserStatus.java` · `account/repository/UserRepository.java` · `CreditEntryRepository.java` · `common/persistence/AuditedEntity.java`
- 본보기 테스트: `backend/src/test/java/com/mogum/account/domain/UserFixtures.java` · `UserTest.java` · `CreditEntryTest.java` · `account/repository/CreditEntryRepositoryTest.java` · `support/RepositoryTestTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

step 0 이 `V10` 으로 테이블을 만들었다. 이 step 은 `course` 패키지 — 로드맵 · 코스 · 강의 · 강의 자료 — 의 엔티티 · enum · 값 타입 · 레포지토리를 만든다. `card` 패키지는 step 2 다.

**계약 문서(02 §8 · GLOSSARY · `AGENTS.md` 패키지 줄)는 이 phase 전에 정해 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 값 타입 · enum — `com.mogum.course.domain`

- `RoadmapStatus`(`REVIEWING` · `IN_PROGRESS` · `COMPLETED`) · `CourseStatus`(`NOT_PLANNED` · `REVIEWING` · `LEARNING` · `COMPLETED`) · `Level`(`BEGINNER` · `BASICS` · `FAMILIAR` · `OTHER`) · `Purpose`(`WORK` · `INTERVIEW` · `GENERAL` · `OTHER`) — 02 §8.7 의 값 그대로다. 상수마다 02 §8.7 표의 이름 · 뜻으로 한국어 Javadoc 한 줄을 단다(`AGENTS.md` 작업 방식).
- **`LearningInput`** — 입력 칸 묶음(`topic` · `level` · `levelOther` · `purpose` · `purposeOther` · `memo`)을 담는 `@Embeddable` 이다. 칸 이름은 02 §8.1 그대로다. `roadmaps` · `courses` 가 같은 매핑을 쓰고, 뒤의 만드는 작업 TASK 도 `generation_jobs` 에 같은 타입을 쓴다. 값을 바꾸지 않는 타입으로 둔다(입력은 만든 뒤 바꾸지 않는다 — ADR-041). 로드맵의 코스처럼 칸이 모두 비면 엔티티에서 `null` 로 읽혀도 된다.
- **`PlannedConcept`** — 다룰 개념 하나(`name` · `definition`)의 record 다. `lessons.concepts` · `courses.plan_concepts`(`jsonb` — `[{name, definition}]`)에 `List<PlannedConcept>` 로 담는다.
    - **`jsonb` 매핑은 Hibernate 7.4.5(지금 Boot 4.1.1 이 고르는 버전)에서 확인해서 정한다.** 확인하지 않은 동작을 가정하지 마라. 예를 들어 `@JdbcTypeCode(SqlTypes.JSON)` 이 어느 JSON 라이브러리(Jackson 2 · 3)로 직렬화하는지, 설정이 필요한지를 직접 본다. 저장한 목록을 다시 읽어 순서 · 한국어 글이 같은지 레포지토리 테스트로 확인한다.
    - DB 의 JSON 키는 `name` · `definition` 이다(02 §8.3).
    - 칸 타입을 `text` 등으로 바꾸지 마라. 매핑이 안 되면 `blocked` 로 멈춘다.

### 2. 엔티티 — `com.mogum.course.domain`

모두 `AuditedEntity` 를 상속하고(금지 28), `@Getter` · `@NoArgsConstructor(access = PROTECTED)` 만 쓰며, **생성 흐름마다 이름 있는 정적 팩토리 하나**로만 만든다(금지 24). 처음 상태는 팩토리 안에 고정한다. 아래 이름은 예시다 — 뜻이 드러나면 바꿔도 되지만, 흐름과 인자는 지킨다.

| 엔티티 | 테이블 | 팩토리(예) | 생성 흐름(02 §2) | 처음 상태 |
|---|---|---|---|---|
| `Roadmap` | `roadmaps` | `Roadmap.plan(long userId, LearningInput input, String title, String description)` | 새로 배우기 — 로드맵 계획이 성공할 때 | `REVIEWING` |
| `Course` | `courses` | `Course.single(long userId, LearningInput input, String title)` | 새로 배우기 — 코스(강의 목록)가 성공할 때 | `REVIEWING` |
| `Course` | `courses` | `Course.inRoadmap(Roadmap roadmap, int position, String title, String planDescription, List<PlannedConcept> planConcepts)` | 로드맵 계획이 성공할 때 그 코스들(ADR-132) | `NOT_PLANNED` · `userId` 는 로드맵의 것 · 입력 칸 묶음은 비운다(ADR-046) |
| `Lesson` | `lessons` | `Lesson.listed(Course course, int position, String title, String description, List<PlannedConcept> concepts)` | 강의 목록이 성공할 때 | `completedAt` 비어 있음 |
| `Material` | `materials` | `Material.write(Lesson lesson, String body)` | 강의 자료가 성공할 때 | — |

- **연결**(금지 27)
    - 같은 도메인의 부모는 `@ManyToOne(fetch = LAZY)` 단방향이다: `Course → Roadmap`(비어 있을 수 있다) · `Lesson → Course` · `Material → Lesson`.
    - 다른 도메인(`account`)의 사용자는 `Long userId` 값으로 둔다.
    - `@OneToMany` · cascade · orphanRemoval 을 두지 않는다.
    - `Material` 도 `@ManyToOne` 으로 가리킨다. 하나뿐인 것은 DB 의 `UNIQUE` 가 지킨다.
- **매핑하는 칸** — 02 §8.3 의 칸을 매핑한다. 뒤 TASK 가 바꿀 칸(`started_at` · `last_lesson_opened_at` · `completed_at`)도 매핑하되 팩토리는 비워 둔다. **`courses.mastery_report` 는 매핑하지 않는다** — 숙련도 고리 TASK 가 모양을 정하며 매핑한다(README 2026-11-10).
- **상태를 바꾸는 메서드를 두지 마라** — 「이대로 시작」 · 제목 고치기 · 학습 완료 · 다 마침 · 다시 짜기는 그것을 쓰는 TASK(강의 목록 · 강의 자료 · 복습)가 더한다.
- 팩토리 안에서 DB `CHECK` 를 다시 검사하는 코드를 쓰지 마라. 범위 · 길이 · 빈 글은 DB 와 뒤의 API(`@Valid`)가 막는다.

### 3. 레포지토리 — `com.mogum.course.repository`

`RoadmapRepository` · `CourseRepository` · `LessonRepository` · `MaterialRepository` 를 둔다. `Repository<T, Long>` 만 상속하고 `save` · `findById` 만 선언한다(TEST_GUIDE §4). 주인 확인 조회 · 목록 조회는 API 를 만드는 TASK 가 더한다.

### 4. 테스트

- **픽스처** — `backend/src/test/java/com/mogum/course/domain/` 에 `RoadmapFixtures` · `CourseFixtures` · `LessonFixtures` · `MaterialFixtures` 를 둔다(필요 없으면 줄여도 된다). 정적 팩토리를 불러 저장하지 않은 엔티티를 돌려준다. 테스트가 확인하는 값은 인자로 받는다(TEST_GUIDE §4). step 2 가 카드를 만들 때 강의 행이 필요하므로, 강의까지 쉽게 만들 수 있게 둔다.
- **단위**(`course/domain/{엔티티}Test`) — 팩토리마다 채운 값을 getter 로 모두 확인한다. 같은 타입 인자가 이어지기 때문이다(금지 24). 확인할 것은 처음 상태 · 로드맵의 코스의 `userId` 가 로드맵의 것 · 로드맵의 코스의 입력 칸 묶음이 비어 있다는 것 등이다.
- **레포지토리**(`@RepositoryTest` · `course/repository/{레포지토리}Test`)
    - 팩토리로 만든 엔티티 다섯 종류(단일 코스 · 로드맵의 코스 포함)가 저장된다 — step 0 의 `CHECK` 를 통과한다.
    - `LearningInput` 이 그대로 다시 읽힌다. `OTHER` + 기타 글 · `memo` 없음을 모두 본다.
    - `List<PlannedConcept>` 가 순서 · 한국어 글까지 같게 다시 읽힌다.
    - 커밋 뒤 다시 읽은 `Lesson` 의 `getCourse()` 를 트랜잭션 밖에서 읽으면 `LazyInitializationException` 이 난다.
    - `created_at` · `updated_at` 이 공용 `TestClock` 의 시각이다(한 엔티티로 충분).
- `ArchitectureTest` 가 통과해야 한다(`AuditedEntity` 상속 · 넓은 레포지토리 상속 금지).

### 5. 문서 — 구현으로 정해진 사실만

`docs/04-jobs-and-cost.md` §2.3 「커리큘럼 모델」 줄에 이 step 이 만든 클래스 · 팩토리 시그니처 · `jsonb` 매핑 방법(무엇으로 확인했는지 포함)을 덧붙인다. 규칙 문장은 고치지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.course.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 엔티티가 금지 24 · 27 · 28 을 따르는가? 사용자는 `Long userId` 이고, `@OneToMany` · cascade 가 없는가?
   - 팩토리가 생성 흐름마다 하나이고 처음 상태가 02 §2 · §8.7 과 같은가? 상태를 바꾸는 메서드가 없는가?
   - `mastery_report` 를 매핑하지 않았는가? `jsonb` 칸 타입을 바꾸지 않았는가?
   - enum 상수마다 한국어 Javadoc 이 02 §8.7 과 같은가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/5-learning-model/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 엔티티 · 팩토리 시그니처 · 레포지토리 메서드 · `LearningInput` · `PlannedConcept` 매핑 방법(어느 JSON 라이브러리 · 설정) · 픽스처 이름과 시그니처(step 2 가 쓴다) · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 · `jsonb` 매핑 불가 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `card` 패키지(카드 · 카드의 개념 연결 · 복습 · 개념)를 만들지 마라. 이유: step 2 의 범위다.
- 서비스 · 컨트롤러 · DTO · API · 화면을 만들지 마라. 이유: SCHEDULE §3 상 강의 목록 · 강의 자료 · 복습 TASK 에서 그 동작과 함께 만든다.
- 상태를 바꾸는 메서드(시작 · 제목 고치기 · 학습 완료 · 다 마침)와 주인 확인 조회를 만들지 마라. 이유: 쓰는 곳이 생길 때 만든다(`AGENTS.md` 작업 방식 — 후속 기능은 미리 만들지 않는다).
- `courses.mastery_report` 를 매핑하지 마라. 이유: 숙련도 반영 내역의 모양은 숙련도 고리 TASK 가 정한다.
- 마이그레이션(`V1` ~ `V10`)을 고치거나 새 마이그레이션을 더하지 마라. 이유: 테이블은 step 0 에서 정했다. 엔티티와 테이블이 맞지 않으면 엔티티를 고치고, 테이블이 틀렸으면 `blocked`.
- 테스트 전용 `@Entity` · `Repository` 를 `com.mogum` 아래에 두지 마라. 이유: 모든 컨텍스트 테스트에 섞인다(TEST_GUIDE §5).
- 통합 테스트 이름에 `IT` 를 쓰지 마라. 이유: ArchUnit 이 빌드를 깬다(TEST_GUIDE §7).
- 계약 문서(02 · ERD · GLOSSARY · ADR · `AGENTS.md` 금지 목록)의 규칙 문장을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
