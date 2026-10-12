# Step 3: google-login

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 25 · 27, 작업 방식(TDD 짝 테스트 규칙) · 명령어 · `.env` 규칙
- `docs/05-api.md` §1(경로 — OAuth 경로는 `/api` 접두사 없음) · §1.1 전체(특히 "공개 전 임시 — 관리자 이메일만 로그인" · "콜백 실패는 화면의 U1 로")
- `docs/02-domain-data.md` §6.1(`google_sub` · 세션 `principal_name`)
- `docs/06-screens.md` §3(U1 — `?error=` 별 문구)
- `docs/ADR.md` ADR-010 · ADR-060 · ADR-062
- 이전 step 산출물: step 0 의 `src/main/java/com/mogum/core/account/`(로그인 판정 유스케이스) · step 1 의 보안 설정 · 세션에 로그인 사용자를 담는 형식 · 세션 테스트 지원 코드 · step 2 의 `app.web.origin` 설정 · `build.gradle` · `src/main/resources/application.yml`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

구글 로그인으로 **세션을 만드는 쪽**을 붙인다. 판정은 step 0 의 유스케이스, 세션 형식은 step 1, 화면 주소는 step 2 의 `app.web.origin` 을 그대로 쓴다.

### 1. 의존성 · 등록

- `spring-boot-starter-security-oauth2-client`. **버전을 적지 않는다**(Boot 4.1.1 관리).
- 등록 이름 `google`. 클라이언트 id · 비밀은 **환경 변수로만** 받는다(예: `GOOGLE_CLIENT_ID` · `GOOGLE_CLIENT_SECRET`). 저장소의 설정 파일에 진짜처럼 보이는 기본값을 넣지 않는다. 값이 없을 때 어떻게 되는지(기동 실패 등)를 정하고 문서에 적는다.
- 범위(scope)는 `sub` · `email` · `email_verified` 를 얻는 최소한으로 한다. OIDC(ID 토큰) 방식이든 사용자 정보 엔드포인트 방식이든 재량이다. 어느 쪽이든 식별자는 구글 `sub` 이고 이메일 인증 여부를 step 0 유스케이스에 넘긴다.
- **모든 `@SpringBootTest` 가 실제 비밀 없이 뜨게** 테스트 공통 값을 둔다. `src/test/resources/application.yml` 은 만들지 마라 — main 설정을 통째로 가린다.

### 2. 경로 — `/api` 접두사 없음(05 §1)

| 경로 | 동작 |
|---|---|
| `GET /auth/google` | 구글 동의 화면으로 302 |
| `GET /auth/google/callback` | 구글이 돌려보내는 곳 — 아래 3 · 4 |

- **로컬 콜백 주소는 정확히 `http://localhost:5173/auth/google/callback`** 이다. 화면 개발 서버(Vite)가 `/auth` 를 백엔드로 넘기고(`frontend/vite.config.ts` — `Host` 헤더를 바꾸지 않는다), 사용자는 이 주소를 구글 콘솔에 등록했다. 요청에서 유도하든 설정으로 고정하든 재량이지만 로컬에서 이 값이 나와야 하고, 운영(`https://api.{도메인}/auth/google/callback`)에서 맞추려면 무엇을 설정해야 하는지 문서에 적는다(운영 값은 `5-deploy` 에서 넣는다).
- 기본 로그인 페이지(`/login`)를 만들지 않는다. 이유: `/login` 은 화면의 U1 주소다.

### 3. 로그인 전 — 세션을 만들지 않는다

- `GET /auth/google` 은 **세션(세션 행 · 세션 쿠키)을 만들지 않는다.** OAuth 요청 정보(`state` 등)는 짧은 수명 쿠키에 담는다(05 §1.1) — `HttpOnly` · `Secure` · `SameSite=Lax` · 짧은 `Max-Age`(10분 이하) · 필요한 경로로 제한. 콜백을 처리하면 지운다.
- **그 쿠키 값을 Java 직렬화로 복원하지 마라.** 이유: 쿠키는 브라우저가 보내는 값이라 역직렬화 공격 경로가 된다. JSON 등 데이터 형식으로 담고 필요한 값만 되살린다.
- 콜백의 `state` 가 쿠키와 다르거나 쿠키가 없으면 실패다(로그인 CSRF 방어). 이때 구글 토큰 엔드포인트를 부르지 않는다.

### 4. 콜백 — 판정 · 세션 · 리다이렉트

- 구글 계정(`sub` · `email` · `email_verified`)을 step 0 의 로그인 판정 유스케이스에 넘긴다.
- **성공** — step 1 이 정한 형식으로 세션에 사용자 id 만 기록한다. `spring_session.principal_name` = `users.id`. **세션 번호를 새로 발급한다**(세션 고정 방어 — 이미 세션이 있던 브라우저면 이전 세션 번호는 더 이상 통하지 않는다). 그다음 `{app.web.origin}/` 로 302.
- **실패** — 세션 · 사용자 행을 만들지 않고 화면으로 302(05 §1.1).
  - 관리자 목록 밖 → `{app.web.origin}/login?error=not_allowed`
  - 그 밖(이메일 미인증 · `state` 불일치 · 구글에서 취소(`?error=access_denied` 등) · 토큰 교환 실패) → `{app.web.origin}/login?error=login_failed`
- **돌아갈 주소를 요청에서 받지 않는다** — 리다이렉트 대상은 `app.web.origin` 뿐이다(열린 리다이렉트 방지). 로그인 전에 열려던 주소는 화면이 기억한다(step 4).
- **구글이 준 토큰(접근 토큰 · ID 토큰)과 구글 사용자 정보를 남기지 않는다** — 세션 · DB · 프로세스 메모리 어디에도. Spring Security 의 기본 `OAuth2AuthorizedClient` 저장(세션 또는 메모리)을 쓰지 않게 설정한다. 이유: 로그인 뒤 구글 API 를 부르지 않는다 · 세션에는 id 만(금지 목록 27) · 메모리 공유 상태 금지(금지 목록 25). 인증 처리 중 만들어진 `OAuth2AuthenticationToken` · `OidcUser` 가 세션에 저장되지 않게 step 1 형식으로 바꿔 담는다.

### 5. 테스트

TDD 가드: 구현 파일마다 같은 패키지에 `{클래스명}*Test.java` 또는 `{클래스명}*IT.java` 가 먼저 있어야 쓰기가 허용된다.

**실제 구글을 부르지 마라.** 구글의 토큰 엔드포인트(OIDC 면 JWKS, 사용자 정보 방식이면 사용자 정보 엔드포인트도)를 테스트 안의 가짜 HTTP 서버로 흉내 내고, 테스트 설정으로 제공자 주소를 그쪽으로 돌린다. 도구는 재량이다(JDK `HttpServer` 등). Boot 가 관리하지 않는 테스트 라이브러리를 더하면 버전을 명시하고 이유를 `build.gradle` 주석에 적는다.
**`@WithMockUser` · `oauth2Login()` · `oidcLogin()` 후처리기로 콜백을 대신하지 마라** — 그건 이 step 이 만드는 경로를 건너뛴다. `/auth/google` 로 시작해 받은 쿠키와 `state` 로 콜백을 부르는 흐름으로 검증한다.

관리자 목록 · `app.web.origin` 은 테스트 값으로 준다(예: `admin@example.com` · `https://mogum.example`).

- `GET /auth/google` → 302 로 구글(가짜) 동의 주소. 쿼리에 `state` · `client_id` · `redirect_uri`(콜백 경로)가 있다. OAuth 요청 쿠키가 위 속성으로 내려오고, **세션 쿠키가 없고 `spring_session` 행이 늘지 않는다.**
- 관리자 이메일로 콜백 성공 → `users` 에 `ACTIVE` · `ADMIN` 행. `spring_session` 행의 `principal_name` = 그 사용자 id. 세션 쿠키는 `HttpOnly` · `Secure` · `SameSite=Lax` · `Domain` 없음. 302 `https://mogum.example/`. 그 세션 쿠키로 `GET /api/me` → `200` · 그 이메일. OAuth 요청 쿠키는 만료된다.
- **세션에는 id 만** — 그 세션의 `spring_session_attributes.attribute_bytes` 어디에도 이메일 · 구글 `sub` · 가짜 서버가 준 접근 토큰 · ID 토큰 문자열이 없다.
- 세션 고정 — 이미 로그인한 세션 쿠키를 가진 채로 로그인을 다시 하면 새 세션 번호가 나오고, 이전 세션 쿠키로 `GET /api/me` 는 `401` 이다.
- 같은 `sub` 로 이메일만 바꿔 다시 로그인 → 같은 사용자 id · 이메일 갱신.
- 관리자 목록 밖 → `users` · `spring_session` 행 없음 · 302 `https://mogum.example/login?error=not_allowed`.
- 이메일 미인증 → 행 없음 · `…/login?error=login_failed`.
- `state` 불일치 · OAuth 요청 쿠키 없음 → 행 없음 · `…/login?error=login_failed` · 가짜 토큰 엔드포인트가 호출되지 않았다.
- 구글이 `?error=access_denied` 로 돌려보냄 → `…/login?error=login_failed`.
- OAuth 요청 쿠키 값을 망가뜨려 보내도 `500` 이 아니라 `…/login?error=login_failed` 다.

### 6. 문서

- `docs/05-api.md` §1.1 — 환경 변수 이름(구글 클라이언트 id · 비밀, `app.auth.admin-emails` 를 덮어쓰는 이름), 범위(scope), 로컬 콜백 주소, 운영 콜백 주소를 맞추는 방법, 값이 없을 때의 동작. 표의 `/auth/google` · 콜백 행이 구현과 다르면 맞춘다(`PENDING` · U7 · U8 분기는 `12` 범위라는 기존 문장은 그대로 둔다).
- `AGENTS.md` 명령어 — `./gradlew bootRun` 줄의 주석에 실제 로그인에 필요한 환경 변수 이름을 덧붙인다. 다른 줄은 건드리지 않는다. **값은 적지 않는다.**

## Acceptance Criteria

```bash
./gradlew test    # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 패키지 구조(`core` ← `api` · `worker` · `infra`)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/1-auth/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 경로 · `error` 값 · 환경 변수 이름 · 로컬 콜백 주소를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 실제 구글(`accounts.google.com` · `oauth2.googleapis.com` · `www.googleapis.com`)을 테스트에서 부르지 마라. 이유: 테스트가 네트워크 · 실제 비밀에 묶인다.
- `.env` 를 만들거나 읽지 마라. 클라이언트 비밀 · 실제 이메일을 저장소에 적지 마라. 이유: `AGENTS.md` — 비밀은 저장소와 에이전트 밖에 둔다.
- 관리자 목록 밖 계정의 정보를 저장 · 로그로 남기지 마라(이메일 포함). 이유: ADR-062 — 동의 전 이메일 저장 금지.
- `PENDING` 생성 · 약관 · 대기 명단 분기를 만들지 마라. 이유: `12-account-public` 의 범위다(ADR-062).
- 로그인 뒤 돌아갈 주소를 쿼리 매개변수 · 쿠키로 받아 리다이렉트하지 마라. 이유: 열린 리다이렉트(05 §1.1).
- `/auth/*` 레이트리밋을 넣지 마라. 이유: `9-cost-control` 의 범위다(05 §1.4).
- 화면 코드 · `frontend/vite.config.ts` 를 고치지 마라. 이유: step 4 의 범위다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
