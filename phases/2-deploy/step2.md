# Step 2: server-runtime

## 배경 — 이 phase(`2-deploy`)가 하는 일

지금의 앱을 운영에 올린다(`docs/ADR.md` ADR-072). 운영은 서울 리전 EC2 한 대(arm64 · 2GB · Amazon Linux 2023)다. 호스트에는 Docker 만 깔고 **Docker Compose 하나**로 Caddy(HTTPS 자동 발급) · 앱 · Postgres 를 띄운다.

배포는 이렇게 흐른다 — GitHub Actions 가 앱 이미지를 ECR 에 올리고, `deploy/` 디렉터리와 이미지 지문 파일을 묶은 **릴리스 묶음**을 S3 에 올린 뒤, SSM 으로 서버에 명령을 보낸다. 그 명령이 묶음을 `/opt/mogum/releases/{SHA}/` 에 풀고 그 안의 **`deploy/deploy.sh {SHA}`** 를 실행한다(묶음을 받고 푸는 명령은 step 5 의 워크플로가 만든다). 이 step 은 서버에서 도는 파일들 — 운영 Compose · Caddyfile · `deploy.sh` · 서버 env 파일의 견본 — 을 만든다.

step 0 에서 `GET /actuator/health`(인증 없이 · 상태만 · DB 포함 판정)와 정상 종료 설정(130초)을, step 1 에서 이미지 검사(`./backend/gradlew -p backend imageTest`)를 만들었다.

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 · 작업 방식
- `docs/ADR.md` — ADR-044 · ADR-061(무중단 배포는 공개 후) · ADR-069 · ADR-072
- `docs/04-jobs-and-cost.md` §3(배포 — 운영 구성과 배포 흐름 표) · §4.5(정상 종료 · 「같이 맞출 설정」 — 프로세스 관리자의 강제 종료 대기 150초 이상)
- `docs/05-api.md` §1.1 — 운영 환경 변수(`GOOGLE_CLIENT_ID` · `GOOGLE_CLIENT_SECRET` · `GOOGLE_REDIRECT_URI` · `APP_WEB_ORIGIN` · `APP_WEB_COOKIE_DOMAIN` · `APP_AUTH_ADMIN_EMAILS`) · 「aws 프로필」 언급(부하 테스트 프로필과 함께 켜지면 기동 거부 — `16` 에서 만든다)
- `compose.yaml`(로컬) · `.env.example` · `backend/Dockerfile` · `backend/src/main/resources/application.yml`
- step 1 산출물: `backend/build.gradle`(`test` · `imageTest` 작업)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

정한 이름 — 바꾸지 않는다: Compose 프로젝트 이름 `mogum` · 서버의 릴리스 디렉터리 `/opt/mogum/releases/{전체 40자 SHA}/` · 지금 릴리스를 가리키는 링크 `/opt/mogum/current` · 서버 env 파일 `/etc/mogum/mogum.env`(root 전용 `600` — 릴리스 밖에 있어 배포가 덮어쓰지 않는다) · 릴리스 묶음 안의 이미지 지문 파일 `app-image.ref`(묶음 루트, 한 줄: `<계정 ID>.dkr.ecr.ap-northeast-2.amazonaws.com/mogum-app@sha256:<64자 hex>`).

### 1. 운영 Compose — `deploy/compose.yaml`

- 서비스 셋: `caddy` · `backend` · `postgres`.
- `backend` — 이미지는 환경 변수 `APP_IMAGE`(위 지문 참조)로 받고, 없으면 Compose 가 실패하게 한다. `SPRING_PROFILES_ACTIVE=aws`(05 §1.1 의 운영 프로필 이름 — 지금은 프로필 전용 설정이 없다). `DB_URL` 은 Compose 네트워크의 `postgres` 를 가리킨다. 강제 종료 대기 **150초**(04 §4.5).
- `postgres` — `pgvector/pgvector:pg17`(로컬 · 테스트와 같은 태그). 데이터는 이름 있는 볼륨. **호스트 포트를 열지 않는다.** 준비 확인(healthcheck)이 통과한 뒤 `backend` 가 뜬다.
- `caddy` — 공식 `caddy` 이미지(메이저 태그). 호스트의 80 · 443(TCP)만 연다. 인증서 · 설정 저장 디렉터리는 이름 있는 볼륨에 둔다 — 배포 · 재시작마다 인증서를 다시 받으면 발급 기관의 횟수 제한에 걸린다.
- **각 서비스에는 필요한 변수만 넘긴다** — 구글 비밀은 `backend` 에만, DB 비밀번호는 `backend` · `postgres` 에만, API 도메인은 `caddy` 에만. 값은 `/etc/mogum/mogum.env` 에서 온다. Compose 파일에 비밀 값을 적지 않는다.
- **서비스별 메모리 상한** — 잠정값 `backend` 1024MiB · `postgres` 512MiB · `caddy` 128MiB(나머지 약 380MiB 는 OS · Docker · SSM 에이전트 몫, 스왑 없음). 이미지의 `-XX:MaxRAMPercentage=75` 가 컨테이너 상한을 기준으로 힙을 잡는다. 확정은 `16-capacity` 가 같은 파일로 재서 한다.
- 모든 서비스는 재부팅 · 비정상 종료 뒤 다시 뜬다(재시작 정책).
- 앱 포트는 호스트 밖에 열지 않는다. 배포 스크립트가 서버 안에서 헬스를 보려고 `127.0.0.1` 에만 묶는 것은 허용한다.
- **배포 때 정의가 바뀌지 않은 서비스(`postgres` · `caddy`)는 다시 만들어지지 않아야 한다.** 릴리스마다 디렉터리가 바뀌므로, 서비스 정의가 릴리스 경로에 기대면(예: 릴리스 안의 파일을 바인드 마운트) 매 배포마다 DB · 프록시가 재시작된다 — 그러지 않게 한다(방법은 재량 — 예: Caddyfile 을 고정 경로로 복사해 마운트).

### 2. `deploy/Caddyfile`

- 도메인은 환경 변수(예: `API_DOMAIN` = `api.{도메인}`)로 받는다. 그 도메인의 HTTPS 를 Caddy 가 자동으로 발급 · 갱신하고 모든 요청을 `backend:8080` 으로 넘긴다. 그 밖의 설정은 필요할 때 더한다.

### 3. 배포 스크립트 — `deploy/deploy.sh {SHA}`

서버에서 root 로 실행된다(SSM). 릴리스 묶음은 이미 `/opt/mogum/releases/{SHA}/` 에 풀려 있다. **원하는 수준 — 위에서 아래로 읽히는 짧은 bash 스크립트. 로그 파일 · 회전 · 백그라운드 수집기를 만들지 않는다 — 출력은 표준 출력(SSM 이 담는다)으로 충분하다.**

1. 인자가 40자 소문자 hex 인지 확인한다. 쓰는 외부 명령(`docker` · `docker compose` · `aws` · `curl` 등)이 있는지 먼저 확인하고, 없으면 무엇이 없는지 말하고 실패한다.
2. 릴리스의 `app-image.ref` 를 읽고 형식(위 「정한 이름」)을 확인한다.
3. ECR 에 로그인한다 — 인스턴스 역할 자격으로 `aws ecr get-login-password` 를 `docker login --password-stdin` 에 넘긴다. 토큰을 화면 · 인자에 드러내지 않는다.
4. 이미지를 지문으로 받는다(`docker pull`). 서버에서 이미지를 빌드하지 않는다.
5. `APP_IMAGE` 를 그 지문으로 두고 이 릴리스의 Compose 파일로 `up -d` 한다(프로젝트 이름 `mogum` · env 파일 `/etc/mogum/mogum.env`).
6. 서버 안에서 헬스를 본다 — `GET /actuator/health` 가 `200` 이 될 때까지 상한(예: 180초 — Flyway · 기동 포함) 안에서 기다린다. DNS · 인증서와 무관하게 판정하도록 공개 주소가 아니라 서버 안의 주소를 쓴다.
7. **성공** → `/opt/mogum/current` 를 이 릴리스로 바꾸고, 오래된 릴리스 디렉터리를 정리한다(지금 · 직전을 포함해 최근 3개만 남긴다). 쓰지 않는 이미지를 정리하되 **직전 릴리스의 이미지는 남긴다** — 되돌리기는 ECR 없이도 돼야 한다.
8. **실패** → 직전 릴리스(`current` 가 가리키던 것)가 있으면 그 릴리스의 Compose 파일 · 지문으로 다시 `up -d` 하고 헬스를 확인한 뒤, *"되돌렸다"* 를 알리고 **실패 코드로 끝낸다**(워크플로가 실패로 보이게). 직전 릴리스가 없으면(첫 배포) 그대로 실패 코드로 끝낸다.

공통 규칙:
- `set -euo pipefail`. `set -x` 를 쓰지 않는다.
- **비밀 · 환경 변수 · env 파일 내용 · 로그인 토큰을 출력하지 않는다.** 공개 저장소라 Actions 로그와 SSM 출력이 공개된다(ADR-072). 출력은 단계 이름과 결과만.
- Amazon Linux 2023 에 기본으로 있는 명령 또는 `deploy/README.md` 의 설치 절차에 적은 명령만 쓴다(`rg` · `jq` 처럼 개발 기기에만 있을 수 있는 명령에 기대지 않는다).
- Flyway 마이그레이션은 되돌리지 않는다 — 되돌린 앱이 새 스키마에서 뜨는지는 마이그레이션마다 다르다는 것을 스크립트 머리 주석에 적는다.

### 4. 서버 env 파일 견본 — `deploy/mogum.env.example`

`/etc/mogum/mogum.env` 에 둘 **이름만** 적은 견본(값은 비우거나 `{도메인}` 같은 자리표시). 이 step 의 Compose · Caddyfile 이 읽는 이름과 05 §1.1 의 운영 변수가 모두 있어야 한다 — `GOOGLE_REDIRECT_URI=https://api.{도메인}/auth/google/callback` · `APP_WEB_ORIGIN=https://{도메인}` · `APP_WEB_COOKIE_DOMAIN={도메인}` 처럼 형식이 정해진 값은 자리표시와 함께 적는다. 다음 step(백업)이 같은 파일에 이름을 더한다.

### 5. 불변식 테스트 — `backend/src/test/java/com/mogum/deploy/DeployFilesTest.java`

배포 파일은 Java 테스트로 지킨다(리뷰어는 `./backend/gradlew` 만 실행할 수 있다). 테스트는 `backend/` 를 작업 디렉터리로 돈다 — 파일은 `../deploy/…` 로 읽는다.

- **`deploy/` 를 Gradle `test` 작업의 입력으로 선언한다.** 안 하면 `deploy/` 파일만 바꿨을 때 `test` 가 UP-TO-DATE 로 건너뛰어 불변식이 다시 돌지 않는다(`2-deploy` 1차 시도의 교훈).
- 확인할 것(최소): Compose 의 세 서비스 · 프로젝트 이름 · `backend` 이미지가 `APP_IMAGE` 에서 옴 · 강제 종료 대기 ≥ 150초 · 세 서비스 메모리 상한이 있고 합이 1664MiB 이하 · `postgres` 에 호스트 포트 없음 · `backend` 포트가 없거나 `127.0.0.1` 에만 묶임 · `caddy` 가 80/443 만 · DB · 인증서 볼륨이 이름 있는 볼륨 · Compose 파일에 비밀 값이 없음 · `mogum.env.example` 이 Compose 가 쓰는 변수 이름과 05 §1.1 운영 변수를 모두 담고 값이 비어 있거나 자리표시임 · `deploy.sh` 가 `bash -n` 을 통과하고 `set -euo pipefail` 이 있으며 `set -x` 가 없음 · 실행 권한이 있음.
- YAML 은 파서로 읽는다(문자열 검색으로 서비스 구조를 판정하지 않는다). 파서가 새 의존성이면 `testImplementation` · Spring Boot BOM 관리 버전으로 더한다.

### 6. 문서

- `deploy/README.md`(새 파일 — 운영 절차서의 시작) — 「서버 구성」 절: 디렉터리 · 파일 위치(위 「정한 이름」) · 서비스 · 메모리 상한(잠정) · `deploy.sh` 가 하는 일과 되돌리기 규칙 · 서버에 필요한 명령 목록. 절차서 전체(처음 구축 순서 · 확인 목록)는 step 7 이 완성한다 — 이 step 은 이 절만 쓴다.
- `docs/04-jobs-and-cost.md` §3 운영 구성 표의 「서버」 행 — 메모리 상한 잠정값과 `deploy/README.md` 링크를 더한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # DeployFilesTest 포함 통과
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ADR.md` ADR-072 의 구성(Compose 하나 · Caddy · 같은 EC2 Postgres · 지문으로 받기 · 헬스 실패 시 되돌림)과 맞는가?
   - `AGENTS.md` 금지 목록 · 작업 방식을 위반하지 않았는가?
3. 결과에 따라 `phases/2-deploy/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 만든 파일 · env 변수 이름 목록 · 메모리 상한 · `deploy.sh` 의 단계와 헬스 대기 상한 · 테스트 수를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 서버에서 이미지를 빌드하지 마라(`build:` · `docker build`). 이유: 2GB 서버에서 앱 · Postgres 와 Gradle 빌드가 메모리를 다투고, CI 가 검사한 이미지와 달라진다(ADR-072).
- 이미지를 태그(`:latest` · `:{SHA}`)로 받지 마라 — 지문(`@sha256:…`)으로 받는다. 이유: CI 가 검사한 바로 그 이미지를 돌린다.
- 로컬 `compose.yaml`(저장소 루트)을 고치지 마라. 이유: 로컬 개발용이고 ADR-069 로 정했다. 운영 파일은 `deploy/compose.yaml` 로 따로 둔다.
- SSH 설정 · 22번 포트 · 키를 만들지 마라. 이유: 서버 접속은 SSM 이다(ADR-072).
- 무중단 배포(두 앱을 겹쳐 띄우기 · 트래픽 전환)를 만들지 마라. 이유: 공개 후에 넣는다(ADR-061).
- 백업 · 복원 · systemd 타이머를 이 step 에서 만들지 마라. 이유: step 3 범위다.
- 워크플로(`.github/workflows/`) · AWS 정책 파일(`deploy/aws/`)을 만들지 마라. 이유: step 4 · 5 범위다.
- 실제 도메인 · 계정 ID · 비밀을 파일에 적지 마라. 이유: 공개 저장소다 — 자리표시만 쓴다.
- `.env` 파일을 만들거나 읽지 마라. 이유: `AGENTS.md`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
