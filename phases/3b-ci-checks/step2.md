# Step 2: sonar-sources

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `sonar-project.properties` — step 1 이 더한 `sonar.tests` · 클래스 · 커버리지 설정
- `backend/src/test/java/com/mogum/deploy/SonarAnalysisFilesTest.java` — 특히 `separatesBackendTestsAndImportsXmlWithoutChangingSourceScope` 의 `doesNotContainKey("sonar.sources")`
- `deploy/README.md` 「PR 검사」 → 「SonarCloud · 백엔드 커버리지」 — `sonar.sources` 를 따로 정하지 않는다고 적은 문장
- `docs/TEST_GUIDE.md` §2 · §3

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

step 1 은 "`sonar.sources` 를 정하지 않으면 저장소 전체가 운영 코드로 분석된다" 는 전제로 `sonar.tests=backend/src/test` 만 더했다. 이 전제가 틀렸다. PR #25 의 SonarCloud 실행 로그(GitHub Actions run `42702619647`, 2026-11-09)에서 Claude 가 확인한 사실:

- `93 files indexed` · `Using ECJ batch to parse 93 Test java source files` — 백엔드 테스트 파일만 색인됐다.
- `0 files ignored because of inclusion/exclusion patterns` — 운영 코드가 제외 패턴에 걸린 것이 아니라 아예 소스로 잡히지 않았다.
- 이전 실행(step 1 전)은 `151 Main java source files` 로, 운영 코드 · 테스트 코드 · 워크플로 · 배포 스크립트 · 화면이 모두 운영 코드로 색인됐다.
- 결과로 커버리지 측정값이 없고 코드 스멜이 0 이다 — 운영 코드 분석이 통째로 빠졌다.

`sonar.tests` 를 둘 때는 `sonar.sources` 도 명시해야 한다. 저장소 전체를 운영 코드 범위로 유지하려면 `sonar.sources=.` 이다. 테스트 폴더는 이미 `sonar.exclusions` 에 `backend/src/test/**` 로 빠져 있어 두 번 색인되지 않는다.

## 작업

1. **테스트 먼저** — `SonarAnalysisFilesTest` 의 해당 테스트에서 `doesNotContainKey("sonar.sources")` 를 `containsEntry("sonar.sources", ".")` 로 바꾸고, 메서드 이름 · `@DisplayName` 을 새 사실에 맞게 고친다(예: "운영 코드 범위를 저장소 전체로 명시하고 백엔드 테스트와 클래스 및 XML 경로를 지정한다"). 실패하는지 확인한다.
2. `sonar-project.properties` 에 `sonar.sources=.` 를 더한다. 기존 주석 형식대로 이유를 단다 — `sonar.tests` 를 두면 `sonar.sources` 를 비워 둘 때 운영 코드가 색인되지 않으므로(PR #25 Sonar 로그) 저장소 전체를 명시한다. `sonar.exclusions` 의 `backend/src/test/**` 주석에서 "sonar.sources 를 따로 정하지 않아" 라는 표현도 이 사실에 맞게 고친다.
3. `deploy/README.md` 「SonarCloud · 백엔드 커버리지」에서 "`sonar.sources`는 따로 정하지 않아 저장소 전체 분석 범위를 유지한다" 를 고친다 — `sonar.sources=.` 로 저장소 전체를 운영 코드 범위로 명시하고, 비워 두면 `sonar.tests` 만 색인됐다는 것(PR #25 로그 — 93 Test 파일만 색인)을 한 문장으로 적는다. 「아직 확인하지 않은 것」 문장은 그대로 둔다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*SonarAnalysisFilesTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `sonar-project.properties` 에 `sonar.sources=.` 가 있고, `sonar.tests` · `sonar.exclusions`(`backend/src/test/**` 포함) · 나머지 값과 예외 규칙 e1 · e2 가 그대로인가?
   - 테스트가 `sonar.sources` 를 `.` 로 검사하고, 다른 단언은 그대로인가?
   - `deploy/README.md` 에 `sonar.sources` 를 정하지 않는다는 문장이 남지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3b-ci-checks/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `sonar.sources` 를 `.` 이외의 목록(`backend/src/main,frontend/src` 등)으로 정하지 마라. 이유: 지금까지의 분석 범위(워크플로 · 배포 스크립트 · 화면 포함)를 줄이지 않기로 했다.
- `sonar.tests` · `sonar.exclusions` · 커버리지 설정 · 예외 규칙 e1 · e2 를 바꾸지 마라. 이유: 이 step 은 빠진 운영 코드 범위 하나만 고친다.
- `.github/workflows/` · `backend/build.gradle` 을 고치지 마라. 이유: 워크플로와 JaCoCo 는 의도대로 돌았다(같은 로그에서 `jacocoTestReport` · JaCoCo 가져오기 센서 실행 확인).
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
