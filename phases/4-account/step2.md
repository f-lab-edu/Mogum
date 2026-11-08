# Step 2: apply-and-guard

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/05-api.md` §1.1(**승인되지 않은 사람은 「나」 읽기 · 신청 · 로그아웃만**) · §1.2(오류 묶음) · §1.4 · §4(신청 줄과 그 아래 **「신청은 한 사람에 한 번이다」** 줄) — **이 step 의 계약이다**
- `docs/02-domain-data.md` §8.2(**첫 크레딧은 신청할 때** · `credit_balance` 와 기록의 합이 같다) · §8.7
- `docs/ADR.md` — ADR-055 · ADR-102(MVP 는 드문 경우를 넘어간다) · ADR-104 · ADR-133(서버가 여러 대여도) · ADR-145(오류 code 와 묶음) · ADR-154 · ADR-173
- `docs/04-jobs-and-cost.md` §7(크레딧) · §9.1(로그)
- `AGENTS.md` — 금지 3(`@Transactional` 은 일반 서비스에) · 11 · 22(서버 메모리 잠금 금지) · 23 · 25(`ApiResponse` · `ApiException` · 보안 경로는 `ApiErrorResponses.write`) · 26
- `docs/TEST_GUIDE.md` §1(보안 필터 · 동시성은 실제 HTTP · 실제 DB) · §4(픽스처 · 리플렉션은 픽스처 안에서만) · §5
- step 0 · 1 이 만든 것: `account/domain/User.java` · `CreditEntry.java` · `CreditEntryType.java` · `account/repository/` · `account/service/LoginService.java` · `auth/OAuthLoginHandler.java` · `auth/SessionUserFilter.java` · `account/controller/MeController.java` · `account/dto/` · `support/TestSessions.java` · `account/domain/UserFixtures.java`
- 지금 코드: `auth/SecurityConfiguration.java` · `common/api/ErrorCode.java` · `common/api/ApiErrorResponses.java` · `support/IntegrationTestConfiguration.java`(`/api/test-*` 컨트롤러들)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`4-account`)는 계정을 신청 · 승인 · 거절됨 흐름으로 바꾼다. step 1 까지 로그인은 행이 없는 사람에게 구글 신원 세션을 주고, 「나」가 `NOT_APPLIED` · `APPLIED` · `APPROVED` · `REJECTED` 를 돌려준다.

이 step 은 **신청 API** 와 **승인 전 사람을 기본으로 막는 보안 규칙**을 만든다.

**계약 문서는 phase 전에 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 한 달 몫 설정

- `config/CreditProperties`(`@ConfigurationProperties("app.credit")`) — `monthlyAmount` 하나. `application.yml` 에 `app.credit.monthly-amount: 100`. **한 달 몫은 이 한 곳에만 둔다**(04 §10 — 강의 목록 TASK 에서 토큰을 잰 뒤 정한다). 테스트도 이 값을 읽는다.

### 2. 신청 API

- 경로 · 메서드는 네가 정한다(05 §1 — 리소스 주소 · 바꾸는 동작은 보내기). 요청 본문은 없다(06 §3 — 입력 칸 없음). 응답은 「나」와 같은 모양이다(`ApiResponse` 로 감싼다 — 금지 25).
- **구글 신원 세션(신청 전)**
    - 한 트랜잭션에서 `User.apply(...)` 로 `APPLIED` 행을 만들고, `credit_balance` 를 한 달 몫으로 채우고, 채움(`REFILL`) 기록 한 줄을 남긴다(02 §8.2 — 셋은 같은 트랜잭션).
    - 그 뒤 세션 ID 를 바꾸고(`changeSessionId`), 구글 신원 속성을 지우고, `PRINCIPAL_NAME_INDEX_NAME` 에 사용자 ID 만 둔다(ADR-154 — 로그인 성공과 같은 방식).
- **이미 행이 있는 사람이 신청하면**(사용자 ID 세션 — 상태와 상관없이) 아무것도 바꾸지 않고 지금의 「나」를 성공으로 돌려준다(05 §4).
- **같은 구글 계정의 구글 신원 세션 둘이 동시에 신청해도 사용자 행 · 채움 기록은 하나이고, 둘 다 성공한다**(05 §4). 막는 방법은 DB 로 네가 고른다 — `(provider, subject)` unique 제약이 이미 있다. 서버 메모리의 잠금 · `synchronized` 로 막지 마라(금지 11 · 22). unique 위반으로 트랜잭션이 깨진 쪽이 상대가 만든 행을 다시 읽는다면 새 트랜잭션에서 읽어야 한다는 점을 챙긴다.
- **세션 하나로 동시에 보낸 두 요청 가운데 늦은 쪽의 응답은 정하지 않는다**(05 §4 — 세션 ID 를 바꾸기 때문이다). 이 경우를 시험으로 고정하지 마라. 시험은 「행 · 기록 하나」만 본다.
- 로그 줄을 남기지 않는다 — 신청한 때는 `users.created_at` 이 담는다(금지 26 · 04 §9.1).

### 3. 승인 전 사람 막기

- 승인되지 않은 사람(신청 전 · `APPLIED` · `REJECTED`)은 **`GET /api/me` · 신청 · `POST /api/auth/logout` 만** 쓴다. 나머지 `/api/**` 는 `APPROVED` 만 쓴다(05 §1.1). **보안 설정 한 곳에서 기본으로 막는다** — 뒤에 생기는 API 가 따로 막지 않아도 되게, 허용하는 쪽을 목록으로 둔다.
- 막힐 때의 오류 — 새 code `ACCOUNT_NOT_APPROVED` · `403` 을 `ErrorCode` 에 더한다. 문구는 기존 `ErrorCode` 문구의 말투에 맞춰 네가 쓴다(시스템 정보 없이 — 05 §1.4). 보안 경로이므로 `ApiErrorResponses.write` 로 쓴다(금지 25 — 필터에서 `ApiException` 을 던지지 마라).
- 로그인하지 않은 요청은 지금처럼 `401 UNAUTHORIZED` · CSRF 실패는 지금처럼 `CSRF_INVALID` 다. 순서가 바뀌지 않는지 시험한다.
- **이 규칙은 실제 HTTP(`@IntegrationTest`)로 시험한다**(TEST_GUIDE §1) — 상태 넷(신청 전 · `APPLIED` · `REJECTED` · `APPROVED`)마다 허용 셋은 통과하고, 그 밖의 `/api/**`(공용 설정의 `/api/test-*` 컨트롤러 등)는 승인 전이면 `403 ACCOUNT_NOT_APPROVED` · 승인이면 통과.

### 4. 기존 테스트 · 픽스처

- `/api/test-*` 같은 다른 `/api/**` 를 세션으로 부르는 기존 통합 테스트는 이 규칙으로 막힌다 — **규칙을 무르지 말고**, 승인된 사용자를 만드는 픽스처 도우미(예: `UserFixtures.approved(...)`)를 더해 그 테스트들이 쓰게 한다. `APPROVED` 는 `approved_at` · `credit_refill_at` 이 함께 차야 DB `CHECK` 를 지난다(02 §8.2). 상태를 바꾸는 것은 픽스처 안의 리플렉션 또는 SQL 로 한다.

### 5. 시험

- 신청 서비스 — 단위(Fake): 신청 전 → 행 하나 · `APPLIED` · `credit_balance` = 한 달 몫 · 채움 기록 하나(`amount` = 한 달 몫) · 이미 있는 사람 → 아무것도 바뀌지 않음.
- 통합(실제 HTTP · 실제 DB):
    - 신청 → 응답 `APPLIED` · 세션 ID 가 바뀌었다 · 세션에 사용자 ID 만 있고 주인 색인이 그 ID 다 · 구글 신원 속성이 없다 · 다음 「나」가 `APPLIED`.
    - 신청 두 번(순서대로) → 둘 다 성공 · 행 · 기록 하나.
    - 동시 신청 — 같은 구글 신원의 세션 둘이 함께 신청 → 둘 다 성공 · 행 · 기록 하나 · `credit_balance` 와 기록 합이 같다. 두 요청이 함께 지나가게 하는 방식은 step 0 이 지운 `LoginServiceConcurrencyIntegrationTest`(`git show main:backend/src/test/java/com/mogum/account/service/LoginServiceConcurrencyIntegrationTest.java` — `CyclicBarrier` · 실제 저장소를 감싼 대역)를 참고해 네가 고른다. 컨텍스트 설정(`@MockitoSpyBean` 등)을 더하지 마라(TEST_GUIDE §5).
    - CSRF 토큰 없는 신청 → `CSRF_INVALID`.
- 로그 — 신청이 `INFO` 이상 줄을 더하지 않고, 이메일 · 이름 표식 문자열이 로그에 없는지 출력 캡처로 확인한다.

### 6. 문서

- `docs/05-api.md` §4 — 신청의 경로 · 메서드 · 응답 칸을 구현 사실로 적는다. §1.2 — 「나머지 묶음은 TASK 에서 맞춘다」 아래에 `ACCOUNT_NOT_APPROVED` (403 · 언제 · 화면이 할 일 — 「나」를 다시 읽어 상태 화면으로 간다)를 구현 사실로 더한다. 규칙 문장은 고치지 않는다.
- `docs/04-jobs-and-cost.md` §7 — 한 달 몫 설정의 위치(`app.credit.monthly-amount`)를 구현 사실로 적는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.account.*' --tests '*SecurityConfiguration*' --tests '*OAuthLoginHandler*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 행 · `credit_balance` · 채움 기록이 한 트랜잭션에서 함께 생기는가? 동시 신청이 실제 DB 에서 행 · 기록 하나인가?
   - 승인 전 사람을 막는 규칙이 보안 설정 한 곳에 있고, 허용하는 쪽이 목록인가? 실제 HTTP 로 상태 넷을 시험했는가?
   - 막힘 오류가 `ApiErrorResponses.write` 경로로 공통 모양인가? 401 · CSRF 순서가 그대로인가?
   - 한 달 몫이 설정 한 곳에만 있는가? 서버 메모리 잠금이 없는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/4-account/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 신청 경로 · 서비스 · 동시 신청을 막는 방법 · 허용 목록이 있는 곳 · 새 오류 code · 픽스처 도우미 이름 · 바꾼 기존 테스트 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 승인 전 사람을 막는 검사를 컨트롤러마다 두지 마라. 이유: 05 §1.1 — 기본으로 막아야 뒤에 생기는 API 가 빠뜨려도 닫힌다.
- 기존 테스트를 통과시키려고 막는 규칙을 무르거나 허용 목록에 `/api/test-*` 를 더하지 마라. 이유: 규칙을 시험하는 테스트가 규칙을 우회하게 된다 — 승인된 픽스처를 쓴다.
- 신청에 입력 칸 · 동의 칸을 받지 마라. 이유: ADR-092 · 06 §3.
- `User` 에 승인 · 거절 메서드를 더하지 마라. 이유: 승인 · 거절은 운영자 SQL 만 한다(ADR-060 · step 3).
- 「나」에 크레딧 칸을 넣지 마라. 이유: 05 §3 — 내 정보 탭 TASK 에서 더한다.
- 탈퇴 API 를 만들지 마라. 이유: SCHEDULE §3 상 TASK 12 에서 만든다.
- 동시성을 서버 메모리의 잠금으로 막지 마라. 이유: 금지 11 · 22 — 서버가 여러 대면 깨진다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 화면(`frontend/`) · `deploy/` 를 고치지 마라. 이유: step 3 ~ 5 의 범위다.
- 통합 테스트 이름에 `IT` 를 쓰지 마라(`*IntegrationTest`). 이유: ArchUnit 이 빌드를 깬다(TEST_GUIDE §7).
- 계약 문서(02 · 05 의 규칙 문장 · ADR · AGENTS 금지 목록)를 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
