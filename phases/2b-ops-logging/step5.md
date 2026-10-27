# Step 5: log-timezone

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 30(로그) · 20(한국 날짜) · 명령어
- `docs/ADR.md` **ADR-075** — 「사람이 읽는 시각은 모두 한국 시간」 항목 · 이유 · 트레이드오프(이 step 의 결정이다. Claude 가 먼저 적었다)
- `docs/04-jobs-and-cost.md` **§9.2 로그** — 「시각 기준」(이 step 이 맞출 계약)
- `deploy/compose.yaml` — `postgres` 의 `command`
- `deploy/deploy.sh` — 9줄의 필수 명령 검사, 헬스 통과 · `current` 갱신 뒤의 systemd 단위 · journald 설정 설치 부분
- `deploy/README.md` — 「처음 구축 순서」 4번(서버 명령 목록 · 도구 확인 블록) · 「서버 구성」 · 「로그 보기」 · 「운영 확인 목록」의 journald 항목
- `backend/src/test/java/com/mogum/deploy/DeployFilesTest.java` — `세_서비스는_journald에_기록하고_Postgres는_오류_상세를_뺀다`, 그리고 명령 대체 시나리오 테스트 `배포_실패는_직전으로_복귀하고_성공만_링크와_보존_대상을_바꾼다`(가짜 명령이 `$TEST_ROOT/commands` 에 호출을 기록하는 방식)
- `deploy/backup.sh` · `deploy/systemd/mogum-backup.timer` — 서버 현지 시각에 기대지 않는지 볼 곳(바꾸지 않는다)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

운영 중 장애에 대응하려면 사용자가 말한 한국 시각으로 로그를 찾아야 한다. 앱 줄은 이미 한국 시간(`+09:00`)이지만 두 곳은 UTC 다.

- Postgres 서버 로그: 이미지 기본값이 `log_timezone = 'Etc/UTC'` 다.
- `journalctl` 의 줄 앞 시각과 `--since`: 서버(EC2) 시간대를 따르는데, 기본값이 UTC 다.

ADR-075 · 04 §9.2 「시각 기준」은 사람이 읽는 시각을 모두 한국 시간으로 맞추기로 정했다. 계산 · 저장의 기준은 UTC 로 둔다.

## 확인한 사실 — 2026-10-27 Claude 확인

**Postgres** — 일회용 컨테이너 `pgvector/pgvector:pg17` 로 확인했다.
- 옵션 없이 초기화하면 `postgresql.conf` 에 `log_timezone = 'Etc/UTC'` · `timezone = 'Etc/UTC'` 가 들어간다.
- `postgres -c log_error_verbosity=terse -c log_timezone=Asia/Seoul` 로 띄우면 다음과 같다.
    - `show log_timezone` 은 `Asia/Seoul`, `show timezone` 은 `Etc/UTC` 다. 세션 시간대는 그대로다.
    - 로그 줄은 `2026-10-27 06:51:26.792 KST [72] ERROR:  division by zero` 다.

**EC2 시간대** — 근거는 [AWS EC2 사용 설명서 「Change the time zone of your instance」](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/change-time-zone-of-instance.html)와 [timedatectl(1)](https://www.freedesktop.org/software/systemd/man/latest/timedatectl.html)이다.
- 인스턴스 기본 시간대는 UTC 다.
- Amazon Linux 에서는 `timedatectl` 로 보고, `sudo timedatectl set-timezone <zone>` 으로 바꾼다.
- `set-timezone` 은 `/etc/localtime` 링크를 바꾼다.

**서버에서 현지 시각에 기대는 곳** — 저장소의 서버 파일에는 두 곳뿐이고, 둘 다 영향이 없다.
- `deploy/backup.sh`: 객체 이름을 만들 때 `TZ=Asia/Seoul` 을 명시한다.
- `deploy/systemd/mogum-backup.timer`: `OnCalendar=*-*-* 19:00:00 UTC` 로 UTC 를 명시한다.

**확인하지 않은 것** — 아래는 실제 서버에서 확인하지 않았다. 절차서의 운영 확인 항목으로 남긴다.
- 서버의 `timedatectl` 동작
- 같은 값을 다시 설정할 때 서버에서 일어나는 일

## 작업

### 1. `deploy/compose.yaml` — Postgres 로그 시간대

- `postgres` 의 `command` 에 `-c` `log_timezone=Asia/Seoul` 을 더한다. 기존 `log_error_verbosity=terse` 는 그대로 둔다.
- **테스트 먼저**: `DeployFilesTest` 의 Postgres `command` 단언을 새 목록 `["postgres", "-c", "log_error_verbosity=terse", "-c", "log_timezone=Asia/Seoul"]` 로 고친다. 메서드 이름도 새 사실(로그 시간대)을 담게 바꾼다.

### 2. `deploy/deploy.sh` — 서버 시간대

- 성공한 배포마다 `timedatectl set-timezone Asia/Seoul` 을 한 번 부른다.
    - 자리는 헬스 통과와 `current` 갱신 뒤, 지금 systemd 단위 · journald 설정을 설치하는 곳 근처다.
    - 같은 값을 다시 설정해도 결과(`/etc/localtime` 이 가리키는 곳)는 같으므로, 비교 없이 매번 부르는 단순한 방식으로 둔다.
- 헬스 실패로 복귀한 경로와 링크 갱신 실패 경로에서는 부르지 않는다. journald 설정과 같은 규칙이다.
- 맨 위의 필수 명령 검사(`for command in …`)에 `timedatectl` 을 더한다.
- **테스트 먼저**: 기존 명령 대체 시나리오 테스트에 가짜 `timedatectl` 을 더한다.
    - 성공 시나리오: `timedatectl set-timezone Asia/Seoul` 이 정확히 한 번 기록된다.
    - 헬스 실패 · 링크 갱신 실패 시나리오: 기록되지 않는다.
    - 필수 명령 검사에 `timedatectl` 이 있는지도 기존 방식대로 확인한다. 같은 파일에 필수 명령을 확인하는 테스트가 이미 있으면 그 방식을 따른다.

### 3. `deploy/README.md`

- 「처음 구축 순서」 4번
    - 서버 명령 목록의 systemd 괄호에 `timedatectl` 을 더한다.
    - 도구 확인 블록의 `for tool in …` 에 `timedatectl` 을 더한다.
    - 2026-10-26 수행 기록 문단(도구 26개)은 그때의 사실이므로 고치지 않는다.
- 「서버 구성」 또는 그에 맞는 곳에 한 줄을 더한다. 내용: 서버 시간대는 `Asia/Seoul` 이고, `deploy.sh` 가 성공한 배포마다 맞춘다. 계산 · 저장 기준은 UTC 이고, 타이머 · 스크립트는 시간대를 명시한다.
- 「로그 보기」
    - `journalctl` 예시의 `TZ=Asia/Seoul` 접두와 "한국 시간으로 보려면 `TZ=Asia/Seoul` 을 지정한다" 는 문장을 지운다.
    - 대신 적을 것: 서버 시간대가 한국 시간이라 줄 앞 시각과 `--since` 가 한국 시간이다. Postgres 줄은 `… KST` 로 찍힌다.
- 「운영 확인 목록」 journald 항목(미체크)에 확인할 것을 더한다.
    - `timedatectl` 이 `Time zone: Asia/Seoul` 을 보인다.
    - `docker logs --tail 20 mogum-postgres-1` 의 줄이 `KST` 다.
    - 항목은 미체크로 두고 "서버에서의 실제 동작은 아직 확인하지 않았다" 를 유지한다.

### 4. 문서

- `docs/ADR.md` ADR-075 「검증」에 `2b-ops-logging` step 5 기록을 한 줄 더한다. 담을 것: 실제로 확인한 것과 테스트 수.
- ADR-075 의 결정 · 이유 · 트레이드오프 문장과 04 §9.2 「시각 기준」은 Claude 가 이미 적었다. 구현이 이 문장과 다르게 가야 하면 문서를 고치지 말고 `blocked` 로 멈춘다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*DeployFilesTest'
./backend/gradlew -p backend build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 패키지 구조(도메인별 패키지 · 그 안은 `controller` · `service` · `repository` · `domain` · `dto` · `generation` 은 `worker` · `config` 만 참조)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
   - 컨테이너 · 앱 · Postgres 세션의 기본 시간대가 UTC 그대로인가?
3. 결과에 따라 `phases/2b-ops-logging/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 컨테이너(`backend` · `postgres` · `caddy`)에 `TZ` 환경 변수나 `/etc/localtime` 마운트를 더하지 마라. Postgres `timezone`(세션 시간대)도 바꾸지 마라. 이유: 계산 · 저장 기준은 UTC 다(금지 20 · ADR-075). 기본 시간대에 기대는 코드가 생기면 운영과 테스트(UTC)에서 함께 드러나야 한다.
- 앱 로그 패턴(`logging.pattern.*`)과 `deploy/Caddyfile` 을 바꾸지 마라. 이유: 앱 줄은 이미 한국 시간이다. Caddy 자체 로그는 드물고 `journalctl` 의 줄 앞 시각으로 읽는다.
- `deploy/backup.sh` · `deploy/systemd/` 를 바꾸지 마라. 이유: 둘 다 시간대를 명시해서 서버 시간대와 무관하다.
- 서버 시간대를 바꾸는 명령을 절차서의 수동 단계로만 두지 마라. 이유: 사람이 잊으면 줄 앞 시각이 다시 UTC 가 된다. `deploy.sh` 가 맞춘다.
- 서버 명령 목록에 없는 도구를 `deploy.sh` 에 쓰지 마라(`timedatectl` 은 이 step 에서 목록에 더한다). 이유: 서버(Amazon Linux 2023)에 없을 수 있다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
