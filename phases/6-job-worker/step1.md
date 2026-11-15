# Step 1: credit-ledger

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §8.2(`users` · `credit_entries` — 복구 조건 · 잔액과 기록의 합 · 계정 패키지가 다룬다 — **이 step 의 계약이다**) · §8.7(`credit_entries.type`)
- `docs/04-jobs-and-cost.md` §7(크레딧) · §10(종류별 크레딧 값)
- `docs/05-api.md` §1.2(오류 묶음 — 「크레딧 부족」은 다음에 채워지는 날을 알린다 · 문구는 서버가 넣는다)
- `docs/07-testing.md` §1 「크레딧」 줄
- `docs/ADR.md` — ADR-107 · ADR-112 · ADR-113 · ADR-116 · ADR-131 · ADR-153 · ADR-166 · ADR-175
- `docs/TEST_GUIDE.md` §1(DB 동작이 규칙인 서비스는 통합 테스트) · §4 · §5
- `AGENTS.md` — 금지 11 · 17 · 22 · 24 · 25 · 27 · 28
- step 0 이 만든 것: `backend/src/main/resources/db/migration/V11__create_generation_jobs.sql` · `backend/src/main/java/com/mogum/job/domain/` · `backend/src/test/java/com/mogum/job/domain/GenerationJobFixtures.java`
- 지금 계정 코드: `backend/src/main/java/com/mogum/account/domain/User.java` · `CreditEntry.java` · `CreditEntryType.java` · `account/repository/UserRepository.java` · `UserIdentityLock.java` · `UserIdentityLockImpl.java` · `CreditEntryRepository.java` · `account/service/ApplyService.java` · `config/CreditProperties.java`
- 오류 경로: `backend/src/main/java/com/mogum/common/api/ErrorCode.java` · `ApiException.java` · `ApiExceptionHandler.java` · `ApiError.java`
- 본보기 테스트: `backend/src/test/java/com/mogum/account/service/ApplyServiceTest.java` · `account/repository/UserIdentityLockImplIntegrationTest.java` · `account/repository/FakeUserRepository.java` · `FakeCreditEntryRepository.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

크레딧은 계정 패키지(`account`)가 다룬다 — 채움 · 씀 · 복구 모두 `users` 행을 잠그고 바꾸며, 다른 패키지는 계정 서비스를 부른다(02 §8.2). 이 step 은 만드는 작업이 부를 **씀 · 복구**를 계정 서비스로 만든다. 받기(step 2)와 워커(step 4)가 이것을 부른다.

**계약 문서는 이 phase 전에 정해 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 기록 — `CreditEntry`

- `job_id` 를 `Long jobId` 로 매핑한다(작업은 다른 도메인 — 금지 27).
- 팩토리를 더한다 — 예: `CreditEntry.consume(User user, long jobId, int amount)`(기록의 `amount` 는 음수) · `CreditEntry.restore(User user, long jobId, int amount)`(양수). 인자 `amount` 를 양수로 받을지는 정해서 Javadoc 에 적는다. `refill` 은 그대로 둔다.

### 2. 계정 서비스 — `com.mogum.account.service`

이름은 예시다. 컨트롤러가 부르는 일반 서비스라 `@Transactional` 을 쓴다(금지 3). 받기 · 워커는 이 메서드를 **자기 트랜잭션 안에서** 부른다 — 그 트랜잭션에 참여해야 작업 행과 크레딧이 함께 커밋 · 롤백된다.

```java
public class CreditLedger {
    /** users 행을 잠근다 — 받기가 건 수를 세기 전에 부른다. 같은 사람의 요청을 한 줄로 세운다. */
    void lock(long userId);
    /** 잠그고 잔액을 줄이고 CONSUME 기록을 남긴다. 모자라면 「크레딧 부족」. */
    void consume(long userId, long jobId, int amount);
    /** 조건(02 §8.2)이 맞을 때만 잠그고 잔액을 늘리고 RESTORE 기록을 남긴다. 맞지 않으면 아무것도 하지 않는다. */
    void restoreIfOwed(long userId, long jobId);
}
```

- **잠금** — `users` 행 잠금(`SELECT … FOR UPDATE` 등)이다. 서버 메모리 잠금을 쓰지 않는다(금지 22). 이미 있는 `UserIdentityLock`(신원 advisory lock)은 신청용이라 이것과 다르다.
- **씀** — 잔액이 `amount` 보다 작으면 새 오류 code(예: `CREDIT_INSUFFICIENT`)의 `ApiException` 을 던진다. 묶음은 05 §1.2 의 「크레딧 부족」이고, **문구에 다음에 채워지는 날(`credit_refill_at` 의 한국 날짜 — 금지 17)을 서버가 넣는다**. 지금 `ApiException` 은 `ErrorCode` 의 고정 문구만 싣는다 — 경우마다 문구를 넣을 수 있게 `common.api` 를 넓힌다(오류 JSON 을 직접 쓰지 않는다 — 금지 25). 문구는 정해서 05 §1.2 에 적는다.
- **복구 조건**(02 §8.2 · ADR-166 · ADR-175) — 다음이 모두 맞을 때만 그 작업의 마지막 `CONSUME` 의 크기만큼 돌려준다:
    1. 그 작업의 마지막 기록이 `CONSUME` 이다 — 이미 돌려준 작업(실패한 뒤 버리기)은 두 번 돌려주지 않는다.
    2. 그 `CONSUME` 이 그 사람의 마지막 `REFILL` 뒤다 — 지난 주기에 쓴 몫은 돌려주지 않는다.
    - 작업이 성공하지 않았는지는 부르는 쪽이 안다(워커는 실패로 끝낼 때, 버리기는 실패한 작업에만 부른다). 이 메서드는 `job` 패키지를 참조하지 마라 — 계정이 작업에 기대게 된다.
    - **기록의 앞뒤를 `created_at` 만으로 가르지 마라.** 공용 `TestClock` 은 멈춰 있어 같은 테스트의 기록이 모두 같은 시각이고, 운영에서도 같은 순간이 생긴다. 같은 시각에서도 맞게 가르는 순서(예: `id`)를 정해 Javadoc 에 적는다.
- **잔액과 기록** — `users.credit_balance` 는 그 사람 `amount` 의 합과 늘 같다(ADR-153). 둘을 같은 트랜잭션에서 바꾼다. `CHECK (credit_balance >= 0)` 에 기대지 말고 씀이 먼저 막는다.
- 엔티티 `User` 에 잔액을 바꾸는 메서드가 필요하면 더한다(이름 있는 동작으로 — 금지 24 의 정신). setter 를 두지 마라.

### 3. 종류별 크레딧 값 — 설정

- 종류(`JobType`)마다 쓰는 크레딧을 `config.CreditProperties` 한 곳(`application.yml` 의 `app.credit` 아래)에 **임시 값**으로 둔다. 값은 강의 목록 TASK 가 토큰을 재서 정한다(04 §10). 계정 패키지가 `job` 을 참조하지 않게, 값을 고르는 것은 부르는 쪽(step 2)이 하고 `consume` 은 `amount` 를 받는다.
- 정한 설정 이름과 임시 값을 04 §7 · §10 에 적는다.

### 4. 테스트

- **서비스 단위**(Fake) — 잔액 부족 · 씀 · 복구 조건의 세 갈래(마지막이 씀 · 이미 돌려줌 · 마지막 채움 앞의 씀)를 본다. 문구에 들어간 날짜가 `credit_refill_at` 의 한국 날짜인지 본다(새벽 4시 경계 근처 값 하나 — 금지 17).
- **통합**(`@IntegrationTest` — 잠금 · 합이 DB 동작이라 TEST_GUIDE §1)
    - 같은 사람에게 동시에 두 `consume` 이 와도 잔액이 음수가 되지 않고, 잔액 = 기록의 합이다(스레드 둘 · 잔액은 한 번만 쓸 만큼).
    - 같은 작업에 `restoreIfOwed` 를 두 번 불러도 한 번만 돌려준다 — 동시에 불러도 같다.
    - 씀 → 채움 → `restoreIfOwed` 는 돌려주지 않는다.
    - 모두 같은 `TestClock` 시각에서 돈다(시각을 움직이지 않고도 순서가 맞는지 본다).
- 「크레딧 부족」 오류가 공통 오류 응답 경로로 나가는지 — 상태 코드 · code · 문구 — 를 `ApiExceptionHandler` 테스트(또는 넓힌 곳의 짝 테스트)로 본다.

### 5. 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §1.2 — 「크레딧 부족」의 code · 상태 코드 · 문구(예시가 아니라 코드에 둔 그대로).
- `docs/04-jobs-and-cost.md` §7 · §10 — 종류별 크레딧 설정 이름과 임시 값. §2.3 「계정」 줄에 씀 · 복구 서비스와 잠금 방법.
- 규칙 문장은 고치지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.account.*' --tests 'com.mogum.common.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 크레딧을 바꾸는 코드가 `account` 안에만 있는가? `account` 가 `job` · `worker` · `generation` 을 참조하지 않는가?
   - 씀 · 복구가 `users` 행을 잠그고, 잔액과 기록을 같은 트랜잭션에서 바꾸는가?
   - 복구 조건이 02 §8.2 와 같은가? 같은 시각의 기록에서도 맞는가?
   - 오류가 `ApiException` → 공통 오류 응답 경로로 나가는가(금지 25)?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/6-job-worker/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 서비스 이름과 메서드 시그니처 · 잠금 방법 · 복구 순서를 가르는 기준 · 새 오류 code 와 넓힌 `ApiException` 사용법 · 종류별 크레딧 설정 이름과 읽는 방법 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 크레딧 채우기(30일마다 새벽 4시)를 만들지 마라. 이유: SCHEDULE §3 상 홈 · 크레딧 TASK 에서 만든다.
- 「나」 응답에 크레딧을 싣지 마라. 이유: 내 정보 탭 TASK 에서 더한다(05 §3).
- 성공을 기록하는 종류를 더하지 마라. 이유: 성공은 기록하지 않는다(02 §8.2 · ADR-166).
- `account` 에서 `job` 패키지를 참조하지 마라. 이유: 계정이 작업에 기대면 도메인이 묶인다 — 작업 쪽이 계정을 부른다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 크레딧은 DB 기록에서 본다.
- 계약 문서(02 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙 문장을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
