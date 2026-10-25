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
5. 성공하면 `current` 링크를 교체하고 릴리스를 현재 · 직전을 포함해 최근 **3개**로 정리한다. 현재 · 직전 이미지 ID를 보호하고 나머지 이미지는 강제 삭제 없이 정리한다. 정리 실패는 정상 기동한 앱을 되돌리지 않고 결과만 알린다.
6. 실패하면 기존 `current`의 Compose · 지문 · Caddyfile로 복귀하고 같은 헬스 확인을 한다. 복귀에는 `--pull never`를 사용하므로 ECR 없이 로컬 이미지로 실행한다. 복귀 성공도 배포 자체는 **실패 코드**로 끝낸다. 첫 배포 실패는 링크를 만들지 않고 실패로 끝낸다.

Flyway 마이그레이션은 되돌리지 않는다. 직전 앱이 새 스키마에서 기동할 수 있는지는 마이그레이션마다 확인해야 한다. 이 배포는 앱을 하나만 실행하므로 배포 중 중단이 있다(ADR-061). 단계 이름과 결과만 표준 출력으로 내보내며 비밀 · 환경 변수 · env 파일 내용 · 로그인 토큰을 출력하지 않는다. 별도의 배포 로그 파일은 만들지 않는다.

서버에 필요한 명령은 **Bash**, **Docker Engine**, **Docker Compose 플러그인**(`docker compose`), **AWS CLI v2**(`aws`), **curl**, 그리고 Amazon Linux 2023 기본 도구인 `cat` · `stat` · `install` · `readlink` · `ln` · `mv` · `sort` · `rm` · `sleep`(GNU coreutils), `find`(GNU findutils)다. Docker 데몬은 부팅 시 자동 시작하도록 활성화해야 한다. 설치와 초기화 순서는 step 7에서 다룬다. 스크립트는 AWS 키를 받지 않고 인스턴스 역할을 사용한다.

배포 파일 불변식과 명령 대체를 통한 성공/실패 경로는 저장소 루트에서 `./backend/gradlew -p backend test`로 검사한다. 실제 EC2 · ECR · 운영 도메인에서의 기동/복귀 확인은 아직 수행하지 않았다.
