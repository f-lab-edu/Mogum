# Step 2: card-entities

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §3(학습과 복습) · §4(카드) · §6(개념) · §8.4(**이 step 의 계약이다**) · §8.7(`cards.status` · `cards.fsrs_state` · `reviews.rating`)
- `docs/GLOSSARY.md` — 「학습과 복습」(`Card` · `Review`) · 「개인화」(`Concept`)
- `docs/ADR.md` — ADR-155 · ADR-159 · ADR-160 · ADR-164 · ADR-165
- `docs/04-jobs-and-cost.md` §2.1 · §2.3(「커리큘럼 모델」 줄)
- `docs/TEST_GUIDE.md` 전체
- `AGENTS.md` — 금지 16 · 21 · 24 · 27 · 28 · 작업 방식(enum Javadoc · TDD)
- step 0 · 1 이 만든 것: `backend/src/main/resources/db/migration/V10__create_learning_model.sql` · `backend/src/test/java/com/mogum/card/repository/CardSchemaRepositoryTest.java` · `backend/src/main/java/com/mogum/course/` 전체 · `backend/src/test/java/com/mogum/course/domain/*Fixtures.java`
- 본보기 코드: `backend/src/main/java/com/mogum/account/domain/CreditEntry.java` · `account/repository/CreditEntryRepository.java` · `common/persistence/AuditedEntity.java`
- 참고: `backend/build.gradle`(FSRS 라이브러리는 지금 `testImplementation`) · `backend/src/test/java/com/mogum/learning/FsrsLibraryLearningTest.java`(라이브러리 카드의 칸)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

step 0 이 테이블을, step 1 이 `course` 패키지를 만들었다. 이 step 은 `card` 패키지 — 카드 · 카드의 개념 연결 · 복습 · 개념 — 의 엔티티 · enum · 레포지토리를 만든다. 카드는 강의(`course` 패키지)를 **`Long lessonId` 값**으로 가리킨다. 다른 도메인이고(금지 27), 코스를 지우면 그 칸이 비고 카드는 남는다(ADR-165).

**계약 문서(02 §8 · GLOSSARY · `AGENTS.md` 패키지 줄)는 이 phase 전에 정해 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. enum — `com.mogum.card.domain`

`CardStatus`(`ACTIVE` · `COURSE_DELETED`) · `FsrsState`(`LEARNING` · `REVIEW` · `RELEARNING`) · `Rating`(`AGAIN` · `HARD` · `GOOD` · `EASY`) — 02 §8.7 의 값 그대로다. 상수마다 02 §8.7 표의 이름 · 뜻으로 한국어 Javadoc 한 줄을 단다. 이 enum 들은 우리 코드의 것이다 — FSRS 라이브러리의 타입을 엔티티에 쓰지 않는다(라이브러리는 복습 TASK 에서 `implementation` 으로 옮긴다).

### 2. 엔티티 — `com.mogum.card.domain`

모두 `AuditedEntity` 를 상속하고(금지 28), `@Getter` · `@NoArgsConstructor(access = PROTECTED)` 만 쓰며, **생성 흐름마다 이름 있는 정적 팩토리 하나**로만 만든다(금지 24). 아래 이름은 예시다 — 뜻이 드러나면 바꿔도 되지만, 흐름과 인자는 지킨다.

| 엔티티 | 테이블 | 팩토리(예) | 생성 흐름 | 처음 값 |
|---|---|---|---|---|
| `Card` | `cards` | `Card.write(long userId, long lessonId, int position, String question, String answer)` | 강의 자료와 함께 만들 때(02 §4) | `status = ACTIVE` · `fsrsState = LEARNING` · `fsrsStep = 0` · `dueAt` · `stability` · `difficulty` · `lastReviewedAt` 비어 있음(02 §8.4 — 학습 완료 전이라 숨겨진다) |
| `Concept` | `concepts` | `Concept.add(long userId, String name, String definition, float[] embedding)` | 카드가 생길 때 사전에 넣을 때(ADR-048) | — |
| `CardConcept` | `card_concepts` | `CardConcept.link(Card card, Concept concept)` | 카드에 개념을 붙일 때 | — |
| `Review` | `reviews` | `Review.record(Card card, Rating rating)` | 스스로 평가 한 번(ADR-015) | 평가한 때는 `createdAt` 이다 |

- **연결**(금지 27)
    - 같은 도메인의 부모는 `@ManyToOne(fetch = LAZY)` 단방향이다: `CardConcept → Card` · `CardConcept → Concept` · `Review → Card`.
    - 다른 도메인은 값이다: `Card.userId` · `Concept.userId`(`account`) · `Card.lessonId`(`course` — `Long`, 비어 있을 수 있다).
    - `@OneToMany` · cascade · orphanRemoval 을 두지 않는다.
- **`CardConcept` 의 키**는 `(card_id, concept_id)` 두 칸이다. 키 타입(`@EmbeddedId` · `@IdClass`)은 네가 고른다. 키 클래스에 equals · hashCode 가 필요하면 record 로 둔다 — 엔티티에는 Lombok `@EqualsAndHashCode` 를 쓰지 않는다(`AGENTS.md` 스택).
- **`concepts.embedding`**(차원 없는 `vector` — ADR-160)
    - **매핑은 Hibernate 7.4.5(지금 Boot 4.1.1 이 고르는 버전)와 `ddl-auto: validate` 에서 확인해서 정한다.** 예를 들어 `hibernate-vector` 모듈과 `@JdbcTypeCode(SqlTypes.VECTOR)` 가 차원 없는 칸을 받는지, 버전은 Boot 가 관리하는지를 직접 본다. 확인하지 않은 동작을 가정하지 마라.
    - 의존성을 더하면 버전은 Boot · Hibernate BOM 이 정하게 두고, 무엇을 더했는지 summary 에 적는다.
    - 저장한 임베딩을 다시 읽어 값이 같은지, **차원이 다른 두 개념(예: 3 · 5)이 함께 저장 · 조회되는지** 레포지토리 테스트로 확인한다.
    - 칸 타입을 바꾸거나 차원을 고정하지 마라. 매핑이 안 되면 `blocked` 로 멈춘다.
- **상태를 바꾸는 메서드를 두지 마라** — 학습 완료(`dueAt` 채우기) · 복습(FSRS 칸 갱신) · 코스 지워짐 · 카드 지우기는 강의 자료 · 복습 · 지우기 TASK 가 더한다. `Review.record` 는 기록 행만 만들고 카드를 바꾸지 않는다.
- **같은 개념 판정 · 개념 넣기 규칙을 만들지 마라** — 개념 사전 쓰기의 한 곳(금지 16)은 강의 자료 · 카드 TASK 가 정한다. 여기서는 행을 만드는 팩토리만 둔다.

### 3. 레포지토리 — `com.mogum.card.repository`

`CardRepository` · `CardConceptRepository` · `ReviewRepository` · `ConceptRepository` 를 둔다. `Repository<T, ID>` 만 상속하고 `save` · `findById` 만 선언한다(TEST_GUIDE §4). 오늘 복습할 카드 · 개념 검색 같은 조회는 그 TASK 가 더한다.

### 4. 테스트

- **픽스처** — `backend/src/test/java/com/mogum/card/domain/` 에 `CardFixtures` · `ConceptFixtures` 등을 둔다. 저장하지 않은 엔티티를 돌려주고, 테스트가 확인하는 값은 인자로 받는다. 카드에 필요한 강의 행은 step 1 의 `course` 픽스처 · 레포지토리로 만든다.
- **단위**(`card/domain/{엔티티}Test`) — 팩토리마다 채운 값을 getter 로 모두 확인한다(금지 24 — `userId` · `lessonId` · `position` 처럼 같은 타입이 이어진다). 카드의 처음 FSRS 값이 위 표와 같은지 본다.
- **레포지토리**(`@RepositoryTest` · `card/repository/{레포지토리}Test`)
    - 팩토리로 만든 카드가 저장된다 — 처음 FSRS 값이 step 0 의 `CHECK` 를 통과한다.
    - 임베딩을 다시 읽으면 값이 같고, 차원이 다른 두 개념이 함께 저장 · 조회된다.
    - 같은 카드 · 개념 연결 둘을 저장하면 막힌다.
    - 커밋 뒤 다시 읽은 `Review` 의 `getCard()` 를 트랜잭션 밖에서 읽으면 `LazyInitializationException` 이 난다.
    - `Review` 의 `createdAt` 이 공용 `TestClock` 의 시각이다.
- `ArchitectureTest` 가 통과해야 한다.

### 5. 문서 — 구현으로 정해진 사실만

`docs/04-jobs-and-cost.md` §2.3 「커리큘럼 모델」 줄에 이 step 이 만든 클래스 · 팩토리 시그니처 · 임베딩 매핑 방법(더한 의존성 · 무엇으로 확인했는지)을 덧붙인다. 의존성을 더했으면 `AGENTS.md` 스택 줄에 한 마디로 적는다(예: "pgvector — `hibernate-vector`"). 규칙 문장은 고치지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.card.*' --tests 'com.mogum.course.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 엔티티가 금지 24 · 27 · 28 을 따르는가? `Card` 가 강의를 `Long lessonId` 로 가리키고 `course` 패키지의 엔티티를 import 하지 않는가?
   - 카드의 처음 값이 02 §8.4 와 같은가? 상태를 바꾸는 메서드가 없는가?
   - `concepts.embedding` 의 칸 타입 · 차원을 바꾸지 않았는가? 마이그레이션을 더하지 않았는가?
   - FSRS 라이브러리 타입이 제품 코드에 없는가?
   - enum 상수마다 한국어 Javadoc 이 02 §8.7 과 같은가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/5-learning-model/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 엔티티 · 팩토리 시그니처 · 키 타입 · 레포지토리 메서드 · 임베딩 매핑 방법(더한 의존성 · 확인 방법) · 픽스처 이름 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 · 차원 없는 `vector` 매핑 불가 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 서비스 · 컨트롤러 · DTO · API · 화면을 만들지 마라. 이유: SCHEDULE §3 상 강의 자료 · 복습 TASK 에서 그 동작과 함께 만든다.
- 학습 완료 · 복습 · FSRS 계산 · 코스 지워짐 · 카드 지우기 · 숙련도 계산 코드를 만들지 마라. 이유: 쓰는 TASK(강의 자료 · 복습 · 숙련도 · 지우기)의 범위다.
- FSRS 라이브러리를 `implementation` 으로 옮기거나 그 타입을 엔티티에 쓰지 마라. 이유: 복습 TASK 가 라이브러리를 쓰기 시작할 때 옮긴다. 이 step 의 엔티티는 우리 enum 으로 칸을 담는다(ADR-159).
- 같은 개념 판정 · 임베딩 호출 코드를 만들지 마라. 이유: 유료 호출은 `generation` 의 일이고(금지 1), 개념 사전 쓰기의 한 곳은 강의 자료 TASK 가 정한다(금지 16).
- `Card` 에서 `course` 패키지의 엔티티(`Lesson`)를 가리키지 마라. 이유: 다른 도메인은 ID 값으로 둔다(금지 27). 코스를 지우면 칸이 비고 카드는 남는다(ADR-165).
- 마이그레이션(`V1` ~ `V10`)을 고치거나 새 마이그레이션을 더하지 마라. 이유: 테이블은 step 0 에서 정했다. 테이블이 틀렸으면 `blocked`.
- 테스트 전용 `@Entity` · `Repository` 를 `com.mogum` 아래에 두지 마라. 이유: 모든 컨텍스트 테스트에 섞인다(TEST_GUIDE §5).
- 통합 테스트 이름에 `IT` 를 쓰지 마라. 이유: ArchUnit 이 빌드를 깬다(TEST_GUIDE §7).
- 계약 문서(02 · ERD · GLOSSARY · ADR · `AGENTS.md` 금지 목록)의 규칙 문장을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
