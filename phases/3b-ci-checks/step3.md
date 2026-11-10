# Step 3: npm-ignore-scripts

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `.github/workflows/ci.yml` — `frontend` job 의 `Install frontend dependencies` 단계(`run: npm ci`)
- `backend/src/test/java/com/mogum/deploy/CiWorkflowFilesTest.java` — `checksFrontendInOrderUsingTheDeclaredNodeVersion` 의 `containsExactly("npm ci", …)`
- `deploy/README.md` 「PR 검사」 — `frontend` job 표 줄
- `docs/TEST_GUIDE.md` §2 · §3

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

PR #25 의 SonarCloud 품질 게이트가 실패했다(2026-11-10). 새 코드 보안 등급이 C 다. 지적은 하나다.

- `githubactions:S6505`(MAJOR · 취약점) — `.github/workflows/ci.yml:63` 의 `npm ci` 에 `--ignore-scripts` 가 없다. 의존 패키지의 설치 스크립트(lifecycle scripts)가 CI 에서 실행될 수 있다.

Claude 가 확인한 사실:

- `frontend/package-lock.json` 에서 설치 스크립트가 있는 패키지(`hasInstallScript`)는 `node_modules/fsevents`(macOS 전용 선택 의존성) 하나뿐이다.
- 이 브랜치의 `frontend/` 를 깨끗한 폴더에 꺼내 `npm ci --ignore-scripts` → `npm run lint` → `npm run test`(174개 통과) → `npm run build` 가 모두 성공했다.
- Vercel 은 자체 설치 과정을 쓰므로 이 워크플로 변경과 무관하다.

## 작업

1. **테스트 먼저** — `CiWorkflowFilesTest` 의 화면 명령 기대값을 `"npm ci --ignore-scripts", "npm run lint", "npm run test", "npm run build"` 로 바꾼다. 필요하면 `@DisplayName` 에 "설치 스크립트 없이 설치" 를 반영한다. 실패하는지 확인한다.
2. `.github/workflows/ci.yml` 의 설치 단계를 `npm ci --ignore-scripts` 로 바꾼다. 다른 줄은 바꾸지 않는다.
3. `deploy/README.md` 「PR 검사」의 `frontend` 줄을 `npm ci --ignore-scripts` 로 고치고, 이유를 한 문장 더한다 — 의존 패키지의 설치 스크립트를 CI 에서 실행하지 않는다(SonarCloud `githubactions:S6505`). 지금 lockfile 에서 설치 스크립트가 있는 것은 macOS 전용 `fsevents` 뿐이다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*CiWorkflowFilesTest'
./backend/gradlew -p backend test   # Testcontainers — Docker 필요
npm --prefix frontend run lint
npm --prefix frontend run test
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `ci.yml` 의 변경이 설치 명령 한 줄뿐인가? `ci-ok` · 액션 SHA · 권한 · 트리거가 그대로인가?
   - 테스트의 다른 단언이 그대로인가?
   - `deploy/README.md` 에 `--ignore-scripts` 없는 `npm ci` 설명이 남지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3b-ci-checks/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `sonar-project.properties` 에 `githubactions:S6505` 예외를 더하지 마라. 이유: 명령 하나로 바로 고칠 수 있는 지적이라 예외를 둘 근거가 없다.
- `frontend/package.json` · `frontend/package-lock.json` 을 고치지 마라. 이유: 설치 스크립트 없이도 lint · test · build 가 통과함을 확인했다.
- `.github/workflows/sonarcloud-analyze.yml` · `deploy.yml` · `sonar-project.properties` · `backend/build.gradle` 을 고치지 마라. 이유: 이 step 은 지적 하나만 고친다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
