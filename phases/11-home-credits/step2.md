# Step 2: me-credits

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/05-api.md` §1(넓힐 수 있게 둔다) · §1.4 · §3(「나」 줄과 그 아래 「「나」의 크레딧 … 내 정보 탭 TASK 에서 더한다」 줄)
- `docs/06-screens.md` §2.6 · §13(내 정보 탭)
- `docs/02-domain-data.md` §8.2(`credit_balance` · `credit_refill_at` 은 `APPROVED` 일 때만)
- `docs/ADR.md` — ADR-017 · ADR-089 · ADR-113 · ADR-131 · ADR-189
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 17 · 20 · 23 · 25
- `backend/src/main/java/com/mogum/account/controller/MeController.java` · `account/dto/MeResponse.java` · `auth/AccountPrincipal.java` · `auth/SessionUserFilter.java`
- `backend/src/main/java/com/mogum/account/service/CreditLedger.java`(크레딧 부족 문구가 날짜를 만드는 방식) · `common/time/SeoulClock.java`
- `backend/src/test/java/com/mogum/account/controller/MeControllerTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

내 정보 탭이 남은 크레딧과 다음에 채워지는 날을 보인다(06 §13 · ADR-113). 크레딧은 「나」 읽기에서만 싣는다(05 §1.4). 계정 TASK 의 「나」는 상태 · 이메일 · 이름만 싣고, 크레딧은 이 TASK 에서 더하기로 했다(05 §3).

**계약 문서는 이 phase 전에 정해 커밋했다(05 §1.4 · §3 · 06 §13). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

- `GET /api/me` 의 `data` 에 크레딧 객체 하나를 더한다 — 이름 예: `credit: {balance, nextRefillDate}`. 객체로 두는 이유: 나중에 충전 몫을 더하면 칸 하나만 는다(05 §1 · ADR-131).
  - `balance` — `users.credit_balance` 정수.
  - `nextRefillDate` — `users.credit_refill_at` 을 `Asia/Seoul` 로 바꾼 **한국 날짜 문자열 `yyyy-MM-dd`**. 크레딧 부족 문구(`CreditLedger`)가 쓰는 날짜와 같은 값이어야 한다 — 변환을 한 곳에 두고 함께 쓴다. 시각(Instant)으로 보내지 마라 — 브라우저 시간대에 따라 날짜가 바뀐다(금지 17).
  - **`APPROVED` 일 때만** 싣고, 그 밖(신청 전 · 신청 · 거절됨)은 칸을 생략한다(비어 있는 선택 칸 생략 — 05 §3). 신청 · 거절됨 사람에게도 잔액은 있지만 쓸 수 없다(ADR-173).
- 크레딧은 **요청마다 `users` 에서 읽는다** — 세션에 넣지 마라(금지 23). 읽기는 `account` 서비스에 두고 컨트롤러는 부르기만 한다. 읽기는 아무것도 바꾸지 않는다.
- 신청(`POST /api/account/apply`)의 응답은 「나」와 같은 모양이다(05 §4) — 신청 직후 상태는 `APPLIED` 라 크레딧 칸은 생략된다. 두 응답이 같은 DTO 를 쓰면 같은 규칙이 되게 둔다.
- 사용자 ID · 크레딧 기록 · 한 주 몫 · 종류별 값은 싣지 마라(금지 20 · ADR-113 — 「남은 크레딧과 다음에 채워지는 날」만).

### 시험

- 컨트롤러(`@WebMvcTest`) — `APPROVED` 면 `data.credit.balance` 가 정수, `data.credit.nextRefillDate` 가 `yyyy-MM-dd` · 그 밖 상태에서는 `credit` 키가 없다.
- 통합(실제 DB) — 승인된 사람의 잔액 · 날짜가 DB 값이다 · 날짜 경계: `credit_refill_at` 이 서울 04:00(= UTC 전날 19:00)이면 서울 날짜가 나온다(UTC 날짜가 아니다) · 크레딧을 쓴 뒤 다시 읽으면 바뀐 잔액이 보인다(세션에 담지 않았다).
- 크레딧 부족 문구의 날짜와 「나」의 날짜가 같은 변환을 쓴다(같은 사용자로 둘을 비교하거나, 변환 하나를 시험).

### 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §3 — 「계정 TASK 구현: 「나」는 `GET /api/me` …」 줄에 크레딧 칸(이름 · 모양 · 싣는 조건)을 더하고, 「「나」의 크레딧은 내 정보 탭 TASK 에서 더한다」 줄을 구현한 사실로 바꾼다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.account.*' --tests 'com.mogum.auth.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 크레딧이 객체이고 `APPROVED` 일 때만 있는가? 날짜가 서울 날짜 문자열인가?
   - 세션에 크레딧을 넣지 않았는가(금지 23)? 시스템 정보가 응답에 없는가(금지 20)?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/11-home-credits/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 응답 칸 이름 · 모양 · 싣는 조건(step 7 화면이 쓴다) · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 화면(`frontend/`)을 고치지 마라. 이유: step 7 의 범위다.
- 크레딧을 다른 응답(홈 · 코스 · 작업)에 싣지 마라. 이유: 05 §1.4 · ADR-113 — 「나」에서만.
- 탈퇴 API 를 만들지 마라. 이유: SCHEDULE §3 상 12 지우기 · 탈퇴 TASK 에서 만든다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 접근 줄이 요청을 남긴다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
