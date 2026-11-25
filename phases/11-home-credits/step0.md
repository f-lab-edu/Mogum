# Step 0: weekly-cycle

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/ADR.md` — **ADR-189**(7일 주기 · 한 주 몫 · 임시 값 25 · 이름) · ADR-110 · ADR-173 · ADR-175
- `docs/02-domain-data.md` §8.2(`users.credit_refill_at` · `credit_entries`) · §8.7(`credit_entries.type`)
- `docs/04-jobs-and-cost.md` §7(크레딧 — 「한 주 몫의 구현 설정」 줄) · §10(「종류별 크레딧 값 · 한 주 몫」 줄)
- `docs/GLOSSARY.md` — 「한 주 몫」 · 쓰지 않는 이름 「한 달 몫」
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `deploy/README.md` — 운영자 SQL 절(「승인은 상태 · 승인 시각 · 다음 채움 시각 …」 문단)
- `deploy/sql/approve-account.sql`
- `backend/src/main/java/com/mogum/config/CreditProperties.java` · `backend/src/main/resources/application.yml`(`app.credit`)
- `backend/src/main/java/com/mogum/account/service/ApplyService.java` · `account/domain/CreditEntryType.java`
- `backend/src/test/java/com/mogum/config/CreditPropertiesTest.java` · `account/service/ApplyServiceTest.java` · `account/controller/ApplyControllerIntegrationTest.java` · `account/repository/UserIdentityLockImplIntegrationTest.java` · `deploy/OperatorSqlIntegrationTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

크레딧 채움 주기를 30일에서 7일로 바꾸고, 한 번에 채우는 양의 이름을 「한 달 몫」에서 「한 주 몫」으로 바꿨다(ADR-189). 이 step 은 이름 · 임시 값 · 운영자 승인 SQL 을 새 계약에 맞춘다. 정해진 때 채우는 일은 다음 step 이 만든다.

**계약 문서는 이 phase 전에 정해 커밋했다(ADR-189 · 02 §8.2 · 04 §7 · §10 · GLOSSARY). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

1. **이름과 임시 값**
   - `app.credit.monthly-amount` → `app.credit.weekly-amount`, 값 `100` → `25`.
   - `CreditProperties.monthlyAmount` → `weeklyAmount`. 이것을 쓰는 곳(`ApplyService` 와 위 테스트들)을 함께 바꾼다. 신청할 때 채우는 양은 한 주 몫이다.
   - `CreditEntryType.REFILL` 의 Javadoc 을 02 §8.7 표 그대로 맞춘다(「한 주 몫으로 다시 맞춘다. 금액이 0 이어도 남긴다 — 주기의 경계다」).
   - 코드 · 테스트 이름 · 주석에 남은 「monthly · 한 달 몫」을 찾아 바꾼다(`grep -rn 'monthly\|한 달 몫' backend/src`). 단, 아래 금지사항의 V9 와 그 시험은 그대로 둔다.
2. **운영자 승인 SQL** — `deploy/sql/approve-account.sql` 의 다음 채움을 「승인한 날(서울 새벽 4시 경계) + **7일** 04:00」으로 바꾼다. 식의 나머지(경계 계산 · `AT TIME ZONE 'Asia/Seoul'`)는 그대로다.
   - `OperatorSqlIntegrationTest` 의 기대값을 7일로 바꾼다. 서울 03:59 · 04:00 경계 시험은 그대로 두고 기대 날짜만 맞춘다.
   - `deploy/README.md` 의 승인 설명 문단에서 「+ 30일」을 「+ 7일」로 고친다. 「V9의 기존 회원 이전과 같은 식이다」는 더 이상 맞지 않으므로 「V9 의 기존 회원 이전은 그때의 30일 식이다(ADR-189 — 고치지 않는다)」처럼 사실대로 고친다.

### 시험

- `CreditPropertiesTest` — `app.credit.weekly-amount` 가 `weeklyAmount()` 로 바인딩된다. 운영 설정(`application.yml`)의 값이 25 다.
- 신청 시험 — 신청하면 잔액과 채움 기록의 금액이 한 주 몫(설정 값)이다.
- `OperatorSqlIntegrationTest` — 승인 뒤 `credit_refill_at` 이 승인한 날 + 7일 서울 04:00 이다(03:59 · 04:00 경계 둘 다).

### 문서 — 구현으로 정해진 사실만

- `docs/04-jobs-and-cost.md` §10 의 종류별 임시 값 표 위아래에 설정 키 · 읽는 메서드 이름이 남아 있으면 새 이름으로 맞춘다. 규칙 문장은 고치지 마라.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.account.*' --tests 'com.mogum.config.*' --tests 'com.mogum.deploy.*' --tests 'com.mogum.auth.*'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `grep -rn 'monthly' backend/src/main` 이 비었는가?
   - 승인 SQL 의 7일과 시험의 기대값이 같은가? V9 와 그 시험을 건드리지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/11-home-credits/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 새 설정 키 · 메서드 이름 · 바꾼 파일 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `backend/src/main/resources/db/migration/V9__migrate_account_schema.sql` 과 `AccountSchemaMigrationTest` 를 고치지 마라. 이유: 적용한 마이그레이션은 고치지 않는다(04 §2.1 · ADR-189). V9 의 숫자 100 · 30일은 그때의 사실이다.
- 새 마이그레이션으로 이미 승인된 계정의 `credit_refill_at` 을 고치지 마라. 이유: ADR-189 — 운영자 한 명뿐이고 첫 채움 뒤로 7일씩 돈다.
- 다른 테스트의 사용자 픽스처가 쓰는 `Duration.ofDays(30)` · `interval '30 days'`(임의의 미래 채움 시각)를 일괄로 바꾸지 마라. 이유: 주기와 상관없는 시험 데이터다 — 바꾸면 이 step 의 diff 만 커진다.
- 정해진 때 채우는 일(스케줄러)을 만들지 마라. 이유: step 1 의 범위다.
- 종류별 크레딧 값(`new-course` 등)을 바꾸지 마라. 이유: 수정 phase `11a` 에서 호출 기록으로 정한다(SCHEDULE §3).
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
