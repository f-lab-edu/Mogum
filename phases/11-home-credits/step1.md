# Step 1: credit-refill

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/04-jobs-and-cost.md` §2(패키지 경계) · §5(서버가 여러 대여도) · §7(크레딧) · **§8(서버가 정해진 때 하는 일 — 이 step 의 계약)** · §9.1(로그) · §10
- `docs/02-domain-data.md` §8.2(`users` · `credit_entries` — 복구 조건) · §8.7
- `docs/ADR.md` — ADR-017 · ADR-110 · ADR-133 · ADR-166 · ADR-173 · ADR-175 · ADR-189
- `docs/07-testing.md` §1(「크레딧 채우기」 줄)
- `docs/TEST_GUIDE.md` 전체(특히 시계 · `@IntegrationTest` · 출력 캡처) · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 17 · 22 · 26 · 28
- step 0 이 만든 것(summary 참고) — `CreditProperties.weeklyAmount()`
- `backend/src/main/java/com/mogum/account/` 전체 — 특히 `service/CreditLedger.java`(잠금 · 복구 조건) · `domain/User.java` · `domain/CreditEntry.java`(`refill`) · `repository/UserRepository.java`(`findByIdForUpdate`)
- `backend/src/main/java/com/mogum/common/time/SeoulClock.java` · `config/ClockConfiguration.java` · `config/WorkerConfiguration.java`(서버가 뜰 때 · 꺼질 때 도는 일의 예)
- `backend/src/test/java/com/mogum/support/IntegrationTestConfiguration.java`(`app.worker.count=0` 을 넣는 방식) · `support/TestClock.java`
- 로그 예: `backend/src/main/java/com/mogum/worker/WorkerLifecycle.java`(반복 실패의 첫 스택만) · `common/logging/`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

첫 크레딧은 신청할 때 넣었다. 그 뒤 채우기는 서버가 매일 새벽 4시에 한다 — 다음에 채우는 때(`credit_refill_at`)가 지난 승인된 사람을 한 주 몫으로 다시 맞추고 그 때를 7일 뒤로 옮긴다(04 §8 · ADR-189). 지금 서버에는 정해진 때 도는 일이 하나도 없다.

**계약 문서는 이 phase 전에 정해 커밋했다(04 §8 · 02 §8.2 · ADR-189). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 채우기 — `account` 패키지

- 「지금 채울 사람을 모두 채운다」 메서드 하나를 둔다(이름 · 클래스는 재량, 예: `account.service.CreditRefillService.refillDue()`). 지금 시각은 `Clock` 빈에서 한 번 읽는다.
- **사람마다 따로 트랜잭션**으로 한다. 한 사람의 트랜잭션 안에서 — 사용자 행을 잠그고(`findByIdForUpdate` 또는 같은 잠금) **잠근 뒤 다시** `status = APPROVED` 이고 `credit_refill_at <= 지금` 인지 확인한다. 아니면 아무것도 하지 않는다. 맞으면 같은 트랜잭션에서 셋을 함께 바꾼다:
  1. 잔액을 한 주 몫으로 맞춘다.
  2. `REFILL` 기록을 남긴다 — 금액은 「한 주 몫 − 이전 잔액」. **0 · 음수여도 반드시 남긴다**(크레딧 복구 조건이 마지막 채움 기록으로 주기를 가른다 — 02 §8.2 · 04 §8).
  3. `credit_refill_at` 을 7일씩 앞으로 옮겨 **지금보다 뒤의 첫 시각**으로 맞춘다. 여러 주기가 밀렸어도 채움은 한 번이다. 7일 더하기는 서울 시간대 기준 날짜로 한다 — 새벽 4시가 유지돼야 한다(금지 17).
- 대상 고르기는 `status = 'APPROVED' AND credit_refill_at <= 지금` 인 사용자 ID 목록이다. 목록을 읽은 뒤 사람마다 위 트랜잭션을 연다 — 목록을 읽는 트랜잭션이 행을 쥐고 있지 마라.
- 엔티티에 바꾸는 메서드가 필요하면 `User` 에 이름 있는 메서드로 더한다(예: `refillCredits(int weeklyAmount, Instant nextRefillAt)`). 반환으로 바뀐 금액을 받든 재량이다.
- 다른 패키지에서 크레딧을 바꾸지 않는다 — 계정 패키지만 다룬다(02 §8.2).

### 돌리는 때 — 여러 서버에서도 사람마다 한 번

- 매일 **새벽 4시, 시간대 `Asia/Seoul`** 에 돈다(예: Spring `@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")` + `@EnableScheduling`). 서버 기본 시간대에 기대지 마라(금지 17).
- **서버가 뜰 때도 한 번** 돈다(예: `ApplicationReadyEvent`) — 새벽 4시에 서버가 꺼져 있었으면 켜진 뒤 따라잡는다.
- 한 번만 일어나는 것은 위의 「잠근 뒤 다시 확인」이 지킨다. 서버 메모리의 잠금 · 표시 · 「마지막으로 돈 때」를 두지 마라(금지 22). 따로 된 잠금 라이브러리(ShedLock 등)를 더하지 마라 — 사람마다 조건 확인으로 충분하다.
- 트리거를 둘 곳은 재량이다(`config` 의 설정 클래스가 `account` 서비스를 부르는 식 등). `ArchitectureTest` 를 지킨다.
- **테스트에서는 저절로 돌지 않게 한다** — 설정 값 하나(예: `app.credit.refill-enabled`, 운영 기본 `true`)를 두고 공용 테스트 설정(`IntegrationTestConfiguration` 이 `app.worker.count=0` 을 넣는 곳)에서 끈다. 시험은 채우기 메서드를 직접 부른다. 클래스마다 설정을 더하지 마라(TEST_GUIDE — 컨텍스트가 클래스마다 새로 뜬다).

### 로그 — 이 step 에서 남길 줄

- 정상 채움은 줄을 남기지 않는다 — `credit_entries` 에 남는다(금지 26 · 04 §9.1).
- 한 사람의 채움이 예상 밖 예외로 실패하면 `ERROR` 의 `credit refill failed`, MDC `userId`(`common.logging` 의 키 상수)를 넣고 끝나면 지운다. 그 사람은 건너뛰고 다음 사람을 계속한다 — 다음 실행(다음 날 · 다음 기동)이 다시 시도한다.
- 한 실행 안에서 스택은 **첫 실패에만** 싣고 다음 실패는 같은 한 줄만 남긴다(금지 26 — 반복 루프). 스택에는 원래 메시지 · cause · suppressed 없이 예외 타입 이름과 위치만 싣는다 — `WorkerLifecycle` 의 방식을 따른다.
- 대상 목록 읽기 자체가 실패하면 `ERROR` 의 `credit refill failed` 한 줄(스택 포함, `userId` 없음)로 그 실행을 끝낸다. 예외가 스케줄러 · 기동 이벤트 밖으로 새어 서버 기동을 막지 않게 한다.

### 시험

- 서비스 단위(`Clock.fixed`) 또는 통합(`TestClock`) 으로 — 쓰기 규칙은 실제 DB 에서 확인한다(`@IntegrationTest` · TEST_GUIDE §1):
  - 때가 지난 승인된 사람 → 잔액 = 한 주 몫 · `REFILL` 기록 하나(금액 = 몫 − 이전 잔액) · `credit_refill_at` = 이전 + 7일(같은 서울 04:00).
  - 잔액이 이미 한 주 몫 → 금액 0 인 `REFILL` 기록이 남는다.
  - 잔액이 한 주 몫보다 많다(몫을 낮춘 경우) → 음수 금액 기록 · 잔액 = 몫.
  - 세 주기가 밀렸다(때가 22일 전) → 채움 기록 하나 · `credit_refill_at` 은 지금 뒤의 첫 `이전 + 7k`.
  - 때가 아직이다 · `APPLIED` · `REJECTED` → 바뀌지 않는다.
  - 서울 03:59 와 04:00 경계 — 때가 04:00 인 사람은 03:59 에 채우지 않고 04:00 에 채운다.
  - **두 번 부르면 두 번째는 아무것도 바꾸지 않는다.** 두 스레드가 동시에 부르면 채움 기록은 하나다(실제 DB · 행 잠금).
  - `users.credit_balance` 가 그 사용자 `credit_entries.amount` 합과 같다(02 §8.2).
  - 채움 뒤 실패한 작업의 복구: 채움 **전**에 쓴 작업은 복구하지 않는다(`CreditLedger.restoreIfOwed` 와 함께 — ADR-166).
  - `updated_at` 이 바뀐다(금지 28 — 네이티브 SQL 로 바꾸면 SQL 에 직접 쓴다).
- 설정 · 트리거:
  - 공용 테스트 설정에서 자동 실행이 꺼져 있다 — 컨텍스트가 뜬 뒤 때가 지난 사용자가 그대로다.
  - cron 식과 시간대가 `0 0 4 * * *` · `Asia/Seoul` 이다(어노테이션 값이나 등록된 작업을 읽어 확인 — 실제 새벽 4시를 기다리지 마라).
  - 켜면 기동 때 한 번 돈다(짧은 컨텍스트 · `ApplicationContextRunner` 등 재량).
- 로그(`OutputCaptureExtension`):
  - 한 사람의 실패 → `credit refill failed` 와 `userId` 가 찍히고 다른 사람은 채워진다.
  - 두 사람이 실패 → 스택은 한 번.
  - 정상 채움은 `INFO` 이상 줄이 없다.

### 문서 — 구현으로 정해진 사실만

- `docs/04-jobs-and-cost.md` §8 끝에 구현 줄을 더한다 — 클래스 · 메서드, 트리거(cron · 기동), 끄는 설정 키, 사람마다 트랜잭션과 잠금, 실제 캡처한 로그 줄(테스트가 찍은 줄을 옮긴다 — 시각 · PID · 스레드는 실행마다 다르다고 적는다).
- `docs/04-jobs-and-cost.md` §9.1 에 새 로그 줄(수준 · 언제 · 키)을 더한다.
- `docs/04-jobs-and-cost.md` §10 의 「크레딧 채우기를 여러 서버에서 한 번만 · 따라잡는 방법」 줄을 정한 방법으로 바꾼다.
- `docs/07-testing.md` §1 「크레딧 채우기」 줄의 근거에 시험 클래스 이름을 더한다(규칙 문장은 그대로).

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.account.*' --tests 'com.mogum.config.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 잠근 **뒤** 조건을 다시 확인하는가? 금액 0 · 음수에도 기록을 남기는가? 밀린 주기에서 채움은 한 번인가?
   - 서버 메모리 상태 · 새 잠금 라이브러리 없이 한 번만 일어나는가(금지 22)?
   - cron 시간대가 `Asia/Seoul` 인가? 공용 테스트 설정에서 꺼지는가?
   - 로그 줄이 작업에 적은 것뿐이고 원문 · 이메일이 없는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/11-home-credits/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 클래스 · 메서드 · 트리거 · 끄는 설정 키 · 로그 줄 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 테이블 · 칸을 더하지 마라(「마지막으로 돈 때」 등). 이유: 02 §8 에 없다 — 조건은 `credit_refill_at` 하나로 고른다(ADR-175).
- 금액이 0 이라고 `REFILL` 기록을 건너뛰지 마라. 이유: 복구 조건이 마지막 채움 기록으로 주기를 가른다 — 빠지면 지난 주기에 쓴 크레딧이 복구된다(ADR-166).
- 밀린 주기마다 채움을 여러 번 하지 마라. 이유: 04 §8 — 한 번 채우고 때만 옮긴다.
- 「나」 응답 · 화면을 고치지 마라. 이유: step 2 · 7 의 범위다.
- 크레딧 값(한 주 몫 · 종류별)을 바꾸지 마라. 이유: 수정 phase `11a` 에서 정한다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
