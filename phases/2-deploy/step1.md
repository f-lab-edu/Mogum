# Step 1: image-check

## 배경 — 이 phase(`2-deploy`)가 하는 일

지금의 앱을 운영에 올린다(`docs/ADR.md` ADR-072). GitHub Actions 가 서버와 같은 arm64 러너에서 앱 이미지를 **한 번** 빌드하고, **그 이미지로** 기동 · 정상 종료 검사를 통과한 뒤 ECR 에 올린다 — CI 가 확인한 이미지가 곧 서버에서 도는 이미지가 되게 한다. 이 step 은 그 **이미지 검사**를 만든다. 워크플로(step 5)는 빌드한 이미지 이름을 넘겨 이 검사를 돌린다.

step 0 에서 `GET /actuator/health`(인증 없이 · 상태만)와 정상 종료 설정(`server.shutdown: graceful` · `spring.lifecycle.timeout-per-shutdown-phase: 130s`)을 만들었다.

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 명령어 · 작업 방식
- `docs/ADR.md` — ADR-044(정상 종료) · ADR-069(서버 이미지 — 검증 문단에 수동으로 확인한 기동 · SIGTERM 결과가 있다) · ADR-072
- `docs/04-jobs-and-cost.md` §4.5 「같이 맞출 설정」
- `docs/07-testing.md` §1.0(로컬 서버 테스트 실행)
- `backend/Dockerfile` · `backend/.dockerignore` · `backend/build.gradle` · `compose.yaml`(로컬 — 서버 컨테이너에 넘기는 환경 변수의 예)
- `backend/src/main/resources/application.yml` — 필요한 환경 변수(`DB_URL` · `DB_USERNAME` · `DB_PASSWORD` · `GOOGLE_CLIENT_ID` · `GOOGLE_CLIENT_SECRET` …)
- `backend/src/test/java/com/mogum/support/PostgresTestConfiguration.java` — 테스트가 쓰는 Postgres 이미지 태그
- step 0 산출물: `backend/src/main/java/com/mogum/auth/SecurityConfiguration.java` · 헬스 테스트

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

### 1. 검사 테스트 — `backend/src/test/java/com/mogum/AppImageIT.java`

**이미 빌드된 이미지 하나**를 받아 실제 컨테이너로 띄우고 확인한다. 이미지 이름은 시스템 속성 `appImage` 로 받는다 — 없으면 테스트가 실패한다(조용히 건너뛰지 않는다).

- Testcontainers 로 네트워크 하나에 Postgres(`pgvector/pgvector:pg17` — 테스트와 같은 태그)와 앱 컨테이너를 띄운다. 앱에는 운영과 같은 방식의 환경 변수(`DB_URL` 등)와 **가짜** 구글 클라이언트 값(`build.gradle` 의 테스트 값과 같은 것)을 넘긴다.
- 확인할 것:
  1. 앱이 뜨고 `GET /actuator/health` 가 `200` · `status` `UP` 이다(Flyway 마이그레이션이 이미지 안에서 돈다는 확인을 겸한다).
  2. 컨테이너 안의 java 가 **root 가 아닌 사용자**로 돈다.
  3. 컨테이너를 정지(SIGTERM)하면 **정상 종료**한다 — 종료 코드 `143`(SIGTERM 을 받은 JVM 의 종료 코드 · ADR-069 검증에서 관찰), 진행 중인 작업이 없으니 곧바로 끝난다. 강제 종료(SIGKILL · 종료 코드 `137`)로 끝나면 실패다.
- 기다림에는 상한을 둔다(예: 기동 2분). 상한을 넘으면 테스트가 실패해야 한다 — 무한히 기다리지 않는다.

### 2. Gradle — 기본 `test` 에서 빼고 `imageTest` 로 돌린다

이미지 빌드는 수십 초~수 분이 걸려 매 step 의 `test` 에 넣지 않는다.

- 기본 `test` 작업은 `*ImageIT` 를 **실행하지 않는다.**
- 새 작업 `imageTest` 가 `*ImageIT` 만 실행한다.
  - `-PappImage=<이미지 이름>` 을 주면 그 이미지를 검사한다(CI 가 쓰는 방식 — 빌드한 이미지를 그대로 넘긴다).
  - 주지 않으면 먼저 `backend/` 에서 **`docker build`** 로 로컬 태그(예: `mogum-app:image-test`)를 만들고 그 이미지를 검사한다(로컬 · 리뷰어가 쓰는 방식).
- `docker build` 는 Docker CLI 로 한다. 이유: `backend/Dockerfile` 의 `RUN --mount=type=cache` 는 BuildKit 문법이고, Docker CLI 는 BuildKit 으로 빌드한다. Testcontainers 의 이미지 빌드 기능이 이 문법을 처리하는지는 확인하지 않았으므로 쓰지 않는다.
- `test` 작업이 테스트 JVM 에 넣는 가짜 구글 값과 같은 값을 `imageTest` 에도 쓴다(설정 값이 두 곳으로 갈라지지 않게 한 곳에서 정한다).

### 3. 문서

- `AGENTS.md` 「명령어」 — `./backend/gradlew -p backend imageTest` 한 줄을 더한다: 서버 이미지를 빌드해 기동 · 헬스 · 비루트 · SIGTERM 정상 종료를 확인한다(Docker 필요 · 기본 `test` 에는 없다). `-PappImage=` 로 이미 만든 이미지를 검사할 수 있다.
- `docs/07-testing.md` §1.0 — 위 명령을 한 줄로 덧붙인다.
- `docs/ADR.md` ADR-069 의 **검증** 문단 끝에 *"`imageTest` 가 같은 확인(기동 · 헬스 · 비루트 · SIGTERM 종료 코드 143)을 자동으로 한다(`2-deploy` step 1)"* 를 덧붙인다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test        # 기본 테스트 통과 — AppImageIT 는 실행되지 않는다
./backend/gradlew -p backend imageTest   # 이미지를 빌드해 AppImageIT 통과 (Docker 필요)
```

## 검증 절차

1. 위 AC 커맨드를 실행한다. `test` 결과에 `AppImageIT` 가 없고, `imageTest` 결과에만 있는지 확인한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가? 새 의존성이 있다면 테스트 범위(`testImplementation`)이고 Spring Boot BOM 이 버전을 관리하는가?
   - `AGENTS.md` 금지 목록 · 작업 방식을 위반하지 않았는가?
3. 결과에 따라 `phases/2-deploy/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 테스트 파일 · `imageTest` 사용법(`-PappImage`) · 기본 로컬 태그 · 두 AC 의 결과 · 이미지 빌드 · 검사에 걸린 시간을 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (Docker 를 쓸 수 없음 · 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `backend/Dockerfile` 을 고치지 마라. 이유: ADR-069 에서 수동으로 확인한 이미지다. 검사가 이미지의 결함을 찾으면 고치지 말고 `blocked` 로 사유를 적는다.
- 검사를 기본 `test` 작업에 넣지 마라. 이유: 모든 step 의 AC 가 이미지 빌드 시간만큼 느려진다.
- `appImage` 가 없을 때 검사를 건너뛰지(skip) 마라. 이유: CI 가 이미지 이름을 잘못 넘겨도 초록으로 끝나면 검사하지 않은 이미지가 운영에 나간다.
- 실제 구글 비밀 · `.env` 를 쓰지 마라. 이유: 검사는 가짜 값으로 충분하고 `.env` 는 읽지 않는다(`AGENTS.md`).
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
