# Step 4: coverage-scope

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `sonar-project.properties` — `sonar.coverage.exclusions=frontend/**` 와 그 주석
- `backend/src/test/java/com/mogum/deploy/SonarAnalysisFilesTest.java` — `containsEntry("sonar.coverage.exclusions", "frontend/**")`
- `deploy/README.md` 「PR 검사」 → 「SonarCloud · 백엔드 커버리지」 — `sonar.coverage.exclusions=frontend/**` 를 설명하는 문장
- `docs/TEST_GUIDE.md` §2 · §3

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

SonarCloud 커버리지는 **백엔드 운영 코드의 테스트 커버리지**를 뜻하게 하려고 넣었다. 그런데 PR #25 의 값이 실제와 크게 다르다. Claude 가 2026-11-10 에 확인한 사실:

- 로컬 `./backend/gradlew -p backend test` 의 JaCoCo XML 합계 — 줄 538 중 526 덮임(97.8%) · 분기 90.3%.
- 같은 PR 의 Sonar 값 — 덮어야 할 줄 2,038 · 커버리지 32.8%.
- 화면(`frontend/**` — 커버리지 제외)과 백엔드 테스트(`sonar.tests`)를 빼면, 커버리지를 재는 언어의 파일은 `backend/src/main` 의 Java 52개와 Python 5개(`scripts/execute.py` · `scripts/tdd_guard.py` · `scripts/test_execute.py` · `scripts/test_tdd_guard.py` · `docs/design/tools/fetch.py`)뿐이다. `docs/design/**` 는 이미 `sonar.exclusions` 로 분석에서 빠져 있다.
- 따라서 차이(약 1,500줄)는 커버리지 리포트가 없는 하네스 Python 스크립트(`scripts/`)가 「덮이지 않음」으로 세어진 것으로 본다. PR 분석은 바뀐 파일만 저장해 파일별 내역은 확인하지 못했다 — 고친 뒤 PR 의 Sonar 값으로 확인한다(Claude 가 한다).

`scripts/` 는 하네스(개발 도구)이고 제품 코드가 아니다. 정적 분석은 그대로 두고 커버리지 계산에서만 뺀다.

## 작업

1. **테스트 먼저** — `SonarAnalysisFilesTest` 의 기대값을 `sonar.coverage.exclusions` = `frontend/**,scripts/**` 로 바꾼다. 쉼표 순서에 기대지 않게 나눠서 정확히 두 값인지 검사해도 된다. 필요하면 `@DisplayName` 을 맞춘다. 실패하는지 확인한다.
2. `sonar-project.properties` 의 `sonar.coverage.exclusions` 를 `frontend/**,scripts/**` 로 바꾸고, 주석에 `scripts/**` 의 이유를 더한다 — 하네스 Python 스크립트(제품 코드 아님)는 커버리지 리포트가 없어 덮이지 않은 줄로 세어져 백엔드 커버리지를 왜곡한다(PR #25 — Sonar 2,038줄 · 32.8% vs JaCoCo 538줄 · 97.8%). 정적 분석에서는 빼지 않는다.
3. `deploy/README.md` 「SonarCloud · 백엔드 커버리지」의 커버리지 제외 설명을 `frontend/**` · `scripts/**` 둘로 고치고, 위 이유를 한 문장으로 적는다. Sonar 커버리지가 백엔드 운영 코드(`backend/src/main`)만 뜻한다는 것을 적는다. 「아직 확인하지 않은 것」 문장이 있으면 그대로 둔다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*SonarAnalysisFilesTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `sonar-project.properties` 의 변경이 `sonar.coverage.exclusions` 값과 그 주석뿐인가? `sonar.sources` · `sonar.tests` · `sonar.exclusions` · 예외 규칙 e1 · e2 가 그대로인가?
   - 테스트의 다른 단언이 그대로인가?
   - `deploy/README.md` 에 화면만 커버리지에서 뺀다는 옛 설명이 남지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3b-ci-checks/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `scripts/**` 를 `sonar.exclusions`(정적 분석 제외)에 넣지 마라. 이유: 하네스 스크립트의 정적 분석은 계속 받는다 — 커버리지 계산만 뺀다.
- 커버리지 제외를 `backend/src/main/**` 이외 전부로 뒤집는 식(`sonar.coverage.inclusions` 등)으로 바꾸지 마라. 이유: 이 step 은 확인한 원인 하나만 고친다.
- Python 커버리지 도구를 더하거나 `scripts/` 를 고치지 마라. 이유: 하네스는 이 phase 범위가 아니다.
- `.github/workflows/` · `backend/build.gradle` 을 고치지 마라. 이유: 워크플로와 JaCoCo 는 의도대로 돈다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
