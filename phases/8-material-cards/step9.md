# Step 9: concept-threshold

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/ADR.md` — ADR-161 · ADR-187(이 phase 전에 실측 결과와 정한 값을 적어 두었다)
- `docs/02-domain-data.md` §6 · §9
- `docs/04-jobs-and-cost.md` §10(같은 개념 판정 설정 문단)
- 대상: `backend/src/main/resources/application.yml`(`app.concept.same-threshold`) · `backend/src/main/java/com/mogum/config/ConceptProperties.java` · 그 짝 시험

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

같은 개념 판정의 기준값(코사인 유사도)은 임시 값 `0.80` 이었다. phase 뒤 실측(`llmTest` 의 `ConceptSimilarityMeasurementTest` · 2026-11-23)에서 같은 개념 쌍 15개가 0.56~0.72, 다른 개념 쌍 15개가 0.19~0.71 로 겹쳤다. 다른 개념을 잘못 묶으면 되돌릴 수 없으므로(ADR-050) 표본의 다른 쌍 최댓값(0.709)보다 높은 **`0.72`** 로 정했다(사용자 결정 — ADR-187). 실제 흐름(같은 개념의 정의를 LLM 이 다른 강의에서 두 번 쓴 쌍)으로 다시 재는 일은 숙련도 고리 TASK 전에 한다.

## 작업

- `app.concept.same-threshold` 를 `0.72` 로 바꾼다. 설정 옆 주석의 「임시 값 — 실측 전」을 「2026-11-23 실측으로 정함(ADR-187) — 실제 흐름으로 다시 잰다」 취지로 바꾼다.
- 설정 값을 단언하는 시험이 있으면 새 값에 맞춘다. 판정 시험은 벡터를 직접 정하므로 값과 무관해야 한다 — 값에 기대는 시험이 있으면 설정에서 값을 읽도록 고친다.
- `docs/02-domain-data.md` §9 · `docs/04-jobs-and-cost.md` §10 의 「`0.80` 은 임시 값 — 실측 전」 문장을 「`0.72` — 2026-11-23 실측(ADR-187)으로 정함. 숙련도 고리 TASK 전에 실제 흐름으로 다시 잰다」로 바꾼다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.config.*' --tests 'com.mogum.card.*'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 설정 값이 `0.72` 이고 문서 두 곳이 같은 값과 근거를 적는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/8-material-cards/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 판정 방법(가장 가까운 하나 · 정의 임베딩 · LLM 에 다시 묻지 않음)을 바꾸지 마라. 이유: ADR-161 · ADR-030.
- 실측 시험이나 실제 OpenAI 를 부르지 마라. 이유: 비용 — 실측은 이미 했다.
- `docs/ADR.md` 를 고치지 마라. 이유: 이 step 전에 Claude 가 적었다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
