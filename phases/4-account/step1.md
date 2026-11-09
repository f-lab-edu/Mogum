# Step 1: login-session

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/05-api.md` §1.1(인증 — **매번 계정을 고르게 한다** · 승인 전 사람) · §1.4 · §3(「나」 줄과 그 아래 「나」의 크레딧 줄) — **이 step 의 계약이다**
- `docs/02-domain-data.md` §8.2(`users` — `email` · `name` 줄: 이름이 없으면 이메일 `@` 앞부분) · §8.7(`users.status`)
- `docs/ADR.md` — ADR-060(상태는 요청마다 읽는다) · ADR-062(저장하는 정보 — 이메일 · 이름) · ADR-092 · ADR-154(신청 전에는 세션에만 구글 신원)
- `docs/04-jobs-and-cost.md` §9.1(로그) · `AGENTS.md` 금지 23(세션에는 사용자 id 만) · 26(로그) · 25(오류 응답 경로)
- `docs/TEST_GUIDE.md` §1(보안 필터는 실제 HTTP — `@IntegrationTest`) · §5(공용 테스트 설정)
- `docs/GLOSSARY.md` — 「신청 · 승인 · 거절됨」 줄
- step 0 이 바꾼 것: `backend/src/main/resources/db/migration/V9__*.sql` · `account/domain/User.java` · `account/service/LoginService.java` · `LoginResult.java` · `auth/OAuthLoginHandler.java`(임시 실패 리다이렉트) · `auth/SessionUserFilter.java` · `account/controller/MeController.java` · `account/dto/MeResponse.java`
- 지금 코드: `auth/GoogleAccountMapper.java` · `auth/SecurityConfiguration.java`(요청 해석기 · 로그아웃) · `backend/src/main/resources/application.yml`(Google `scope`)
- 지금 테스트: `backend/src/test/java/com/mogum/support/IntegrationTestConfiguration.java`(`FakeGoogle`) · `support/TestSessions.java` · `auth/OAuthLoginHandler*IntegrationTest.java` · `auth/GoogleAccountMapperTest.java` · `auth/SessionUserFilterTest.java` · `auth/SecurityConfigurationLogoutIntegrationTest.java` · `account/controller/MeControllerTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`4-account`)는 계정을 신청 · 승인 · 거절됨 흐름으로 바꾼다. step 0 이 테이블과 엔티티를 바꿨고, 로그인은 행이 없는 사람에게 임시로 실패 리다이렉트를 준다.

이 step 은 **로그인 세션 두 종류와 「나」 읽기**를 만든다.
- 행이 있는 사람 — 세션에 사용자 ID 만 둔다(지금과 같다).
- 행이 없는 사람(신청 전) — 행을 만들지 않고, 세션에 구글 신원(로그인 수단 · 계정 ID · 이메일 · 이름)만 잠시 둔다(ADR-154). 신청 API 는 step 2 가 만든다.

**계약 문서는 phase 전에 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 구글 로그인

- `application.yml` 의 Google `scope` 를 `email` · `profile` 로 바꾼다 — 이름을 받으려면 `profile` 이 필요하다.
- 로그인을 시작할 때마다 계정을 고르게 한다 — 구글 인가 요청에 `prompt=select_account` 를 싣는다(05 §1.1). 지금 `SecurityConfiguration` 의 요청 해석기(`DefaultOAuth2AuthorizationRequestResolver` 를 GET 전용으로 감싼 것)에 더하는 방법은 네가 고른다. PKCE 등 지금 싣는 값은 그대로 둔다.
- `GoogleAccountMapper` — `name` 을 읽어 `ExternalAccount` 에 담는다. **`name` 이 없거나 공백뿐이면 이메일의 `@` 앞부분을 쓴다**(02 §8.2 — `test123@gmail.com` → `test123`). 이 규칙은 Java 에서 이 한 곳에만 둔다.

### 2. 로그인 결과 → 세션

- `OAuthLoginHandler` — step 0 의 임시 처리를 바꾼다.
    - `Success(userId)` → 지금과 같다(세션 ID 바꾸기 · 속성 비우기 · 사용자 ID 를 `PRINCIPAL_NAME_INDEX_NAME` 에 · `origin + "/"` 로).
    - 「신청 전」 → 같은 방식으로 세션 ID 를 바꾸고 속성을 비운 뒤, 세션에 구글 신원만 둔다. **`PRINCIPAL_NAME_INDEX_NAME`(주인 색인)에는 아무것도 넣지 않는다** — 그 칸은 사용자 ID 자리이고, 운영자 SQL 이 그 값으로 세션을 지운다(step 3). 그리고 `origin + "/"` 로 보낸다 — 화면이 「나」를 읽어 신청 화면으로 간다(step 5).
    - 세션에 담는 모양(속성 이름 · 직렬화할 값 타입)은 네가 정한다. Spring Session JDBC 가 저장 · 복원할 수 있어야 한다.
- 로그 줄 — 신청 전 로그인은 `INFO` `login result=not_applied` 한 줄을 남긴다. `userId` 는 없다(행이 없다). 이메일 · 이름 · `subject` 는 남기지 않는다(금지 26). `success` 와 실패 줄은 그대로다. 줄이 찍히는지 출력 캡처(`OutputCaptureExtension`)로 확인하고, 이메일 · 이름 표식 문자열이 로그에 없는지도 확인한다.

### 3. `SessionUserFilter` — 두 종류 세션

- 사용자 ID 세션 → 지금처럼 `users` 를 읽어 인증한다. 행이 없으면 지금처럼 세션을 버리고 401.
- 구글 신원 세션 → `users` 를 읽지 않고 신청 전 사람으로 인증한다. MDC 에 `userId` 를 넣지 않는다.
- 어느 쪽도 아니거나 값이 깨졌으면 지금처럼 세션을 버리고 401.
- 인증 주체(principal)의 모양 — 컨트롤러가 「누구 · 어떤 상태」를 한 타입으로 받도록 네가 정한다(예: sealed interface 로 사용자 / 신청 전). 상태는 세션이 아니라 요청마다 `users` 에서 읽은 값이다(금지 23).
- 권한(`GrantedAuthority`)은 이 step 에서 정하지 않아도 된다 — 승인 전 사람을 막는 규칙은 step 2 다.

### 4. 「나」(`GET /api/me`)

- 응답 칸 — `status` · `email` · `name`. `status` 는 `NOT_APPLIED`(신청 전) · `APPLIED` · `APPROVED` · `REJECTED`. `NOT_APPLIED` 는 DB 값이 아니라 「나」 응답의 값이다.
- 신청 전이면 세션의 구글 신원에서, 아니면 `users` 행에서 채운다.
- **크레딧 칸을 넣지 마라** — 05 §3: 내 정보 탭 TASK 에서 더한다.
- `MeControllerTest`(`@WebMvcTest`) · 실제 HTTP 통합 테스트로 두 종류 세션의 응답을 확인한다.

### 5. 로그아웃

- `POST /api/auth/logout` 이 두 종류 세션 모두에서 204 를 주고 세션을 없애는지 실제 HTTP 로 확인한다. 지금 설정이 이미 그렇다면 테스트만 더한다.

### 6. 테스트 도우미 · 가짜 구글

- `support/TestSessions` — 구글 신원 세션을 만드는 도우미를 더한다(주인 색인 없이).
- `support/IntegrationTestConfiguration.FakeGoogle` — 실제 구글이 하는 검사를 그대로 한다(`/harness` 원칙 12).
    - `/userinfo` 가 `name` 을 준다. 테스트가 `name` 을 빼거나 비울 수 있게 한다.
    - 시작 주소(`GET /auth/google` 의 리다이렉트 주소)에 `scope` 의 `email` · `profile` 과 `prompt=select_account` 가 있는지 통합 테스트로 확인한다. 지금의 PKCE 검사는 유지한다.

### 7. 문서

- `docs/04-jobs-and-cost.md` §9.1 — 새 로그 줄(`INFO` · 신청 전 로그인 · `login result=not_applied` · `userId` 없음)을 더한다. 줄의 모양은 테스트가 실제로 찍은 줄에서 옮기고, 키 순서처럼 정해지지 않은 것은 「정해지지 않았다」고 적는다.
- `docs/GLOSSARY.md` 「신청 · 승인 · 거절됨」 줄 — 「나」 응답의 상태 코드 이름 `NOT_APPLIED`(신청 전)을 더한다.
- `docs/05-api.md` §3 「나」 — 경로(`GET /api/me`)와 칸 이름(`status` · `email` · `name`)을 구현 사실로 적는다. 규칙 문장은 고치지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*GoogleAccountMapperTest' --tests '*OAuthLoginHandler*' --tests '*SessionUserFilterTest' --tests '*MeController*' --tests '*SecurityConfiguration*'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 신청 전 로그인이 `users` 행을 만들지 않고, 세션의 주인 색인이 비어 있는가?
   - 구글 신원 세션이 실제 HTTP(`@IntegrationTest`)로 「나」 · 로그아웃까지 확인됐는가?
   - 가짜 구글이 `name` · `scope` · `prompt` · PKCE 를 검사하는가?
   - 로그에 이메일 · 이름 · `subject` 가 남지 않는가? MDC 를 지우는가?
   - 「나」에 크레딧 · 역할 · 시스템 정보가 없는가(05 §1.4)?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/4-account/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 세션 속성 이름과 값 타입 · 인증 주체 타입 · 「나」 경로와 칸 · 테스트 도우미 이름 · 로그 줄 · **바꾼 기존 테스트의 검증 사실 → 새 위치** 대응 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 신청 전 사람의 `users` 행을 만들지 마라. 이유: ADR-154 — 신청하지 않고 떠난 사람의 정보를 남기지 않는다.
- 세션에 상태(신청 · 승인 · 거절됨)를 담지 마라. 이유: 금지 23 — 운영자가 승인해도 로그인 중인 사람에게 반영되지 않는다.
- 구글 신원 세션의 주인 색인(`PRINCIPAL_NAME_INDEX_NAME`)에 이메일 · `subject` 를 넣지 마라. 이유: 그 칸은 사용자 ID 자리이고 운영자 SQL 이 그 값으로 세션을 찾아 지운다.
- 신청 API · 승인 전 사람을 막는 규칙을 만들지 마라. 이유: step 2 의 범위다.
- 「나」에 크레딧 칸을 넣지 마라. 이유: 05 §3 — 내 정보 탭 TASK 에서 더한다.
- 로그인할 때마다 `users` 의 이름 · 이메일을 고치지 마라. 이유: 02 · 06 에 없는 동작이다.
- 화면(`frontend/`)을 고치지 마라. 이유: step 4 · 5 의 범위다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 테스트 전용 컴포넌트를 `@SpringBootTest` 스캔에 넣지 마라 — 공용 `IntegrationTestConfiguration` 의 중첩 static 클래스로 둔다. 이유: 컨텍스트가 클래스마다 새로 뜬다(TEST_GUIDE §5).
- 통합 테스트 이름에 `IT` 를 쓰지 마라(`*IntegrationTest`). 이유: ArchUnit 이 빌드를 깬다(TEST_GUIDE §7).
- 계약 문서(02 · 05 · ADR · AGENTS 금지 목록)의 규칙 문장을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
