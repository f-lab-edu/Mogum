# Step 2: cross-origin

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 화면 · 운영 설명(화면 `{도메인}` · API `api.{도메인}` — 같은 사이트 · 다른 출처)
- `docs/05-api.md` §1.1 의 "화면과 API 의 출처" 목록 전체
- `docs/ADR.md` ADR-060
- `docs/04-jobs-and-cost.md` §3(배포 — 공개 운영에 더하는 것)
- 이전 step 산출물: step 1 의 보안 설정 · CSRF 쿠키 설정 · 세션 테스트 지원 코드(`src/test/java/com/mogum/support/`) · `src/main/resources/application.yml`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

운영에서 화면은 `https://{도메인}`(Vercel), API 는 `https://api.{도메인}`(EC2)이다 — **같은 사이트 · 다른 출처**다(ADR-060). 개발은 Vite proxy 로 같은 출처라 이 조건이 드러나지 않으므로 **테스트로 확인한다.** 이 step 은 CORS 와 `XSRF-TOKEN` 쿠키 도메인을 설정하고, 출처가 다른 조건의 테스트를 쓴다.

### 1. 설정 키

| 키 | 뜻 | 로컬 기본값 |
|---|---|---|
| `app.web.origin` | 화면 출처 하나(스킴 · 호스트 · 포트, 끝 `/` 없음). CORS 허용 출처이고, step 3 에서 로그인 뒤 보낼 화면 주소로도 쓴다 | `http://localhost:5173` |
| `app.web.cookie-domain` | `XSRF-TOKEN` 쿠키의 `Domain`. 비어 있으면 `Domain` 을 두지 않는다 | 비어 있음 |

환경 변수로 덮어쓸 수 있게 한다. 운영 값(`https://{도메인}` · `{도메인}`)은 `5-deploy` 에서 넣는다 — 이 step 은 실제 도메인을 적지 않는다.

### 2. CORS

- `/api/**` 에 적용한다. 허용 출처는 **`app.web.origin` 하나**이고 credentials 를 허용한다.
- `*` · 출처 패턴(`allowedOriginPatterns`) · 여러 출처를 쓰지 않는다(05 §1.1).
- 허용 메서드는 `GET` · `POST` · `PUT` · `PATCH` · `DELETE`, 허용 요청 헤더는 최소한 `Content-Type` · `X-XSRF-TOKEN`.
- CORS 는 Spring Security 필터 체인 안에서 처리해, 인증 · CSRF 보다 먼저 preflight 가 응답되게 한다.

### 3. `XSRF-TOKEN` 쿠키 도메인

- `app.web.cookie-domain` 이 있으면 `XSRF-TOKEN` 쿠키에 그 `Domain` 을 둔다 — 화면 스크립트(`{도메인}`)가 API(`api.{도메인}`)가 내려준 쿠키를 읽어야 한다(05 §1.1).
- **세션 쿠키에는 `Domain` 을 두지 않는다** — API 호스트에만 있다. 같은 사이트라 `SameSite=Lax` 그대로 화면의 요청에 실린다.
- step 1 의 나머지 쿠키 속성(`XSRF-TOKEN`: `HttpOnly` 아님 · `Secure` · `SameSite=Lax` / 세션: `HttpOnly` · `Secure` · `SameSite=Lax`)은 그대로 유지한다.

### 4. 테스트

TDD 가드: 구현 파일마다 같은 패키지에 `{클래스명}*Test.java` 또는 `{클래스명}*IT.java` 가 먼저 있어야 쓰기가 허용된다.

운영과 같은 모양의 값으로 컨텍스트를 띄운다(예: `app.web.origin=https://mogum.example` · `app.web.cookie-domain=mogum.example`). 테스트 전용 값은 `@SpringBootTest(properties = …)` 등으로 준다. 세션은 step 1 의 테스트 지원 코드로 만든다.

- 허용 출처의 preflight(`OPTIONS /api/me` · `Origin: https://mogum.example` · `Access-Control-Request-Method: POST` · `Access-Control-Request-Headers: X-XSRF-TOKEN`) → 허용. `Access-Control-Allow-Origin` 이 그 출처 그대로이고(`*` 아님) `Access-Control-Allow-Credentials: true`.
- 다른 출처의 preflight — `https://evil.example` · PR 미리보기 모양 `https://mogum-git-x.vercel.app` · 같은 등록 도메인의 다른 서브도메인 `https://other.mogum.example` → 거절되고 `Access-Control-Allow-Origin` 이 없다.
- 허용 출처에서 세션 쿠키로 `GET /api/me` → `200` + CORS 응답 헤더. 받은 `XSRF-TOKEN` 쿠키의 `Domain=mogum.example`.
- 허용 출처에서 세션 쿠키 + `XSRF-TOKEN` 쿠키 + 같은 값의 `X-XSRF-TOKEN` 헤더로 `POST /api/auth/logout` → `204`. 헤더 없이 → `403 CSRF_INVALID`.
- `app.web.cookie-domain` 이 비어 있으면 `XSRF-TOKEN` 에 `Domain` 이 없다(로컬 기본값).
- 세션 쿠키에는 어느 설정에서도 `Domain` 이 없다.

### 5. 문서

- `docs/05-api.md` §1.1 "화면과 API 의 출처" — 설정 키 이름(`app.web.origin` · `app.web.cookie-domain`)과 비었을 때의 동작을 해당 항목 옆에 적는다.

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
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 설정 키와 그 바인딩 위치를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 허용 출처에 `*` · 패턴 · 목록을 쓰지 마라. 이유: credentials 를 허용하는 CORS 에서 출처를 넓히면 다른 사이트가 로그인한 사용자의 권한으로 API 를 부른다(05 §1.1).
- 세션 쿠키에 `Domain` 을 두지 마라. 이유: 등록 도메인 전체(다른 서브도메인 포함)로 세션 쿠키가 퍼진다.
- 실제 운영 도메인을 설정 · 테스트 · 문서에 적지 마라. 이유: 아직 정하지 않았다(`5-deploy`). 테스트는 `example` 도메인을 쓴다.
- Vite proxy 설정(`frontend/vite.config.ts`)과 화면 코드를 고치지 마라. 이유: 화면은 step 4 의 범위다.
- `src/test/resources/application.yml` 을 만들지 마라. 이유: `src/main/resources/application.yml` 을 통째로 가린다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
