# 운영 절차서

## 서버 구성

서울 리전(`ap-northeast-2`) EC2 한 대(arm64 · 2GB · Amazon Linux 2023)에서 Docker Compose 프로젝트 **`mogum`** 하나를 실행한다. 화면은 Vercel에 둔다. 서버 접속과 명령 실행은 SSM을 사용한다(ADR-072). 처음 구축 순서와 운영 확인 목록은 `2-deploy` step 7에서 완성한다.

| 위치 | 용도 |
|---|---|
| `/opt/mogum/releases/{전체 40자 소문자 SHA}/` | S3 릴리스 묶음을 푸는 디렉터리. `deploy/`와 `app-image.ref`를 포함한다 |
| `/opt/mogum/releases/{SHA}/app-image.ref` | 한 줄의 `<계정 ID>.dkr.ecr.ap-northeast-2.amazonaws.com/mogum-app@sha256:<64자 소문자 hex>`. 태그로 실행하지 않는다 |
| `/opt/mogum/current` | 마지막으로 헬스를 통과한 릴리스의 심볼릭 링크 |
| `/etc/mogum/mogum.env` | root 소유 · 권한 **600**인 서버 환경 변수 파일. 릴리스 밖에 두며 배포가 덮어쓰지 않는다. 이름과 형식은 [mogum.env.example](./mogum.env.example) 참고 |
| `/etc/mogum/caddy/Caddyfile` | 배포 시 해당 릴리스에서 복사한 Caddy 설정. 고정 디렉터리를 읽기 전용 마운트하고 Caddy에 reload한다 |

| 서비스 | 이미지 | 호스트 포트 | 메모리 상한(잠정) | 영속 볼륨 |
|---|---|---|---|---|
| `backend` | `APP_IMAGE` 지문 | `127.0.0.1:8080`만 | 1024MiB | 없음 |
| `postgres` | `pgvector/pgvector:pg17` | 없음 | 512MiB | `mogum_postgres_data` |
| `caddy` | 공식 `caddy:2` | 80 · 443(TCP) | 128MiB | `mogum_caddy_data`(인증서) · `mogum_caddy_config` |

합계는 1664MiB이고 나머지 약 380MiB는 OS · Docker · SSM 에이전트 몫이다. 스왑 없이 측정하며 `16-capacity`가 같은 [compose.yaml](./compose.yaml)로 재서 상한을 확정한다. 앱 힙은 이미지의 `-XX:MaxRAMPercentage=75`로 컨테이너 상한의 75%를 사용한다. `backend`는 Postgres 준비 확인 후 `aws` 프로필로 기동하며, 종료 대기는 **150초**로 Spring의 130초보다 길다. 세 서비스 모두 `restart: always`로 재부팅과 비정상 종료에 대응한다.

환경 변수는 Compose의 `--env-file /etc/mogum/mogum.env`로 읽고 서비스마다 필요한 이름만 전달한다. `API_DOMAIN`은 Caddy에만, Google 비밀은 앱에만, DB 비밀번호는 앱과 Postgres에만 전달한다. `APP_IMAGE`는 `deploy.sh`가 지문 파일을 검증한 뒤 지정한다. DB와 인증서는 이름 있는 볼륨에 보존하며, 고정된 프로젝트 이름과 Caddy 설정 경로 덕분에 릴리스 디렉터리가 바뀌어도 정의가 같은 Postgres와 Caddy는 재생성되지 않는다.

SSM은 릴리스 묶음을 푼 뒤 root로 해당 릴리스의 `deploy/deploy.sh {SHA}`를 실행한다. 스크립트는 다음 순서로 동작한다.

1. SHA · 필요한 명령 · 서버 env 파일 소유권/권한 · 이미지 지문을 검사한다.
2. 인스턴스 역할 자격으로 ECR에 로그인하고 지문으로 이미지를 받는다. 서버에서 빌드하지 않는다.
3. 고정 경로에 Caddyfile을 복사하고 해당 릴리스의 Compose로 `up -d --no-build`한다. Caddy 설정을 reload하며 첫 기동 준비는 최대 10회(1초 간격) 재확인한다.
4. DNS · HTTPS와 무관한 `http://127.0.0.1:8080/actuator/health`가 **200**이 될 때까지 최대 **180초** 기다린다.
5. 성공하면 `current` 링크를 교체한다. 이 릴리스의 `deploy/systemd/mogum-backup.service` · `mogum-backup.timer`를 `/etc/systemd/system/`과 비교해 다른 파일만 권한 644로 복사하고, 변경이 있을 때만 `systemctl daemon-reload`와 `systemctl enable --now mogum-backup.timer`를 실행한다. 같으면 복사와 systemctl 호출을 생략한다. 헬스 실패로 복귀하는 경로에서는 설치하지 않는다. 이어서 릴리스를 현재 · 직전을 포함해 최근 **3개**로 정리한다. 현재 · 직전 이미지 ID를 보호하고 나머지 이미지는 강제 삭제 없이 정리한다. 정리 실패는 정상 기동한 앱을 되돌리지 않고 결과만 알린다.
6. 실패하면 기존 `current`의 Compose · 지문 · Caddyfile로 복귀하고 같은 헬스 확인을 한다. 복귀에는 `--pull never`를 사용하므로 ECR 없이 로컬 이미지로 실행한다. 복귀 성공도 배포 자체는 **실패 코드**로 끝낸다. 첫 배포 실패는 링크를 만들지 않고 실패로 끝낸다.

Flyway 마이그레이션은 되돌리지 않는다. 직전 앱이 새 스키마에서 기동할 수 있는지는 마이그레이션마다 확인해야 한다. 이 배포는 앱을 하나만 실행하므로 배포 중 중단이 있다(ADR-061). 단계 이름과 결과만 표준 출력으로 내보내며 비밀 · 환경 변수 · env 파일 내용 · 로그인 토큰을 출력하지 않는다. 별도의 배포 로그 파일은 만들지 않는다.

서버에 필요한 명령은 **Bash**, **Docker Engine**, **Docker Compose 플러그인**(`docker compose`), **AWS CLI v2**(`aws`), **curl**, **systemd**(`systemctl`), 그리고 Amazon Linux 2023 기본 도구인 `cat` · `stat` · `install` · `readlink` · `ln` · `mv` · `sort` · `rm` · `sleep` · `id` · `mktemp` · `date` · `wc`(GNU coreutils), `cmp`(GNU diffutils), `find`(GNU findutils)다. Docker 데몬은 부팅 시 자동 시작하도록 활성화해야 한다. 설치와 초기화 순서는 step 7에서 다룬다. 스크립트는 AWS 키를 받지 않고 인스턴스 역할을 사용한다.

배포 파일 불변식과 명령 대체를 통한 성공/실패 경로는 저장소 루트에서 `./backend/gradlew -p backend test`로 검사한다. 실제 EC2 · ECR · 운영 도메인에서의 기동/복귀 확인은 아직 수행하지 않았다.

## GitHub Actions 배포

[deploy.yml](../.github/workflows/deploy.yml)의 `Deploy backend`는 `main` push 중 **`backend/**` · `deploy/**` · `.github/workflows/deploy.yml`** 중 하나가 바뀐 경우에 실행한다. 문서나 화면만 바뀌면 서버를 재시작하지 않는다. `workflow_dispatch`는 경로와 무관하게 실행하며 `main`만 허용한다. 단일 `mogum-backend-deploy` 동시 실행 그룹에서 진행 중인 배포를 취소하지 않는다. GitHub 동시 실행 그룹의 대기 실행은 새 실행으로 교체될 수 있으므로 모든 중간 커밋의 순차 배포를 보장하지는 않는다([동시 실행 규칙](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)). 화면은 별도 Vercel Git 연동으로 배포한다.

저장소 **Settings → Secrets and variables → Actions → Variables**에 다음 여섯 개를 등록한다. 식별자이며 Secrets에 넣지 않는다. `DEPLOY_ENABLED`가 정확히 `true`일 때만 작업이 실행된다. 처음 AWS를 준비하는 동안에는 미설정 또는 `false`로 두고, 서버 env · AWS · DNS 설정이 끝난 뒤 `true`로 켠다. 끄면 다음 실행부터 건너뛰며 진행 중인 배포는 취소하지 않는다.

| 이름 | 값 예 | 어디서 얻나 |
|---|---|---|
| `DEPLOY_ENABLED` | `true` / `false` | 운영자가 배포 활성화 여부를 정한다 |
| `AWS_REGION` | `ap-northeast-2` | 위 AWS 설정에서 정한 서울 리전 |
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::<계정 ID>:role/mogum-deploy` | IAM의 `mogum-deploy` 역할 상세 ARN |
| `DEPLOY_BUCKET` | `<전용 버킷 이름>` | S3 버킷 이름. `s3://` 없이 서버 `MOGUM_BUCKET`과 같은 값 |
| `DEPLOY_INSTANCE_ID` | `i-<인스턴스 ID>` | EC2 인스턴스 상세 ID. 배포 정책의 대상 한 대 |
| `API_ORIGIN` | `https://api.{도메인}` | DNS · Caddy에 설정한 공개 API 출처. 끝 `/` 없이 입력 |

활성화된 작업은 빌드나 AWS 호출에 앞서 필수 변수의 빈 값을 검사하며, 비었으면 **변수 이름만** 출력하고 실패한다. 브랜치 · SHA · 리전 · 식별자 형식도 먼저 검사한다. 자격은 [configure-aws-credentials@v5](https://github.com/aws-actions/configure-aws-credentials/tree/v5)의 OIDC 임시 자격만 사용하며 워크플로 권한은 `contents: read` · `id-token: write`다. GitHub `environment`는 지정하지 않는다. 지정하면 OIDC sub가 `environment:…`로 바뀌어 기존 `ref:refs/heads/main` 신뢰 조건과 맞지 않는다.

일반 push와 `sha` 없는 수동 실행은 다음 순서다.

1. 서버와 같은 **`ubuntu-24.04-arm`** 작업에서 ECR `mogum-app:{전체 40자 소문자 SHA}`를 조회한다. 있으면 이전 실행이 검사 후 올린 이미지이므로 빌드 · 이미지 검사 · push를 건너뛰고 지문을 재사용한다. 태그가 없다는 응답만 새 빌드로 진행하며 권한/통신 오류는 실패한다. 덮어쓰기 금지 태그를 다시 push하지 않는다.
2. 새 이미지가 필요하면 [checkout@v5](https://github.com/actions/checkout/tree/v5) · [setup-java@v5](https://github.com/actions/setup-java/tree/v5)(Temurin 21)를 사용해 `./backend/gradlew -p backend build`로 테스트와 빌드를 수행한다. Docker로 `backend/` 이미지를 **한 번** 빌드한 뒤 `./backend/gradlew -p backend imageTest -PappImage=<그 이미지>`로 검사한다. Testcontainers는 러너의 Docker를 사용한다.
3. 검사한 로컬 이미지를 ECR의 전체 SHA 태그로 바꿔 올리고 `DescribeImages`로 지문을 조회한다. 다시 빌드하지 않는다.
4. 해당 커밋의 `deploy/`와 지문 한 줄인 `app-image.ref`를 묶어 `s3://{DEPLOY_BUCKET}/releases/{SHA}.tar.gz`에 업로드한다. 같은 SHA 재실행은 동일 커밋의 배포 파일과 기존 지문으로 묶음을 다시 올릴 수 있어 이미지 push 후 업로드가 실패한 경우에도 재개할 수 있다.
5. SSM `AWS-RunShellScript`로 지정한 인스턴스 한 대에서 root로 묶음을 받는다. `/opt/mogum/releases/` 아래 임시 디렉터리에서 풀고 지문을 확인한 뒤 `/opt/mogum/releases/{SHA}/`로 옮겨 `bash deploy/deploy.sh {SHA}`를 실행한다. 같은 SHA의 디렉터리가 이미 있으면 지문을 비교하고 그대로 사용해 활성 디렉터리를 덮어쓰지 않는다. 임시 파일은 정리한다.
6. SSM 명령 ID를 출력하고 최대 **20분** 동안 결과를 조회한다(명령 전달 제한 60초 · 서버 실행 제한 1080초). 최초 `InvocationDoesNotExist`는 결과 반영 지연으로 재조회한다. 성공 상태와 종료 코드 0을 모두 확인하고 표준 출력 · 오류를 Actions 로그에 표시한다. 이어 공개 `{API_ORIGIN}/actuator/health`의 HTTPS 응답이 **200**인지 최대 **60초** 확인한다.

### 수동 롤백 · 실패 확인

Actions → **Deploy backend** → **Run workflow**에서 브랜치 `main`을 선택하고 `sha`에 이전 릴리스의 **전체 40자 소문자 SHA**를 넣는다. 비워 두면 현재 커밋의 일반 빌드/배포 경로다. SHA가 있으면 checkout · Java 설정 · 빌드 · 검사 · 업로드 없이, 해당 SHA의 ECR 이미지와 S3 기존 묶음을 먼저 조회하고 묶음의 지문까지 일치하는지 확인한다. 이미지/묶음이 없거나 읽을 수 없으면 어느 쪽인지 출력하고 SSM을 보내지 않는다. 보관 범위는 **S3 현재 릴리스 30일과 ECR 최근 이미지 30개 중 먼저 끝나는 쪽**이다([상세](#보관과-롤백-범위)). `Flyway` 마이그레이션은 되돌리지 않으므로 이전 앱과 현재 스키마의 호환성을 먼저 확인한다.

실패하면 워크플로의 **Deploy through SSM and print the command result** 단계에서 명령 ID · 상태 · stdout · stderr를 본다. `deploy.sh`가 로컬 헬스 실패로 직전 앱에 복귀했어도 워크플로는 실패한다. 결과 조회 상한을 넘기거나 조회 자체가 실패하면 로그의 명령 ID로 Systems Manager Run Command 상태를 확인한 뒤 재배포한다. 공개 헬스만 실패하면 DNS · HTTPS · Caddy를 확인한다. 이 경우 로컬 헬스를 통과한 서버의 `current`는 유지된다. 로그에 비밀/env 파일을 추가 출력하거나 `set -x`를 켜지 않는다.

### 첫 배포 때 확인

불변식과 명령 대체 테스트는 `WorkflowFilesTest`로 실행한다. 액션의 위 메이저 태그와 입력은 공식 저장소에서 확인했다. [GitHub 러너 표](https://docs.github.com/en/actions/reference/runners/github-hosted-runners#standard-github-hosted-runners-for-public-repositories)에서 `ubuntu-24.04-arm`의 공개 저장소 무료 제공을 확인했고, [arm64 이미지 도구 목록](https://github.com/actions/partner-runner-images/blob/main/images/arm-ubuntu-24-image.md)에서 Docker · AWS CLI · jq · curl을 확인했다. 스크립트는 Bash · GNU 기본 도구 · tar · grep도 사용하며 `rg`에 의존하지 않는다. SSM 옵션/결과 필드는 [send-command](https://docs.aws.amazon.com/cli/latest/reference/ssm/send-command.html) · [get-command-invocation](https://docs.aws.amazon.com/cli/latest/reference/ssm/get-command-invocation.html) 공식 문서와 대조했다. 실제 GitHub Actions · AWS 실행은 아직 수행하지 않았다.

- 조직의 Actions 정책이 이 러너와 공식 액션 세 개를 허용하는지, 실제 러너의 arm64 Docker에서 전체 테스트와 `imageTest`가 성공하는지 확인한다.
- 변수를 등록한 `main` 실행에서 OIDC 수임 → ECR → S3 → SSM → 외부 HTTPS 200을 확인한다. 같은 SHA 재실행은 빌드/검사/push를 건너뛰어야 한다.
- 보관된 이전 SHA로 수동 롤백을 한 번 수행하고, SSM 실패 시 출력/실패 코드와 서버의 자동 복귀를 확인한다. 공개 로그에 비밀이 없어야 한다.

## Vercel(화면)

프로젝트는 **2026-10-25에 Hobby 팀에서 이미 만들었다.** 다시 만들 때도 Vercel의 Git 가져오기에서 이 GitHub 저장소 **`f-lab-edu/Mogum`**을 연결하고 다음 값을 사용한다. 운영 브랜치는 **`main`**이며 병합마다 Vercel Git 연동이 화면을 빌드 · 배포한다(ADR-060 · ADR-072).

| 프로젝트 설정 | 값 |
|---|---|
| Root Directory | `frontend` |
| Framework Preset | `Vite` |
| Build Command | 덮어쓰기를 켜고 **`npm run build`** — `tsc --noEmit` 타입 검사 후 Vite 빌드 |
| Output Directory | `dist` — Vite 감지 기본값 유지 |
| Production Branch | `main` |

환경 변수 **`VITE_API_BASE_URL=https://api.{도메인}`**은 **Production에만** 등록하고 운영 배포를 다시 빌드한다. 브라우저는 이 출처의 `/api/*`와 `/auth/*`로 직접 요청한다. PR 미리보기(`*.vercel.app`)는 API와 다른 사이트라 세션 쿠키가 실리지 않으므로 로그인 확인에 사용하지 않는다([05 §1.1](../docs/05-api.md#11-인증--구글-oauth--쿠키-세션db-저장)).

프로젝트의 Domains에 **`{도메인}`**을 연결한다. Vercel이 안내하는 DNS 레코드 종류 · 이름 · 값을 Cloudflare에 그대로 등록하고 **프록시를 끈다(DNS only)**. Vercel이 인증서를 직접 받도록 앞에 프록시를 두지 않는다.

[frontend/vercel.json](../frontend/vercel.json)은 전체 경로를 `/index.html`로 보내는 rewrite 하나만 둔다. Vercel은 정적 파일을 먼저 찾으므로 `index.html` · `assets/…` 등 존재하는 빌드 파일은 그대로 제공하고, 없는 주소만 주소창 경로를 유지한 채 React Router로 처리한다([Vercel rewrite 우선순위](https://vercel.com/docs/project-configuration/vercel-json#rewrites) · [Vite SPA 설정](https://vercel.com/docs/frameworks/frontend/vite#using-vite-to-make-spas)). 빌드 명령 · 출력 디렉터리는 이 파일에 넣지 않는다. API 프록시 · redirect · 함수 · SSR 없이 정적 SPA로 배포한다.

### 요금제 · 첫 배포 때 확인

**Hobby에서 조직 소유 저장소의 Git 연동은 확인했다** — 2026-10-25에 Hobby 팀으로 `f-lab-edu/Mogum`을 가져와 `main` 빌드 · 배포에 성공했다. **Hobby의 비상업 조건은 아직 확인하지 않았다.** 조직 저장소 연동 성공과 약관 적합성 확인은 별개다.

- [01 §10 공개 필수 목록](../docs/01-overview.md#10-공개-필수-목록)에 따라 Hobby 비상업 조건에 이 서비스가 맞는지 확인한다. 조건에 막히면 화면 배포 방식은 사용자가 다시 정한다.
- 운영 도메인의 DNS가 Vercel 안내값이고 Cloudflare가 DNS only인지, HTTPS 인증서가 유효한지 확인한다.
- Production 환경 변수와 `main` 운영 브랜치 설정을 확인하고 운영 도메인에서 로그인 · CSRF를 확인한다. 미리보기에서는 확인하지 않는다.
- `/login`과 `/curriculums/1` 같은 화면 주소를 직접 열고 새로고침해 Vercel 404 없이 SPA가 뜨는지, 빌드된 `assets/…` 요청이 HTML 대신 원래 정적 파일을 반환하는지 확인한다. 현재 미구현 화면은 앱의 기존 경로 처리에 따른다.

## 백업 · 복원

`mogum-backup.timer`가 매일 **한국 시각 04:00**에 `mogum-backup.service`(root · oneshot)를 실행한다. 일정은 서버 시간대와 무관한 `OnCalendar=*-*-* 19:00:00 UTC`이며 `Persistent=true`로 꺼져 있던 동안 놓친 실행은 켤 때 한 번 보충한다([systemd 시간 형식](https://freedesktop.org/software/systemd/man/latest/systemd.time.html) · [Persistent 규칙](https://www.freedesktop.org/software/systemd/man/252/systemd.timer.html)). 서비스는 `/etc/mogum/mogum.env`에서 환경을 받아 `/opt/mogum/current/deploy/backup.sh`를 실행한다. `MOGUM_BUCKET`에 백업과 릴리스가 공유하는 버킷 이름을 넣는다. 자격은 EC2 인스턴스 역할이며 키 파일은 두지 않는다.

백업은 Compose 프로젝트 `mogum` · 서비스 `postgres` 라벨로 실행 중 컨테이너를 찾는다. Compose 파일을 읽지 않으므로 `APP_IMAGE`가 없어도 실행한다. 재부팅 직후에는 5초 간격으로 최대 120초 준비를 기다린다. 컨테이너 안의 `pg_dump -Fc`로 운영 DB `mogum` 전체(스키마 · 데이터 · 시퀀스 · 확장)를 custom 형식으로 받아, 덤프가 성공하고 파일이 비어 있지 않을 때만 업로드한다. 호스트 임시 파일은 root 전용(`umask 077` · 600)이며 성공/실패 시 trap으로 지운다. 강제 전원 종료/SIGKILL은 trap을 실행할 수 없으므로 서버 재기동 후 남은 임시 파일 유무를 확인한다. 출력은 객체 경로와 바이트 크기 한 줄이다. 호스트에 PostgreSQL 도구를 설치하지 않는다.

객체 형식은 **`s3://${MOGUM_BUCKET}/backups/YYYYMMDDTHHMMSS+0900.dump`**(예: `backups/20261025T040000+0900.dump`)다. 키의 시각은 `TZ=Asia/Seoul`로 만든다. 배포 묶음은 같은 버킷의 `releases/`를 쓴다. 버전 관리를 켜고 **현재 버전 6일 + 이전 버전 1일, 합쳐 총 7일 상한**으로 보관한다(ADR-072). [AWS 설정](#aws-설정)의 수명주기 규칙과 IAM 정책 견본을 적용한다. 백업/연습 스크립트에는 S3 객체나 버전을 지우는 명령이 없으며 서버 역할에도 삭제 권한을 주지 않는다. 이 설정은 버킷에 적용한 뒤 첫 배포 때 확인해야 한다.

### 복원 연습

SSM에서 아래 명령을 실행한다. `systemd-run`은 서비스와 같은 서버 env 파일을 읽고 그 내용을 출력하지 않는다. `latest`는 `backups/`의 `.dump` 중 S3 `LastModified`가 가장 최근인 현재 객체를 고른다. [AWS CLI의 JSON 출력](https://docs.aws.amazon.com/cli/latest/userguide/cli-usage-output-format.html)을 사용해 전체 페이지를 모은 뒤 정렬한다. 특정 객체를 연습하려면 마지막 `latest`를 위 형식의 객체 키로 바꾼다. 동시에 여러 복원 연습을 실행하지 않는다.

```bash
sudo systemd-run --unit=mogum-restore-check --wait --pipe --collect \
  --property=Type=oneshot --property=EnvironmentFile=/etc/mogum/mogum.env \
  /opt/mogum/current/deploy/restore.sh latest
```

`restore.sh`는 백업을 root 전용 임시 파일로 받은 다음, 같은 Postgres 컨테이너의 **`mogum_restore_check`만** 지우고 다시 만들어 복원한다. 모든 사용자 스키마의 테이블 이름과 정확한 행 수만 출력한다. 운영 DB 이름을 받는 옵션은 없다. 성공 · 실패 시 연습 DB와 임시 파일을 정리하고, DB 정리가 실패해도 파일을 지우며 실패 코드와 메시지를 남긴다. 이때는 원인을 해결한 후 연습 DB가 남았는지 확인한다. 운영 데이터는 변경하지 않지만 디스크/CPU를 공유하므로 한가할 때 한 번 실행한다.

### 실제 재해 복구 — 사람이 수행

아래는 **운영 DB를 지우고 복원하는 명령**이다. 먼저 복구 시점 · 사용할 객체 · 그 이후의 탈퇴/삭제 목록을 확인한다. 연습 스크립트가 성공한 백업을 선택하며 복구 전에 생긴 최신 데이터는 잃는다(일일 백업 사이 최대 하루). 새 서버라면 같은 Postgres 이미지/사용자와 볼륨을 먼저 준비한다. 현재 릴리스는 선택한 백업 스키마에서 시작할 수 있어야 한다.

SSM의 root Bash(`sudo -i` 후 `bash`)에서 실행한다. 버킷/키 자리표시를 실제 선택값으로 바꾸되 저장소에 기록하지 않는다. 이 블록은 중간 명령이 실패하면 멈춘다. 실패 시 앱/타이머를 다시 켜지 말고 원인을 해결한다.

```bash
set -euo pipefail
umask 077
export MOGUM_BUCKET='버킷 이름'
key='backups/YYYYMMDDTHHMMSS+0900.dump'
export APP_IMAGE="$(cat /opt/mogum/current/app-image.ref)"
compose() {
  docker compose --project-name mogum --env-file /etc/mogum/mogum.env \
    -f /opt/mogum/current/deploy/compose.yaml "$@"
}
systemctl stop mogum-backup.timer mogum-backup.service
compose stop caddy backend
postgres=$(docker ps --filter label=com.docker.compose.project=mogum \
  --filter label=com.docker.compose.service=postgres --format '{{.ID}}')
[[ -n $postgres && $postgres != *$'\n'* ]]
dump=$(mktemp)
trap 'rm -f -- "$dump"' EXIT
aws s3 cp "s3://${MOGUM_BUCKET}/${key}" "$dump" --only-show-errors
[[ -s $dump ]]
docker exec -i "$postgres" pg_restore --list < "$dump" >/dev/null
docker exec "$postgres" sh -c 'dropdb -U "$POSTGRES_USER" --if-exists --force mogum'
docker exec "$postgres" sh -c 'createdb -U "$POSTGRES_USER" --template=template0 mogum'
docker exec -i "$postgres" sh -c \
  'pg_restore --exit-on-error --no-owner --no-privileges -U "$POSTGRES_USER" -d mogum' < "$dump"
rm -f -- "$dump"
trap - EXIT
```

**서비스 재개 전 탈퇴 · 삭제를 다시 적용한다.** 백업에 탈퇴/삭제 데이터가 최대 7일 남을 수 있다. [02 §7의 탈퇴 범위](../docs/02-domain-data.md#7-계정--정원--탈퇴)와 §6.2의 커리큘럼 완전 삭제 범위를 기준으로, 백업 시점부터 장애까지 접수한 탈퇴 · 삭제를 확인하고 같은 트랜잭션/잠금/원장 연결 해제 규칙으로 재적용한다. 자동 재적용 기능은 없다. 재적용을 확인하기 전에는 Caddy를 켜지 않는다. 복원으로 되살아난 로그인 세션도 모두 무효화한다.

```bash
docker exec "$postgres" sh -c \
  'psql -X --set=ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d mogum -c "DELETE FROM spring_session"'
# 위 탈퇴/삭제 재적용을 완료한 뒤 실행한다.
compose start backend
curl --fail --silent http://127.0.0.1:8080/actuator/health
# 아직 기동 중이면 200 UP이 될 때까지 기다리고 실패 원인을 확인한다.
compose start caddy
systemctl start mogum-backup.timer
```

### 백업 실패 확인 · 첫 배포 때 확인

현재는 운영자가 systemd 상태와 저널을 확인한다. 별도 로그 파일/회전은 없으며 슬랙 알림은 `10-cost-control`에서 연결한다.

```bash
sudo systemctl status mogum-backup.service mogum-backup.timer
sudo journalctl -u mogum-backup.service --since '2 days ago' --no-pager
sudo systemctl list-timers mogum-backup.timer --all
```

첫 배포 때 다음을 직접 확인한다. 실제 AWS 백업/복원과 Amazon Linux 2023의 systemd 일정 검증은 아직 수행하지 않았다.

- `systemd-analyze calendar '*-*-* 19:00:00 UTC'`가 매일 한국 04:00에 해당하는 다음 실행을 표시하는지 확인한다. 서버 시간대가 UTC여도 동일해야 한다.
- `systemctl is-enabled mogum-backup.timer`와 `list-timers`로 설치/활성화/다음 실행을 확인한다. `Persistent=true`의 재부팅 후 보충 실행도 확인한다.
- `sudo systemctl start mogum-backup.service` 후 저널의 성공 객체 키/크기를 확인하고, 버킷의 버전 관리 및 [총 7일 수명주기](#보관과-롤백-범위)가 적용됐는지 확인한다.
- 위 `restore.sh latest`를 한 번 실행해 테이블별 행 수와 종료 코드를 확인하고 `mogum_restore_check`가 제거됐는지 확인한다. 운영 DB에 데이터 변경이 없어야 한다.

## AWS 설정

`2-deploy` step 4의 산출물은 아래 **JSON 견본**과 불변식 테스트다. 실제 AWS API 호출이나 적용은 수행하지 않았다. 운영자가 기존 관리자 세션의 임시 자격으로 설정하며, 배포 역할과 인스턴스 역할에 자원 생성/설정 권한을 추가하지 않는다. 처음 구축의 전체 순서는 step 7에서 정리한다. 리전은 **`ap-northeast-2`**로 고정한다.

### 견본과 자리표시

| 파일 | 적용 위치와 용도 |
|---|---|
| [github-oidc-trust.json](./aws/github-oidc-trust.json) | `mogum-deploy` 역할의 신뢰 정책. 계정의 GitHub OIDC 공급자에서 STS audience와 저장소 `main` sub가 정확히 일치할 때만 수임 |
| [deploy-policy.json](./aws/deploy-policy.json) | `mogum-deploy`의 인라인 권한 정책 `mogum-deploy-access`. `mogum-app` push/존재·지문 조회, `releases/*` 읽기/쓰기, 운영 인스턴스 한 대의 지정 SSM 문서 실행과 결과 조회 |
| [ec2-trust.json](./aws/ec2-trust.json) | `mogum-instance` 역할의 신뢰 정책. EC2 서비스만 수임 |
| [instance-policy.json](./aws/instance-policy.json) | `mogum-instance`의 인라인 권한 정책 `mogum-instance-access`. ECR pull, `releases/*` 읽기, `backups/*` 읽기/쓰기, `backups/` 목록 조회 |
| [ecr-lifecycle.json](./aws/ecr-lifecycle.json) | ECR `mogum-app` 수명주기. 태그 유무와 무관하게 최근 이미지 30개 보존 |
| [s3-lifecycle.json](./aws/s3-lifecycle.json) | 같은 S3 버킷의 `backups/`와 `releases/`에 적용하는 규칙 두 개 |

견본 여섯 개를 **저장소 밖 로컬 디렉터리**에 복사한 뒤 편집기의 전체 바꾸기로 다음 문자열을 치환한다. 실제 값을 넣은 사본은 커밋하지 않는다. 치환 뒤 JSON 파싱과 미치환 자리표시가 없는지 확인하고 콘솔에 붙여 넣거나 아래 CLI의 파일 입력으로 사용한다.

| 자리표시 | 치환할 값 |
|---|---|
| `ACCOUNT_ID` | 자원을 만드는 AWS 계정의 12자리 ID(모든 ARN에서 동일) |
| `BUCKET_NAME` | 서울 리전에 만들 전용 S3 버킷의 전역 고유 이름(`s3://` 없이). 서버 `MOGUM_BUCKET`에도 동일한 값 |
| `INSTANCE_ID` | 운영 EC2 한 대의 `i-`로 시작하는 인스턴스 ID |

리전 · 저장소 `mogum-app` · GitHub 저장소명 · OIDC 조건은 바꾸지 않는다. AWS 소유 SSM 문서 ARN의 계정 칸은 **비어 있는 것이 맞다**. `ACCOUNT_ID`를 여기에 추가하지 않는다.

### 만들 자원과 연결

콘솔에서는 다음 자원과 설정을 만든다. 기존 자원이 있으면 같은 설정인지 확인한다.

1. **IAM OIDC 공급자** — URL `https://token.actions.githubusercontent.com`, audience `sts.amazonaws.com`. 배포 역할 신뢰 정책의 공급자 ARN은 이 계정의 공급자를 가리킨다.
2. **배포 역할 `mogum-deploy`** — `github-oidc-trust.json`과 `deploy-policy.json`을 각각 신뢰/인라인 정책에 적용한다. GitHub Actions가 이 역할 ARN으로 OIDC 임시 자격을 받는다. `aud`와 `sub`의 연산자는 `StringEquals`, sub는 **`repo:f-lab-edu/Mogum:ref:refs/heads/main`** 그대로다. `main` push와 `main`에서의 `workflow_dispatch`만 사용하며 워크플로에 GitHub `environment`를 지정하지 않는다(environment를 쓰면 sub가 바뀐다).
3. **인스턴스 역할 `mogum-instance`** — `ec2-trust.json`과 `instance-policy.json`을 적용하고, AWS 관리형 정책 **`arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore`**를 추가로 연결한다. 관리형 정책은 파일로 복제하지 않는다. 인스턴스 프로파일 `mogum-instance`에 이 역할을 넣어 EC2에 연결한다(EC2용 역할을 콘솔에서 만들면 프로파일도 생성된다). 다른 권한 정책을 추가하지 않는다.
4. **ECR 비공개 저장소 `mogum-app`** — 태그 변경 불가 **`IMMUTABLE`**, 예외 없음. 이미지는 전체 40자 소문자 커밋 SHA 태그로 올리고 실행은 지문을 쓴다. `ecr-lifecycle.json`을 수명주기에 적용한다.
5. **S3 범용 버킷 `BUCKET_NAME`** — 전용 버킷 하나에 **퍼블릭 액세스 차단 네 항목 모두 활성화**, 객체 소유권은 **Bucket owner enforced(ACL 비활성화)**, 기본 암호화는 **SSE-S3(AES-256)**, **버전 관리 Enabled**. `s3-lifecycle.json` 전체를 적용한다. KMS 키는 사용하지 않으므로 두 역할에 KMS 권한이 필요하지 않다.
6. **운영 EC2 한 대** — 서울 · arm64 · `t4g.small` · Amazon Linux 2023, 인스턴스 프로파일 `mogum-instance`. SSM Agent가 실행되어 Systems Manager의 관리형 노드에 Online으로 보여야 한다. 보안 그룹은 웹 TCP 80/443만 열고 SSH와 Postgres는 열지 않는다. ECR/S3/SSM으로 나가는 HTTPS 통신이 가능해야 한다. 생성 후 나온 ID로 정책 사본의 `INSTANCE_ID`를 치환한다.

역할에 붙인 두 권한 정책에는 이미지/객체/버전 삭제나 저장소·버킷 정책/수명주기/버전 관리 변경이 없다. 배포 역할은 `backups/`에 접근하지 못하고, 서버 역할은 이미지를 올리거나 `releases/`에 쓰지 못한다. ECR 인증 `GetAuthorizationToken`과 SSM 결과 `GetCommandInvocation`만 자원 `*`를 쓴다. [SSM 권한 표](https://docs.aws.amazon.com/service-authorization/latest/reference/list_ssm.html)에서 결과 조회는 자원별 제한을 지원하지 않는다. 명령 전송은 AWS 소유 `AWS-RunShellScript` 문서와 운영 EC2 ARN 두 개로 제한한다.

`aws s3 cp`의 multipart 업로드 시작/조각 전송/완료는 `s3:PutObject`를 사용하며, 실패한 미완료 업로드를 취소하는 `s3:AbortMultipartUpload`도 쓰기 접두사에만 허용한다. 완성된 객체/버전 삭제 권한은 아니다([S3 multipart 권한](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html)). `restore.sh latest`의 `ListObjectsV2`는 버킷 ARN과 `StringEquals: s3:prefix=backups/`로 제한하며 전체 버킷/버전 목록 권한은 주지 않는다. SSM 에이전트 통신 권한은 위 AWS 관리형 정책이 담당한다.

### AWS CLI v2 적용 예

아래 명령은 **운영자가 적용할 예시이며 이 step에서 실행하지 않았다**. 저장소 루트에서 실행하되 `BUCKET_NAME`과 `file://` 뒤 경로를 치환한 사본의 경로로 바꾼다. 수명주기 적용은 기존 구성을 교체하므로 전용 저장소/버킷인지 먼저 확인한다. 명령 형식은 [ECR 생성](https://docs.aws.amazon.com/cli/latest/reference/ecr/create-repository.html) · [ECR 수명주기 적용](https://docs.aws.amazon.com/cli/latest/reference/ecr/put-lifecycle-policy.html) · [S3 수명주기 적용](https://docs.aws.amazon.com/cli/latest/reference/s3api/put-bucket-lifecycle-configuration.html)의 공식 CLI v2 문서와 대조했다.

```bash
aws ecr create-repository --region ap-northeast-2 \
  --repository-name mogum-app --image-tag-mutability IMMUTABLE
aws ecr put-lifecycle-policy --region ap-northeast-2 \
  --repository-name mogum-app \
  --lifecycle-policy-text file:///LOCAL/RENDERED/ecr-lifecycle.json
aws s3api put-bucket-lifecycle-configuration --region ap-northeast-2 \
  --bucket BUCKET_NAME \
  --lifecycle-configuration file:///LOCAL/RENDERED/s3-lifecycle.json
```

### 보관과 롤백 범위

백업의 설정상 보관은 **현재 6일 + 이전 1일 = 총 7일**이며 각각을 7일로 바꾸지 않는다. 현재 객체 만료 시 삭제 표시가 생기고 이전 버전이 된 덤프는 1일 뒤 영구 삭제 대상이 된다. `releases/`는 현재 30일 · 이전 1일이다. 두 접두사 모두 미완료 multipart 업로드는 시작 1일 뒤 정리한다. 이전 버전 개수 보존 조건을 추가하지 않는다.

삭제 표시는 `Expiration.Days`의 만료 동작으로 이전 버전이 모두 없어진 뒤 자동 정리된다([S3 삭제 표시 정리](https://docs.aws.amazon.com/AmazonS3/latest/userguide/ManagingDelMarkers.html)). `Expiration`에 `Days`와 `ExpiredObjectDeleteMarker`를 함께 넣을 수 없으므로 견본에는 `Days`만 사용한다([S3 수명주기 예제](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-configuration-examples.html#lifecycle-config-conceptual-ex6)). 따라서 규칙 두 개로 만료 · 이전 버전 삭제 · 삭제 표시 정리 · 미완료 업로드 정리를 모두 수행한다.

**S3 수명주기 삭제는 비동기다.** 일 단위 만료 계산과 처리 지연 때문에 정확히 업로드 168시간 안에 물리 삭제된다는 보장은 아니다. 총 7일은 설정한 현재/이전 일수의 합이며, 실제 삭제는 늦어질 수 있다([S3 만료 동작](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-expire-general-considerations.html)).

원격 수동 재배포 범위는 **ECR 최근 이미지 30개와 S3 릴리스 현재 객체 30일 중 먼저 끝나는 쪽**이다. ECR은 일수가 아니라 이미지 수 기준이다. S3 이전 버전을 읽는 권한은 주지 않는다. 서버의 자동 실패 복귀는 앞 절처럼 로컬 직전 이미지와 릴리스로 수행하며, 로컬 릴리스는 현재/직전 포함 최근 3개다. 어떤 복귀도 Flyway를 되돌리지 않는다.

### 첫 배포 때 확인

JSON 파싱과 권한 범위는 `AwsFilesTest`로 검증했으며 실제 IAM 평가 · AWS 자원 적용은 검증하지 않았다. 새 CLI 예제의 명령/옵션 형식은 위 공식 문서로 확인했지만 실제 환경의 실행은 다음 목록에 남긴다.

- 치환한 신뢰/권한 정책을 콘솔이 받아들이는지, OIDC 공급자 audience와 역할의 신뢰 조건이 일치하는지 확인한다. `main`의 push/수동 실행은 수임되고 다른 브랜치/PR/포크는 거절되어야 한다. 워크플로에는 `environment`가 없어야 한다(step 5).
- `mogum-deploy`와 `mogum-instance`에 이 절에서 정한 정책만 연결됐는지 확인한다. 인스턴스 프로파일과 `AmazonSSMManagedInstanceCore` 연결, SSM Agent Online을 확인한다.
- 위 CLI v2 세 명령의 실제 적용 성공과 ECR `IMMUTABLE`/최근 30개, S3 공개 차단/기본 암호화/버전 관리/접두사별 규칙 두 개를 콘솔에서 확인한다.
- 배포 역할의 이미지 push·지문 조회·릴리스 업로드/존재 확인·지정 인스턴스 SSM 전송/결과 조회를 확인한다. 다른 인스턴스/문서 전송과 `backups/` 접근은 거절되어야 한다.
- 인스턴스 역할로 `deploy.sh`의 로그인/pull, 릴리스 묶음 다운로드, `backup.sh` 업로드, `restore.sh latest`의 목록/다운로드를 확인한다. multipart 업로드도 가능해야 한다. 두 역할의 삭제/설정 변경 권한 부재는 IAM 정책 시뮬레이터로 확인한다.
- 시간이 지난 표본의 현재 만료 → 이전 버전 영구 삭제 → 만료된 삭제 표시 정리와 미완료 multipart 정리를 확인한다. 실제 삭제 시각과 지연을 기록한다.
