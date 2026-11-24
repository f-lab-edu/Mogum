# Step 1: concept-lookup

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §5(숙련도) · §6(개념 — 사용자마다 사전 · 같은 개념 판정 · 기준값) · §8.4(`concepts` — `embedding` · 색인) · §9(같은 개념 기준값)
- `docs/03-generation.md` §2 「관련 개념 검색」 표 · §3 「강의 한 줄의 개념을 사전과 맞추는 법」
- `docs/ADR.md` — ADR-029 · ADR-030 · ADR-032 · ADR-138 · ADR-161 · ADR-187
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 1 · 3 · 4(네이티브 쿼리 전 `flush`) · 16 · 18 · 22
- step 0 이 만든 것(summary 참고) — 판정 enum · 판정 메서드
- `backend/src/main/java/com/mogum/card/repository/ConceptSimilaritySearch.java` · `ConceptSimilaritySearchImpl.java`(같은 개념 판정의 쓰기 경로 쿼리 — 본뜬다) · `ConceptRepository.java`
- `backend/src/main/java/com/mogum/card/service/LessonCardWriter.java`(사전 쓰기의 유일한 경로) · `backend/src/main/java/com/mogum/config/ConceptProperties.java`
- `backend/src/test/java/com/mogum/card/repository/ConceptSimilaritySearchImplTest.java`(벡터로 유사도를 정하는 시험)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

뒤 step 의 생성 흐름이 개념 사전을 **읽기만** 하는 세 가지 조회를 `card` 에 만든다. 임베딩은 호출자(`generation`)가 트랜잭션 밖에서 계산해 벡터로 넘긴다 — 이 step 은 임베딩을 부르지 않는다.

**계약 문서는 이 phase 전에 정해 커밋했다(03 §2 · §3). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

`card.service` 에 읽기 전용 서비스 하나(이름은 정한다)를 두고 아래 셋을 공개한다. 돌려주는 값은 엔티티가 아니라 값(record)이다 — 호출자가 트랜잭션 밖에서 LLM 호출을 사이에 두고 쓴다(금지 4).

### 1. 사전이 비었나

```java
boolean hasConcepts(long userId);
```

- 뒤 step 이 사전이 비었으면 임베딩을 부르지 않는다(03 §2 · §3).

### 2. 관련 개념 — 강의 목록 · 로드맵 계획

```java
List<RelatedConcept> findRelated(long userId, List<float[]> queries, int limit, Instant now);
record RelatedConcept(String name, String definition, Judgment judgment) {}   // 이름은 정해도 된다
```

- 그 사용자의 사전 전체에서, **개념마다 질의들 가운데 가장 높은 코사인 유사도**로 줄 세워 높은 순으로 최대 `limit` 개를 고른다. 같은 개념은 한 번만 나온다(03 §2 「넘기는 수」). 유사도가 같으면 개념 ID 로 순서를 고정한다.
- 유사도 하한은 두지 않는다(03 §2 「하한」).
- 고른 개념마다 step 0 의 판정을 붙인다 — 판정은 한 번에 묶어 부른다.
- 유사도 숫자는 돌려주지 않는다(03 §2 「넘기는 모양」 — 숫자를 넘기지 않는다). 시험에서 순서를 확인하는 데는 순서로 충분하다.
- `queries` 가 비었으면 빈 목록이다.

### 3. 같은 개념 맞추기 — 강의 자료

```java
List<Judgment> judgeMatching(long userId, List<float[]> definitions, Instant now);
```

- 정의 벡터마다 그 사용자의 사전에서 가장 가까운 개념 하나를 찾아, 코사인 유사도가 **같은 개념 기준값**(`ConceptProperties.sameThreshold()` — 카드의 개념을 사전에 붙일 때와 같은 값) 이상이면 그 개념의 판정, 아니면 기록 없음이다(03 §3). 돌려준 목록은 받은 순서와 길이가 같다.
- 가장 가까운 개념을 고르는 순서(유사도 → 개념 ID)와 「이상」 비교는 `ConceptSimilaritySearchImpl.findSameConcept` 와 같게 한다 — 쓰기 경로와 읽기 경로가 같은 개념을 고르게 한다. 시험으로 고정한다.
- 판정은 한 번에 묶어 부른다.

### 4. 쿼리

- 유사도 쿼리는 `card.repository` 의 네이티브 쿼리(pgvector `<=>`)로 둔다. **읽기 전용 경로라 `flush` 하지 않는다** — 쓰기 트랜잭션 안에서 부르는 `findSameConcept` 와 따로 둔다. 기존 `findSameConcept` 는 바꾸지 마라. 이유: 사전 쓰기의 유일한 경로(금지 16)다.
- 질의 벡터 여럿을 질의마다 왕복하지 말고 한 쿼리(또는 묶음 몇 개)로 보낸다. 벡터는 3072 차원이다 — 지금 테이블에 벡터 색인이 없는지 확인하고, 색인을 새로 만들지 마라(정원 10명 · 사전이 작다).
- 다른 사용자의 개념은 어떤 경우에도 보지 않는다(금지 16 · 18 — 사용자마다 사전).

### 5. 시험

- 레포지토리(`@RepositoryTest`) 또는 통합 — 벡터를 직접 정해 유사도를 만든다(`ConceptSimilaritySearchImplTest` 를 본뜬다):
    - `findRelated` — 높은 유사도 순 · `limit` 개까지 · 질의가 여럿이면 개념마다 가장 높은 유사도로 줄 선다 · 같은 개념이 두 번 나오지 않는다 · 남의 개념은 나오지 않는다 · 판정이 붙는다 · 사전이 비면 빈 목록.
    - `judgeMatching` — 기준값과 같은 유사도는 맞춘다(「이상」) · 바로 아래는 기록 없음 · 가장 가까운 것이 기준 아래면 기록 없음 · 같은 벡터를 넣으면 `findSameConcept` 가 고르는 개념과 같은 개념의 판정 · 순서와 길이 유지 · 남의 개념과는 맞추지 않는다.
    - `hasConcepts` — 내 사전이 비었으면 거짓, 남의 사전만 있어도 거짓.
    - 세 조회가 아무것도 바꾸지 않는다(행 수 · `updated_at`).

### 6. 문서 — 구현으로 정해진 사실만

- `docs/02-domain-data.md` §6 끝에 구현 줄 하나 — 읽기 전용 조회의 클래스 · 메서드, 쓰기 경로(`findSameConcept`)와 같은 순서 · 비교를 쓴다는 것.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.card.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 세 조회가 읽기 전용이고 `flush` · 쓰기가 없는가? 기존 `findSameConcept` 가 그대로인가?
   - `judgeMatching` 이 쓰기 경로와 같은 기준값 · 같은 순서 · 「이상」 비교인가?
   - 유사도 숫자를 밖으로 내지 않는가? 남의 사전을 보지 않는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/10-mastery-loop/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 서비스 클래스 · 세 메서드 시그니처 · 돌려주는 record 이름과 칸 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 임베딩 · LLM 을 부르지 마라. `card` 에서 `generation` 을 참조하지 마라. 이유: 금지 1 — 유료 호출은 `generation` 만 한다.
- 생성 흐름(`generation`)을 고치지 마라. 이유: step 2 ~ 4 의 범위다.
- 사전에 개념을 넣거나 강의의 다룰 개념을 사전과 잇는 칸 · 테이블을 만들지 마라. 이유: 금지 16 · ADR-138 — 사전 쓰기는 카드가 생길 때 한 곳에서만 하고, 다룰 개념은 글로만 담는다.
- 벡터 색인 · 마이그레이션을 더하지 마라. 이유: 정원 10명이라 사전이 작고, 요청받지 않은 변경이다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
