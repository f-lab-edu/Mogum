# Step 0: ci-workflow

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `.github/workflows/deploy.yml` — 액션을 전체 SHA + 릴리스 주석으로 고정하는 방식, `'on':` 을 인용한 이유
- `.github/workflows/sonarcloud-analyze.yml` — 지금 PR 에서 도는 유일한 워크플로(이 step 에서는 읽기만 한다)
- `backend/src/test/java/com/mogum/deploy/WorkflowFilesTest.java` — 워크플로 불변식을 SnakeYAML 로 검사하는 방식(새 테스트가 따를 형식)
- `backend/build.gradle` — `tasks.named('test')` 의 `inputs.dir('../.github/workflows')`
- `frontend/package.json` — `lint` · `test` · `build` 스크립트
- `docs/TEST_GUIDE.md` §2 · §3 (테스트 이름 · `@DisplayName` · given/when/then · AssertJ)
- `deploy/README.md` 「GitHub Actions 배포」 · 「워크플로 검증 범위」 — 새 절을 쓸 자리와 문체
- `docs/07-testing.md` 머리말(맨 위 인용 블록)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

지금 PR 에서는 SonarCloud 정적 분석만 돈다. 백엔드 테스트는 `main` 에 병합된 뒤 `deploy.yml` 안에서 처음 돌고, 화면의 `lint` · `test` 는 어디서도 돌지 않는다. 이 step 은 PR 마다 백엔드 테스트와 화면 검사를 돌리는 워크플로를 만든다. 저장소 룰셋에는 나중에 사람이 **`ci-ok` 하나만** 필수 상태 검사로 건다.

사용자가 정한 것(바꾸지 마라):

- 백엔드와 화면을 **둘 다 PR 마다 항상** 돌린다. 경로 필터나 "바뀐 파일 판별" 은 두지 않는다.
- 필수 검사는 집계 job `ci-ok` 하나다.
- `imageTest`(Docker 이미지 검사)는 PR 에서 돌리지 않는다. 배포 워크플로가 한다.
- `deploy.yml` 의 `build`(테스트 포함)는 병합 뒤 최종 확인으로 그대로 둔다.

## 작업

### 1. `.github/workflows/ci.yml` (새 파일)

- `name: CI`
- 트리거: `'on':` 을 인용해 쓰고(SnakeYAML 이 YAML 1.1 에서 `on` 을 `true` 로 읽는다 — `WorkflowFilesTest` 주석), `pull_request` 하나만 둔다. `types` · `branches` · `paths` 필터를 두지 않는다.
- 최상위 `permissions: { contents: read }` 만 둔다. job 에서 `permissions` 를 다시 정하지 않는다.
- 최상위 `concurrency`: 같은 PR 의 실행끼리 묶는 그룹(PR 번호를 넣는다) · `cancel-in-progress: true`.
- job 은 정확히 셋 — `backend` · `frontend` · `ci-ok`. 모두 `runs-on: ubuntu-24.04` · `timeout-minutes` 를 둔다(backend 30 · frontend 15 · ci-ok 5).
- **`backend`**
  1. `actions/checkout` — `persist-credentials: false`
  2. `actions/setup-java` — `distribution: temurin` · `java-version: '21'`
  3. `gradle/actions/setup-gradle`
  4. `./backend/gradlew -p backend test`
  5. 실패할 때만(`if: failure()`) `actions/upload-artifact` 로 `backend/build/reports/tests/test` 를 올린다 — `retention-days: 7`.
- **`frontend`** — `defaults.run.working-directory: frontend` 또는 단계마다 같은 효과
  1. `actions/checkout` — `persist-credentials: false`
  2. `actions/setup-node` — `node-version-file: frontend/.nvmrc` · `cache: npm` · `cache-dependency-path: frontend/package-lock.json`
  3. `npm ci` → `npm run lint` → `npm run test` → `npm run build` (단계를 나눠 어디서 실패했는지 보이게 한다)
- **`ci-ok`** — 필수 검사로 걸 job 이다.
  - job id 는 `ci-ok` 이고 `name:` 을 따로 주지 않는다. 이유: 룰셋에 거는 검사 이름이 이 이름이다.
  - `needs: [backend, frontend]` · **`if: always()`**.
  - 단계 하나 — `needs.backend.result` 와 `needs.frontend.result` 가 둘 다 `success` 가 아니면 `exit 1`. 결과 값은 `env:` 로 넘겨 셸에서 비교한다(`run:` 안에 `${{ }}` 를 직접 넣지 않는다).
  - **이유(핵심 규칙)**: GitHub 는 건너뛴(skipped) 필수 검사를 통과로 친다. `if: always()` 가 없으면 `backend` 가 실패할 때 `ci-ok` 가 건너뛰어지고, 실패한 PR 이 병합된다. `cancelled` · `skipped` 도 실패로 친다.
- 액션은 아래 SHA 로 고정하고 줄 끝에 릴리스를 주석으로 단다(`uses: actions/checkout@fbc6f39… # v5.1.0` 형식 — `deploy.yml` 과 같다). Claude 가 2026-11-09 에 GitHub API(`gh api repos/{저장소}/commits/{태그} --jq .sha`)로 각 태그가 가리키는 커밋을 조회한 값이다. 앞의 둘은 `deploy.yml` 이 이미 쓰는 값이다.

  | 액션 | SHA | 릴리스 |
  |---|---|---|
  | `actions/checkout` | `fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09` | v5.1.0 |
  | `actions/setup-java` | `b6effb05e454b25005698d916606bdc6ffcbf961` | v5.7.0 |
  | `gradle/actions/setup-gradle` | `3f5f9adaf7d9fecd50b5935e54106014257a94e6` | v6.4.0 |
  | `actions/setup-node` | `949feb2413d6458794dcd2491c4babbbce0c15c1` | v7.1.0 |
  | `actions/upload-artifact` | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` | v7.0.2 |

### 2. `frontend/.nvmrc` (새 파일)

내용은 `22` 한 줄. 개발 머신의 Node 가 v22.18.0 이고 Vite 8 이 받는 범위다.

### 3. 테스트 — `backend/src/test/java/com/mogum/deploy/CiWorkflowFilesTest.java`

`WorkflowFilesTest` 와 같은 방식(SnakeYAML 로 읽어 단언)으로 최소한 아래 사실을 검사한다. 이름 · `@DisplayName` · given/when/then · AssertJ 는 `docs/TEST_GUIDE.md` §2 · §3 을 따른다.

- 트리거가 `pull_request` 하나이고 `paths` · `paths-ignore` · `branches` 필터가 없다. `pull_request_target` 이 없다.
- 최상위 권한이 `contents: read` 하나이고, 어느 job 에도 `permissions` · `environment` 가 없다.
- 워크플로 어디에도 `secrets.` · `vars.` 참조가 없다.
- job 이 정확히 `backend` · `frontend` · `ci-ok` 이고, `ci-ok` 는 `name` 이 없으며 `needs` 가 두 job 이고 `if` 가 `always()` 다.
- `ci-ok` 단계가 두 결과를 `success` 와 비교한다(`needs.backend.result` · `needs.frontend.result` 를 env 로 받는다).
- 모든 `uses:` 가 `소유자/이름[/경로]@40자 소문자 SHA` 이고, 위 표의 SHA · 릴리스 주석과 같다.
- `backend` 가 `./backend/gradlew -p backend test` 를 실행하고 `imageTest` · `docker` 를 실행하지 않는다.
- `frontend` 가 `npm ci` · `npm run lint` · `npm run test` · `npm run build` 를 이 순서로 실행하고, `setup-node` 가 `frontend/.nvmrc` 를 읽는다.
- `frontend/.nvmrc` 의 내용이 `22` 다.

`backend/build.gradle` 의 `tasks.named('test')` 에 `inputs.file('../frontend/.nvmrc')` 를 더한다. 이유: 테스트가 읽는 비소스 파일이라, 선언하지 않으면 그 파일만 바꿨을 때 `test` 가 UP-TO-DATE 로 건너뛴다. 워크플로 폴더는 이미 입력이다.

### 4. 문서

- `deploy/README.md` — 「GitHub Actions 배포」의 하위 절들이 끝난 뒤, `## Vercel(화면)` 앞에 `## PR 검사` 절을 새로 쓴다. 담을 것:
  - `ci.yml` 이 PR 마다 무엇을 도는지(job 셋과 각 명령) · 경로와 상관없이 항상 돈다는 것과 그 이유(필수 검사가 경로 필터로 생기지 않으면 병합이 「대기 중」으로 막힌다)
  - `ci-ok` 의 `if: always()` 규칙과 이유(건너뛴 필수 검사는 통과로 친다)
  - 러너에서 쓰는 명령(S9) — Bash · `java`(setup-java) · Gradle Wrapper · `node` · `npm`. 그 밖의 도구에 기대지 않는다
  - 액션 SHA 표와 확인 방법(위 표 · 2026-11-09 GitHub API 조회)
  - **아직 하지 않은 것**: ① 저장소 룰셋 `main — PR 로만 병합` 에 `ci-ok` 를 필수 상태 검사로 거는 일 — 사람이 첫 실행을 본 뒤 건다 ② 실제 GitHub Actions 실행 ③ `frontend/.nvmrc` 의 22 가 Vercel 프로젝트의 Node.js 버전과 같은지 확인
  - 불변식은 `CiWorkflowFilesTest` 가 검사한다는 것
- `docs/07-testing.md` — 머리말 인용 블록에 한 줄을 더한다: PR 마다 무엇이 도는지는 `deploy/README.md` 「PR 검사」에 있다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*CiWorkflowFilesTest' --tests '*WorkflowFilesTest'
./backend/gradlew -p backend test   # Testcontainers — Docker 필요
npm --prefix frontend run lint
npm --prefix frontend run test
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `ci-ok` 가 `if: always()` 이고 두 결과가 모두 `success` 일 때만 통과하는가? job id 그대로 검사 이름이 되는가(`name:` 없음)?
   - 모든 액션이 위 표의 SHA + 릴리스 주석으로 고정됐는가? `secrets` · `vars` · `pull_request_target` · 경로 필터가 없는가?
   - `deploy.yml` · `sonarcloud-analyze.yml` · `sonar-project.properties` 가 그대로인가?
   - `deploy/README.md` 「PR 검사」에 아직 하지 않은 것 셋이 그대로 적혀 있는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3b-ci-checks/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 만든 파일 · job 이름 · `ci-ok` 조건 · 쓴 액션 SHA · 새 테스트 수
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `paths` · `paths-ignore` 나 바뀐 파일을 판별하는 단계 · 액션을 넣지 마라. 이유: 사용자가 항상 돌리기로 정했다. 필수 검사가 경로 필터로 생기지 않으면 병합이 막히고, 판별 로직은 그 자체로 틀릴 수 있다.
- `imageTest` · `docker build` 를 넣지 마라. 이유: 사용자 결정 — 배포 워크플로가 같은 검사를 한다.
- `pull_request_target` 을 쓰거나 `secrets` · `vars` 를 참조하지 마라. 이유: 포크 PR 의 코드가 비밀을 가진 채 돈다. 백엔드 테스트는 `build.gradle` 이 가짜 Google 자격을 넣어 비밀이 필요 없다.
- 액션을 태그(`@v5`)나 브랜치로 참조하지 마라. 위 표에 없는 액션을 더하지 마라. 이유: 태그는 옮겨질 수 있고 SonarCloud 가 `githubactions:S7637` 로 PR 을 막는다. 표의 SHA 는 조회한 값이다.
- `.github/workflows/deploy.yml` · `.github/workflows/sonarcloud-analyze.yml` · `sonar-project.properties` 를 고치지 마라. 이유: 배포는 그대로 두기로 했고, Sonar 쪽은 다음 step 범위다.
- `frontend/package.json` 에 `engines` 를 더하지 마라. 이유: Vercel 이 `engines` 로 빌드 Node 버전을 고를 수 있어 배포 환경이 바뀔 수 있다.
- 커버리지(JaCoCo · Vitest coverage)를 넣지 마라. 이유: 다음 step 범위다.
- 저장소 룰셋 · 설정을 바꾸지 마라(`gh api` 등). 이유: 저장소 설정은 사람이 첫 실행을 본 뒤 바꾼다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
