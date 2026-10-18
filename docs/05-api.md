# 05. API

> 엔드포인트 · 요청 · 응답 · 상태 코드 · 에러 `code`. 화면 매핑은 §8, 화면 문구는 [06 §15](./06-screens.md).
> **생성은 전부 비동기다** — 새 잡을 만드는 요청은 `202` + `jobId` 를 돌려주고, 화면은 `GET /jobs/{id}` 를 2초마다 부른다.

---

## 1. 공통

**경로** — JSON API 는 모두 `/api` 아래에 둔다. 이 문서의 경로는 접두사를 뺀 표기다(`GET /me` = `GET /api/me`). 화면 주소([06 §2.3](./06-screens.md))와 겹치기 때문이다 — `/curriculums/{id}` · `/lessons/{id}` 는 화면이기도 하고 API 이기도 해서, 접두사가 없으면 새로고침 때 화면 대신 JSON 이 온다. 운영은 화면(Vercel)과 API(`api.` 서브도메인)의 출처가 다르지만, 개발 서버는 Vite proxy 로 같은 출처를 쓰므로 접두사를 유지한다. 브라우저가 직접 이동하는 OAuth 경로(`/auth/google` · `/auth/google/callback`)는 접두사 없이 둔다.

**운영 헬스** — `GET /actuator/health`는 `/api` 접두사 밖에 두며 로그인 불필요 · 상태만(`{"status":"UP"}`) · 세션 없음이다. 배포 확인과 외부 헬스체크에 쓰고, DB 연결 실패는 `503` · `{"status":"DOWN"}`으로 응답한다. 웹에는 `health`만 노출하며 `/actuator` 발견 목록과 구성 요소 · 상세는 끈다. Boot 4.1.1이 기본으로 켜는 liveness/readiness probes도 꺼서 응답에 `groups`를 싣지 않는다. 나머지 `/actuator/**`는 기존 보안 규칙으로 거절한다.

### 1.1 인증 — 구글 OAuth + 쿠키 세션(DB 저장)

- **운영 프로필** — `SPRING_PROFILES_ACTIVE=aws`로 `src/main/resources/application-aws.yml`을 켠다. `server.forward-headers-strategy: native`는 Tomcat `RemoteIpValve`로 신뢰 프록시의 `X-Forwarded-Proto` · `X-Forwarded-For`를 원래 HTTPS 스킴 · 클라이언트 IP로 반영한다([Boot 4.1 프록시 설정](https://docs.spring.io/spring-boot/4.1/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server)). Boot 4.1.1 `TomcatServerProperties.Remoteip`의 기본 `internal-proxies`를 사용한다: `192.168.0.0/16`, `172.16.0.0/12`, `169.254.0.0/16`, `fc00::/7`, `10.0.0.0/8`, `100.64.0.0/10`, `127.0.0.0/8`, `fe80::/10`, `::1/128`. 별도 `trusted-proxies`는 없다. 발신 주소가 이 범위 밖이면 헤더로 스킴 · IP를 바꾸지 못한다([Tomcat RemoteIpValve](https://tomcat.apache.org/tomcat-11.0-doc/api/org/apache/catalina/valves/RemoteIpValve.html)). 운영은 같은 Docker 네트워크의 Caddy만 외부 요청을 앱에 넘기고 앱 포트는 호스트 루프백에만 공개한다. `deploy/compose.yaml`은 네트워크를 `172.30.0.0/24`로 고정해 기본 신뢰 범위 `172.16.0.0/12` 안에 둔다. Caddy는 클라이언트가 보낸 `X-Forwarded-For/Proto/Host`를 기본적으로 무시하고 실제 연결 기준으로 설정한다([운영 절차서](../deploy/README.md#프록시와-네트워크)). 실제 서버의 대역 충돌/HTTPS 전달은 운영자가 [절차서 §10 확인 1·9번](../deploy/README.md#10-운영-확인-목록--m1)에서 확인한다. 다른 대역을 쓰면 `internal-proxies`를 함께 확인한다. 세션 · `XSRF-TOKEN`의 `Secure` 강제는 요청 스킴과 무관하게 유지한다.

| 엔드포인트 | 동작 |
|---|---|
| `GET /auth/google` | 세션 없이 `state` 를 10분 쿠키에 담고 구글 동의 화면으로 `302` |
| `GET /auth/google/callback` | `state` 를 쿠키와 대조(CSRF) → 공개 전 관리자 계정 판정 → **ID만 담은 세션 · 새 세션 번호** → 화면의 `/` 로 `302`. 실패는 화면의 `/login?error=…`. `PENDING` 생성 · U7 · U8 분기는 `12` 범위 |
| `GET /me` | 현재 사용자 — 화면이 어디로 갈지 이걸로 정한다 |
| `POST /me/terms` | `{ "termsVersion": "…" }` → 동의 기록 → **처음이면 여기서 정원 검사** → `{ "status": "ACTIVE" \| "WAITLISTED" }` |
| `POST /auth/logout` | **이 기기의 세션만** 무효화 · 쿠키 삭제(`HttpOnly` 라 스크립트는 못 지운다). 다른 기기는 로그인이 유지된다 |
| `DELETE /me` | 회원 탈퇴 — 한 트랜잭션 삭제(**그 사용자의 세션 전부 포함**) → `204`. 약관 거부 · 대기 취소도 이걸 쓴다 |

```jsonc
// GET /me
{ "email": "someone@gmail.com", "status": "ACTIVE", "waitlistPosition": null, "termsAgreed": true,
  "role": "USER", "quota": { "usedRatio": 0.6, "resetsAt": "2026-12-15T00:00:00+09:00" } }   // 쿼터는 비율로만
```

`1-auth` 응답 범위는 `email` · `status` · `role` · `termsAgreed` · `waitlistPosition` 이다 — `quota` 는 `10-cost-control` 부터, `waitlistPosition` 은 `13-account-public` 전까지 `null` 이다.

- **세션은 Postgres 에 둔다**(Spring Security `oauth2Login` + **Spring Session JDBC**). 쿠키에는 세션 번호만 있다 — `HttpOnly` · `Secure` · `SameSite=Lax`. `1-auth` 는 `DefaultCookieSerializerCustomizer` 로 발급 · 만료 쿠키의 세 속성을 강제한다(`Secure` 는 요청의 HTTPS 여부와 무관하게 켠다). 로그인 때 세션 번호를 새로 발급한다(세션 고정 방어 — 프레임워크 기본과 성공 handler의 번호 변경). 기존 세션 속성은 비우고 사용자 ID 하나만 남긴다. JDBC 번호 변경은 저장 시 반영되므로 번호를 바꾼 직후 세션을 무효화하지 않는다.
- **비활동 7일이면 만료.** 세션에는 **사용자 id 만** 둔다 — 상태(`ACTIVE` · `WAITLISTED` · `PENDING`) · 역할은 **요청마다 `users` 에서 읽는다.** 승인 · 약관 변경이 바로 반영되게.
    - 저장 형식은 `FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME`(`org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME`) 속성 하나에 `String` 타입의 `users.id` 다. `SecurityContext` 는 저장하지 않는다. `SessionUserFilter` 가 매 요청의 트랜잭션 안에서 사용자 스냅샷을 읽어 인증을 만들고, 컨트롤러는 `@AuthenticationPrincipal User` 로 현재 사용자를 받는다.
- **CSRF** — `SameSite=Lax` 에 더해 Spring Security 기본 CSRF 보호를 켠다(화면은 `XSRF-TOKEN` 쿠키를 읽어 헤더로 보낸다).
- **화면과 API 의 출처** — 화면은 `{도메인}`(Vercel), API 는 `api.{도메인}`(EC2)이다. 같은 등록 도메인이라 **같은 사이트 · 다른 출처**다(ADR-060).
    - 화면의 API 출처는 빌드 환경 변수 `VITE_API_BASE_URL` 로 받는다(운영 예: `https://api.{도메인}`). 비어 있으면 상대 경로를 쓰며, 개발에서는 `frontend/vite.config.ts` 의 Vite proxy 가 `/api` · `/auth` 를 백엔드(`http://localhost:8080`)로 넘긴다. JSON 호출은 `{API 출처}/api{경로}`, 로그인 시작 링크는 `{API 출처}/auth/google` 이다. 실제 Vercel 변수는 운영자가 [처음 배포 절차서 §7](../deploy/README.md#7-vercel-화면-배포)에 따라 설정한다(아직 미실행).
    - 세션 쿠키는 API 호스트에만 둔다(`Domain` 없음). 같은 사이트라 `SameSite=Lax` 그대로 화면의 요청에 실린다. 화면의 fetch 는 `credentials: 'include'` 로 보낸다.
    - CORS — 허용 출처는 `app.web.origin` 하나(환경 변수 `APP_WEB_ORIGIN`, 로컬 기본값 `http://localhost:5173`)이고 credentials 를 허용한다. 스킴 · 호스트 · 선택적 포트만 지정하며 끝 `/` 는 없다. `*` · 패턴 · 여러 출처를 허용하지 않으며, 잘못된 출처 설정은 기동을 거부한다. `SecurityConfiguration` 이 루트 `WebProperties` 에 바인딩한 값을 사용해 `/api/**` 의 preflight 를 인증 · CSRF보다 먼저 처리한다. 허용 메서드는 `GET` · `POST` · `PUT` · `PATCH` · `DELETE`, 요청 헤더는 `Content-Type` · `X-XSRF-TOKEN` 이다.
    - `XSRF-TOKEN` 쿠키는 화면 스크립트가 읽을 수 있게 `Domain` 을 등록 도메인으로 둔다(`app.web.cookie-domain`, 환경 변수 `APP_WEB_COOKIE_DOMAIN`). 로컬 기본값은 비어 있으며, 비어 있으면 `Domain` 을 두지 않는다. 세션 쿠키의 `Domain` 에는 적용하지 않는다. `HttpOnly` 아님 · `Secure` · `SameSite=Lax` 는 유지한다.
    - 로그인 시작 · 콜백은 API 호스트의 `/auth/*` 에서 하고, 끝나면 화면 주소(`app.web.origin`)로 보낸다. `1-auth` step 3 은 성공 시 U2(`/`), 실패 시 U1(`/login?error=…`)로 보낸다. U7 · U8 분기는 `12` 범위다.
    - 개발은 Vite proxy 로 같은 출처다 — 출처가 다른 운영 조건(CORS · 쿠키 도메인)은 테스트로 확인한다.
    - PR 미리보기(`*.vercel.app`)는 다른 사이트라 세션 쿠키가 실리지 않는다 — 미리보기에서 로그인을 확인하지 않는다.
- 요청마다 **세션 조회 + 사용자 조회**, 그리고 마지막 접근 시각 갱신(세션 행 UPDATE)이 붙는다 — 용량 측정에 포함한다.
- **공개 전 임시 — 관리자 이메일만 로그인**(ADR-062). `13-account-public` 전까지 콜백은 관리자 이메일 목록(`app.auth.admin-emails`)에 없는 계정을 거절하고 `users` 행 · 세션을 만들지 않는다. 목록의 계정은 처음 로그인할 때 `ACTIVE` · `ADMIN` 으로 만든다. 위 표의 `PENDING` 생성 · U7 · U8 분기와 `POST /me/terms` · `DELETE /me` 는 `13` 에서 만든다 — 그전까지 콜백 성공은 언제나 화면 주소의 `/`(U2)로 보낸다.
- **Google 등록 · 환경 변수** — 등록 이름은 `google` 이다. 클라이언트 ID는 `GOOGLE_CLIENT_ID`, 비밀은 `GOOGLE_CLIENT_SECRET` 환경 변수로 받으며 저장소에 실제 값/대체 자격 정보는 두지 않는다. 둘 중 하나라도 없거나 공백이면 기동을 거부한다. 관리자 목록을 덮어쓰는 환경 변수는 `APP_AUTH_ADMIN_EMAILS`(쉼표로 구분)이며 기본값은 빈 목록이다 — 빈 목록이면 모든 계정을 `not_allowed`로 거절한다. 테스트 JVM은 Gradle의 `test` 작업에서 가짜 클라이언트 ID/비밀만 공통으로 주입하며 main 설정을 그대로 읽는다.
    - 범위는 **`email` 하나**다. OAuth2 authorization code 교환 뒤 Google 사용자 정보 엔드포인트에서 `sub` · `email` · `email_verified`를 읽어 `GoogleLogin`에 넘긴다. `profile` · `openid` 범위와 ID 토큰은 요청하지 않는다.
    - 로컬 콜백 주소는 정확히 **`http://localhost:5173/auth/google/callback`** 이다. 등록의 `redirect-uri`는 이 값으로 고정되어 요청 Host/돌아갈 주소 매개변수에서 유도하지 않는다. `frontend/vite.config.ts` 의 Vite proxy 는 `/api` · `/auth` 에 문자열 축약형을 쓰므로 `changeOrigin: true` 로 변환되어 Host 가 백엔드 주소(`localhost:8080`)로 바뀐다. 백엔드는 설정으로 고정한 `redirect-uri` 를 쓰므로 Host 에 기대지 않는다(`GoogleLoginHandlerIT` 의 Host 테스트로 검증).
    - 운영은 **`GOOGLE_REDIRECT_URI=https://api.{도메인}/auth/google/callback`** 을 설정하고 Google 콘솔에도 동일한 승인된 리디렉션 URI를 등록한다(운영자가 [처음 배포 절차서 §4·§6](../deploy/README.md#4-서버-준비)에 따라 실제 값을 설정하며 아직 미실행). `APP_WEB_ORIGIN`은 별도로 화면의 `https://{도메인}`에 맞춘다. `GOOGLE_REDIRECT_URI`가 없으면 로컬 콜백 주소를 사용한다.
- **로그인 전 요청 쿠키** — PKCE(S256)는 켜 둔다. `MOGUM_OAUTH_REQUEST`에 `state` · 발급 시각 · PKCE `code_verifier`만 JSON → Base64URL로 담고 콜백에서 verifier를 복원해 토큰 교환에 보낸다. `HttpOnly` · `Secure` · `SameSite=Lax` · `Max-Age=600` · `Path=/auth/google` · `Domain` 없음. Java 역직렬화는 하지 않으며 나머지 OAuth 요청 필드는 서버의 등록 설정으로 복원한다. `GET /auth/google`은 세션을 만들지 않는다. 콜백은 쿠키 없음/손상/만료/`state` 불일치와 verifier 누락/형식 오류(RFC 7636: 43~128자 · `[A-Za-z0-9-._~]`)를 토큰 교환 전에 거절하고 성공/실패 모두 요청 쿠키를 만료시킨다. verifier는 로그 · 에러 응답에 남기지 않는다. 등록되지 않은 한 단계 `GET /auth/{id}`는 공통 `404 NOT_FOUND` JSON을 반환한다.
- **Google 정보 보관** — Google 접근/ID 토큰과 OAuth 인증 주체는 세션 · DB · 로그인 뒤 메모리에 보관하지 않는다. `DiscardingOAuth2AuthorizedClients`가 웹 저장소와 서비스의 기본 저장을 모두 대체한다. `NullSecurityContextRepository`로 OAuth `SecurityContext`를 저장하지 않고 성공 handler는 기존의 ID 세션 형식으로 바꾼 뒤 요청의 인증 문맥을 비운다. 로그인 뒤에는 기존 `SessionUserFilter`가 `users`를 다시 읽는다. 백엔드 기본 `/login` 페이지는 만들지 않는다 — `/login`은 화면의 U1 주소다.
- **콜백 실패는 화면의 U1 로** — `{화면 주소}/login?error=…` 로 보낸다. `error=not_allowed` 는 위 임시 거절, `error=login_failed` 는 그 밖의 실패(구글에서 취소 · `state` 불일치 · 이메일 미인증 · 토큰 교환 실패)다. 화면 문구는 [06 §3](./06-screens.md). 백엔드는 설정한 화면 주소로만 보낸다 — 요청 매개변수로 돌아갈 주소를 받지 않는다(열린 리다이렉트 방지). 로그인 전에 열려던 주소는 화면이 기억한다([06 §2.3](./06-screens.md)).
- **부하 테스트 세션** — `POST /dev/session { "userKey": "vu-17" }` 가 사용자(`ACTIVE` · 약관 동의)와 세션을 만들고 세션 쿠키를 준다. **`loadtest` 프로필에만 빈이 있다.** `loadtest` 와 `aws` 프로필이 함께 켜지면 **기동을 거부**한다. 제품 API 수에 세지 않는다.

> **왜 JWT 가 아닌가** — 로그아웃 · 탈퇴를 즉시 반영하고 대기 · 약관 같은 바뀌는 상태를 확인하려면 **어차피 요청마다 DB 를 본다.** 그러면 JWT 의 장점(DB 없이 검증)이 사라지고 발급 · 검증 필터 · 서명 키 관리만 남는다.
> 세션은 프레임워크 기본 동작이고, 기기별 로그아웃이 되고, DB 에 있어 배포 · 프로세스 분리에도 유지된다. 다시 볼 조건 — 쿠키를 못 쓰는 클라이언트(모바일 앱)나 다른 도메인의 서비스가 같은 인증을 믿어야 할 때.

### 1.2 에러

```json
{ "code": "USER_QUOTA_EXCEEDED", "message": "일일 한도를 모두 사용했습니다", "retryAfterSeconds": 32400 }
```

포맷보다 **`code` 가 고정돼 있는 것**이 중요하다. RFC 9457 은 쓰지 않는다(`type` URI 를 운영할 클라이언트가 하나뿐이다).
MVC 표준 예외와 서블릿 컨테이너의 ERROR 디스패치도 원래 HTTP 상태를 유지하며 위 JSON 형식으로 응답한다. 오류 디스패치에 인증을 다시 요구하지 않는다. 예외 메시지 · 스택 · 요청 원문은 응답에 싣지 않는다.

| `code` | HTTP | 언제 |
|---|---|---|
| `UNAUTHORIZED` | 401 | 세션 없음 · 만료 · 로그아웃 · 탈퇴로 삭제됨 |
| `TERMS_REQUIRED` | 403 | 약관 전 — `GET /me` · `POST /me/terms` · 로그아웃 · `DELETE /me` 만 된다 |
| `ACCOUNT_WAITLISTED` | 403 | 대기 명단 — 위 네 개만 된다 |
| `CSRF_INVALID` | 403 | 상태를 바꾸는 요청에 `X-XSRF-TOKEN` 헤더가 없거나 `XSRF-TOKEN` 쿠키와 다르다. 화면은 같은 요청을 자동으로 다시 보내지 않는다 |
| `RATE_LIMITED` | 429 | 우리 API 레이트리밋 · `Retry-After` |
| `NOT_FOUND` | 404 | 없거나 **남의 것**(§1.3) |
| `METHOD_NOT_ALLOWED` | 405 | 해당 경로가 지원하지 않는 HTTP 메서드. `Allow` 헤더 유지 |
| `INVALID_REQUEST` | 4xx(404 · 405 제외) | MVC/컨테이너의 요청 오류 — 잘못된 JSON · 필수 매개변수 누락(400) · 응답 형식 불일치(406) · 지원하지 않는 본문 형식(415) 등. 인증 · CSRF · 도메인 오류는 각 전용 code 사용 |
| `INTERNAL_ERROR` | 5xx | 처리되지 않은 예외(500) 등 MVC/컨테이너 서버 오류. 도메인 오류는 각 전용 code 사용 |
| `STRUCTURAL_LIMIT` | 400 | 입력 길이(주제 100 · 목적 200 · 피드백 500자) 위반. 생성 목록의 총 1~40강 위반은 수정 요청 후에도 잘못되면 잡 `LLM_OUTPUT_INVALID`([03 §2](./03-generation.md)) |
| `USER_QUOTA_EXCEEDED` | 429 | 일일 생성 비용 한도 · `Retry-After` = 한국 시간 자정까지 |
| `ACTIVE_JOB_LIMIT` | 429 | 진행 중 생성이 이미 2개. 하나 끝나면 풀린다 |
| `GLOBAL_BUDGET_EXHAUSTED` | 503 | 전역 킬스위치 · `Retry-After` |
| `REGENERATION_LIMIT` | 409 | 이 커리큘럼의 오늘 다시 짜기 3회를 다 썼다 · `Retry-After` = 한국 시간 자정까지 |
| `CURRICULUM_NOT_DRAFT` · `CURRICULUM_GENERATING` · `CURRICULUM_NOT_ACTIVE` | 409 | 승인 · 다시 짜기 · 학습 상태 충돌. 커리큘럼 삭제는 생성 중에도 허용 |
| `CURRICULUM_NOT_READY` | 409 | 승인 요청에 검증을 통과해 저장된 강의 목록이 없음 — 단계 상세 생성 실패의 빈 초안 포함. 초기 생성 실패에는 커리큘럼 자체가 없음 |
| `LESSON_NOT_READY` | 409 | 자료 없는 강의에 학습 완료 |
| `JOB_NOT_FAILED` | 409 | 실패하지 않은 잡에 재시도 |
| `JOB_NOT_RETRYABLE` | 409 | 거절된 주제(`TOPIC_REJECTED`) · 누적 호출 상한 소진 · 입력 상한 초과 잡, 또는 커리큘럼이 이미 `DRAFT` 를 벗어난 커리큘럼 생성 잡에 재시도. 강의 자료가 `JOB_CALL_LIMIT_EXCEEDED` · `INPUT_TOO_LARGE` 로 끝났으면 처음부터 다시 만들기(§3.1)로 |
| `LESSON_RESTART_LIMIT` | 409 | 이 강의의 오늘 처음부터 다시 만들기 2번을 다 썼다 · `Retry-After` = 한국 시간 자정까지 |
| `NO_CARDS_DUE` | 409 | 복습할 카드가 없는데 세션 요청 |
| `REVIEW_ALREADY_PROCESSED` | 409 | 다른 평가로 카드 상태가 이미 바뀜 — 제출한 평가는 반영하지 않는다 |
| `REVIEW_REQUEST_CONFLICT` | 409 | 같은 `reviewRequestId`를 다른 카드 · 등급 · 요청 버전에 재사용 |

- `429` = *"이 사용자가 많이 썼다 · 시간이 지나면 풀린다"*. `503` = *"서비스 전체가 지금 신규를 안 받는다"*. `403` = *"누구인지는 알지만 아직 못 쓴다"* — `401` 과 구분해야 화면이 로그인으로 튕기지 않는다.
- **우리 `429` 와 프로바이더 `429` 는 다른 층이다** — 메트릭에서 `api.reject{code}` 와 `llm.call.retry{reason}` 로 나눈다.

### 1.3 소유권 — 남의 것은 없는 것으로

`{id}` 가 붙은 모든 엔드포인트(`/curriculums/{id}` · `/lessons/{id}` · `/jobs/{id}` · `POST /reviews` 의 `flashcardId`)는 주인을 확인하고, 아니면 **`404`** 다. *"그 id 가 있다"* 는 사실도 남의 정보다. 관리자 전용 `GET /system/status` 도 관리자가 아니면 `404`.

### 1.4 우리 API 레이트리밋

| 대상 | 한도 |
|---|---|
| 로그인한 사용자 | 분당 120회(폴링 30회 + 화면 이동 여유) |
| 로그인 전(`/auth/*`) | IP 당 분당 20회 |

프로세스 안 **메모리 버킷**이다(서버가 한 대). 비용 방어가 아니라 폴링 폭주 · 스크립트 남용 방어다. `loadtest` 프로필에서는 끈다.

### 1.5 새 잡을 만드는 요청의 공통 검사

`POST /curriculum-requests` · `POST /curriculums/{id}/regenerate` · `POST /lessons/{id}/materials`(신규 · 처음부터 다시 만들기) · `POST /jobs/{id}/retry` 는 적재 전에 **킬스위치 → 쿼터 → 진행 중 2개** 순서로 검사한다([04 §7.1](./04-jobs-and-cost.md)).

---

## 2. 커리큘럼 — U2 · U3

| | 엔드포인트 | 응답 |
|---|---|---|
| 목록 | `GET /curriculums` | 진행 중 · 완료 + 진도 + `nextLesson: { id, orderNo, title }`(순서상 처음으로 안 끝난 강의, `ACTIVE` 가 아니면 `null`) |
| 초기 생성 접수 | `POST /curriculum-requests` `{ requestId, topic, purpose }` | `202 { jobId }` — 입력을 초기 잡 I에 먼저 저장. 커리큘럼/그룹 ID는 성공 후 반환 |
| 초기 요청 복귀 | `GET /curriculum-requests` | 본인 초기 잡 중 `QUEUED` / `RUNNING` / `FAILED`의 `{ jobId, topic, purpose, status }` 목록. 결과가 아직 없는 요청을 커리큘럼 목록과 구분 |
| 실패한 요청 지우기 | `DELETE /curriculum-requests/{jobId}` | `204`. `FAILED` 초기 잡만 — 잡 행을 지우고 그 잡의 `llm_calls.job_id` 를 NULL 로 해제한다(쓴 비용은 원장에 남고 `job_events` 는 FK 로 연결만 풀린다). `QUEUED` · `RUNNING` · `SUCCEEDED` 면 `409 JOB_NOT_FAILED`, 없거나 남의 것이면 `404`. 화면은 묻지 않고 지운다([06 §2.7](./06-screens.md)) |
| 상세 | `GET /curriculums/{id}` | 강의 목록 · 키워드 · `masteryReflection` · `generation` |
| 다시 짜기 | `POST /curriculums/{id}/regenerate` `{ feedback? }`(500자 이내) | `202 { jobId }` · 오늘 3회를 다 썼으면 `409 REGENERATION_LIMIT` |
| 승인 | `POST /curriculums/{id}/approve` | `DRAFT → ACTIVE`. `DRAFT` 가 아니면 `409 CURRICULUM_NOT_DRAFT`, 활성 생성 잡이 있으면 `409 CURRICULUM_GENERATING`, 검증된 강의 목록이 없으면 `409 CURRICULUM_NOT_READY` |
| 완전 삭제 | `DELETE /curriculums/{id}` | `204`. `DRAFT` / `ACTIVE` / `COMPLETED` 및 생성 중에도 허용. 단일 결과를 연결한 초기 잡 I와 연결 잡 A/B · 강의 · 자료 · 플래시카드 · 카드 상태/복습 이력 · 해당 자료의 판정 기록을 한 트랜잭션으로 삭제([02 §6.2](./02-domain-data.md)). 다른 커리큘럼의 기록과 생성 비용 합계는 유지. 없거나 남의 것이면 `404` |

```jsonc
// GET /curriculums/{id}
{
  "id": 12, "title": "경제 기초", "topic": "경제 기초", "status": "DRAFT",
  "regenerationsToday": 1, "regenerationDailyLimit": 3,                // 한국 날짜 기준 · 0시에 다시 0
  "masteryReflection": [ { "concept": "기회비용", "action": "EXCLUDED" } ],
  "lessons": [ { "id": 101, "orderNo": 1, "title": "…", "objective": "…",
                 "keywords": ["기회비용", "희소성"], "status": "NOT_STARTED",
                 "hasMaterial": false, "generating": false } ],      // generating 은 파생값 — 진행 중 잡이 있나
  "generation": { "jobId": 90, "status": "FAILED" }                   // 이 커리큘럼의 최근 잡 · 없으면 null
}
```

초기 접수 응답에는 `jobId`만 있다. 화면은 이 ID로 대기 상태에 연결해 `GET /jobs/{jobId}`를 폴링하며, URL에 `jobId`를 보존해 새로고침 뒤에도 같은 잡으로 복귀한다. 성공한 `result.kind`/ID에 따라 단일 커리큘럼 검토 또는 그룹 분할안 검토로 연결한다. 결과 리소스가 없을 때 가짜 커리큘럼 ID로 U3 상세를 조회하지 않는다. 다른 기기/재로그인에는 `GET /curriculum-requests`의 미완료 요청과 실제 결과 리소스 목록에서 복귀한다.

접수 시 기존 인증/입력 길이/킬스위치/쿼터/진행 중 2개 검사를 적용하고 같은 트랜잭션에서 입력과 잡을 저장한다. 실패한 초기 잡은 `POST /jobs/{jobId}/retry`로 같은 입력/체크포인트/예산을 유지해 재개한다. 기존 거절/호출 상한/입력 초과의 재시도 제한도 적용한다. 승인 대상은 성공 후 만들어진 커리큘럼이며 초기 실패에는 승인하거나 삭제할 커리큘럼이 없다. **초기 요청 중복 처리 방향은 확정했다.** 화면이 생성 요청마다 `requestId`를 만들고 같은 요청 재전송에는 같은 ID를 사용한다. 서버는 같은 사용자/요청 ID/내용이면 기존 작업을 반환하고 추가 작업을 만들지 않는다. 실패한 작업을 반환해도 자동 재실행하지 않으며 재시도는 기존 별도 동작이다. 사용자가 새로 생성하는 요청은 같은 주제라도 새 ID를 쓴다. 식별자 저장/내용 충돌 검사/삭제 뒤 재전송/동시 접수와 검사 순서는 해당 TASK에서 설계한다. 삭제한 결과를 재전송으로 되살리지 않는 기존 원칙은 유지한다. 사용자는 **실패한** 초기 요청만 지울 수 있다(위 표). 짜는 중인 요청은 지우지 않는다 — 수십 초면 끝나고 이미 나간 호출은 되돌릴 수 없다.

기간 · 주당 학습량 입력과 예상 완료 기간/주차별 일정 응답은 없다. 다시 짜기는 강의 내용 · 범위 · 깊이 · 권장 순서를 조정하며 일정 조정은 제공하지 않는다([03 §2](./03-generation.md)).

승인은 최근 잡이 `FAILED` 여도 이전에 성공한 목록이 남아 있으면 가능하다. 승인과 다시 짜기 적재 · 커리큘럼 잡 재시도 · 결과 저장은 같은 커리큘럼 잠금 안에서 검사와 변경을 수행한다. 다시 짜기는 `DRAFT` 에서만 가능하며, 승인 후 예전 실패 잡의 재시도는 `409 JOB_NOT_RETRYABLE` 로 막는다. 재시도가 먼저 적재되면 승인 요청은 `409 CURRICULUM_GENERATING` 이다.

삭제 전에 화면에서 연결된 학습 기록과 플래시카드도 삭제되고 되돌릴 수 없음을 안내한다([06 §2.7](./06-screens.md)). 삭제 요청 중에는 버튼 중복 제출을 막는다. 삭제 응답이 유실돼 다시 요청했을 때 `404` 면 상세를 확인하고 이미 삭제된 것으로 화면을 정리한다. 삭제 후 해당 커리큘럼/강의/카드/잡의 조회 · 변경 요청도 `404` 이며 데이터를 다시 만들지 않는다.

---

### 2.1 분할안 · 다음 단계 생성 — 추가 계약 설계 필요

단일/그룹 구분은 LLM이 주제/학습 목적의 의미를 판단하고 사용자가 결과를 검토한다. **최초 생성 호출에서 유형 판단과 해당 결과를 함께 반환한다** — 단일이면 강의 목록, 그룹이면 분할안이다([03 §2.1](./03-generation.md)). 판단만 한 뒤 목록/분할안을 생성하는 별도 호출은 두지 않는다. 초기 요청은 `POST /curriculum-requests`로 잡 I(`CURRICULUM_INITIAL`)에 먼저 접수하고 성공할 때 해당 리소스를 생성한다. 미완료 요청은 `GET /curriculum-requests`, 상태와 결과는 `GET /jobs/{jobId}`로 복귀한다. 화면은 묶음 화면 U11 과 기존 커리큘럼 화면 U3 이며, 화면이 쓰는 데이터는 [06 §6 · §9](./06-screens.md) 에 있다.

§2 표는 초기 접수/복귀와 실제 단일 커리큘럼을 다루는 API다. 초기 생성은 빈 `DRAFT` 없이 `202 { jobId }`를 반환한다. 그룹의 특정 단계 생성은 대상이 정해져 있으므로 기존 단계 초안/잡 A 적재 모델을 유지한다. 분할안은 `CurriculumGroup`/`CurriculumStep`에 저장하고 실제 커리큘럼은 `step_id`로 연결한다([02 §6.2.1](./02-domain-data.md)). 초기 분할안 생성의 접수 · 복귀는 위 `POST /curriculum-requests` · `GET /jobs/{jobId}` 로 이미 통합됐다. 추가 API 계약이 필요한 것은 그룹(분할안) 조회 · 단계 생성 · 묶음 삭제다. 엔드포인트 이름과 요청/응답 스키마는 구현 전에 확정한다. 현재 표만으로 분할안 화면의 API가 완성됐다고 볼 수 없다.

- 조회에는 묶음 ID와 전체 목표, 단계 ID와 제목/범위/목적/순서, 연결된 커리큘럼/잡과 생성 가능 여부가 필요하다. 미생성 단계를 `DRAFT` 커리큘럼으로 가장하지 않는다
- 생성 요청은 사용자가 클릭할 때만 보낸다. `curriculum_steps.id`로 저장된 주제/목적과 묶음 소유권을 확인하고, 다음 단계는 직전 커리큘럼 `COMPLETED`를 재확인한다. 다른 사용자/없는 분할안이나 단계는 기존 소유권 규칙에 따라 `404`
- 유료 생성은 기존 비동기 워커 경로 · 킬스위치 · 비용 쿼터 · 진행 중 2개 검사를 받는다. 완료 이벤트만으로 요청을 적재하지 않는다
- 같은 단계의 동시/응답 유실 재요청은 기존 커리큘럼/잡으로 연결하며, 이미 생성된 단계에는 새 초안을 만들지 않는다. 삭제/탈퇴와의 경합 및 실패 재시도도 확장 설계에서 다룬다
- 홈은 묶음을 한 항목으로 보여 주므로 묶음 목록에는 단계마다 연결 커리큘럼의 상태 · 진도 · 다음 강의 · 활성 잡이 필요하다. 단계 커리큘럼은 `GET /curriculums` 에서 따로 항목이 되지 않게 구분할 수 있어야 한다
- **묶음 삭제** — 묶음과 연결된 단계 커리큘럼 · 그 학습 기록 · 플래시카드를 한 번에 지운다(`204`, 남의 것 · 없는 것은 `404`, 생성 중에도 허용 — [02 §6.2.1](./02-domain-data.md)). 단계 커리큘럼 하나는 기존 `DELETE /curriculums/{id}` 로 지우고 그 단계는 미생성으로 돌아간다

## 3. 강의 — U4

### 3.1 자료 생성 요청 — `POST /lessons/{id}/materials`

| 상황 | 응답 |
|---|---|
| 자료가 이미 있다 | `200 { materialId }` |
| 신규 | `202 { jobId, aheadCount }` |
| 이 강의에 진행 중인 잡이 있다 | `202` + **기존 `jobId`** — 에러가 아니다 |
| 이 강의의 최근 잡이 `FAILED` | 재시도 가능하면 `202` + 기존 `jobId` — **그 잡을 재시도한다**(산출물이 이어진다). 누적 호출 상한 소진 · 입력 상한 초과는 `409 JOB_NOT_RETRYABLE` |
| 위 두 경우에 `{ "restart": true }` | **처음부터 다시 만들기** — `202 { jobId, aheadCount }` 새 잡(새 호출 몫 · 1단계부터). 강의당 하루 2번, 넘으면 `409 LESSON_RESTART_LIMIT` |
| 검사에 걸림 | §1.5 의 `429` · `503` |

동시 요청 둘은 부분 unique 인덱스가 하나를 실패시키고, API 가 그 예외를 잡아 기존 잡을 돌려준다([04 §4.7](./04-jobs-and-cost.md)).

`restart` 는 사용자가 버튼을 눌렀을 때만 보낸다 — 강의를 다시 열 때의 자동 재시도는 새 잡을 만들지 않는다. 최근 잡이 위 두 코드로 끝난 경우가 아니면 `restart` 는 무시되고 일반 요청과 같다. 오늘 2번을 다 쓰면 그날은 `409 LESSON_RESTART_LIMIT` 이고 한국 자정에 다시 열린다([04 §4.9](./04-jobs-and-cost.md)).

### 3.2 열람 · 학습 완료

| | 엔드포인트 | 비고 |
|---|---|---|
| 열람 | `GET /lessons/{id}/material` | 정상 종료 · 필수 6섹션 검증을 통과한 마크다운 자료. 잘린 본문은 제공하지 않음 |
| 학습 완료 | `POST /lessons/{id}/complete` | 최초 완료 때 강의 상태 · 카드별 `card_states` 최초 삽입 · 전 강의 완료 시 커리큘럼 전환을 같은 트랜잭션으로 처리. 이미 완료한 강의도 `200`, 기존 FSRS/복습 상태 · 이력 변경 없음([02 §3](./02-domain-data.md)) |

```jsonc
// POST /lessons/{id}/complete → 200
{ "lessonStatus": "COMPLETED",
  "reviewCards": [ { "flashcardId": 1, "cardVersion": 0, "question": "…", "modelAnswer": "…", "keyPoints": ["…"], "concept": "기준금리",
                     "source": { "curriculumTitle": "경제 기초", "lessonOrder": 6 } } ] }   // 그 강의에서 현재 아직 첫 평가를 하지 않은 카드만
```

응답의 카드는 **편의**다. 화면이 무시하고 떠나도 미평가 카드는 이미 오늘의 복습에 들어 있다.

같은 커리큘럼 잠금 안에서 최초 완료/중복 요청을 구분한다. 응답 유실 · 동시 두 요청 · 커리큘럼 전체 완료 후 재요청 모두 완료한 강의에는 `200` 이며, 카드 상태를 초기화하거나 복습 기록을 새로 만들지 않는다. `reviewCards` 는 현재 `review_count=0` 인 해당 강의 카드와 현재 버전이다. 그 사이 평가한 카드가 있으면 최초 응답과 목록이 다를 수 있고, 모두 평가했으면 빈 배열이다. 첫 복습 버튼은 남은 카드가 있을 때만 표시한다. 최초 완료는 `ACTIVE` 커리큘럼의 준비된 강의에만 허용하며, 삭제/소유권 없음은 중복 요청에도 `404` 다.

---

## 4. 잡 — 초기 생성 대기 · U3 · U4

```
GET /jobs/{jobId}          Cache-Control: no-store       — 화면은 2초마다
```

```jsonc
{ "jobId": 77, "status": "RUNNING",          // QUEUED | RUNNING | SUCCEEDED | FAILED
  "step": 2, "totalSteps": 3, "stepName": "본문 작성",
  "aheadCount": null,                        // QUEUED 일 때만 — 수령 순서(available_at, 같으면 id)로 나보다 앞선 QUEUED 잡 수. 0 이면 다음 차례.
                                             //   화면은 이 값을 그대로 「앞에 N명」 으로 쓴다(잡 하나를 한 명으로 부른다 — 같은 사용자의 잡 둘은 둘로 센다).
                                             //   enqueued_at 으로 세면 재시도한 잡(available_at = now())이 실제로는 줄 끝인데 "앞에 0명"으로 보인다
  "retried": true,                           // 재시도가 한 번이라도 있었나 — 화면은 "평소보다 조금 더 걸려요" 로만
  "result": null,                            // SUCCEEDED일 때만 { kind, id }. 아래 결과 계약 참조
  "failure": null }                          // FAILED 면 { code, message }
```

**결과 연결 계약** — `result`는 성공 전 NULL이다. 잡 I의 단일 결과는 `{ "kind": "CURRICULUM", "id": 12 }`, 그룹 결과는 `{ "kind": "CURRICULUM_GROUP", "id": 8 }`다. 잡 A는 `CURRICULUM`, 잡 B는 `MATERIAL`과 해당 ID를 반환한다. 초기 잡 I의 결과 종류는 연결된 FK에서 읽고 `jobs.type`을 바꾸지 않는다. 결과 생성/연결/잡 성공은 같은 커밋이므로 성공인데 결과 ID가 없는 응답을 만들지 않는다. 삭제된 잡과 남의 잡은 `404`다. 내부 `payload`/체크포인트는 사용자 응답으로 반환하지 않는다.

토큰 · 비용은 사용자 응답에 싣지 않는다 — 사용자가 할 수 있는 게 없는 정보다. 관리자 U6 에 있다.

**재시도** — `POST /jobs/{jobId}/retry` → `202 { jobId, aheadCount }`. 같은 행을 되살려 **실패한 단계부터** 재개한다. §1.5 검사를 똑같이 받는다.

초기 잡 I의 재시도는 커리큘럼/그룹이 아직 없으므로 계정/잡 소유권과 실패 상태를 확인하고, 같은 입력/체크포인트/누적 호출 몫을 유지한다. 이미 성공한 초기 잡은 재시도로 새 결과를 만들지 않는다. 대상이 정해진 커리큘럼 잡 A는 해당 커리큘럼이 여전히 `DRAFT` 인지 잠금 안에서 확인한다. 승인 후에는 `409 JOB_NOT_RETRYABLE` 이며, 재적재 · 추가 외부 호출 · 강의 목록 변경을 하지 않는다. 같은 커리큘럼에 이미 활성 생성 잡이 있으면 `409 CURRICULUM_GENERATING` 이다.

누적 외부 호출 이력과 적재 시 확정한 상한을 유지한다. `JOB_CALL_LIMIT_EXCEEDED` 는 `409 JOB_NOT_RETRYABLE` 이며, 강의 다시 열기의 자동 재시도도 같은 규칙을 따른다. 소진한 실패 잡을 자동으로 새 행으로 바꿔 주지 않는다 — 강의 자료는 사용자가 처음부터 다시 만들기를 고를 때만 새 잡이다(§3.1). **재시도 거절은 실패 코드(주제 거절 · 호출 상한 소진 · 입력 초과)와 커리큘럼 상태로만 판단한다.** 다음 단계가 어떤 호출을 얼마나 쓸지는 API 가 따지지 않는다 — 그건 생성 단계의 지식이라 API 쪽(`core.job`)이 알면 패키지 경계가 깨진다([04 §2](./04-jobs-and-cost.md)). 다른 실패 코드의 재시도에서 몫이 모자라면 첫 호출 등록이 외부 전송 없이 `JOB_CALL_LIMIT_EXCEEDED` 로 끝내고, 그때 강의 자료는 처음부터 다시 만들기가 열린다. 모든 새 외부 전송은 등록 시 다시 검사한다([04 §4.8](./04-jobs-and-cost.md)).

## 5. 실패 코드 — `failure.code`

| `code` | 언제 | 다시 시도 |
|---|---|---|
| `DEADLINE_EXCEEDED` | 잡 시한 10분 초과 | ✅ |
| `LLM_OUTPUT_INVALID` | 재요청까지 파싱 실패 · 개요 없음 · 섹션 누락/빈 섹션 · 이어 쓰기 뒤에도 본문 잘림 | ✅ — 본문 실패는 검증된 개요를 유지하고 ②부터 새 본문 생성 |
| `LLM_UNAVAILABLE` | 재시도 소진(429 · 5xx · 타임아웃) · `retry-after` > 60초 | ✅ |
| `USAGE_RECORD_FAILED` | 호출 등록 · 사용량 기록의 DB 재시도 소진. 추가 외부 호출을 멈춤 | ✅ — DB 복구 후 |
| `JOB_CALL_LIMIT_EXCEEDED` | 작업 전체 누적 외부 시도 상한 소진. 재시작 · 사용자 재시도로 초기화하지 않음 | ❌ — 강의 자료는 처음부터 다시 만들기(§3.1 · 강의당 하루 2번) |
| `INPUT_TOO_LARGE` | 시스템 · 대화 이력 · 툴 결과 · 스키마를 포함해 직렬화한 요청 전체의 UTF-8 바이트 수가 단계별 바이트 상한 초과([03 §8.7](./03-generation.md)). 해당 요청 전송 없음 | ❌ — 같은 입력은 다시 보내지 않음. 강의 자료는 처음부터 다시 만들기(§3.1 · 강의당 하루 2번) |
| `LLM_REQUEST_REJECTED` | 프로바이더가 요청 거부(400 · 401 · 403 · 404 · 413) — **우리 버그** | ✅ |
| `PROVIDER_SPEND_CAP` | 프로바이더 크레딧 소진(`insufficient_quota`) | ❌ |
| `TOPIC_REJECTED` | 초기 잡 I 또는 잡 A가 주제를 거절했다 — 위험 · 불법 · 연습형([03 §2](./03-generation.md)) | ❌ — 같은 주제는 같은 결과다. 새 주제로 만든다 |

커리큘럼 삭제 시 연결 잡도 삭제되므로 조회/재시도는 `404` 다. `TOPIC_REJECTED` 잡에 `POST /jobs/{id}/retry` 를 부르면 `409 JOB_NOT_RETRYABLE` — 같은 주제는 다시 돌려도 같은 거절에 돈만 쓴다. `PROVIDER_SPEND_CAP` 은 화면에 버튼이 없을 뿐 API 는 막지 않는다 — 크레딧을 채운 뒤 강의를 다시 열면 그 잡이 재시도된다(§3.1).

위 표의 ✅는 재시도를 받는다는 뜻이고, 누적 몫이 모자라면 재시도된 잡이 첫 호출 등록에서 외부 전송 없이 `JOB_CALL_LIMIT_EXCEEDED` 로 끝난다. 일일 쿼터가 새 날짜에 초기화되어도 작업 전체 호출 상한은 유지된다.
`INPUT_TOO_LARGE` 도 재시도 API · 강의 다시 열기의 자동 재시도에서 `409 JOB_NOT_RETRYABLE` 이다. 이전 단계 산출물과 실패한 다시 짜기의 이전 커리큘럼을 보존한다. 설정 오류는 `LLM_REQUEST_REJECTED` 로 분류해 운영자가 구성 수정 후 재시도할 수 있다.

---

## 6. 복습 — U5

| | 엔드포인트 | 비고 |
|---|---|---|
| 오늘의 복습 | `GET /reviews/due` | **한국 날짜로 오늘 안에 예정된 카드 전부** — 일일 개수 제한 없이 오래 밀린 순, 동률이면 카드 ID 순([02 §8.1](./02-domain-data.md)) |
| 평가 | `POST /reviews` `{ reviewRequestId, flashcardId, rating, expectedCardVersion }` | 같은 요청은 최초 성공 응답 반환. 새 요청은 카드 버전 검사 후 FSRS 갱신 + 복습 기록을 한 트랜잭션으로 저장 |

```jsonc
// GET /reviews/due
{ "cards": [ { "flashcardId": 1, "cardVersion": 3, "question": "…", "modelAnswer": "…", "keyPoints": ["…"], "concept": "기준금리",
               "source": { "curriculumTitle": "경제 기초", "lessonOrder": 6 } } ],   // 출처 — 섞여 나와도 맥락을 알게
  "overdueCount": 4 }

// POST /reviews → 200
{ "nextReviewAt": "2026-10-02T09:00:00Z", "remaining": 7 }
```

복습이 밀려도 다음 강의는 막지 않는다 — `overdueCount` 로 배지만 띄운다. 평가 되돌리기는 없다(알려진 한계).

일일 복습 장수로 조회/평가를 막지 않는다. `remaining` 은 평가 후 해당 사용자의 오늘까지 예정된 카드 수이며, 현재 세션의 진행률은 화면에 고정한 목록으로 계산한다. 사용자는 도중에 나갈 수 있고 저장한 평가까지 유지된다. 다음 복습일은 기존 FSRS 규칙을 따른다.

**평가의 멱등성** — `reviewRequestId`는 평가를 누를 때 생성하는 UUID다. 응답 유실 · 네트워크 오류 후 재시도는 **같은 ID · 같은 요청 내용**을 보낸다. `expectedCardVersion`에는 목록 또는 학습 완료 응답의 `cardVersion`을 넣는다.
- 이미 성공한 동일 요청이면 현재 카드 버전과 관계없이 최초 `200` 응답을 그대로 반환한다. FSRS와 복습 횟수를 다시 갱신하지 않는다.
- 다른 요청이 먼저 평가해 버전이 바뀌었으면 `409 REVIEW_ALREADY_PROCESSED`. 화면은 「이미 평가한 카드예요.」를 알리고 해당 카드를 건너뛴다. 최신 버전으로 자동 재제출하지 않는다.
- 같은 요청 ID에 다른 내용을 보내면 `409 REVIEW_REQUEST_CONFLICT`. 자동 재시도하지 않고 복습 목록을 다시 불러오게 한다.
- 처리 후 복습 목록 · 배지 · 남은 카드 수를 갱신한다. 재전송의 최초 응답에 담긴 장수는 최신값으로 간주하지 않는다. 저장 순서는 [02 §6.5](./02-domain-data.md).

---

## 7. 시스템 상태 — U6 (관리자)

```
GET /system/status          관리자 아니면 404 · 화면은 진입 시 1회 + 새로고침 버튼만
```

```jsonc
{ "asOf": "2026-10-11T05:32:05Z",                // 이 응답의 모든 시간창에 사용하는 DB 기준 시각
  "jobs":        { "queued": 3, "running": 2, "executors": 4, "oldestQueuedSeconds": 40 },
  "cost":        { "todayTokens": 84000, "todayCost": 620, "killSwitchRemaining": 9380, "unknownCalls": 2 },
  "reliability": { "rate429": 0.02, "retries": 5, "failureRate": 0.01, "reclaimed": 0,     // 최근 24시간
                   "succeededAttempts": 99, "failedAttempts": 1,                         // failureRate의 분모는 둘의 합
                   "stuckLessons": 0 },                                                    // 실패한 채 남은 강의 — 날짜 제한 없음
  "conceptMatch": { "linked": 41, "split": 3, "created": 128,
                    "borderline":      [ { "userId": 7, "logId": 3051,
                                           "inputName": "엔트로피", "inputDefinition": "정보의 불확실성을 나타내는 양",
                                           "conceptId": 201, "concept": "엔트로피", "conceptDefinition": "열역학계의 무질서도를 나타내는 상태량",
                                           "sim": 0.48 } ],
                    "mergeCandidates": [ { "userId": 7,
                                           "keepId": 12, "keepName": "인플레이션", "keepDefinition": "전반적인 물가 수준의 지속적인 상승",
                                           "dropId": 57, "dropName": "inflation", "dropDefinition": "물가가 계속 오르는 현상",
                                           "sim": 0.77 } ] },
  "users":       { "active": 14, "cap": 25, "waitlisted": 3, "activeToday": 6 } }
```

**전부 DB 에서 읽는다** — 운영 서버에 Prometheus 가 없다. `jobs` · `job_events` · `llm_calls` · `concept_match_logs` · `users`. 비용 합계는 원장을 오늘 날짜로 더한 값이다([02 §6.6](./02-domain-data.md)).

**신뢰성 집계 계약** — DB의 기준 시각을 한 번 정해 `asOf`로 반환하고 `[asOf - 24시간, asOf)`를 최근 24시간으로 쓴다. 날짜를 ‘오늘’로 잘라 계산하지 않는다.

- `succeededAttempts` · `failedAttempts` · `reclaimed`: `job_events.occurred_at`이 창 안인 각 이벤트 건수. 현재 `jobs.status`와 조인해 과거 결과를 버리지 않는다. 삭제로 `job_id`가 NULL인 이벤트도 포함한다
- `failureRate`: `failedAttempts / (succeededAttempts + failedAttempts)`. 합이 0이면 NULL. 재시도 전 실패와 재시도 후 성공을 각각 세는 **종결된 실행 기준**이며 고유 작업의 최종 실패율/가용성이 아니다. 주제 거절 등 정식 `FAILED`도 포함하며 실패 원인은 이벤트의 고정 코드로 진단한다
- `rate429`: 창 안에 `finished_at`이 있고 HTTP 응답 코드가 기록된 `llm_calls` 중 `http_status=429`의 비율. HTTP 코드가 없는 미확인/타임아웃은 분모에서 제외한다. 해당 HTTP 응답이 0개면 NULL
- `retries`: 창 안의 `registered_at`과 `is_retry=true`를 가진 `llm_calls` 건수. **외부 호출 재시도 등록 횟수**이며 잡 재실행 횟수가 아니다. 등록 후 실제 전송 여부가 미확인인 시도도 포함한다

성공/실패/회수의 저장 원자성 · 삭제 후 연결 해제는 [02 §6.9](./02-domain-data.md)에 따른다. 일반 사용자 API에는 이 운영 이력을 노출하지 않는다.
`stuckLessons` 는 자료 없이 최근 잡이 `JOB_CALL_LIMIT_EXCEEDED` · `INPUT_TOO_LARGE` 로 실패한 채 남은 강의 수다([04 §4.9](./04-jobs-and-cost.md)). 사용자는 처음부터 다시 만들기(강의당 하루 2번)로 풀 수 있지만, 이 수가 계속 남아 있으면 장애 · 입력 초과가 이어지고 있다는 신호다 — 사용자가 알릴 경로가 없으니 관리자가 여기서 본다.
`todayCost` 는 확인된 사용량을 설정 단가로 환산한 오늘 합계다. `unknownCalls` 는 날짜 제한 없이 남아 있는 `UNKNOWN` + 시한 지난 `PENDING` 건수로, 오늘 비용에 들어 있지 않은 과금 가능성을 함께 보여 준다. 실제 청구액과의 일치를 뜻하지 않는다.
둘 다 `concept_match_logs` 에서 읽고 방향이 반대다([02 §6.4](./02-domain-data.md)). `borderline` 은 임계값 **위** +0.05 안에서 연결한 판정 — 잘못 합쳤을 수 있는 것이다. 들어온 이름 · 정의와 연결된 개념의 정의를 나란히 보고 판단하고, 되돌리기 SQL 에는 `logId` 를 쓴다([02 §5.3](./02-domain-data.md)). `mergeCandidates` 는 이름 다름 분기에서 임계값 **아래** −0.05 안으로 새로 만든 쌍 — 놓친 동의어다. **임계값을 옮길지, 이 쌍만 고칠지**를 같은 자리에서 판단한다. 개념 사전이 사용자별이라 쌍은 늘 같은 사용자의 개념이고, `userId` 는 병합 SQL 의 사용자 락에 쓴다.

---

## 8. 화면 ↔ 엔드포인트

| 화면 | 엔드포인트 |
|---|---|
| U1 로그인 | `GET /auth/google` · `/callback` · `GET /me` |
| U2 홈 | `GET /curriculums` · `GET /curriculum-requests` · `POST /curriculum-requests` · `DELETE /curriculum-requests/{jobId}` · `GET /jobs/{id}` · `GET /reviews/due` · `GET /me` · 묶음 목록 · 단계 생성(§2.1) |
| U10 커리큘럼 만드는 중 | `GET /jobs/{id}` · `GET /curriculum-requests` · `POST /jobs/{id}/retry` · `DELETE /curriculum-requests/{jobId}` |
| U3 커리큘럼 | `GET /curriculums/{id}` · `/approve` · `/regenerate` · `DELETE /curriculums/{id}` · `GET /jobs/{id}` · `POST /jobs/{id}/retry` · 단계 커리큘럼이면 묶음 · 단계 정보와 단계 생성(§2.1) |
| U11 커리큘럼 묶음 | 묶음 조회 · 단계 생성 · 묶음 삭제(§2.1) |
| U4 강의 | `POST /lessons/{id}/materials` · `GET /jobs/{id}` · `POST /jobs/{id}/retry` · `GET /lessons/{id}/material` · `POST /lessons/{id}/complete` |
| U5 복습 | `GET /reviews/due` · `POST /reviews` |
| U6 시스템 상태 | `GET /system/status` |
| U7 가입 · U8 대기 · U9 설정 | `POST /me/terms` · `GET /me` · `POST /auth/logout` · `DELETE /me` |

**현재 계약 22개**(계정 6 · 초기 요청 3 · 커리큘럼 5 · 강의 3 · 잡 2 · 복습 2 · 상태 1, `/dev/session` 제외). 그룹 조회/단계 생성/묶음 삭제 API는 §2.1에서 추가 설계 중이며 전체 개수는 확정 후 갱신한다. U2 · U3 · U11 이 이 API 를 쓴다.

**만들지 않는 것** — 커리큘럼 목록 페이징(사용자당 몇 개뿐) · `/v1/` 경로 버저닝 · 개념 병합 · 정원 · 대기 승인 관리 API(SQL 로) · SSE.
