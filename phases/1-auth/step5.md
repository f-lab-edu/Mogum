# Step 5: pkce-cookie

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 25 · 27, 작업 방식(TDD 짝 테스트 규칙)
- `docs/05-api.md` §1.1(인증 — 로그인 전에는 세션을 만들지 않고 `state` 를 짧은 수명 쿠키에 둔다 · 콜백 실패는 `?error=login_failed`) · §1.2(`NOT_FOUND`)
- `docs/ADR.md` ADR-062
- 이전 step 산출물:
  - `src/main/java/com/mogum/api/auth/CookieAuthorizationRequestRepository.java` — OAuth 요청 쿠키(`MOGUM_OAUTH_REQUEST`)
  - `src/main/java/com/mogum/api/auth/SecurityConfiguration.java` — `/auth/{registrationId}` 해석기 · `oauth2Login` 배선
  - `src/main/java/com/mogum/api/auth/GoogleLoginHandler.java`
  - `src/test/java/com/mogum/api/auth/GoogleLoginHandlerIT.java` — 가짜 구글 서버(`fakeGoogle()` 의 `/token` · `/userinfo`)
  - `src/test/java/com/mogum/api/auth/CookieAuthorizationRequestRepositoryTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

phase 리뷰 중에 찾은 **step 3 의 결함 수정**이다. 지금 코드로는 실제 구글 로그인이 언제나 실패한다.

### 1. 결함 — PKCE 의 `code_verifier` 를 잃는다

- Spring Security 7.1.1 은 `ClientRegistration.ClientSettings` 의 `requireProofKey` 기본값이 `true` 다(jar 바이트코드로 확인했다). 그래서 `DefaultOAuth2AuthorizationRequestResolver` 는 기밀 클라이언트(클라이언트 비밀이 있는 경우)에도 PKCE 를 적용한다. 구글 동의 주소에 `code_challenge` · `code_challenge_method=S256` 이 붙고, 짝이 되는 `code_verifier` 는 인가 요청의 attributes 에 들어간다.
- `CookieAuthorizationRequestRepository` 는 쿠키에 `state` · 발급 시각만 저장하고, 복원할 때 attributes 에 `registration_id` 만 넣는다. 그래서 토큰 교환 요청에 `code_verifier` 가 빠진다.
- 실제 구글은 동의 단계에서 `code_challenge` 를 받았는데 토큰 요청에 `code_verifier` 가 없으면 `invalid_grant` 로 거절한다. 그 결과 실제 로그인은 언제나 `?error=login_failed` 로 끝난다.
- 테스트가 통과한 이유: 가짜 구글 토큰 엔드포인트가 PKCE 를 검사하지 않는다.

### 2. 고칠 것 — PKCE 는 켠 채로, `code_verifier` 를 쿠키로 왕복시킨다

- **PKCE 를 끄지 마라**(`requireProofKey(false)` 등 금지). 이유: 인가 코드가 새어도 쓸 수 없게 하는 방어다 — OAuth 보안 권고는 기밀 클라이언트에도 PKCE 를 권한다.
- 인가 요청을 저장할 때 attributes 의 `code_verifier` 를 쿠키 JSON 에 함께 담는다. 복원할 때 attributes 에 되살려 Spring Security 의 토큰 교환이 `code_verifier` 를 보내게 한다.
- 쿠키의 다른 성질은 그대로 둔다 — JSON(Java 직렬화 금지) · `HttpOnly` · `Secure` · `SameSite=Lax` · `Max-Age=600` · `Path=/auth/google` · 콜백 뒤 만료. 쿠키 값 길이 상한 · `state` 검사 · 망가진 쿠키를 실패로 다루는 기존 동작을 유지한다. 복원한 `code_verifier` 도 형식(RFC 7636 — 43~128자, `[A-Za-z0-9-._~]`)을 검사하고 어긋나면 복원하지 않는다(실패로 끝난다).
- 엔드포인트 · 클라이언트 id · 콜백 주소 · 범위는 지금처럼 서버 설정에서 복원한다 — 쿠키에서 믿는 값을 `state` · 발급 시각 · `code_verifier` 로 한정한다.
- `code_verifier` 를 로그 · 에러 응답에 남기지 마라.

### 3. 테스트 — 가짜 구글이 PKCE 를 검사하게 한다

TDD 가드: 구현 파일마다 같은 패키지에 `{클래스명}*Test.java` 또는 `{클래스명}*IT.java` 가 먼저 있어야 쓰기가 허용된다. **고치기 전에 아래 테스트가 실패하는 것을 먼저 확인한다.**

- `GoogleLoginHandlerIT` 의 가짜 구글을 실제 구글처럼 바꾼다 — 동의 주소의 `code_challenge` 를 기억하고, `/token` 요청의 `code_verifier` 를 SHA-256 → base64url(패딩 없음)한 값이 그 challenge 와 다르거나 `code_verifier` 가 없으면 `400 {"error":"invalid_grant"}` 를 돌려준다. 다른 기존 시나리오도 이 검사를 거친 채로 통과해야 한다.
- `/auth/google` 의 동의 주소에 `code_challenge` 와 `code_challenge_method=S256` 이 있다.
- 관리자 콜백 성공 흐름이 PKCE 검사를 통과해 세션이 만들어진다.
- 쿠키의 `code_verifier` 를 다른 값으로 바꾸거나 빼면 가짜 토큰 엔드포인트가 거절하고 `…/login?error=login_failed` 이며 사용자 · 세션 행이 없다. 형식이 틀린 `code_verifier` 는 토큰 엔드포인트를 부르기 전에 실패한다.
- `CookieAuthorizationRequestRepositoryTest` — 저장 → 복원 왕복에서 `code_verifier` 가 그대로 돌아온다.

### 4. 함께 고칠 것 — 등록되지 않은 `/auth/{id}`

- 지금 `GET /auth/foo` 처럼 등록되지 않은 한 단계 경로는 해석기가 `InvalidClientRegistrationIdException` 을 던져 `500` · 에러 로그가 된다(step 3 리뷰 지적).
- `404 {"code":"NOT_FOUND"}`(05 §1.2 형식)로 바꾸고 에러 로그를 남기지 않는다. `/auth/google` · 콜백 동작은 바꾸지 않는다.
- 실제 HTTP 로 확인한다(서블릿 ERROR 디스패치까지 거치게 — MockMvc 만으로 확인하지 마라).

### 5. 문서

- `docs/05-api.md` §1.1 — OAuth 요청 쿠키에 담는 값(`state` · 발급 시각 · PKCE `code_verifier`)과 PKCE 를 켜 둔다는 사실을 해당 문장에 반영한다.

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
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- PKCE 를 끄지 마라. 이유: 위 2 — 결함은 PKCE 가 아니라 `code_verifier` 를 버린 저장소에 있다.
- OAuth 요청 정보를 세션에 두는 방식(`HttpSessionOAuth2AuthorizationRequestRepository`)으로 돌아가지 마라. 이유: 로그인 전에는 세션을 만들지 않는다(05 §1.1). step 1 의 `SessionUserFilter` 는 사용자 id 없는 세션을 무효화한다.
- 쿠키 값을 Java 직렬화로 복원하지 마라. 이유: 역직렬화 공격 경로가 된다.
- 실제 구글을 테스트에서 부르지 마라. 이유: 테스트가 네트워크 · 실제 비밀에 묶인다.
- 화면 코드(`frontend/`)를 고치지 마라. 이유: 이 step 은 백엔드 결함 수정이다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라(가짜 구글의 PKCE 검사 추가로 바뀌는 테스트는 그 검사를 통과하도록 고친다).
