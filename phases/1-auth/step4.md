# Step 4: frontend-auth

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 화면 스택 · 작업 방식(TDD — 화면 짝 테스트 규칙)
- `docs/ARCHITECTURE.md` — `frontend/src/` 구조(`api/` · `pages/` · `lib/`) · 상태 관리 표
- `docs/05-api.md` §1(경로) · §1.1(인증 — `GET /me` · 로그아웃 · CSRF · 화면과 API 의 출처 · 콜백 실패는 화면의 U1 로) · §1.2(에러 형식 · `UNAUTHORIZED` · `CSRF_INVALID`)
- `docs/06-screens.md` §2.3(화면 목록 · 로그인 전에 열었던 주소) · §2.6(공통 상태 — 401 · 첫 로딩) · §2.11(접근성 · 바뀐 내용 알리기) · §3(U1) · §16(단계별 범위 — U1 최소판)
- `docs/UI_GUIDE.md` — 안티패턴 · 버튼 · 레이아웃 · 타이포그래피
- `docs/ADR.md` ADR-060 · ADR-062
- 이전 step 산출물: `frontend/src/App.tsx` · `frontend/src/App.test.tsx` · `frontend/src/pages/PlaceholderPage.tsx` · `frontend/vite.config.ts` · `frontend/src/index.css`(테마 토큰) · `frontend/package.json` · 백엔드의 `GET /api/me` 응답 · CSRF 쿠키 · `/auth/google` 경로(step 1 · 2 · 3 summary)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

화면 쪽 인증 배선과 **U1 로그인 최소판**을 만든다. 백엔드는 고치지 않는다.

### 1. API 주소 — `VITE_API_BASE_URL`

- API 출처는 빌드 환경 변수 `VITE_API_BASE_URL` 로 받는다(예: 운영 `https://api.{도메인}` — Vercel 프로젝트에 넣는 값은 `5-deploy` 에서). **비어 있으면 상대 경로**다 — 개발 서버는 Vite proxy 로 `/api` · `/auth` 를 백엔드에 넘긴다(`frontend/vite.config.ts` 는 이미 그렇게 되어 있다).
- `import.meta.env` 의 타입 선언을 둔다(TypeScript strict).

### 2. fetch 래퍼 — `frontend/src/api/`

이름은 재량이다(예: `client.ts`).

- JSON API 호출은 `{API 출처}/api{경로}` 로 보낸다.
- **언제나 `credentials: 'include'`** — 운영은 화면과 API 의 출처가 달라, 이게 없으면 세션 쿠키가 실리지 않는다(05 §1.1).
- `GET` · `HEAD` 가 아닌 요청에는 `XSRF-TOKEN` 쿠키 값을 **그대로** `X-XSRF-TOKEN` 헤더에 싣는다(05 §1.1). 쿠키는 `document.cookie` 에서 읽는다.
- 실패 응답의 `{ code, message }`(05 §1.2)를 HTTP 상태와 함께 담은 에러 타입으로 던진다. 화면은 `code` 로 갈라 문구를 고른다 — 서버 `message` 를 화면에 그대로 보이지 않는다(06 §15 의 문구를 쓴다).
- 로그인 시작 주소(`{API 출처}/auth/google`)를 만드는 함수도 여기에 둔다.

### 3. 현재 사용자 · 로그인 확인

- `GET /api/me` 를 부르는 TanStack Query 훅(예: `useMe`). 응답 타입은 05 §1.1 중 지금 오는 필드(`email` · `status` · `role` · `termsAgreed` · `waitlistPosition`)다. `401` 은 자동 재시도하지 않는다.
- 로그인이 필요한 화면을 감싸는 공통 장치(예: 라우트 가드)를 둔다. 지금 감쌀 화면은 `/`(자리표시 — U2 는 `4-curriculum-gen`)뿐이지만, 이후 화면이 모두 같은 장치를 쓴다.
  - 확인 중에는 회색 줄(스켈레톤)을 보인다 — 화면 가운데 스피너를 쓰지 않는다(06 §2.6 · UI_GUIDE).
  - **`401` 이면 `/login` 으로 보낸다.** 그때 열려던 주소(경로 · 쿼리 · 해시)를 `sessionStorage` 에 기억한다(06 §2.3).
- **401 처리는 `/api/me` 만이 아니라 모든 쿼리 · 변경 요청에 한 곳에서 적용한다**(예: `QueryCache` · `MutationCache` 의 `onError`). 이후 TASK 의 API 가 401 을 받아도 같은 경로로 U1 에 간다.
- **U1 [U1-3] 문구를 가른다**(06 §3) — 처음 온 사람(이 탭에서 로그인 확인이 성공한 적이 없다)이면 문구 없음, 쓰다가 세션이 끝난 사람이면 「다시 로그인해 주세요.」.
- **로그인 뒤 돌아가기** — 백엔드는 로그인 성공 뒤 언제나 화면의 `/` 로 보낸다(05 §1.1). `/` 에서 로그인 확인이 성공했는데 기억한 주소가 있으면 그 주소로 한 번 옮기고 지운다. **화면 안의 경로만 되살린다** — `/` 로 시작하고 `//` 로 시작하지 않으며 `/login` 이 아닌 값만. 그 밖의 값은 버린다(열린 리다이렉트 방지).
- `403 TERMS_REQUIRED` · `ACCOUNT_WAITLISTED` 처리(U7 · U8)는 만들지 않는다 — `12-account-public` 의 범위다.

### 4. U1 로그인 — `frontend/src/pages/` · 주소 `/login`

06 §3 · §16 의 최소판이다. 상단 바 없음 · 로그인 확인을 거치지 않는다.

- 서비스 이름 「머금」(제목)
- `버튼: Google 계정으로 계속하기` — 로그인 시작 주소로 가는 링크(브라우저가 페이지째 이동한다 — fetch 아님). UI_GUIDE 의 Primary 버튼 모양. 구글 표준 버튼 모양(G 로고)은 `11-screens-complete` 에서 한다.
- **[U1-3] 알림 자리** — 06 §3 표의 문구:
  - `?error=login_failed` → 「로그인하지 못했어요. 다시 시도해 주세요.」
  - `?error=not_allowed` → 「지금은 공개 전이라 정해진 계정만 들어올 수 있어요.」
  - 그 밖의 `error` 값 → 로그인 실패 문구
  - 세션이 끝나 넘어왔을 때 → 「다시 로그인해 주세요.」
  - 기본 → 없음
  - 06 §2.11 에 맞춰 화면 읽기 프로그램이 문구를 읽게 한다.
- 문구는 `frontend/src/lib/` 의 문구 표에 모은다(ARCHITECTURE — 06 §15 문구 표 자리).
- U1-1 소개 · U1-4 바닥글 · 정원 안내는 넣지 않는다(06 §16 — 공개 전 완성 · 공개 때).

### 5. 테스트

TDD 가드: `frontend/src/…/foo.ts(x)` 는 같은 폴더의 `foo.test.ts(x)` 또는 `__tests__/foo.test.ts(x)` 가 먼저 있어야 쓰기가 허용된다. `types.ts` · `*.types.ts` · `*.d.ts` 는 검사하지 않는다.

`fetch` 는 테스트에서 흉내 낸다(실제 백엔드 없음).

- fetch 래퍼 — `credentials: 'include'` · `VITE_API_BASE_URL` 이 있을 때와 비었을 때의 주소 · `POST` 에 쿠키 값 그대로의 `X-XSRF-TOKEN` · `GET` 에는 없음 · 에러 본문의 `code` 를 담은 에러 · 로그인 시작 주소.
- 로그인 확인 — 처음 온 사람이 `/curriculums/12?tab=1` 을 열었다가 `/me` `401` → `/login` · 문구 없음 · 주소 기억. 그다음 `/` 에서 `/me` `200` → 기억한 주소로 옮기고 기억을 지운다. 기억한 값이 `//evil.example` · `https://evil.example` · `/login` 이면 옮기지 않는다.
- 쓰는 중 `401`(이 탭에서 로그인 확인이 성공한 뒤) → `/login` · 「다시 로그인해 주세요.」.
- `/me` 가 아닌 다른 쿼리 · 변경 요청의 `401` 도 같은 경로로 간다.
- U1 — `?error=login_failed` · `?error=not_allowed` · 모르는 값 · 없음 각각의 문구, 버튼 링크 주소.
- `frontend/src/App.test.tsx` 는 `/` 가 로그인 확인을 거치게 되므로 `/api/me` 응답을 흉내 내도록 고친다. 그 테스트가 확인하던 것(실제 앱의 라우터와 `QueryClientProvider` 를 거쳐 그린다)은 유지한다.

### 6. 문서

- `docs/05-api.md` §1.1 "화면과 API 의 출처" — 화면의 API 주소 환경 변수 이름(`VITE_API_BASE_URL`)과 비었을 때의 동작(상대 경로 · Vite proxy)을 적는다.
- `docs/04-jobs-and-cost.md` §3 "공개 운영에 더하는 것" 의 "Vercel 프로젝트(API 주소 환경 변수 …)" 에 변수 이름을 적는다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
npm --prefix frontend run build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 의 `frontend/src/` 구조와 상태 관리 표(서버 상태는 TanStack Query · 전역 상태 라이브러리 없음)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가?
   - `AGENTS.md` 작업 방식(TDD) · `docs/UI_GUIDE.md` 안티패턴을 위반하지 않았는가?
3. 결과에 따라 `phases/1-auth/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — fetch 래퍼 · 로그인 확인 장치 · 문구 표의 경로와 이름을 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 백엔드(`src/`)를 고치지 마라. 이유: 이 step 은 화면만 다룬다. 백엔드와 어긋나면 `blocked` 로 멈추고 사유를 적는다.
- axios · 쿠키 라이브러리 · 전역 상태 라이브러리(zustand · redux 등)를 더하지 마라. 이유: `fetch` 와 TanStack Query 로 충분하다(ARCHITECTURE 상태 관리 · ADR 스택).
- 로그인 여부 · 사용자 정보를 `localStorage` 에 저장하지 마라. 이유: 로그인 상태의 출처는 서버 세션(`GET /api/me`)뿐이다. `sessionStorage` 에는 돌아갈 주소처럼 탭 안의 화면 상태만 둔다.
- 로그아웃 버튼 · U9 · U7 · U8 · 상단 바를 만들지 마라. 이유: `12-account-public` · `4-curriculum-gen` 의 범위다.
- 서버 `message` 를 화면 문구로 쓰지 마라. 이유: 문구는 06 이 정한다 — `code` 로 고른다.
- 모달 · 화면 가운데 스피너 · UI_GUIDE 안티패턴을 쓰지 마라. 이유: UI_GUIDE.
- `.env` 파일을 만들거나 읽지 마라. 이유: `AGENTS.md`. 환경 변수 이름은 문서에만 적는다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라(위 `App.test.tsx` 수정은 예외).
