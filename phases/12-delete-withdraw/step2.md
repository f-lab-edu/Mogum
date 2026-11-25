# Step 2: account-withdraw

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/01-overview.md` §4(정원제 · 탈퇴는 승인된 사람만 · 탈퇴한 사람이 다시 오면 처음 온 사람)
- `docs/02-domain-data.md` §7(「탈퇴할 때」 칸) · §8.1(`ON DELETE SET NULL` 연결 넷) · §8.2 · §8.6(「탈퇴」 순서 · 「지우기 TASK 에서 정한 것」 ③ — 탈퇴는 복구하지 않는다)
- `docs/03-generation.md` §6(호출 기록 — 탈퇴 뒤에도 남긴다)
- `docs/05-api.md` §1.1(세션에는 사용자 id 만 · 승인되지 않은 사람이 쓰는 API) · §1.2(`ACCOUNT_NOT_APPROVED`) · §4(「탈퇴」 · step 0 · 1 이 적은 지우기 구현 줄)
- `docs/06-screens.md` §13
- `docs/07-testing.md` §1(「지운 뒤의 늦은 결과」 · 「지우기 범위」)
- `docs/ADR.md` — ADR-059 · ADR-083 · ADR-084 · ADR-139 · ADR-140 · ADR-154 · ADR-156
- `docs/TEST_GUIDE.md` 전체(특히 실제 포트 통합 시험 · 요청 도구) · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 9 · 14 · 19 · 22 · 23 · 25 · 26 · 28
- step 0 · 1 이 만든 것(summary 참고) — 코스 · 로드맵 지우기와 잠그는 순서
- `backend/src/main/java/com/mogum/account/` 전체 — 특히 `controller/ApplyController.java` · `service/ApplyService.java`(신청할 때 세션을 바꾸는 방법) · `repository/UserRepository.java`(`findByIdForUpdate`) · `repository/UserIdentityLockImpl.java`(`account` 안의 네이티브 SQL 예)
- `backend/src/main/java/com/mogum/auth/SecurityConfiguration.java`(승인되지 않은 사람의 기본 막기 · 로그아웃이 세션을 끝내는 방법) · `backend/src/main/java/com/mogum/auth/SessionUserFilter.java`(사용자 행이 없으면 세션을 끝내고 401) · `backend/src/main/java/com/mogum/auth/OAuthLoginHandler.java`(세션의 `PRINCIPAL_NAME` 에 사용자 id 를 넣는 곳)
- `backend/src/main/resources/db/migration/V3__create_spring_session.sql`(`SPRING_SESSION.PRINCIPAL_NAME` · 속성 테이블의 `ON DELETE CASCADE`)
- `backend/src/main/java/com/mogum/generation/call/LlmCallWriter.java` · `backend/src/main/java/com/mogum/worker/JobRunner.java`
- `backend/src/test/java/com/mogum/support/TestSessions.java` · `TestRequests.java` · `IntegrationTest.java`
- `backend/src/test/java/com/mogum/ArchitectureTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

승인된 사람이 내 정보 탭에서 탈퇴한다(ADR-059). 그 사람의 것을 모두 지우고, 호출 기록만 작업 · 사람과의 연결을 끊어 남긴다(02 §7 · ADR-139). 탈퇴한 계정을 기억해 두지 않는다 — 같은 구글 계정으로 다시 오면 처음 온 사람처럼 신청하고 승인받는다(ADR-084).

**계약 문서는 이 phase 전에 정해 커밋했다. 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. API — `account` 패키지

- `POST /api/account/withdraw` — 본문 없음. 성공하면 **`204` · 본문 없음**.
- **승인된 사람만** 쓴다 — 지금 보안 설정이 `/api/**` 의 나머지를 승인된 사람에게만 열고 신청 전 · 신청 · 거절됨에는 `ACCOUNT_NOT_APPROVED`(403)를 준다. 이 경로를 예외 목록에 넣지 마라. 실제 HTTP 시험으로 확인만 한다.
- 성공하면 **이 요청의 세션을 끝낸다**(로그아웃과 같게 — 응답이 세션 쿠키를 지우는지 실제 HTTP 로 확인한다).

### 2. 지우기 — 한 트랜잭션, 02 §8.6 「탈퇴」 순서

- 잠그는 순서는 step 0 · 1 과 같다(02 §8.6 ③): **그 사람의 모든 `generation_jobs` 행을 `FOR UPDATE` 로 먼저 잡고, 그다음 `users` 행을 잡는다.** 사용자 행을 먼저 잡지 마라. 이유: 작업 행을 쥔 채 사용자 행을 기다리는 워커와 서로를 기다린다.
- 그 뒤 02 §8.6 순서대로 지운다 — `generation_jobs` → `reviews` → `card_concepts` → `cards` → `materials` → `lessons` → `courses` → `roadmaps` → `concepts` → `credit_entries` → 로그인 세션 → `users`. `llm_calls` 의 `job_id` · `user_id` 는 `ON DELETE SET NULL` 로 저절로 빈다 — `llm_calls` 행을 지우지 마라(ADR-139).
- **크레딧을 복구하지 마라.** 이유: 크레딧 · 크레딧 기록을 함께 지운다(02 §8.6 ③).
- **어디에 두나** — `course` · `card` · `job` 이 이미 `account`(`CreditLedger` 등)를 참조한다. `account` 가 그 패키지의 Java 타입을 부르면 의존이 순환한다. 탈퇴는 `account` 안에서 **네이티브 SQL**(`EntityManager` — `UserIdentityLockImpl` 과 같은 짜임)로 테이블을 사용자 ID 로 지운다. `ArchitectureTest` 에 「`account` 는 `course` · `card` · `job` · `generation` · `worker` 를 참조하지 않는다」 규칙을 먼저 더하고(실패하는 짝 시험을 함께 — 기존 규칙들과 같은 방식) 지킨다.
- **로그인 세션** — 그 사람의 모든 세션(`SPRING_SESSION` 에서 `PRINCIPAL_NAME` 이 그 사용자 ID 인 행 — 다른 기기 것 포함)을 지운다. 속성은 `ON DELETE CASCADE` 로 함께 지워진다. **이 요청의 세션**은 SQL 로만 지우면 요청 끝에 Spring Session 이 그 세션을 다시 저장할 수 있다 — 지금 세션을 끝내는 방법(`invalidate` 등)을 쓰고, 응답 뒤 DB 에 그 사용자의 세션 행이 하나도 없는지 시험으로 확인한다.
- 서버 메모리 잠금을 쓰지 마라(금지 22). 탈퇴와 같은 순간에 다른 기기에서 온 받기 · 평가 같은 경합은 막지 않는다(ADR-102) — 어느 쪽이든 실패하면 트랜잭션이 되돌려져 데이터가 어긋나지 않으면 된다.

### 3. 시험

- 컨트롤러(`@WebMvcTest`) — 경로 · `204`.
- 아키텍처 — 위 새 규칙과 그 짝 시험.
- 실제 HTTP 통합(`@IntegrationTest` — 실제 포트 · `TestSessions` · `TestRequests`). 이유: 보안 필터 · 세션 · 쿠키는 MockMvc 로 확인할 수 없다(1-auth 교훈):
    - **지우기 범위**(07 §1) — 단일 코스 · 로드맵 · 강의 · 강의 자료 · 카드(쓰는 중 · 코스 지워짐) · 복습 기록 · 개념 · 작업(모든 상태) · 크레딧 기록 · 세션 둘(다른 기기)이 있는 승인된 사람이 탈퇴하면: 그 사람의 행이 위 테이블 어디에도 없다 · `users` 행이 없다 · 그 사람의 호출 기록은 남고 `job_id` · `user_id` 가 빈 값 · 다른 사람의 것은 모두 그대로.
    - 탈퇴 응답 뒤 같은 쿠키로 `GET /api/me` 는 `401`, 다른 기기 세션 쿠키도 `401`, DB 의 `SPRING_SESSION` 에 그 사용자 ID 의 행이 없다.
    - 신청 전 · 신청(`APPLIED`) · 거절됨(`REJECTED`)인 사람의 탈퇴는 `ACCOUNT_NOT_APPROVED`(403)이고 아무것도 지우지 않는다. 로그인하지 않은 요청은 `401`.
    - **다시 오면 처음 온 사람**(ADR-084) — 탈퇴한 구글 계정으로 다시 로그인하면 신청 전이고, 신청하면 새 `users` 행 · 새 채움 기록이 생긴다(이전 크레딧 · 기록은 없다).
- 통합(워커):
    - **지운 뒤의 늦은 결과**(07 §1) — 그 사람의 강의 자료 작업이 만드는 중일 때 탈퇴한 뒤 워커가 결과를 저장하려 하면 아무것도 쓰지 않고 멈춘다 · 그 시도의 호출 기록은 `user_id` · `job_id` 빈 값으로 남는다 · 탈퇴 뒤 시작한 호출 기록 쓰기도 실패하지 않는다.

### 4. 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §4 — 「탈퇴」 구현 줄(경로 · `204` · 승인된 사람만 · 세션을 끝낸다 · 다른 기기 세션도 지운다). §5 첫 줄의 「…에서 정했다」 목록에 이 step 을 더한다.
- `docs/02-domain-data.md` §8.6 — 「지우기 TASK 에서 정한 것」 아래에 탈퇴 구현 사실(클래스 · 세션을 지우는 방법) 한 줄. 규칙 문장은 고치지 않는다.
- `docs/07-testing.md` §1 — 앞 step 들이 더한 시험 클래스 옆에 이 step 의 시험 클래스를 더한다.
- `docs/04-jobs-and-cost.md` §2.2 — `account` 가 다른 도메인을 참조하지 않는다는 새 ArchUnit 규칙 한 줄.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.account.*' --tests 'com.mogum.auth.*' --tests 'com.mogum.worker.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 작업 행 → 사용자 행 순서로 잡는가? 02 §8.6 「탈퇴」 순서로 지우는가?
   - 호출 기록을 지우지 않고, 크레딧을 복구하지 않는가?
   - 이 요청의 세션과 다른 기기 세션이 모두 사라지는가(DB 로 확인)?
   - 승인되지 않은 사람이 탈퇴할 수 없는가? 보안 설정의 예외 목록을 넓히지 않았는가?
   - `account` 가 `course` · `card` · `job` · `generation` · `worker` 를 참조하지 않는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/12-delete-withdraw/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 경로 · 응답 · 응답 쿠키 · 승인 전 거절 code · 세션을 지우는 방법 · 새 ArchUnit 규칙 이름 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 탈퇴한 계정 · 이메일 · 구글 신원을 따로 기억해 두지 마라(표시 · 다른 테이블 · 로그 포함). 이유: ADR-084 — 탈퇴하면 아무것도 남지 않는다.
- 승인 전 사람이 탈퇴할 길을 만들지 마라. 이유: ADR-059 — 운영자에게 요청해 지운다.
- 탈퇴 완료 화면 · 안내 응답 본문을 만들지 마라. 이유: ADR-083 — `204` 뒤 화면이 로그인 화면으로 간다. 화면은 step 4 에서 만든다.
- `llm_calls` 행을 지우거나 FK 를 바꾸지 마라. 이유: ADR-139 · ADR-156 — 연결만 끊어 숫자로 남긴다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 접근 줄에 경로 · 결과 · `userId` 가 이미 남는다. 이메일 · 이름은 어떤 로그에도 남기지 마라(금지 26).
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
