# Step 2: concept-dictionary

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §4(카드) · §6(개념) · §8.1 · §8.4(`cards` · `card_concepts` · `concepts`) · §9
- `docs/ADR.md` — ADR-029 · ADR-030 · ADR-033 · ADR-048 · ADR-049 · ADR-050 · ADR-133 · ADR-138 · ADR-155 · ADR-160 · ADR-161 · ADR-165 · **ADR-187**
- `docs/04-jobs-and-cost.md` §2.2 · §10
- `docs/TEST_GUIDE.md` 전체
- `AGENTS.md` — 금지 3 · 4 · 16 · 22 · 24 · 27 · 28
- step 1 이 만든 것(summary 참고) — `TestEmbeddingProvider`
- 엔티티 · 리포지토리: `backend/src/main/java/com/mogum/card/domain/Card.java`(`write`) · `CardConcept.java` · `CardConceptId.java` · `Concept.java`(`add`) · `card/repository/` 전체
- 같은 사람의 동시 요청을 DB 로 줄 세운 기존 예: `backend/src/main/java/com/mogum/account/repository/UserIdentityLock.java` · `UserIdentityLockImpl.java` · `account/service/CreditLedger.java`
- 시험 데이터: `backend/src/test/java/com/mogum/card/domain/*Fixtures.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

강의 자료를 만들 때 카드가 생기고, 카드의 개념은 그때 사용자의 개념 사전에 들어간다(ADR-048). 같은 개념인지는 한 줄 정의의 임베딩이 **그 사용자 사전에서 가장 가까운 개념 하나와 코사인 유사도가 기준값 이상인지**로 정한다 — 넘으면 그 개념에 붙이고, 아니면 새 개념으로 넣는다(ADR-161). `concepts.name` 에 unique 가 없어 DB 가 유일성을 지켜 주지 않는다(금지 16).

이 step 은 **카드와 그 개념을 넣는 일을 `card` 패키지의 한 곳**으로 만든다. 임베딩은 이미 계산된 값으로 받는다 — 호출(유료)은 step 3 의 생성 처리기가 트랜잭션 밖에서 한다(금지 3). 이 서비스는 step 3 의 결과 저장 트랜잭션 안에서 불린다.

**계약 문서는 이 phase 전에 정해 커밋했다. 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 강의의 카드 넣기 — `com.mogum.card.service`

시그니처 수준(이름 · 모양은 맞게 다듬어도 된다):

```java
// 강의 자료를 만든 결과 저장 트랜잭션 안에서 부른다. 카드 순서는 목록 순서(1부터).
void writeLessonCards(long userId, long lessonId, List<NewCard> cards);

record NewCard(String question, String answer, List<NewConcept> concepts) {}
record NewConcept(String name, String definition, float[] embedding) {}
```

- 카드는 `Card.write` 로 만든다 — 상태 `ACTIVE` · FSRS 처음 상태 · `due_at` 비움(학습 완료 전에는 숨긴다 — ADR-014 · ADR-155). 엔티티는 팩토리로만(금지 24).
- 개념마다 같은 개념 판정 → 붙일 개념 ID 를 정하고 `card_concepts` 로 잇는다.
- **같은 저장 안에서 먼저 넣은 새 개념도 판정 대상이다** — 1번 카드의 새 개념 「격리 수준」 뒤 3번 카드의 「트랜잭션 격리 수준」은 그 새 개념에 붙어야 한다. 네이티브 쿼리로 찾기 전에 `flush()` 한다(금지 4).
- **한 카드 안의 두 개념이 같은 개념으로 판정되면 연결은 하나만 둔다** — 그대로 넣으면 `card_concepts` 의 키(두 칸)가 겹쳐 저장이 깨진다.
- 다른 사용자의 개념은 판정 대상이 아니다(ADR-029).
- 개념의 이름 · 정의는 처음 넣은 것을 그대로 둔다 — 붙일 때 기존 개념을 고치지 않는다.
- 호출하는 쪽이 트랜잭션을 열고 있어야 한다. 이 서비스가 트랜잭션 없이 불리면 어떻게 되는지 정하고(예: 전파 `MANDATORY`) 시험한다.

### 2. 가장 가까운 개념 찾기 — 리포지토리

- 그 사용자의 개념 가운데 정의 임베딩과 코사인 유사도가 가장 큰 하나를 찾는다. pgvector 의 코사인 거리 연산을 `EntityManager` 네이티브 쿼리로 쓴다(AGENTS 스택 — Postgres 고유 기능). 근사 검색 색인은 두지 않는다 — 모두 비교한다(ADR-160).
- 유사도 = 1 − 코사인 거리. 기준값 이상이면 같다(경계값 포함). 같은 거리의 후보가 여럿이면 결정적으로 하나를 고른다(예: 작은 ID).

### 3. 같은 사람의 동시 저장을 줄 세운다(금지 16 · 22)

같은 사람의 두 강의가 동시에 저장되면, 둘 다 사전에 없던 같은 개념을 각자 넣어 두 행이 된다. **판정과 넣기를 사용자 단위로 줄 세운다** — 방법은 정해서 쓴다(예: 이 트랜잭션에서 사용자별 PostgreSQL 트랜잭션 advisory lock · 사용자 행 잠금). 서버 메모리의 잠금 · 캐시를 쓰지 마라(서버가 여럿이어도 맞아야 한다 — ADR-133). 다른 사용자끼리는 서로 기다리지 않게 한다. 고른 방법과 이유를 02 §6 의 「막는 방법은 TASK」 자리에 한 줄로 적는다.

### 4. 기준값 — 임시 값

- 설정 한 곳에 둔다(예: `app.concept.same-threshold` · `config` 패키지의 properties record). 0 초과 1 이하가 아니면 기동을 거부한다.
- **임시 값 `0.80`** 이다 — 근거 없는 값이고, phase 가 끝난 뒤 · 병합 전에 step 1 의 실측 시험으로 사람이 바꾼다(ADR-187). 설정 옆 주석과 02 §9 · 04 §10 에 「임시 값 — 실측 전」을 적는다.

### 5. 테스트

- 리포지토리(`@RepositoryTest`) — 가장 가까운 개념 하나 · 다른 사용자 제외 · 경계값 · 같은 거리.
- 서비스 통합(`@IntegrationTest` 또는 TEST_GUIDE 가 정한 DB 규칙 시험) — 시험이 벡터를 직접 정한다(같음 = 기준값 이상 · 다름 = 미만):
    - 기존 개념에 붙는다 / 새 개념이 생긴다 / 다른 사용자의 같은 개념에는 붙지 않는다.
    - 같은 저장 안에서 먼저 넣은 새 개념에 뒤 카드가 붙는다.
    - 한 카드의 두 개념이 같은 개념이면 연결이 하나다.
    - 카드 순서 · 상태 `ACTIVE` · `due_at` 비어 있음 · FSRS 처음 상태.
    - **동시성** — 같은 사용자의 두 트랜잭션이 사전에 없는 같은 개념(같은 벡터)을 동시에 넣어도 개념 행이 하나다. 두 스레드와 래치로 겹치게 만들고, 잠금이 없으면 실패하는 모양인지 확인한다. 다른 사용자 둘은 서로 막히지 않는다.
- 시험 데이터는 `{엔티티}Fixtures` 를 쓴다. 테스트는 트랜잭션을 걸지 않는다(TEST_GUIDE).

### 6. 문서 — 구현으로 정해진 사실만

- `docs/02-domain-data.md` §6 — 줄 세우는 방법 한 줄 · §9 — 기준값의 설정 키와 임시 값.
- `docs/04-jobs-and-cost.md` §10 — 기준값 설정 키 · 임시 값.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.card.*' --tests 'com.mogum.config.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 개념을 넣는 경로가 이 서비스 한 곳뿐인가(금지 16)? `card` 가 `generation` · `job` · `worker` 를 참조하지 않는가?
   - 같은 사람의 동시 저장이 DB 로 줄 서는가? 메모리 잠금이 없는가? 동시성 시험이 있는가?
   - 같은 저장 안의 새 개념 · 한 카드의 겹친 개념을 다루는가? 네이티브 쿼리 전에 flush 하는가?
   - 카드가 `due_at` 없이 숨은 채로 생기는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/8-material-cards/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 서비스 클래스 · 메서드 시그니처와 값 record(step 3 이 쓴다) · 트랜잭션 전파 · 줄 세우는 방법 · 기준값 설정 키 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 이 서비스 안에서 임베딩 · LLM 을 부르지 마라. 이유: 금지 1 · 3 — `card` 는 `generation` 을 참조하지 않고, 저장 트랜잭션은 유료 호출 동안 커넥션을 쥐면 안 된다.
- 애매한 판정을 LLM 에게 묻거나 개념 이름으로 맞추지 마라. 이유: ADR-161 — 가장 가까운 하나와 기준값만 쓴다. 이름은 겹칠 수 있다.
- 강의의 다룰 개념(`lessons.concepts`)을 사전과 잇지 마라. 이유: ADR-138 — 사전과 이어지는 것은 카드뿐이다.
- 개념을 지우거나 고치는 기능 · 판정을 바로잡는 기능을 만들지 마라. 이유: ADR-049 · ADR-050.
- 학습 완료 · 복습(FSRS 계산) · 카드 지우기를 만들지 마라. 이유: step 4 와 복습 TASK(SCHEDULE §3 의 9)의 범위다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
