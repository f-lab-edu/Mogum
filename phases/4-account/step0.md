# Step 0: account-schema

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §8 머리말 · §8.1(공통 규칙 · 제약 이름) · §8.2(`users` · `credit_entries` — **이 step 의 계약이다**) · §8.7(`users.status` · `credit_entries.type`)
- `docs/ERD.md` — `users` · `credit_entries`
- `docs/ADR.md` — ADR-017(하루의 경계) · ADR-147 · ADR-154 · ADR-173 · ADR-175
- `docs/04-jobs-and-cost.md` §2(패키지) · §10(「운영 DB 의 사용자 줄을 신청 흐름으로 옮기는 방법」 줄)
- `docs/TEST_GUIDE.md` 전체 — 테스트 종류 · 이름 · 픽스처 · Fake
- `AGENTS.md` — 금지 24(정적 팩토리) · 27(`@ManyToOne(LAZY)` 단방향 · cascade 없음) · 28(`AuditedEntity`) · 작업 방식(TDD · 짝 테스트 이름)
- 지금 코드: `backend/src/main/resources/db/migration/V2__create_users.sql` ~ `V8__create_curriculum_model.sql`(특히 `V5` 의 `RAISE EXCEPTION` 방식) · `backend/src/main/java/com/mogum/account/` 전체 · `backend/src/main/java/com/mogum/config/AuthProperties.java` · `backend/src/main/java/com/mogum/auth/SessionUserFilter.java` · `auth/OAuthLoginHandler.java` · `backend/src/main/resources/application.yml`
- 지금 테스트: `backend/src/test/java/com/mogum/account/` 전체 · `config/AuthPropertiesTest.java` · `auth/SessionUserFilterTest.java` · `support/IntegrationTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`4-account`)는 계정을 신청 · 승인 · 거절됨 흐름으로 바꾼다. 지금은 허용 목록(`app.auth.admin-emails`)에 있는 이메일이 처음 로그인하면 `ACTIVE` 행이 생긴다. 새 흐름에서는 구글로 로그인한 사람이 「신청」을 눌러야 `APPLIED` 행이 생기고, 운영자가 SQL 로 승인 · 거절한다.

**계약 문서(`docs/02-domain-data.md` §8 · `docs/05-api.md` §1 · §3 · §4)는 이 phase 전에 정해 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

이 step 은 테이블 · 엔티티 · 저장소와 허용 목록 걷어내기를 맡는다. 로그인 세션 · 「나」 · 신청 API · 화면은 뒤 step 이다.

## 작업

### 1. 마이그레이션 `V9`

`backend/src/main/resources/db/migration/V9__{이름}.sql` 하나로 02 §8.2 의 두 테이블 모양을 만든다.

**`users`**
- 지운다 — `role` · `terms_agreed_at` · `terms_version`.
- 더한다 — `name text NOT NULL` · `approved_at timestamptz` · `credit_balance integer NOT NULL` + `CHECK (credit_balance >= 0)` · `credit_refill_at timestamptz` + `CHECK ((status = 'APPROVED') = (credit_refill_at IS NOT NULL))`.
- `approved_at` 도 `APPROVED` 일 때만 있다(02 §8.2) — 같은 모양의 `CHECK` 로 묶는다.
- `status` 의 값 목록을 `APPLIED` · `APPROVED` · `REJECTED` 로 바꾼다(02 §8.7).
- 제약 이름은 02 §8.1 규칙(`{테이블}_{칸}_{key|check|not_blank|idx}`)을 따른다.

**기존 행 옮기기**(02 §8.2 「지우는 칸」 줄 — 칸을 NOT NULL 로 묶기 전에 채운다)
- `name` — 모든 기존 행에 이메일의 `@` 앞부분(`test123@gmail.com` → `test123`).
- 옛 `ACTIVE`(운영자) → `APPROVED`. `approved_at` = 마이그레이션 시각(`now()`). `credit_refill_at` = 「승인한 날 + 30일의 새벽 4시(Asia/Seoul)」 — 승인한 날은 Asia/Seoul 새벽 4시를 경계로 센다(ADR-017 · ADR-175). 이 식은 step 3 의 승인 SQL 과 같은 뜻이어야 한다.
- 옛 `PENDING` · `WAITLISTED` → `APPLIED`. `approved_at` · `credit_refill_at` 은 비운다.
- 옮기는 모든 행에 `credit_balance = 100` 과 `credit_entries` 채움(`REFILL`) 기록 한 줄(`amount = 100`)을 넣는다. **100 은 SQL 에 숫자로 쓴다** — 그때의 한 달 몫 임시 값이다. Flyway placeholder 나 설정을 읽지 마라(적용한 마이그레이션은 고치지 않는다 — 그때의 기록이다).
- 위 셋이 아닌 상태 값이 있으면 `V5` 처럼 `RAISE EXCEPTION` 으로 멈춘다.

**`credit_entries`**(02 §8.2)
- `id bigserial PRIMARY KEY` · `user_id bigint NOT NULL` → `users`(기본 동작 — `ON DELETE` 를 붙이지 않는다) · `type text NOT NULL` + `CHECK (type IN ('REFILL','CONSUME','RESTORE'))` · `amount integer NOT NULL` · `created_at` · `updated_at`(`timestamptz NOT NULL`).
- **`job_id` 칸은 만들지 않는다** — 가리킬 `generation_jobs` 가 아직 없다. 그 칸과 연결은 `generation_jobs` 를 만드는 마이그레이션이 더한다(02 §8 머리말).
- 색인 `(user_id, created_at)`.

### 2. 엔티티

- `account/domain/UserStatus` — `APPLIED` · `APPROVED` · `REJECTED`. `UserRole` 은 지운다.
- `account/domain/User` — 칸을 V9 에 맞춘다. `User.join` 을 지우고 **`User.apply(provider, subject, email, name, creditBalance)`** 처럼 신청으로 만드는 이름 있는 정적 팩토리 하나를 둔다. 처음 상태 `APPLIED` 는 팩토리 안에 고정한다. 한 달 몫은 바깥 값(설정)이라 인자로 받는다(금지 24). 같은 타입 인자가 이어지므로 팩토리가 채운 값을 getter 로 모두 확인하는 테스트를 둔다.
- **승인 · 거절 메서드를 두지 마라** — 승인 · 거절은 운영자 SQL 만 한다(ADR-060 · step 3).
- `account/domain/CreditEntry` — `AuditedEntity` 상속 · `@ManyToOne(fetch = LAZY)` 로 `User` 를 가리킨다(같은 도메인 — 금지 27) · 컬렉션 · cascade 없음. 채움을 만드는 팩토리(예: `CreditEntry.refill(user, amount)`) 하나만 둔다 — 씀 · 복구는 만드는 작업 TASK 에서 더한다.
- `account/domain/CreditEntryType` — `REFILL` · `CONSUME` · `RESTORE`(DB `CHECK` 와 같은 값 목록 — 02 §8.7).
- `account/repository/CreditEntryRepository` — 기존 `UserRepository` 처럼 `Repository<T, Long>` 상속, 이 phase 가 쓰는 메서드만 선언한다(ArchUnit 이 넓은 상속을 막는다).

### 3. 허용 목록 걷어내기 · 컴파일되는 최소 변경

- `config/AuthProperties` · `application.yml` 의 `app.auth.admin-emails` · `LoginService` 의 허용 목록 분기 · `roleFor` 를 지운다.
- `LoginService.login(ExternalAccount)` 의 결과를 바꾼다:
    - 이메일 미인증 → 지금처럼 거절(`EMAIL_NOT_VERIFIED`). 거절은 DB 접근 전이다(지금 테스트의 `shouldHaveNoInteractions` 규칙 유지).
    - `(provider, subject)` 행이 있으면 → `Success(userId)`(상태와 상관없이 — 상태는 요청마다 읽는다).
    - 행이 없으면 → **행을 만들지 않고** 「신청 전」 결과(예: `LoginResult.NotApplied(ExternalAccount)`)를 돌려준다(ADR-154). `NOT_ALLOWED` 거절은 없앤다.
- `ExternalAccount` 에 `name` 을 더할지는 step 1 이 정한다 — 이 step 에서는 더하지 않는다.
- 아래만 컴파일 · 기존 동작이 유지될 만큼 고친다. 나머지 모양은 step 1 · 2 가 정한다.
    - `OAuthLoginHandler` — 「신청 전」 결과는 **임시로** 지금의 거절과 같은 실패 리다이렉트(`/login?error=not_allowed`)와 로그 줄로 보낸다. step 1 이 세션 처리로 바꾼다.
    - `SessionUserFilter` — 역할 권한(`ROLE_{role}`)을 빼고 권한 없는 인증으로 둔다.
    - `MeController` · `MeResponse` — 역할 · 약관 · 대기 순번을 빼고 `email` · `status` 만 남긴다.
- 테스트의 허용 목록 값(`support/IntegrationTest` 의 `app.auth.admin-emails` 속성 · `AuthPropertiesTest` 등)을 걷어낸다. 테스트에서 사용자를 만드는 곳은 `UserFixtures` 를 새 팩토리에 맞추고, 상태를 바꾸는 도우미(`withStatus` 등)는 픽스처 안의 리플렉션으로 둔다(TEST_GUIDE §4).
- `deploy/` · `.env.example` · `AGENTS.md` 명령어 줄 · `docs/TEST_GUIDE.md` 의 허용 목록 언급은 **step 3 이 걷는다** — 이 step 에서 건드리지 마라. 단 `deploy/DeployFilesTest` 처럼 이 step 의 변경으로 깨지는 검사가 있으면 그 검사만 고친다.

### 4. 테스트

- 마이그레이션 — 실제 Postgres(`@RepositoryTest` 또는 기존 `LoginIdentityMigrationTest` 방식)에서 V8 까지 적용한 DB 에 옛 행(`ACTIVE` 하나 · `PENDING` 하나 · `WAITLISTED` 하나)을 넣고 V9 를 적용해 확인한다: 상태 · `name`(@ 앞부분) · `credit_balance = 100` · 채움 기록 한 줄씩 · `ACTIVE` 였던 행의 `approved_at` · `credit_refill_at` 이 있고 나머지는 비었다 · 알 수 없는 상태 값이면 실패한다. 기존 `LoginIdentityMigrationTest` 가 V9 로 깨지면 그 검사가 확인하던 사실을 유지하도록 고친다.
- `credit_refill_at` 의 새벽 4시 경계 — 경계 양쪽(Asia/Seoul 03:59 · 04:00)의 시각을 식에 넣어 승인한 날이 하루 갈리는지 확인한다. 고정 시각을 넣는 방법(식을 함수 · 값으로 시험 등)은 네가 고른다.
- 제약 — `credit_balance < 0` · `APPROVED` 인데 `credit_refill_at` 이 비거나 `APPLIED` 인데 차 있음 · `credit_entries.type` 의 다른 값이 DB 에서 막힌다(`@RepositoryTest`).
- `User.apply` · `CreditEntry.refill` — 단위 테스트(getter 확인).
- `LoginServiceTest` — 행 없음 → 신청 전 결과 · 행을 만들지 않는다(저장 수 0) · 행 있음 → `Success`(상태 셋 모두) · 미인증 → DB 접근 없음.
- `auth/OAuthLoginHandler*IntegrationTest` — 「첫 로그인이 행을 만든다(역할 `ADMIN` 등)」 검사는 「행이 없으면 만들지 않고 임시 실패 리다이렉트로 간다」로 바꾸고, 로그인 성공 · 세션 · 로그 줄 검사는 행을 미리 넣어(`UserFixtures`) 유지한다. 역할 · 약관 단언은 칸이 없어졌으므로 뺀다.
- 기존 `LoginServiceConcurrencyIntegrationTest`(동시 첫 로그인이 회원 하나만 남긴다)는 로그인이 행을 만들지 않게 되어 의미가 없어진다 — 지운다. 같은 사실(동시 신청 → 행 하나)은 step 2 가 신청 API 에서 다시 시험한다. summary 에 적는다.

### 5. 문서

- `docs/04-jobs-and-cost.md` §10 의 「운영 DB 의 사용자 줄을 신청 흐름으로 옮기는 방법」 줄 — V9 로 정해졌으므로 줄을 지우고, 04 §2.3 에 허용 목록 · 역할을 가리키는 구현 사실이 있으면 지금 코드에 맞춘다.
- 이 step 이 확정한 사실(마이그레이션 파일 이름 · 클래스 이름)만 고친다. 규칙 문장은 고치지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.account.*' --tests '*Migration*' --tests '*SessionUserFilterTest' --tests '*OAuthLoginHandler*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - V9 의 칸 · 제약 · 값 목록이 02 §8.2 · §8.7 과 같은가? `credit_entries.job_id` 가 없는가?
   - 옮기는 행의 크레딧 100 이 SQL 에 숫자로 있고, 채움 기록과 `credit_balance` 가 같은가(02 §8.2 — 합이 같다)?
   - 엔티티가 금지 24 · 27 · 28 을 따르는가? 승인 · 거절 메서드가 없는가?
   - 로그인이 행을 만들지 않는가? 미인증 거절이 DB 접근 전인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/4-account/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — V9 파일 이름 · 엔티티 · 저장소 · 팩토리 시그니처 · `LoginResult` 의 새 모양 · 임시로 둔 곳(핸들러 · 필터 · 「나」) · **옛 로그인 테스트의 검증 사실 → 새 테스트(또는 지운 이유와 다시 시험할 step)** 대응 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 로그인 세션에 구글 신원을 담거나 「나」에 신청 전 상태 · 이름을 더하지 마라. 이유: step 1 의 범위다.
- 신청 API · 승인 전 사람을 막는 보안 규칙 · 한 달 몫 설정(`app.credit.monthly-amount`)을 만들지 마라. 이유: step 2 의 범위다 — 쓰는 곳이 생길 때 만든다.
- 운영자 SQL 파일 · 절차서를 만들지 마라. 이유: step 3 의 범위다.
- `credit_entries.job_id` · `generation_jobs` 를 만들지 마라. 이유: 만드는 작업 TASK 에서 만든다(02 §8 머리말).
- 크레딧 씀 · 복구 · 채우기(새벽 4시) 코드를 만들지 마라. 이유: SCHEDULE §3 상 만드는 작업 · 크레딧 채우기 TASK 에서 만든다.
- 옛 커리큘럼 테이블 · `curriculum` 패키지를 건드리지 마라. 이유: 이 TASK 범위 밖이다(ADR-148).
- 통합 테스트 이름에 `IT` 를 쓰지 마라(`*IntegrationTest`). 이유: TDD 가드는 받지만 ArchUnit 이 빌드를 깬다(TEST_GUIDE §7).
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(02 · 05 · ADR · AGENTS 금지 목록)의 규칙 문장을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
