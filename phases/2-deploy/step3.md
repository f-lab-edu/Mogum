# Step 3: backup

## 배경 — 이 phase(`2-deploy`)가 하는 일

지금의 앱을 운영에 올린다(`docs/ADR.md` ADR-072). Postgres 는 앱과 같은 EC2 의 Docker Compose 안에 있다 — 인스턴스를 잃으면 마지막 백업 뒤의 기록을 잃는다. 그래서 **매일 `pg_dump` → S3** 로 백업한다. 보관은 사용자가 정한 **총 7일 상한**이다(버킷 버전 관리를 켜고 현재 버전 6일 + 이전 버전 1일 — 수명주기 규칙 파일은 step 4 가 만든다).

step 2 에서 운영 Compose(`deploy/compose.yaml` — 프로젝트 이름 `mogum`, 서비스 `caddy` · `backend` · `postgres`), 서버 env 파일 견본(`deploy/mogum.env.example` → 서버의 `/etc/mogum/mogum.env`), 배포 스크립트(`deploy/deploy.sh {SHA}` — 릴리스는 `/opt/mogum/releases/{SHA}/`, 지금 릴리스 링크는 `/opt/mogum/current`), 불변식 테스트(`DeployFilesTest`)와 절차서의 「서버 구성」 절(`deploy/README.md`)을 만들었다.

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록(특히 20 — "오늘" 은 `Asia/Seoul`) · 작업 방식
- `docs/ADR.md` — ADR-072(백업 · 총 7일 · 트레이드오프의 탈퇴 데이터 보관)
- `docs/04-jobs-and-cost.md` §3(운영 구성 표의 「백업」 행)
- `docs/02-domain-data.md` §7(탈퇴 — 백업에서 복구하면 그 사이의 탈퇴 · 삭제를 다시 적용한다)
- step 2 산출물: `deploy/compose.yaml` · `deploy/deploy.sh` · `deploy/mogum.env.example` · `deploy/README.md` · `backend/src/test/java/com/mogum/deploy/DeployFilesTest.java` · `backend/build.gradle`(`deploy/` 를 `test` 입력으로 선언한 부분)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

S3 버킷은 하나이며 이름은 서버 env 파일의 `MOGUM_BUCKET` 으로 받는다(이 이름을 `deploy/mogum.env.example` 에 더한다). 백업 객체는 `backups/` 접두사 아래에 둔다 — `releases/` 접두사는 배포 묶음이 쓴다(step 5). 자격은 EC2 인스턴스 역할이다(키를 두지 않는다).

### 1. `deploy/backup.sh` — 매일 실행

**원하는 수준 — 위에서 아래로 읽히는 짧은 bash 스크립트.** 덤프를 디스크의 임시 파일로 받고, 끝까지 성공했을 때만 올린다. DB 가 작아 스트리밍 업로드 · 부분 업로드 키 · 로그 파일 회전이 필요 없다.

1. Postgres 가 준비될 때까지 짧게 기다린다(재부팅 직후 타이머가 먼저 돌 수 있다 — 예: 5초 간격으로 최대 2분). 준비되지 않으면 실패한다.
2. 운영 Postgres 컨테이너 안에서 `pg_dump` 를 custom 형식(`-Fc`)으로 돌려 root 전용 임시 파일(`umask 077`)에 받는다. 끝나면 임시 파일을 지운다(실패해도 — `trap`). 컨테이너를 찾는 방법은 재량이다 — 다만 운영 Compose 파일은 `APP_IMAGE` 가 없으면 실패하게 만들어져 있으니(step 2), Compose 명령을 쓴다면 그 때문에 백업이 실패하지 않는지 확인한다.
3. `pg_dump` 가 성공하고 파일이 비어 있지 않을 때만 `s3://${MOGUM_BUCKET}/backups/{한국 시각}.dump` 로 올린다. 시각은 **`Asia/Seoul` 로 명시해** 만든다(서버 시간대를 가정하지 않는다 — 금지 목록 20). 덤프가 실패하면 아무것도 올리지 않고 실패 코드로 끝난다 — 잘린 덤프가 최신 백업이 되면 안 된다.
4. 출력은 결과 한두 줄(객체 키 · 크기)뿐 — 덤프 내용 · 비밀을 출력하지 않는다.

### 2. 매일 실행 — `deploy/systemd/mogum-backup.service` · `deploy/systemd/mogum-backup.timer`

- 서비스는 `/opt/mogum/current/deploy/backup.sh` 를 한 번 실행한다(oneshot). env 는 `/etc/mogum/mogum.env` 에서.
- 타이머는 **매일 한국 시각 04:00** 에 돈다. 서버 시간대에 기대지 않게 적는다(예: UTC 로 환산해 `19:00 UTC` 로 적거나, 시간대를 명시하는 형식을 쓴다 — 쓴 형식이 Amazon Linux 2023 의 systemd 에서 동작하는지 확인하지 못했다면 절차서의 「첫 배포 때 확인」 목록에 적는다). 서버가 꺼져 있어 놓친 실행은 켜질 때 한 번 보충한다.

### 3. 설치 — `deploy/deploy.sh` 에 더한다

- 배포의 **헬스가 성공한 뒤**, 이 릴리스의 `deploy/systemd/` 파일이 `/etc/systemd/system/` 의 것과 다를 때만 복사하고 `systemctl daemon-reload` 한 뒤 타이머를 켠다(`enable --now`). 같으면 아무것도 하지 않는다.
- 되돌리기 경로(헬스 실패)에서는 설치하지 않는다.

### 4. `deploy/restore.sh <객체 키 | latest>` — 복원 연습

운영 DB 를 건드리지 않고 **백업이 실제로 복원되는지** 확인하는 연습용이다.

1. 지정한 객체(또는 `backups/` 의 가장 최근 객체)를 root 전용 임시 파일로 받는다.
2. 같은 Postgres 컨테이너 안에 **별도 DB `mogum_restore_check`** 를 새로 만들고(이미 있으면 지우고 만든다) `pg_restore` 로 복원한다.
3. 복원한 DB 의 테이블별 행 수를 출력한다(행 내용은 출력하지 않는다). 운영 DB(`mogum`)의 같은 수와 나란히 보여 주면 좋다.
4. 끝나면(실패해도) `mogum_restore_check` 와 임시 파일을 지운다.

- **운영 DB 이름(`mogum`)을 복원 대상으로 쓰는 경로가 없어야 한다.** 실제 재해 복구(운영 DB 로 되돌리기)는 스크립트가 아니라 절차서의 명령으로 사람이 한다 — 이 step 에서 그 절차를 `deploy/README.md` 에 적는다(앱 정지 → 복원 → 앱 시작 → 그 사이의 탈퇴 · 삭제를 다시 적용 — 02 §7).

### 5. 불변식 테스트 — `backend/src/test/java/com/mogum/deploy/BackupFilesTest.java`

`deploy/` 는 step 2 에서 이미 `test` 입력이다. 확인할 것(최소): `backup.sh` · `restore.sh` 가 `bash -n` 통과 · `set -euo pipefail` · `set -x` 없음 · 실행 권한 · `backup.sh` 가 custom 형식으로 덤프하고 `backups/` 접두사로 올리며 `Asia/Seoul` 을 명시함 · 덤프가 실패하면 올리지 않는 구조(예: 업로드가 덤프 성공 뒤에만 나옴 — 판정 방법은 재량) · 객체 · 버전을 지우는 S3 명령(`aws s3 rm` · `delete-object`)을 쓰지 않음 · `restore.sh` 의 복원 대상이 `mogum_restore_check` 뿐임 · 타이머가 매일 한국 04:00 에 해당하고 놓친 실행을 보충함 · 서비스가 `/opt/mogum/current/deploy/backup.sh` 를 실행함 · `mogum.env.example` 에 `MOGUM_BUCKET` 이 있음.

### 6. 문서

- `deploy/README.md` — 「백업 · 복원」 절: 언제 · 어디로 · 무엇을(객체 키 형식) · 보관(총 7일 — 현재 6일 + 이전 1일, 수명주기 파일은 step 4) · 복원 연습(`restore.sh`) · 실제 재해 복구 명령 · 탈퇴 · 삭제 재적용 · 백업 실패를 지금은 어떻게 아는지(`systemctl status` · `journalctl` — 슬랙 알림은 `10-cost-control`).
- `docs/04-jobs-and-cost.md` §3 운영 구성 표의 「백업」 행 — 스크립트 · 타이머 이름과 실행 시각(한국 04:00)을 더한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # BackupFilesTest · DeployFilesTest 포함 통과
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - ADR-072 의 백업 결정(매일 · S3 · 끝까지 성공한 덤프만 · 총 7일)과 맞는가?
   - `AGENTS.md` 금지 목록(20 — 한국 시각)을 위반하지 않았는가?
3. 결과에 따라 `phases/2-deploy/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 만든 파일 · 객체 키 형식 · 타이머 표기 · `deploy.sh` 에 더한 설치 동작 · 테스트 수를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 덤프를 S3 로 바로 흘려 올리지 마라(`pg_dump … | aws s3 cp - …`). 이유: 덤프가 중간에 실패해도 잘린 객체가 백업으로 남는다. 임시 파일 → 성공 확인 → 업로드로 한다.
- 백업 스크립트가 S3 객체 · 버전을 지우게 하지 마라. 이유: 보관 기간은 버킷 수명주기 규칙이 정하고(step 4), 서버 역할에는 삭제 권한을 주지 않는다 — 서버가 털려도 백업을 지울 수 없게 한다.
- 로그 파일 · 로그 회전 · 별도 로그 디렉터리를 만들지 마라. 이유: systemd 저널이 출력을 담는다 — 원하는 단순함의 수준이다.
- `restore.sh` 가 운영 DB(`mogum`)에 복원하는 옵션을 만들지 마라. 이유: 연습 스크립트의 실수 한 번이 운영 데이터를 덮는다. 실제 복구는 절차서의 명령으로 사람이 한다.
- 수명주기 규칙 · IAM 정책 파일(`deploy/aws/`)을 만들지 마라. 이유: step 4 범위다.
- 실제 버킷 이름 · 계정 ID 를 파일에 적지 마라. 이유: 공개 저장소다 — 자리표시만 쓴다.
- `.env` 파일을 만들거나 읽지 마라. 이유: `AGENTS.md`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
