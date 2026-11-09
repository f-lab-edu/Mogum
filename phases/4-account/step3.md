# Step 3: operator-sql

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §8.2(**승인 SQL 이 `status` · `approved_at` · `credit_refill_at` 을 바꾼다** · 거절을 풀면 `APPLIED`) · §8.6(**운영자가 신청 중 · 거절됨인 계정을 지울 때** — `credit_entries` → 로그인 세션 → `users`) · §8.7
- `docs/01-overview.md` §4(정원제) · §10(공개 필수 목록 — 운영자 SQL 줄)
- `docs/ADR.md` — ADR-017(하루의 경계 — Asia/Seoul 새벽 4시) · ADR-058(거절 · 풀기) · ADR-059(신청 중 · 거절됨은 운영자가 지운다) · ADR-060(운영자 SQL) · ADR-061(정원 — 운영자가 승인할 때 지킨다) · ADR-175
- `AGENTS.md` — 금지 17(새벽 4시 경계) · 28(운영자 SQL 은 `updated_at` 에 `now()`) · 명령어 줄(`bootRun` 의 `APP_AUTH_ADMIN_EMAILS`)
- `docs/TEST_GUIDE.md` 머리말(공용 값의 「관리자 …」) · §1(레포지토리 · DB 테스트)
- `deploy/README.md` — 「처음 구축 순서」의 환경 변수 표(`APP_AUTH_ADMIN_EMAILS` 줄) · 운영 확인 목록의 `psql` 실행 예(컨테이너 안에서 `psql -X … -d mogum`) · 「백업 · 복원」의 `spring_session` 지우기 예
- `deploy/compose.yaml` · `deploy/mogum.env.example` · `.env.example`
- `backend/build.gradle` — `test` 의 `inputs.dir('../deploy')`
- `backend/src/test/java/com/mogum/deploy/DeployFilesTest.java`(env 이름 목록) · 같은 폴더의 다른 `deploy/` 검사
- step 0 이 만든 `backend/src/main/resources/db/migration/V9__*.sql` — **기존 운영자 행의 `credit_refill_at` 식**(승인 SQL 과 같은 뜻이어야 한다)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`4-account`)는 계정을 신청 · 승인 · 거절됨 흐름으로 바꾼다. step 2 까지 사람이 신청하면 `APPLIED` 행과 첫 크레딧이 생긴다. 승인 · 거절은 화면이 없고 **운영자가 DB 에 붙어 직접 SQL 로 한다**(ADR-060).

이 step 은 운영자가 복사해 쓸 SQL 파일과 절차를 만들고, 그 파일을 테스트가 실제 Postgres 에서 돌려 맞는지 지킨다. 그리고 더는 쓰지 않는 허용 목록 환경 변수(`APP_AUTH_ADMIN_EMAILS`)를 배포 파일 · 문서에서 걷는다.

**계약 문서는 phase 전에 커밋했다. 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. `deploy/sql/` — 운영자 SQL 다섯

| 파일(이름은 네가 정한다) | 하는 것 |
|---|---|
| 신청 목록 | `APPLIED` · `REJECTED` 사람의 ID · 이메일 · 이름 · 상태 · 신청한 때(`created_at`). 정원(ADR-061)을 보도록 승인된 수도 함께 보인다 |
| 승인 | `APPLIED` 한 사람을 `APPROVED` 로 — `approved_at = now()` · `credit_refill_at` = 「승인한 날 + 30일의 새벽 4시(Asia/Seoul)」(ADR-017 · ADR-175) · `updated_at = now()`. **V9 의 식과 같은 뜻이다** |
| 거절 | `APPLIED` 한 사람을 `REJECTED` 로 · `updated_at = now()` |
| 거절 풀기 | `REJECTED` 한 사람을 `APPLIED` 로 · `updated_at = now()`(ADR-058) |
| 계정 지우기 | `APPLIED` · `REJECTED` 한 사람을 02 §8.6 순서로 — 그 사람의 `credit_entries` → 그 사람의 로그인 세션(주인 색인 = 사용자 ID 의 `spring_session` 행 · 속성은 FK 로 함께 지워지는지 확인해서 적는다) → `users`. 한 트랜잭션 |

- 대상은 사용자 ID 하나로 고른다. 사람이 `psql` 로 붙여 넣어 실행하는 파일이다 — 값 자리를 어떻게 채우는지(예: `psql -v user_id=…` 와 `:'user_id'`)를 네가 정하고 절차서에 적는다.
- **상태 조건을 `WHERE` 에 넣는다** — 승인은 `APPLIED` 만, 지우기는 `APPROVED` 가 아닌 사람만(승인된 사람은 탈퇴로 지운다 — TASK 12). 조건에 맞지 않으면 아무 행도 바꾸지 않는다. 몇 행이 바뀌었는지 사람이 볼 수 있게 한다(`psql` 의 `UPDATE n` 출력이면 충분하다).
- 승인 SQL 은 크레딧을 건드리지 않는다 — 첫 크레딧은 신청할 때 이미 들어갔다(02 §8.2 · ADR-173).

### 2. 시험 — 실제 Postgres 에서 그 파일을 돌린다

- `backend/src/test/java/com/mogum/deploy/`(또는 계정 쪽 레포지토리 테스트) 에 시험을 둔다. **테스트가 `deploy/sql/` 의 파일을 그대로 읽어** 실제 Postgres(Testcontainers)에서 돌린다. 값 자리를 채우는 방법은 사람이 쓰는 방법과 같은 뜻이어야 한다 — 테스트용으로 다른 SQL 을 쓰지 마라.
- 확인할 것:
    - 승인 → `APPROVED` · `approved_at` · `credit_refill_at` 이 차고 `CHECK` 를 지난다 · `updated_at` 이 바뀐다. 이미 `APPROVED` · `REJECTED` 인 사람에게는 0 행.
    - 승인의 새벽 4시 경계 — 승인한 때가 Asia/Seoul 03:59 와 04:00 일 때 `credit_refill_at` 의 날이 하루 갈린다. `now()` 를 고정할 수 없으므로 **승인 파일의 식**을 고정 시각에 넣어 확인하는 방법을 네가 고른다(예: 파일을 읽어 `now()` 자리만 고정 시각으로 바꿔 돌리기). 식을 테스트 코드에 복사해 따로 시험하지 마라 — 파일이 바뀌어도 시험이 모른다. V9 의 식과 같은 뜻인지는 summary 에 두 식을 나란히 적어 리뷰가 대조하게 한다.
    - 거절 · 거절 풀기 → 상태 · `updated_at`.
    - 계정 지우기 → 그 사람의 `credit_entries` · 세션 · `users` 행이 사라지고, 다른 사람의 행 · 세션은 남는다. `APPROVED` 인 사람에게는 아무것도 지우지 않는다.
- `backend/build.gradle` 의 `test` 는 이미 `inputs.dir('../deploy')` 를 선언했다 — `deploy/sql/` 만 바꿔도 시험이 다시 도는지 확인하고, 아니면 입력을 더한다.

### 3. 절차서

- `deploy/README.md` 에 「운영자 SQL」 절을 더한다 — 서버에서 `psql` 에 붙는 방법(기존 「운영 확인 목록」의 컨테이너 `psql` 예와 같은 방식), 다섯 파일을 실행하는 명령, 값 자리 채우는 법, 바뀐 행 수를 보는 법, 승인 전에 신청 목록으로 정원을 보는 것. 쓰는 명령은 서버에 기본으로 있는 것만 쓴다(`sudo` · `docker` · 컨테이너 안 `psql`).
- 명령 블록은 권한 전환(`sudo -i`)과 나머지를 나눈다(`/harness` 원칙 18).

### 4. 허용 목록 환경 변수 걷기

`APP_AUTH_ADMIN_EMAILS` 는 step 0 부터 서버가 읽지 않는다. 아래에서 지운다.
- `deploy/compose.yaml`(비면 기동을 거부하는 `:?` 줄) · `deploy/mogum.env.example` · `.env.example` · `deploy/README.md`(환경 변수 표의 줄)
- `backend/src/test/java/com/mogum/deploy/DeployFilesTest.java` — env 이름 목록에서 뺀다(검사가 지키던 다른 사실은 유지한다).
- `AGENTS.md` 명령어 줄의 `bootRun` 설명 — 「로그인 허용 계정은 `APP_AUTH_ADMIN_EMAILS` …」를 지운다. 그 줄의 다른 사실은 그대로 둔다.
- `docs/TEST_GUIDE.md` 머리말 — 공용 값의 「관리자 `admin@example.com,changed@example.com`」을 지금 공용 값에 맞춘다(step 0 이 공용 속성을 지웠다).

### 5. 문서

- `docs/01-overview.md` §10 — 「자주 쓰는 운영자 SQL … 운영 문서에 적기」를 `[x]` 로 바꾸고 절차서 절을 가리킨다.
- 02 · 05 의 규칙 문장은 고치지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.deploy.*' --tests 'com.mogum.account.*'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 시험이 `deploy/sql/` 의 파일을 그대로 읽어 실제 Postgres 에서 돌리는가? 테스트 전용 SQL 이 따로 없는가?
   - 승인 · 지우기의 상태 조건이 `WHERE` 에 있고, 맞지 않는 사람은 0 행인가?
   - 지우는 순서가 02 §8.6 과 같은가? 다른 사람의 세션이 남는가?
   - 승인 SQL 의 `credit_refill_at` 식이 V9 와 같은 뜻이고 새벽 4시 경계 양쪽을 시험했는가?
   - `APP_AUTH_ADMIN_EMAILS` 가 저장소에 남지 않았는가(`git grep APP_AUTH_ADMIN_EMAILS` — `phases/` 의 끝난 step 파일 제외)?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/4-account/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — SQL 파일 이름 다섯 · 값 자리 채우는 법 · 시험 클래스 · 새벽 4시 경계 시험 방법 · 절차서 절 이름 · 걷어낸 파일 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 관리 화면 · 운영자 API · 운영자 역할을 만들지 마라. 이유: ADR-060 — MVP 는 직접 SQL 이다.
- 승인 SQL 에서 크레딧 · 채움 기록을 바꾸지 마라. 이유: 첫 크레딧은 신청할 때 들어갔다(ADR-173).
- 승인된 사람을 지우는 SQL 을 만들지 마라. 이유: 승인된 사람은 화면에서 탈퇴한다 — SCHEDULE §3 상 TASK 12 에서 만든다.
- 크레딧 채우기(새벽 4시) 코드 · SQL 을 만들지 마라. 이유: SCHEDULE §3 상 크레딧 채우기 TASK 에서 만든다.
- `deploy/sql/` 파일에 테스트만 쓰는 자리(시각 변수 등)를 두지 마라 — 고정 시각은 테스트 쪽에서 넣는다. 이유: 사람이 붙여 넣는 파일이 복잡해지고, 사람이 쓰는 모양과 시험받는 모양이 갈린다.
- `deploy/` 의 다른 설정(백업 · journald · Caddy · 워크플로)을 바꾸지 마라. 이유: 이 step 범위 밖이다.
- 화면(`frontend/`)을 고치지 마라. 이유: step 4 · 5 의 범위다.
- 계약 문서(02 · 05 의 규칙 문장 · ADR · AGENTS 금지 목록)를 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
