# Step 2: ci-style-check

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/CODE_STYLE.md` §1 — 「이 절은 7a-naver-style 이 구현하면서 확정 사실로 고친다」
- `docs/ADR.md` — ADR-184
- `deploy/README.md` 「PR 검사」 절 · `.github/workflows/ci.yml`(`backend` 작업 — 지금 `./backend/gradlew -p backend test`)
- **이 step 이 바꾸는 사실을 검사하는 시험**: `backend/src/test/java/com/mogum/deploy/CiWorkflowFilesTest.java`(172행 근처 — `backend` 작업의 `run` 이 `./backend/gradlew -p backend test` 와 같은지) · `backend/src/test/java/com/mogum/deploy/SonarAnalysisFilesTest.java`(`BACKEND_TEST` 상수) — **이 두 검사는 이 step 에서 고쳐도 된다**
- step 0 · 1 이 만든 것: `backend/build.gradle`(Spotless · Checkstyle) · `backend/config/naver/` · `.editorconfig` 와 두 step 의 커밋(`git log` 에서 `7a-naver-style): step 0` · `step 1` 의 `feat` 커밋)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

step 0 · 1 이 포매터 · Checkstyle 을 두고 코드를 맞췄다. 이 step 은 **CI 가 PR 마다 코드 스타일을 검사하게** 하고, `git blame` 이 일괄 포맷 커밋을 건너뛰게 하고, 확정된 사실을 문서에 적는다.

**계약 문서는 이 phase 전에 정해 커밋했다. 규칙 문장을 고치지 말고, 구현으로 확정된 사실(버전 · 명령 · 파일 경로)만 적어라.**

## 작업

### 1. CI — `.github/workflows/ci.yml` 의 `backend` 작업

- 같은 작업에서 테스트와 함께 `spotlessCheck` · `checkstyleMain` · `checkstyleTest` 를 돌린다 — 예: `./backend/gradlew -p backend spotlessCheck checkstyleMain checkstyleTest test`. 위반이면 `backend` 가 실패하고 `ci-ok` 가 막는다(지금 구조 그대로).
- 다른 작업 · 단계 · 액션 버전(SHA 고정)은 바꾸지 마라.
- `CiWorkflowFilesTest` · `SonarAnalysisFilesTest` 의 해당 검사만 새 명령에 맞춘다. **Sonar 워크플로가 돌리는 명령이 `backend` 작업과 같아야 한다는 불변식이면**, Sonar 쪽 명령을 바꿀지 시험의 기대만 바꿀지 시험이 지키려는 사실(커버리지 리포트를 만드는 테스트 실행)을 읽고 정한다 — Sonar 분석에는 스타일 검사가 필요 없다. 정한 것을 summary 에 적는다.

### 2. `.git-blame-ignore-revs` — 저장소 루트

- step 0 · step 1 의 `feat` 커밋 SHA(전체 40자)를 한 줄씩 적고, 각 줄 위에 무엇인지 주석(`#`)을 단다. GitHub 은 이 파일을 blame 화면에서 자동으로 읽는다. 로컬은 `git config blame.ignoreRevsFile .git-blame-ignore-revs` 로 쓴다 — 이 명령을 `AGENTS.md` 「명령어」의 `git config core.hooksPath` 줄 옆에 한 줄로 더한다.

### 3. 문서 — 구현으로 확정된 사실만

- `docs/CODE_STYLE.md` §1 — 「이 절은 7a-naver-style 이 구현하면서 확정 사실로 고친다」 문장을 지우고, 실제 도구 버전(Spotless · Checkstyle)과 `backend/build.gradle` 의 위치 · `@checkstyle:off` 로 감싼 곳이 있으면 그 규칙을 한 줄로 적는다.
- `deploy/README.md` 「PR 검사」 — `backend` 작업이 스타일 검사도 한다는 것.
- `docs/04-jobs-and-cost.md` §2.1 디렉터리 — `backend/config/naver/` 한 줄(지금 표의 모양을 따른다).
- `AGENTS.md` 「명령어」의 `spotlessApply` 줄이 실제 동작과 같은지 확인하고 다르면 고친다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*CiWorkflowFilesTest' --tests '*SonarAnalysisFilesTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - CI `backend` 작업이 `spotlessCheck` · `checkstyleMain` · `checkstyleTest` · `test` 를 돌리는가? 다른 작업 · 액션 SHA 는 그대로인가?
   - 두 워크플로 시험이 새 명령을 검사하는가? Sonar 쪽 결정의 까닭이 summary 에 있는가?
   - `.git-blame-ignore-revs` 의 SHA 가 step 0 · 1 의 `feat` 커밋과 같은가?
   - 문서에 적은 것이 구현으로 확정된 사실뿐인가? 규칙 문장을 고치지 않았는가?
3. 결과에 따라 `phases/7a-naver-style/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: CI 명령 · 시험 변경 · Sonar 결정 · blame 파일 SHA · 고친 문서
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- CI 에서 `spotlessApply` 로 코드를 고쳐 커밋하게 하지 마라. 이유: CI 는 검사만 한다 — 고치기는 사람 · Codex 가 커밋 전에 한다(AGENTS 작업 방식).
- 다른 워크플로 시험 · 배포 워크플로를 고치지 마라. 이유: 이번에 바뀌는 사실은 `backend` 작업의 명령뿐이다.
- 네이버 설정 파일 · Java 코드의 줄 배치를 바꾸지 마라. 이유: step 0 · 1 에서 끝났다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
