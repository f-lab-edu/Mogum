# Step 1: sonar-coverage

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `.github/workflows/sonarcloud-analyze.yml` — F-Lab 이 준 SonarCloud 워크플로. 지금은 빌드 · 테스트 없이 소스만 스캔한다
- `sonar-project.properties` — 스캐너가 읽는 설정과 기존 예외 규칙(e1 · e2)의 이유
- `backend/build.gradle` — 플러그인 · `tasks.withType(Test)` · `tasks.named('test')` 의 입력 · `imageTest`
- `.github/workflows/ci.yml` · `backend/src/test/java/com/mogum/deploy/CiWorkflowFilesTest.java` — step 0 이 만든 PR 검사와 그 테스트(액션 SHA 표)
- `backend/src/test/java/com/mogum/deploy/WorkflowFilesTest.java` — 워크플로 불변식 테스트 형식
- `docs/TEST_GUIDE.md` §2 · §3
- `deploy/README.md` 「PR 검사」 — step 0 이 쓴 절

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

SonarCloud 에 백엔드 테스트 커버리지(JaCoCo)를 올린다. 지금 사실(Claude 가 2026-11-09 에 확인):

- Sonar 워크플로는 컴파일 · 테스트 없이 스캔한다 — 실행 로그에 `Missing 'sonar.java.binaries' and 'sonar.java.libraries' properties` 경고가 있다. 커버리지를 올리려면 **스캔하는 그 job 안에** JaCoCo XML 이 있어야 한다.
- `sonar.tests` 가 없어 `backend/src/test` 의 테스트 코드도 운영 코드로 분석된다(로그 `151 Main java source files`). 이대로 커버리지를 올리면 테스트 줄도 「덮어야 할 줄」로 센다.
- 품질 게이트의 커버리지 조건은 `new_coverage` 가 `0` 미만일 때만 실패다(`https://sonarcloud.io/api/qualitygates/project_status?projectKey=f-lab-edu_Mogum&pullRequest=23` 로 조회). 커버리지를 올려도 게이트가 새로 막지는 않는다.

사용자가 정한 것(바꾸지 마라):

- Sonar 워크플로는 F-Lab 템플릿이라 **기존 줄은 그대로 두고, 스캔 앞에 단계만 더한다.**
- 백엔드 테스트 코드를 `sonar.tests` 로 구분한다. 화면 테스트는 구분하지 않는다.
- 화면(`frontend/**`)은 커버리지 계산에서 뺀다. 화면 커버리지는 올리지 않는다.

## 작업

### 1. `backend/build.gradle` — JaCoCo

- `plugins` 에 `id 'jacoco'` 를 더한다. JaCoCo 버전은 Gradle(9.1.0 Wrapper)이 정하는 기본값을 쓴다. Java 21 클래스를 계측하지 못해 실패할 때만 `jacoco { toolVersion = … }` 로 고정하고 이유를 주석으로 남긴다.
- `test` 가 끝나면 `jacocoTestReport` 가 돌게 한다(`finalizedBy`). 테스트가 실패해도 리포트는 만들어진다.
- `jacocoTestReport` 는 XML 만 만든다(HTML · CSV 는 끈다). 리포트 경로는 기본값을 쓰고 바꾸지 않는다 — `backend/build/reports/jacoco/test/jacocoTestReport.xml` 로 알고 있다. **실제로 만들어진 경로를 확인해서** 아래 properties · 테스트 · 문서에 적는다.
- `imageTest` 에 리포트를 붙이지 않는다.
- `tasks.named('test')` 에 `inputs.file('../sonar-project.properties')` 를 더한다. 이유: 아래 테스트가 읽는 비소스 파일이다.

### 2. `sonar-project.properties`

기존 줄 · 주석 형식을 따라, 키마다 이유를 주석으로 단다.

- `sonar.tests=backend/src/test`
- `sonar.exclusions` 에 `backend/src/test/**` 를 더한다(기존 값은 그대로). 이유: `sonar.sources` 를 따로 정하지 않아 저장소 전체가 운영 코드이므로, 테스트 폴더를 운영 코드에서 빼야 같은 파일이 두 번 색인되지 않는다.
- `sonar.java.binaries=backend/build/classes/java/main` · `sonar.java.test.binaries=backend/build/classes/java/test` — 실제 Gradle 출력 경로를 확인해서 적는다.
- `sonar.coverage.jacoco.xmlReportPaths=` 위에서 확인한 리포트 경로(저장소 루트 기준)
- `sonar.coverage.exclusions=frontend/**`
- 기존 `sonar.issue.ignore.multicriteria` e1 · e2 는 그대로 둔다.

### 3. `.github/workflows/sonarcloud-analyze.yml` — 스캔 앞에 단계 추가

`actions/checkout` 단계 다음, `Set SonarCloud Project Key` 앞에 아래 단계를 더한다. 그 밖의 줄은 한 글자도 바꾸지 않는다.

1. `actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0` — `distribution: temurin` · `java-version: '21'`
2. `gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6 # v6.4.0`
3. `./backend/gradlew -p backend test` — **`continue-on-error: true`**. 이유: 테스트가 실패해도 스캔은 돌아야 한다. 테스트 실패로 병합을 막는 일은 `ci.yml` 의 `ci-ok` 가 한다. 리포트는 `finalizedBy` 로 실패 때도 만들어진다.

SHA 는 Claude 가 2026-11-09 에 GitHub API 로 조회한 값이고 `ci.yml` 이 쓰는 값과 같다.

### 4. 테스트 — `backend/src/test/java/com/mogum/deploy/SonarAnalysisFilesTest.java`

`WorkflowFilesTest` 와 같은 방식으로 최소한 아래 사실을 검사한다. 이름 · 본문 형식은 `docs/TEST_GUIDE.md` §2 · §3.

- Sonar 워크플로에 setup-java · setup-gradle · 백엔드 `test` 단계가 있고, 셋 다 스캔 단계(`SonarSource/sonarcloud-github-action`)보다 앞이다.
- 더한 두 액션이 위 SHA · 릴리스 주석이다.
- 백엔드 `test` 단계가 `continue-on-error: true` 다.
- `sonar-project.properties` 의 `sonar.tests` · `sonar.java.binaries` · `sonar.java.test.binaries` · `sonar.coverage.jacoco.xmlReportPaths` · `sonar.coverage.exclusions` 값이 위와 같고, `sonar.exclusions` 에 `backend/src/test/**` 와 기존 세 값이 모두 있으며, `sonar.sources` 가 없다.

### 5. 문서 — `deploy/README.md` 「PR 검사」

step 0 이 쓴 절에 SonarCloud 부분을 더한다.

- Sonar 워크플로가 스캔 전에 백엔드 테스트와 JaCoCo XML 을 만들고, 테스트가 실패해도 스캔은 돈다는 것(막는 것은 `ci-ok`) — 그래서 PR 마다 백엔드 테스트가 CI 와 Sonar 에서 두 번 돈다
- 백엔드 테스트 코드는 `sonar.tests` 로 구분한다 · 화면은 커버리지에서 뺀다 · 품질 게이트의 커버리지 조건(`new_coverage` < 0 일 때만 실패 — 2026-11-09 API 조회)
- 더한 단계만 SHA 로 고정했고 템플릿의 기존 줄(`actions/checkout@v3` · `@master`)은 F-Lab 템플릿이라 그대로 둔다는 것
- **아직 확인하지 않은 것**: PR 의 Sonar 실행에서 테스트 파일이 테스트로 색인되는지 · 커버리지 값이 올라가는지

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*SonarAnalysisFilesTest' --tests '*CiWorkflowFilesTest' --tests '*WorkflowFilesTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요. 끝나면 JaCoCo XML 이 만들어져 있다
```

## 검증 절차

1. 위 AC 커맨드를 실행한다. `build` 뒤 properties 에 적은 리포트 경로에 XML 파일이 있는지 확인한다.
2. 아키텍처 체크리스트를 확인한다:
   - Sonar 워크플로의 기존 줄이 그대로이고, 더한 단계 셋만 있는가? (`git diff .github/workflows/sonarcloud-analyze.yml` 이 추가 줄뿐인가)
   - properties 의 경로(리포트 · 클래스 출력)가 실제 Gradle 출력과 같은가?
   - `sonar.sources` 를 정하지 않았고, 기존 예외 e1 · e2 가 그대로인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3b-ci-checks/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 실제 리포트 경로 · JaCoCo 버전(기본값인지) · 더한 단계 · 새 테스트 수
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- Sonar 워크플로의 기존 줄(`name` · `on` · `env` · `actions/checkout@v3` · `fetch-depth` · 프로젝트 키 단계 · `sonarcloud-github-action@master` · 그 `continue-on-error` · `env` · `args`)을 바꾸거나 지우지 마라. 이유: F-Lab 이 준 템플릿이라 그대로 두기로 사용자가 정했다.
- `sonar.sources` 를 정하지 마라. 이유: 지금 분석 범위(저장소 전체 — 배포 스크립트 · 워크플로 · 화면 포함)가 바뀐다.
- 화면 테스트를 `sonar.tests` 에 넣거나 화면 커버리지 도구(`@vitest/coverage-v8` 등)를 더하지 마라. 이유: 사용자가 화면은 커버리지에서 빼기로 정했다.
- 기존 예외 규칙 e1 · e2 를 지우지 마라. 이유: 테스트 규칙으로 바뀐 뒤에도 그 지적이 나오는지는 Sonar 를 돌려 봐야 안다.
- 커버리지 최소 기준(`jacocoTestCoverageVerification` 등)을 두지 마라. 이유: 기준은 정하지 않았고, 판정은 Sonar 게이트가 한다.
- `sonar.java.libraries` 를 넣지 마라. 이유: Gradle 캐시 경로에 기대게 된다.
- `.github/workflows/ci.yml` · `deploy.yml` 을 고치지 마라. 이유: 커버리지는 Sonar 워크플로만 올린다. 배포는 그대로 둔다.
- 저장소 · SonarCloud 설정을 바꾸지 마라. 이유: 외부 설정은 사람이 확인한 뒤 바꾼다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
