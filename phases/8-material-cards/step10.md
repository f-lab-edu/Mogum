# Step 10: sonar-record-array

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 작업 방식(TDD · `spotlessApply`)
- `docs/CODE_STYLE.md`
- 대상: `backend/src/main/java/com/mogum/card/service/LessonCardWriter.java`(`NewConcept` record) · 그 짝 시험 `backend/src/test/java/com/mogum/card/service/LessonCardWriterIntegrationTest.java` · 다른 짝 시험이 있으면 그것

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

PR #36 의 SonarCloud 품질 게이트가 신뢰성 등급 C 로 실패했다. 백엔드 이슈는 하나다 — `java:S6218`(MEDIUM) `LessonCardWriter.java:55` 의 `record NewConcept(String name, String definition, float[] embedding)`. record 가 자동으로 만드는 `equals` · `hashCode` · `toString` 은 배열 칸을 내용이 아니라 참조로 다룬다. 지금 이 record 를 비교하는 코드는 없지만 규칙에 걸린다.

## 작업

- `NewConcept` 에 `equals` · `hashCode` · `toString` 을 재정의해 `embedding` 을 내용으로 다룬다(`Arrays.equals` · `Arrays.hashCode`). `toString` 은 벡터 전체를 찍지 말고 차원 수만 보인다(예: `embedding=float[3072]`) — 벡터는 수천 개 숫자라 로그 · 오류 메시지에 섞이면 소음이다.
- 생성자에서 배열을 방어 복사하고 접근자도 복사본을 돌려준다 — 바깥에서 배열을 바꿔 record 의 값이 바뀌지 않게. `Concept.add` 가 이미 `embedding.clone()` 하는 것과 맞춘다.
- 시험을 먼저 쓴다 — 같은 내용의 다른 배열이면 `equals` · `hashCode` 가 같고, 내용이 다르면 다르다 · `toString` 에 벡터 값이 없다 · 넘긴 배열을 바꿔도 record 의 값이 그대로다. 단위 시험이면 `LessonCardWriterTest` 같은 짝 이름을 쓴다(TDD 가드의 짝 규칙).
- `./backend/gradlew -p backend spotlessApply` 로 포맷한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.card.*' --tests 'com.mogum.generation.*'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `NewConcept` 의 `equals` · `hashCode` 가 배열 내용을 비교하고 `toString` 이 벡터 값을 싣지 않는가?
   - 동작(개념 판정 · 저장)이 바뀌지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/8-material-cards/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `embedding` 의 타입을 바꾸지 마라(`List<Float>` 등). 이유: 생성기 · 저장소 · 시험이 모두 `float[]` 를 쓴다 — 이 step 은 record 의 비교만 고친다.
- `@SuppressWarnings` 나 Sonar 억제 주석으로 피하지 마라. 이유: 실제로 고치는 것으로 정했다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
