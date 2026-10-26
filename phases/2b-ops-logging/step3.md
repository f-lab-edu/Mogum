# Step 3: journald-ops

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 30(로그) · 명령어 · 작업 방식
- `docs/ADR.md` **ADR-075** · ADR-072(운영 구성)
- `docs/04-jobs-and-cost.md` **§9.2 로그**(보관 상한 · Postgres 서버 로그 · Caddy 접근 로그)
- `deploy/README.md` — 「서버 구성」 · 「운영 확인 목록」 · 「수동 롤백 · 실패 확인」 · 「백업 실패 확인」
- `deploy/compose.yaml` · `deploy/deploy.sh`(특히 헬스 통과 뒤 systemd 단위를 `cmp` 로 비교해 설치하는 부분 — `units_changed`) · `deploy/systemd/`
- `backend/src/test/java/com/mogum/deploy/DeployFilesTest.java` — 배포 파일 불변식과 명령 대체 시나리오 테스트(가짜 `systemctl` · `docker` 등으로 실행 명령을 기록하는 방식)
- `backend/build.gradle` — `test` 의 `inputs.dir('../deploy')`(배포 파일만 바꿔도 테스트가 다시 돈다)
- 루트 `compose.yaml` — 로컬 개발용이다. 이 step 에서 바꾸지 않는다

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

운영 Compose 에 로그 설정이 없어 Docker 기본(json-file · 회전 없음)으로 로그 파일이 계속 자라고, 배포 때 컨테이너를 새로 만들면 이전 로그가 함께 지워진다. ADR-075 로 세 서비스의 로그를 서버의 journald 에 넣고 **총 14일 · 500MB 중 먼저 닿는 쪽**까지만 두기로 했다.

## 확인한 사실 — 2026-10-27 공식 문서

이 값과 동작에 맞춰 쓴다.

- **journald.conf(5)** — https://www.freedesktop.org/software/systemd/man/latest/journald.conf.html
    - `Storage=auto` 는 `/var/log/journal` 디렉터리가 있을 때만 디스크에 저장하고, 없으면 메모리(재부팅 때 사라진다)다.
    - `SystemMaxUse=` 기본은 파일시스템의 10% 다.
    - `MaxRetentionSec=` 기본은 0(꺼짐)이다.
    - `MaxFileSec=` 는 "파일 하나에 기록을 담는 최대 기간" 이고 기본은 1달이다. journald 는 파일 단위로 지우므로, 기간 상한만 켜면 기록이 상한보다 파일 하나의 기간만큼 늦게 지워질 수 있다.
    - `RateLimitIntervalSec=` · `RateLimitBurst=` 기본은 30초에 10000개다.
    - drop-in 파일(`/etc/systemd/journald.conf.d/*.conf`)이 기본 설정 파일보다 우선한다.
- **systemd-journald.service(8)** — https://www.freedesktop.org/software/systemd/man/latest/systemd-journald.service.html
    - SIGHUP 으로 설정을 다시 읽는 것은 systemd 258 부터다.
    - `systemctl restart` 해도 기존 스트림 연결은 끊기지 않는다.
- **Docker journald 드라이버** — https://docs.docker.com/engine/logging/drivers/journald/
    - 기록마다 `CONTAINER_NAME`(시작 때 이름) · `CONTAINER_ID`(12자) · `CONTAINER_TAG`/`SYSLOG_IDENTIFIER`(tag 옵션) · `IMAGE_NAME` · `CONTAINER_PARTIAL_MESSAGE` 를 싣는다.
    - `docker logs` 로도 읽힌다.
    - 조회 예: `journalctl CONTAINER_NAME=webserver`.
- **PostgreSQL 17 로그 설정** — https://www.postgresql.org/docs/17/runtime-config-logging.html
    - `log_error_verbosity=TERSE` 는 `DETAIL` · `HINT` · `QUERY` · `CONTEXT` 를 로그에서 뺀다.
    - `log_parameter_max_length_on_error` 기본 0 은 바인드 값을 오류 로그에 넣지 않는다.
- **확인하지 않은 것** — 서버(Amazon Linux 2023)의 systemd 버전과 `/var/log/journal` 존재 여부. 이 step 은 어느 쪽이든 동작하게 쓴다(`Storage=persistent` · 바뀌었을 때 restart).

## 작업

### 1. `deploy/compose.yaml`

- `caddy` · `backend` · `postgres` 세 서비스의 로그 드라이버를 journald 로 둔다. tag 같은 옵션은 필요할 때만 더한다 — `CONTAINER_NAME`(`mogum-backend-1` 등)으로 조회할 수 있으면 더하지 않는다.
- `postgres` 를 `log_error_verbosity=terse` 로 띄운다(예: `command` 의 `-c` 인자). 이유: 중복 키 오류 등의 `DETAIL` 에 실제 값(이메일 등)이 실려 서버 로그에 14일 남는다(04 §9.2 · AGENTS 30). 다른 Postgres 설정은 바꾸지 않는다.
- Caddy 접근 로그(`log` 지시어)를 켜지 않는다 — `deploy/Caddyfile` 은 바꾸지 않는다.

### 2. `deploy/journald/mogum.conf` (새 파일)

`[Journal]` 절에 아래만 둔다.

- `Storage=persistent` — 디렉터리 존재에 기대지 않는다.
- 크기 상한 500MB 이하(`SystemMaxUse=`).
- **보관은 총 14일을 넘기지 않는다** — `MaxRetentionSec=` 과 `MaxFileSec=` 를 함께 정해, 파일 단위로 지우는 동작 때문에 기록이 14일을 넘겨 남지 않게 한다. 두 값을 고른 계산을 절차서에 한 줄로 적는다.
- 레이트리밋 · 압축 · 전달(`ForwardTo…`) 등 다른 값은 두지 않는다.

### 3. `deploy/deploy.sh`

- 헬스 통과 · `current` 갱신 뒤의 성공 경로에서, **systemd 단위 설치와 같은 방식(같은 자리 · 같은 오류 처리)** 으로 처리한다.
    - `deploy/journald/mogum.conf` 를 `/etc/systemd/journald.conf.d/mogum.conf` 와 `cmp` 로 비교한다.
    - 다를 때만 디렉터리를 만들고(`install -d`) 권한 644 로 설치한 뒤 `systemctl restart systemd-journald` 한다.
    - 같으면 설치 · restart 를 하지 않는다.
- 헬스 실패 · 링크 갱신 실패로 복귀하는 경로에서는 설치하지 않는다(systemd 단위와 같다).
- 새 외부 명령을 쓰지 않는다 — `cmp` · `install` · `systemctl` 은 이미 명령 검사 목록에 있다. 쓰는 명령이 바뀌면 절차서 「서버 구성」의 명령 목록을 맞춘다.

### 4. 테스트 — `DeployFilesTest` 확장(기존 방식을 따른다)

- compose 의 세 서비스가 모두 journald 드라이버다.
- `postgres` 가 `log_error_verbosity=terse` 로 뜬다. Postgres 동작 자체는 위 공식 문서 사실로 두고, 테스트 컨테이너로 다시 확인하지 않는다.
- `mogum.conf` 가 `[Journal]` 절에 `Storage=persistent` 이고, 크기 상한이 500MB 이하이고, `MaxRetentionSec` + `MaxFileSec` 가 14일 이하다. 단위 표기(`d` · `day` · `h` 등)를 파싱해 비교한다.
- deploy.sh 명령 대체 시나리오:
    - 처음(설치된 파일 없음) → 설치 1회 + `systemctl restart systemd-journald` 1회.
    - 같은 파일이 이미 있음 → 설치 · restart 0회.
    - 헬스 실패 복귀 · 링크 갱신 실패 → 설치 · restart 0회.
- `backend/build.gradle` 의 `test` 입력에 `deploy/` 가 이미 선언돼 있다 — 새 파일이 그 안이면 따로 더하지 않는다.

### 5. 절차서 — `deploy/README.md`

- **「서버 구성」**: 세 서비스의 로그가 journald 로 간다는 것, 설정 파일 위치(`deploy/journald/mogum.conf` → `/etc/systemd/journald.conf.d/mogum.conf`), 보관 상한(총 14일 · 500MB 중 먼저 — 두 값을 고른 계산 한 줄), 설치 시점(성공 배포 뒤 · 바뀌었을 때만 restart — systemd 258 미만은 SIGHUP 으로 설정을 다시 읽지 못한다), Postgres `log_error_verbosity=terse` 를 한 문단으로 적는다.
    - 지금 문단의 "정의가 같은 Postgres와 Caddy는 재생성되지 않는다" 옆에 한 줄을 더한다: **이 변경을 처음 배포할 때는 세 서비스의 정의가 바뀌어 한 번 다시 만들어진다**(데이터 · 인증서는 볼륨에 남는다 · 그 직전까지의 json-file 로그는 그때 사라진다).
- **새 절 「로그 보기」**: `04 §9.2` 가 `#로그-보기` 로 링크한다 — 제목을 정확히 `## 로그 보기` 로 둔다. 내용:
    - root 셸 전환(`sudo -i`)은 따로 둔 블록으로 쓴다.
    - 지금 컨테이너: `docker logs --tail …`.
    - 지난 배포를 포함한 조회: `journalctl CONTAINER_NAME=mogum-backend-1 --since …`. 한국 시간으로 보고 · 넣으려면 `TZ=Asia/Seoul`.
    - 요청 하나: `requestId=…`, 회원 하나: `userId=…` 로 `grep`.
    - 디스크 사용: `journalctl --disk-usage`.
    - 로그를 공개 저장소 · 이슈 · PR 에 붙이지 않는다는 기존 주의를 잇는다.
- **「운영 확인 목록」**: 체크하지 않은 새 항목을 하나 더한다(사람이 서버에서 확인하기 전이다). 확인할 것:
    - 배포 뒤 `systemd-analyze cat-config systemd/journald.conf` 에 우리 값이 보인다.
    - `journalctl CONTAINER_NAME=mogum-backend-1` 에 **배포 전 컨테이너의 로그가 남아 있다**.
    - `docker logs` 가 동작한다.
    - 브라우저 개발자 도구에서 본 응답의 `X-Request-Id` 값으로 그 요청의 접근 줄을 찾는다.
    - 줄 시각이 `+09:00` 이고 이메일 · 쿼리가 없다.
- **「수동 롤백 · 실패 확인」**: 기존 `docker logs` 예는 두고, 지난 배포의 로그는 「로그 보기」로 링크한다.
- **「백업 실패 확인」**: "별도 로그 파일/회전은 없으며" 문장을 journald 보관(총 14일)에 맞게 고친다.

### 6. 문서

- `docs/04-jobs-and-cost.md` §9.2 첫 문단에 실제 설정 값(`MaxRetentionSec` · `MaxFileSec` · `SystemMaxUse`)을 한 줄로 더한다. 이미 있는 규칙 문장은 고치지 않는다.
- `docs/ADR.md` ADR-075 의 **검증** 줄 — 저장소 검사(불변식 · 시나리오)까지 확인했고 **서버에서의 실제 동작은 아직 확인하지 않았다**는 것을 한 항목으로 더한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 보관 상한이 "총 14일 · 500MB" 를 넘지 않는가 — 파일 회전 간격까지 더해서?
   - deploy.sh 가 실패 · 복귀 경로에서 설치하지 않는가? 같은 파일이면 restart 하지 않는가?
   - 운영 확인 목록의 새 항목이 체크되지 않은 채인가?
   - `AGENTS.md` 금지 목록 · 작업 방식을 위반하지 않았는가?
3. 결과에 따라 `phases/2b-ops-logging/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 고른 journald 값과 계산, compose 변경, deploy.sh 설치 조건, 절차서에 더한 절 · 항목을 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 로그 수집 에이전트 · 외부 로그 서비스(CloudWatch Logs · Loki · Fluent Bit · Vector 등)를 넣지 마라. 이유: 01 §6 · ADR-075 — 서버 한 대 · 고정비 없음으로 정했다.
- `/etc/docker/daemon.json` 을 만들거나 바꾸는 단계를 넣지 마라. 이유: 서비스별 설정은 저장소의 compose 에 둔다 — 저장소 밖 서버 설정과 Docker 재시작이 는다.
- 루트 `compose.yaml`(로컬 개발)을 바꾸지 마라. 이유: Docker Desktop 에는 journald 가 없다 — 로컬은 기본 드라이버 그대로다.
- journald 의 다른 값(레이트리밋 · 압축 · 전달)과 Postgres 의 다른 로그 설정을 바꾸지 마라. 이유: 정한 것은 저장 · 크기 · 기간과 오류 상세 제거뿐이다.
- Caddy 접근 로그를 켜지 마라. 이유: 04 §9.2 — 쿼리(OAuth `code` · `state`)를 그대로 싣는다.
- 운영 확인 목록의 새 항목을 체크하지 마라. 이유: 사람이 서버에서 확인하기 전이다.
- `backend/src/main` 을 고치지 마라. 이유: 배포 파일 step 이다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
