# Step 1: curriculum-schema

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 5(「단계 커리큘럼도 성공할 때 만든다」 문단 · 묶음/단계 문단) · 6 · 22 · 28 · 31 · 32 · 작업 방식
- `docs/ADR.md` — ADR-070 · ADR-071 · ADR-076 · ADR-077 · ADR-078
- `docs/02-domain-data.md` — §1 ERD · §2(상태 · 「`DRAFT` 강의에는 자료도 잡 B 도 없다」) · §2.1 · §6 머리 · **§6.2 · §6.2.1(이 step 의 계약 — 표와 bullet 전부)**
- `docs/04-jobs-and-cost.md` §2 — 도메인 패키지의 하위 패키지 규칙 · `domain` 행
- `docs/07-testing.md` §1.2 — 「묶음/단계 모델 제약」 · 「커리큘럼 · 강의 · 묶음 · 단계 컬럼 제약」 · 「생성 · 수정 시각」 행
- `docs/ARCHITECTURE.md` — 디렉터리 트리의 `<도메인>/` 줄
- step 0 이 만든 공통 상위 클래스 · Auditing 설정 · 테스트용 고정 시계(이전 step summary 에 이름이 있다)
- `backend/src/main/java/com/mogum/account/domain/User.java` — 엔티티 모양의 본보기(Lombok · `columnDefinition` · 정적 팩토리)
- `backend/src/main/resources/db/migration/V6__generalize_user_login_identity.sql` — 공백만 금지 CHECK 의 기존 모양(`~ '[^[:space:]]'`)
- `backend/src/test/java/com/mogum/account/repository/UserRepositoryIT.java` · `support/PostgresTestConfiguration.java` — DB 테스트 · 정리 방식

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

묶음(`CurriculumGroup`) → 단계(`CurriculumStep`) ← 커리큘럼(`Curriculum.step`) → 강의(`Lesson`) 의 저장 모델을 만든다. 뒤 TASK 의 잡(`4`)이 이 테이블을 가리키고, 생성(`6`)이 팩토리로 행을 만든다. 이 step 은 **스키마 · 엔티티 · 팩토리 · 기본 리포지토리**까지다. 계약(02 §6.2 · §6.2.1 · ADR-076~078)은 이미 문서에 있다. **코드가 문서와 어긋나면 코드를 문서에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문서를 고치지 마라** — 아래 「6. 문서」 의 사실 갱신만 한다.

## 작업

### 1. 마이그레이션 `V8__…sql`

테이블 넷을 묶음 → 단계 → 커리큘럼 → 강의 순서로 만든다. 컬럼과 제약은 02 §6.2 · §6.2.1 의 표와 bullet 이 기준이다. 빠뜨리기 쉬운 것을 모았다:

- 네 테이블 모두 `id bigserial` PK · `created_at` · `updated_at` `timestamptz NOT NULL`(DB 기본값 없이 — Auditing 이 채운다).
- **주인** — `curriculum_groups.user_id` · `curriculums.user_id` 는 NOT NULL FK → `users.id`, 각각 `(user_id)` 인덱스.
- **주제 · 목적** — `curriculums` · `curriculum_groups` · `curriculum_steps` 세 곳 모두: `topic` NOT NULL · 공백만 금지 · `char_length(topic) <= 100`. `purpose` 는 NULL 허용 — **NULL 이거나, 공백만이 아니고 `char_length(purpose) <= 200`**.
- **제목류** — `curriculums.title` · `curriculum_groups.title` · `curriculum_steps.title` · `curriculum_steps.scope` · `lessons.title` · `lessons.objective` 는 NOT NULL · 공백만 금지. 길이 상한 CHECK 는 두지 않는다(`6`).
- **순서** — `curriculum_steps(group_id, order_no)` · `lessons(curriculum_id, order_no)` 에 UNIQUE, 둘 다 `CHECK (order_no >= 1)`. `group_id` · `curriculum_id` 는 NOT NULL FK.
- **단계 연결** — `curriculums.step_id` 는 nullable FK → `curriculum_steps.id`, **UNIQUE**, `ON DELETE RESTRICT`. NULL 은 여러 행 허용(Postgres 기본 `NULLS DISTINCT` — 독립 커리큘럼 여럿).
- **상태** — `curriculums.status` `CHECK (status IN ('DRAFT','ACTIVE','COMPLETED'))`, `lessons.status` `CHECK (status IN ('NOT_STARTED','READY','COMPLETED'))`.
- **`lessons.keywords`** — `text[] NOT NULL`. 개수 · 빈 원소 검사는 두지 않는다(`6`).
- **FK 삭제 동작** — `step_id` 의 `RESTRICT` 말고는 모두 기본(NO ACTION). `ON DELETE CASCADE` · `SET NULL` 을 쓰지 않는다(ADR-077).
- 숙련도 근거 `jsonb` 두 컬럼(`mastery_reflection` · `mastery_selection`)은 만들지 않는다(02 §6.2 — `6` 에서 더한다).

### 2. 엔티티 · enum — `com.mogum.curriculum.domain`

- `CurriculumGroup` · `CurriculumStep` · `Curriculum` · `Lesson` 은 step 0 의 공통 상위 클래스를 상속한다. `CurriculumStatus`(`DRAFT` · `ACTIVE` · `COMPLETED`) · `LessonStatus`(`NOT_STARTED` · `READY` · `COMPLETED`) enum.
- Lombok 은 `@Getter` · `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 만(ADR-070). public 생성자 · `@Builder` · setter 없음.
- **연관(AGENTS 금지 31)** — `Lesson.curriculum` · `CurriculumStep.group` · `Curriculum.step` 은 `@ManyToOne(fetch = FetchType.LAZY)` 단방향. 부모 쪽 `@OneToMany` · cascade · orphanRemoval 을 두지 않는다. 회원은 `Long userId` 로 두고 `account.User` 를 참조하지 않는다.
- 지연 로딩 프록시는 하위 클래스다 — 엔티티 클래스 · getter 를 `final` 로 두지 마라.
- `ddl-auto: validate` 다 — 매핑이 V8 과 맞아야 모든 컨텍스트가 뜬다. 문자열 컬럼은 `User` 처럼 `columnDefinition = "text"` 로 맞춘다. `keywords`(`text[]`)는 Hibernate 가 `validate` 를 통과시키는 매핑을 확인해서 쓰고, 쓴 매핑을 summary 에 적는다.

### 3. 팩토리 — 생성 흐름마다 하나(AGENTS 금지 28 · ADR-071)

```java
CurriculumGroup.plan(Long userId, String topic, String purpose, String title)
CurriculumStep.of(CurriculumGroup group, int orderNo, String title, String scope, String topic, String purpose)
Curriculum.single(Long userId, String topic, String purpose, String title)   // DRAFT · step 없음
Curriculum.forStep(CurriculumStep step)                                       // DRAFT · step 연결
Lesson.of(Curriculum curriculum, int orderNo, String title, String objective, List<String> keywords) // NOT_STARTED
```

- 처음 상태(`DRAFT` · `NOT_STARTED`)는 팩토리 안에 고정한다.
- **`Curriculum.forStep`** 은 단계의 `title` · `topic` · `purpose` 를 복사하고, `userId` 는 **단계의 묶음(`step.getGroup().getUserId()`)** 에서 가져온다 — 인자로 받지 않는다. 이유: 다른 사용자의 커리큘럼이 단계에 연결될 수 없게 하는 장치다(02 §6.2.1 「단계 잡의 완료」 · ADR-076).
- 팩토리는 길이 · 공백을 검사하지 않는다. 이유: 사용자 입력 검증은 `6` 의 요청 DTO 가, 최종 방어선은 DB CHECK 가 맡는다 — 팩토리까지 검사하면 같은 규칙이 세 곳이 된다.
- 생성 · 수정 시각을 인자로 받지 않는다(AGENTS 금지 32).

### 4. 리포지토리 — `com.mogum.curriculum.repository`

- `CurriculumGroupRepository` · `CurriculumStepRepository` · `CurriculumRepository` · `LessonRepository` — 각각 `JpaRepository<엔티티, Long>`. 이 step 에서는 메서드를 더하지 않는다(주인 확인 조회는 step 2).
- `curriculum` 의 `controller` · `service` · `dto` 하위 패키지는 만들지 않는다 — 쓰는 코드가 생기는 `6` 에서 만든다. 빈 패키지 · `package-info.java` 도 만들지 않는다.

### 5. 테스트

TDD 가드는 `@Entity` · enum · interface 를 검사하지 않지만, 테스트를 먼저 쓴다. 테스트 경로는 대응하는 구현과 같은 하위 패키지다(`backend/src/test/java/com/mogum/curriculum/domain/` · `…/curriculum/repository/`).

- **팩토리 단위 테스트**(`domain`) — 다섯 팩토리가 채운 값을 getter 로 하나씩 확인한다. 같은 타입(`String`) 인자가 이어지므로 자리가 바뀌면 드러나게 서로 다른 값을 넣는다(금지 28). `forStep` 은 단계의 제목 · 주제 · 목적 · 묶음 주인을 가져오는지, 처음 상태가 `DRAFT` 인지 확인한다.
- **DB 테스트**(`repository` — `PostgresTestConfiguration` 상속 · step 0 의 고정 시계 방식) — 07 §1.2 의 두 행을 빠짐없이 옮긴다:
  - 「묶음/단계 모델 제약」 — 같은 묶음의 같은 단계 순서 거절 · 같은 단계에 커리큘럼 둘 거절 · 단계 없는 독립 커리큘럼 둘 허용 · 없는 단계를 가리키면 FK 거절 · `forStep` 으로 저장한 커리큘럼의 `user_id` 가 묶음 주인.
  - 「컬럼 제약」 — 세 테이블의 주제 100자 저장 · 101자 거절, 목적 NULL 저장 · 200자 저장 · 201자 거절 · 공백만 거절, 빈 · 공백 제목 거절(제목류 여섯 컬럼), 목록 밖 상태 거절(두 테이블 — 엔티티로는 못 만들므로 네이티브 SQL 로), 같은 커리큘럼의 같은 강의 순서 거절 · 순서 0 거절(강의 · 단계), 자식이 있는 부모 삭제 거절(강의가 있는 커리큘럼 · 단계가 있는 묶음 · 커리큘럼이 연결된 단계), `curriculums(user_id)` · `curriculum_groups(user_id)` 인덱스 존재.
  - 거절은 "아무 예외" 가 아니라 기대한 제약이 거절했는지 확인한다(SQLState 나 제약 이름).
  - 「생성 · 수정 시각」 — 네 엔티티 모두 저장하면 두 시각이 고정 시계의 시각이다.
  - **LAZY** — 세 연관 각각, 자식을 새 영속성 컨텍스트에서 읽은 직후 부모가 초기화되지 않았는지 확인한다(`PersistenceUnitUtil.isLoaded` 나 `Hibernate.isInitialized`).
  - `keywords` 를 저장하고 다시 읽으면 같은 순서 · 같은 값이다.
- DB 는 테스트 JVM 하나에서 공유된다 — 쓴 행은 테스트가 **자식부터**(강의 → 커리큘럼 → 단계 → 묶음 → 회원) 지운다. cascade 가 없으므로 순서를 어기면 정리가 FK 로 실패하고, 남은 행은 다른 테스트의 회원 삭제를 막는다.

### 6. 문서 (구현으로 정해진 사실만)

- `docs/ARCHITECTURE.md` — 디렉터리 트리에 `curriculum/` 줄을 더한다(`domain/` · `repository/` 의 클래스. `controller` · `service` · `dto` 는 `6` 에서 생긴다고 적는다).
- `docs/02-domain-data.md` §6.2 · §6.2.1 — 각 절 첫머리에 §6.1 의 표기처럼 구현 표기(날짜 · 마이그레이션 파일 이름 · "jsonb 두 컬럼과 길이 상한 CHECK 는 `6`")를 남긴다. 표 · bullet 의 규칙은 고치지 않는다.
- `docs/ADR.md` — ADR-077 트레이드오프 끝의 "구현 · 검증은 아직 수행하지 않았다" 를 이 step 이 한 것(엔티티 · 연관 · FK)과 남은 것(삭제 순서 · 강의 교체 — `6` · `13`)으로 고친다. ADR-078 의 구현 표기(step 0 이 고친 문장)에서 커리큘럼 엔티티가 남았다는 부분을 끝난 것으로 고친다. 결정 · 이유 · 대안 본문은 고치지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 네 엔티티가 공통 상위 클래스를 상속하는가(step 0 의 ArchUnit 규칙이 통과하는가)?
   - `@ManyToOne` 세 개가 모두 `LAZY` 이고, `@OneToMany` · cascade · orphanRemoval · `account.User` 참조가 없는가?
   - V8 에 `jsonb` · `ON DELETE CASCADE` · 시각 기본값 · 제목류 길이 CHECK 가 없는가? `step_id` 만 `RESTRICT` 인가?
   - 07 §1.2 의 두 행의 경우가 테스트에 모두 있는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3-curriculum-model/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — V8 파일 이름 · 엔티티/리포지토리 클래스 이름 · `keywords` 매핑 방식 · DB 테스트의 정리 방식 · 서버 테스트 수(전후)를 넣는다(step 2 가 같은 테스트 방식을 쓴다)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `jsonb` 컬럼 · 제목/범위/목표의 길이 CHECK · 키워드 개수/빈 원소 검사를 만들지 마라. 이유: 02 §6.2 — 출력 규약과 함께 `6-curriculum-gen` 에서 정한다.
- `jobs` 테이블 · 단계 잡 · 잡 FK 를 만들지 마라. 이유: `4-job-queue` 의 범위다.
- 엔티티에 상태 전이(승인 · 완료 · 다시 짜기) · 변경 메서드 · setter 를 만들지 마라. 이유: 그 흐름을 만드는 `6` · `7` 이 규칙과 함께 정한다. 테스트에 필요한 변경은 테스트에서 필드를 바꾼다.
- 서비스 · 컨트롤러 · DTO · API · 화면을 만들지 마라. 이유: `6` 의 범위다.
- 커리큘럼 · 묶음 삭제 · 탈퇴 코드(운영 코드)를 만들지 마라. 이유: 삭제는 `6`, 탈퇴는 `13` 이 잠금 · 잡 연결 해제와 함께 만든다. 테스트의 정리 SQL 은 괜찮다.
- `@OneToMany` · cascade · orphanRemoval · `ON DELETE CASCADE` 를 쓰지 마라. 이유: AGENTS 금지 31 · ADR-077 — 02 §6.2 의 명시적 삭제 순서를 건너뛰고, 컬렉션 교체가 순서 unique 와 부딪힌다.
- 팩토리에 길이 · 공백 검사를 넣지 마라. 이유: 위 「3. 팩토리」.
- 주인 확인 조회 메서드(`findByIdAndUserId` 등)를 만들지 마라. 이유: step 2 의 범위다.
- 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 이 step 은 04 §9.2 표에 줄을 더하지 않는다.
- 계약 문서(ADR 결정 본문 · AGENTS 금지 목록 · 02 의 표 · bullet 규칙 · 05 · 07)를 고치지 마라 — 「6. 문서」 의 사실 갱신만 한다. 이유: 계약은 설계 커밋에서 정했다. 어긋나면 `blocked`.
- V1~V7 을 고치지 마라. 이유: 이미 적용된 마이그레이션이다.
- `frontend/` 를 고치지 마라. 이유: 이 step 과 무관하다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
