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

## 백업 · 복원

`mogum-backup.timer`가 매일 **한국 시각 04:00**에 `mogum-backup.service`(root · oneshot)를 실행한다. 일정은 서버 시간대와 무관한 `OnCalendar=*-*-* 19:00:00 UTC`이며 `Persistent=true`로 꺼져 있던 동안 놓친 실행은 켤 때 한 번 보충한다([systemd 시간 형식](https://freedesktop.org/software/systemd/man/latest/systemd.time.html) · [Persistent 규칙](https://www.freedesktop.org/software/systemd/man/252/systemd.timer.html)). 서비스는 `/etc/mogum/mogum.env`에서 환경을 받아 `/opt/mogum/current/deploy/backup.sh`를 실행한다. `MOGUM_BUCKET`에 백업과 릴리스가 공유하는 버킷 이름을 넣는다. 자격은 EC2 인스턴스 역할이며 키 파일은 두지 않는다.

백업은 Compose 프로젝트 `mogum` · 서비스 `postgres` 라벨로 실행 중 컨테이너를 찾는다. Compose 파일을 읽지 않으므로 `APP_IMAGE`가 없어도 실행한다. 재부팅 직후에는 5초 간격으로 최대 120초 준비를 기다린다. 컨테이너 안의 `pg_dump -Fc`로 운영 DB `mogum` 전체(스키마 · 데이터 · 시퀀스 · 확장)를 custom 형식으로 받아, 덤프가 성공하고 파일이 비어 있지 않을 때만 업로드한다. 호스트 임시 파일은 root 전용(`umask 077` · 600)이며 성공/실패 시 trap으로 지운다. 강제 전원 종료/SIGKILL은 trap을 실행할 수 없으므로 서버 재기동 후 남은 임시 파일 유무를 확인한다. 출력은 객체 경로와 바이트 크기 한 줄이다. 호스트에 PostgreSQL 도구를 설치하지 않는다.

객체 형식은 **`s3://${MOGUM_BUCKET}/backups/YYYYMMDDTHHMMSS+0900.dump`**(예: `backups/20261025T040000+0900.dump`)다. 키의 시각은 `TZ=Asia/Seoul`로 만든다. 배포 묶음은 같은 버킷의 `releases/`를 쓴다. 버전 관리를 켜고 **현재 버전 6일 + 이전 버전 1일, 합쳐 총 7일 상한**으로 보관한다(ADR-072). 수명주기 규칙과 IAM 정책 파일은 step 4에서 만든다. 백업/연습 스크립트에는 S3 객체나 버전을 지우는 명령이 없으며 서버 역할에도 삭제 권한을 주지 않는다. 이 설정은 버킷에 적용한 뒤 첫 배포 때 확인해야 한다.

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
- `sudo systemctl start mogum-backup.service` 후 저널의 성공 객체 키/크기를 확인하고, 버킷의 버전 관리 및 총 7일 수명주기(step 4)가 적용됐는지 확인한다.
- 위 `restore.sh latest`를 한 번 실행해 테이블별 행 수와 종료 코드를 확인하고 `mogum_restore_check`가 제거됐는지 확인한다. 운영 DB에 데이터 변경이 없어야 한다.
