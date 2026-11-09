# Step 0: learning-schema

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §8 머리말 · §8.1(공통 규칙 · 길이 상한 · 제약 이름 · 지울 때 · 입력 칸 묶음) · §8.3 · §8.4(**이 step 의 계약이다**) · §8.6(지우는 순서 — 코스 · 사용자가 카드를 지울 때) · §8.7(`roadmaps.status` · `courses.status` · `level` · `purpose` · `cards.status` · `cards.fsrs_state` · `reviews.rating`)
- `docs/ERD.md` — `roadmaps` ~ `concepts`
- `docs/ADR.md` — ADR-148 · ADR-155 · ADR-156 · ADR-158 · ADR-159 · ADR-160 · ADR-165
- `docs/04-jobs-and-cost.md` §2.1 · §2.3(「커리큘럼 모델」 줄)
- `docs/TEST_GUIDE.md` 전체 — 특히 §1(레포지토리 · 컨테이너 단독) · §2 · §3 · §5
- `AGENTS.md` — 금지 19 · 27 · 28 · 작업 방식(TDD · 짝 테스트 이름)
- 지금 코드: `backend/src/main/resources/db/migration/V8__create_curriculum_model.sql` · `V9__migrate_account_schema.sql`(제약 이름 · `CHECK` 쓰는 모양) · `backend/src/main/java/com/mogum/curriculum/` 전체
- 지금 테스트: `backend/src/test/java/com/mogum/curriculum/` 전체(특히 `repository/CurriculumConstraintRepositoryTest.java` 의 `JdbcTemplate` 로 제약을 시험하는 방식) · `support/RepositoryTestTest.java` · `support/DatabaseCleaner.java` · `account/repository/AccountSchemaMigrationTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`5-learning-model`)는 옛 커리큘럼 모델(`3-curriculum-model`)을 버리고, 새 설계의 배우는 단위(로드맵 · 코스 · 강의 · 강의 자료 · 카드 · 카드의 개념 연결 · 복습 · 개념)의 테이블과 엔티티를 만든다(ADR-148). 패키지는 `course`(로드맵 · 코스 · 강의 · 강의 자료)와 `card`(카드 · 복습 · 개념) 둘이다. API · 화면 · 서비스는 만들지 않는다. 이 단위들을 쓰는 동작은 뒤 TASK 들이 만든다.

**계약 문서(`docs/02-domain-data.md` §8 · `docs/ERD.md` · `AGENTS.md` 패키지 줄 · `docs/GLOSSARY.md`)는 이 phase 전에 정해 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

이 step 은 **테이블만** 만든다. 엔티티는 step 1(`course`) · step 2(`card`)가 만든다. `ddl-auto: validate` 는 매핑된 엔티티만 검사하므로, 엔티티가 없는 테이블이 있어도 컨텍스트가 뜬다.

## 작업

### 1. 마이그레이션 `V10`

`backend/src/main/resources/db/migration/V10__create_learning_model.sql` 하나로 한다.

**옛 테이블 지우기** — `lessons` → `curriculums` → `curriculum_steps` → `curriculum_groups` 순서로 `DROP TABLE` 한다(자식부터). 운영 DB 에는 이 테이블들에 데이터가 없다(커리큘럼을 만드는 API 가 없었다 — ADR-148). 데이터를 옮기지 않는다.

**새 테이블 여덟 개** — 02 §8.3 · §8.4 의 칸 · 타입 · 빈 값 · 키 · `UNIQUE` · `CHECK` · 색인을 그대로 만든다: `roadmaps` · `courses` · `lessons` · `materials` · `cards` · `card_concepts` · `reviews` · `concepts`. 이번 phase 에서 특히 놓치기 쉬운 것은 아래와 같다.

- **공통**(02 §8.1)
    - 키는 `id bigserial PRIMARY KEY` 다. `card_concepts` 만 `(card_id, concept_id)` 가 키다.
    - **모든 테이블**(`card_concepts` 포함)에 `created_at` · `updated_at` `timestamptz NOT NULL` 을 둔다.
    - 글 칸은 `text` + `CHECK (칸 ~ '[^[:space:]]')` 다. 비어 있을 수 있는 글 칸은 `칸 IS NULL OR 칸 ~ …` 로 쓴다.
    - 길이 상한은 사용자가 쓰는 칸에만 둔다: `topic` · `title`(코스 · 로드맵) 100, `level_other` · `purpose_other` 50, `memo` 500. LLM 이 만든 글 칸(강의 제목 · 설명 · 다룰 개념 · 로드맵 설명 · 계획 설명 · 강의 자료 · 카드 · 개념 이름과 정의)에는 상한을 두지 않는다.
    - 값 목록은 `text` + `CHECK (칸 IN (…))` 다. 값은 02 §8.7 그대로 쓴다.
    - 제약 이름은 `{테이블}_{칸}_{key|check|not_blank|idx}` 이다. 여러 칸을 묶는 `CHECK` 는 대표 칸 이름을 쓴다.
- **연결**(02 §8.1 「지울 때」 · ADR-156) — 모든 FK 는 기본 동작(`ON DELETE` 를 붙이지 않는다)이다. 예외는 **`cards.lesson_id` 하나만 `ON DELETE SET NULL`** 이다.
- **입력 칸 묶음**(02 §8.1) — `topic` · `level` · `level_other` · `purpose` · `purpose_other` · `memo` 이다. `level_other` 는 `level = 'OTHER'` 일 때만 있고(`CHECK ((level = 'OTHER') = (level_other IS NOT NULL))`), `purpose_other` 도 같다. `roadmaps` 는 `memo` 만 비어 있을 수 있다.
- **`courses`** — 단일 코스인지(`roadmap_id IS NULL`)에 따라 칸이 함께 차거나 빈다.
    - 단일 코스: `position` · `plan_description` · `plan_concepts` 는 비고, 입력 칸 묶음은 `topic` · `level` · `purpose` 가 차고 `memo` 는 비어 있을 수 있다. `status` 는 `NOT_PLANNED` 가 아니다.
    - 로드맵의 코스: `position` · `plan_description` · `plan_concepts` 가 차고, 입력 칸 묶음은 모두 빈다.
    - `UNIQUE (roadmap_id, position)` · `CHECK (position >= 1)` · `completed_at` 은 `COMPLETED` 일 때만 차고 · `started_at` 은 `LEARNING` · `COMPLETED` 일 때만 찬다(02 §8.3 — 2026-11-10 에 더한 둘).
    - `mastery_report jsonb` 는 비어 있을 수 있고 `CHECK` 를 두지 않는다 — 숙련도 반영 내역을 쓰는 TASK 가 뒤에 온다.
- **`lessons`** — `CHECK (position BETWEEN 1 AND 10)` · `UNIQUE (course_id, position)` · `concepts jsonb NOT NULL`.
- **`materials`** — `lesson_id` 는 `UNIQUE` 다(강의마다 0~1).
- **`cards`** — `UNIQUE (lesson_id, position)` · `CHECK (status = 'COURSE_DELETED' OR lesson_id IS NOT NULL)` · `CHECK ((fsrs_state = 'REVIEW') = (fsrs_step IS NULL))` · `stability` · `difficulty` 는 `double precision` · 색인 `(user_id, due_at)` · `lesson_id`.
- **`concepts`** — `embedding vector NOT NULL` 이다. **차원을 쓰지 않는다**(`vector(1536)` 처럼 쓰지 마라 — ADR-160). 근사 검색 색인(HNSW · IVFFlat)을 두지 않는다. 색인은 `user_id` 다.
- 색인은 02 §8.3 · §8.4 에 적힌 것만 만든다(`UNIQUE` · PK 가 만드는 색인 말고는 `roadmaps.user_id` · `courses.user_id` · `cards (user_id, due_at)` · `cards.lesson_id` · `card_concepts.concept_id` · `reviews.card_id` · `concepts.user_id`).

### 2. 옛 커리큘럼 코드 지우기

- `backend/src/main/java/com/mogum/curriculum/` 전체와 `backend/src/test/java/com/mogum/curriculum/` 전체를 지운다.
- `support/RepositoryTestTest.java` 는 지금 커리큘럼 엔티티로 확인하던 사실을 그대로 지킨다. 그 사실은 「커밋 뒤 다시 읽은 엔티티의 지연 연관은 트랜잭션 밖에서 초기화되지 않는다(`LazyInitializationException`)」이다. 이 step 에서 쓸 수 있는 `@ManyToOne(LAZY)` 는 `account` 의 `CreditEntry → User` 다. `CreditEntryRepository` 에는 `findById` 가 없다. 그래서 **계정 레포지토리에 메서드를 더하지 말고**, 다시 읽기는 `EntityManager` 로 한다.
- 다른 곳에 남은 `curriculum` 참조(테스트 · 설정 · 주석)를 찾아 정리한다. 끝난 phase 파일(`phases/0-skeleton` ~ `3-curriculum-model`)과 `V8` 마이그레이션은 기록이라 그대로 둔다 — 적용한 마이그레이션은 고치지 않는다(ADR-148).

### 3. 테스트 — 제약을 SQL 로 시험한다

엔티티가 아직 없으므로 `@RepositoryTest` + `JdbcTemplate`(옛 `CurriculumConstraintRepositoryTest` 방식)로 행을 넣어 시험한다. 파일은 두 개로 나눈다: `backend/src/test/java/com/mogum/course/repository/CourseSchemaRepositoryTest.java` · `backend/src/test/java/com/mogum/card/repository/CardSchemaRepositoryTest.java`. 사용자 행은 `UserRepository` + `UserFixtures` 로 만든다. 값이 여럿인 검사는 `@ParameterizedTest` 로 묶는다.

확인할 것:

- **코스 · 로드맵**
    - 단일 코스와 로드맵의 코스가 각각 맞는 칸 조합으로 들어간다.
    - 칸 조합이 섞이면 막힌다(단일 코스에 `position` · `plan_concepts` · 로드맵의 코스에 `topic` 등). 단일 코스의 `NOT_PLANNED` 도 막힌다.
    - `completed_at` · `started_at` 이 상태와 어긋나면 막힌다.
    - `position` 0 과 같은 로드맵 안의 같은 `position` 이 막힌다.
- **입력 칸 묶음** — `level = 'OTHER'` 인데 `level_other` 가 없거나, 아닌데 있으면 막힌다(`purpose` 도 같다). 값 목록 밖의 값이 막힌다. 길이 상한은 경계 양쪽(100 · 101, 50 · 51, 500 · 501)을 본다. 공백만 있는 글이 막힌다.
- **강의** — `position` 0 · 11 이 막히고, 같은 코스의 같은 `position` 이 막힌다. 같은 강의에 강의 자료 둘이 막힌다.
- **카드**
    - 값 목록(`status` · `fsrs_state` · `rating`)이 지켜진다. `fsrs_state` 와 `fsrs_step` 이 어긋나면 막힌다.
    - **`ACTIVE` 카드가 달린 강의를 지우면 실패한다** — `SET NULL` 이 `CHECK` 에 걸린다(02 §8.4 「코스를 지울 때」).
    - **02 §8.6 의 코스 지우는 순서**(카드 → `COURSE_DELETED` · `materials` → `lessons` → `courses`, 작업 테이블은 아직 없다)로 지우면 성공하고, 카드 · `card_concepts` · `reviews` 는 남으며 카드의 `lesson_id` 는 빈다.
    - 자식이 있는 부모를 먼저 지우면 실패한다(`NO ACTION` — 예: 강의가 있는 코스, 복습이 있는 카드).
    - 같은 `(card_id, concept_id)` 둘이 막힌다.
- **개념** — 차원이 다른 임베딩(예: 3차원 · 5차원)이 같은 테이블에 들어간다(ADR-160 이 받아들인 것). `embedding` 이 빈 행은 막힌다.

`DatabaseCleaner` 는 카탈로그에서 테이블을 읽어 비우므로 고칠 필요가 없을 것이다. 새 테이블에서 실패하면 그때 고친다.

### 4. 문서 — 구현으로 정해진 사실만

- `docs/04-jobs-and-cost.md` §2.1 — 디렉터리 그림에서 `curriculum/` 줄을 지운다(`course/` · `card/` 줄은 이미 있다).
- `docs/04-jobs-and-cost.md` §2.3 「커리큘럼 모델」 줄 — 이 step 이 `V10__create_learning_model.sql` 로 옛 테이블을 지우고 여덟 테이블을 만들었다는 사실을 덧붙인다.
- `AGENTS.md` 패키지 줄의 "옛 `curriculum` 패키지는 버리고 새 모델로 새로 만든다" 문장을, 이미 지웠다는 사실로 바꾼다.
- `docs/TEST_GUIDE.md` §2 의 예시 클래스 이름(`CurriculumOwnershipRepositoryTest` · `LessonOrderRepositoryTest`)을 지금 있는 테스트 이름으로 바꾼다.
- 규칙 문장은 고치지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*SchemaRepositoryTest' --tests '*RepositoryTestTest' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `V10` 의 칸 · 타입 · 빈 값 · `UNIQUE` · `CHECK` · 색인이 02 §8.3 · §8.4 와 같은가? 문서에 없는 `CHECK` · 색인을 더하지 않았는가?
   - `ON DELETE SET NULL` 은 `cards.lesson_id` 하나뿐인가? `concepts.embedding` 에 차원 · 근사 검색 색인이 없는가?
   - `generation_jobs` · `llm_calls` · `credit_entries.job_id` 를 만들지 않았는가?
   - `curriculum` 패키지(main · test)가 모두 지워졌고, `V8` 은 그대로인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/5-learning-model/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것:
       - `V10` 파일 이름과 테이블별 제약 이름
       - 지운 파일 목록
       - **옛 커리큘럼 테스트가 확인하던 사실 → 새 테스트(또는 버린 이유)** 대응 — 예: 옛 제약 시험은 테이블과 함께 버림, `RepositoryTestTest` 의 지연 로딩은 `CreditEntry → User` 로 유지
       - 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 · 차원 없는 `vector` 를 만들 수 없음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 엔티티 · 레포지토리 · enum 을 만들지 마라. 이유: step 1(`course`) · step 2(`card`)의 범위다.
- `generation_jobs` · `llm_calls` 테이블과 `credit_entries.job_id` 를 만들지 마라. 이유: 만드는 작업 TASK · 강의 목록 TASK 가 그 코드와 함께 만든다(02 §8 머리말).
- 02 §8 에 없는 `CHECK` · 색인(예: `jsonb_typeof` 검사 · 근사 검색 색인 · `cards.position` 하한)을 더하지 마라. 이유: 테이블은 문서가 단일 출처다(ADR-152). 필요해 보이면 `blocked` 로 알린다.
- `concepts.embedding` 에 차원을 쓰지 마라. 이유: 임베딩 모델을 고르기 전이다(ADR-160).
- `V1` ~ `V9` 를 고치지 마라. 이유: 운영에 적용한 마이그레이션이다(ADR-148).
- 계정 패키지(`account`)의 레포지토리 · 엔티티에 메서드를 더하지 마라. 이유: 이 step 의 범위 밖이다. 테스트에서 다시 읽기가 필요하면 `EntityManager` 로 한다.
- 통합 테스트 이름에 `IT` 를 쓰지 마라. 이유: ArchUnit 이 빌드를 깬다(TEST_GUIDE §7).
- 계약 문서(02 · ERD · GLOSSARY · ADR · `AGENTS.md` 금지 목록)의 규칙 문장을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
