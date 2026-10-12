# Step 6: login-design

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 화면 스택 · 작업 방식(TDD — 화면 짝 테스트 규칙)
- `docs/06-screens.md` §2.10(화면 폭 · 누르는 자리 44px) · §2.11(접근성 · 포커스 · 바뀐 내용 알리기) · §3(U1 로그인 — 영역 [U1-1]~[U1-4] 와 [U1-3] 상태 표) · §16(단계별 범위 — U1 행)
- `docs/UI_GUIDE.md` — AI 슬롭 안티패턴 · 색상(데이터/시맨틱 색상) · 버튼 · 포커스 · 레이아웃 · 타이포그래피(서비스 이름 「머금」 U1 크기 · **구글 로그인 버튼 예외**)
- `docs/design/README.md` — 읽는 법 · 값 옮기기(인라인 스타일 → 토큰) · 어디까지 맞추나
- `docs/design/Entry Screens.dc.html` — `screen`: `u1`(M① 기본 · D③ 데스크톱) · `u1relogin`(M② 다시 로그인해 주세요). 이 파일의 `U1 로그인` 영역(`data-layer="U1 로그인"`)만 본다 — U7 · U8 영역은 이 step 의 범위가 아니다
- 이전 step 산출물(step 4): `frontend/src/pages/LoginPage.tsx` · `frontend/src/pages/LoginPage.test.tsx` · `frontend/src/lib/messages.ts`(U1 문구) · `frontend/src/api/client.ts`(`googleLoginUrl`) · `frontend/src/index.css`(테마 토큰 · Pretendard 불러오기) · `frontend/src/App.test.tsx` · `frontend/src/components/RequireAuth.test.tsx`(둘 다 U1 의 링크 이름 「Google 계정으로 계속하기」 를 찾는다)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

U1 로그인 화면(`frontend/src/pages/LoginPage.tsx` · 주소 `/login`)을 시안 `u1` · `u1relogin` 에 맞춰 다시 만든다. 로그인 동작(링크 주소 · `?error=` · 세션 만료 판단 · 탭 제목 · 포커스)은 step 4 그대로 두고 **모양과 영역만** 바꾼다. 백엔드와 다른 화면은 고치지 않는다.

이 step 에서 만드는 영역은 06 §16 U1 행의 최소판 열이다 — **[U1-1] 소개 · [U1-2] 구글 로그인 버튼 · [U1-3] 알림 자리.**

### 1. [U1-1] 소개 — 가운데 정렬

06 §3 의 문구를 그대로 쓴다. 시안의 순서 · 간격 · 글자 크기를 따른다.

- 「머금」 — UI_GUIDE 타이포그래피의 U1 크기(`text-[28px] font-bold text-ink`). **제목 요소가 아니다**(시안도 `p`).
- 슬로건 「배운 건 머금고, 흔들리는 건 다시 한 모금」 — `text-sm text-ink-soft`
- **`h1`**: 「배운 걸 잊지 않게, 복습이 다음 강의를 바꾸는 학습」 — `text-2xl font-semibold text-ink`
- 설명: 「주제만 정하면 AI가 강의를 짜고 만들어요. 복습 결과를 보고 이미 아는 건 짧게, 흔들리는 건 자세히 다음 강의를 만들어요.」

[U1-3] 문구가 없을 때 처음 포커스는 이 `h1` 로 간다(step 4 에서는 「머금」 `h1` 이었다 — 대상만 바뀐다).

### 2. [U1-2] 구글 로그인 버튼

UI_GUIDE 타이포그래피의 **예외 — U1 구글 로그인 버튼** 그대로다: Roboto 500 · 14px · 흰 바탕 · 테두리 `#747775` · 글자 `#1F1F1F` · `rounded`(4px) · 높이 44px(`h-11`) · 구글 G 로고 18px(시안의 인라인 SVG 4색 그대로, `aria-hidden="true"`) · 로고와 글자 사이 10px. 폭은 [U1-2] 영역 전체다.

- **요소는 `<a href={googleLoginUrl()}>` 를 유지한다.** 시안은 `<button>` 으로 그렸지만 로그인 시작은 브라우저가 페이지째 이동하는 링크다(fetch 아님 — step 4). 접근 가능한 이름은 「Google 계정으로 계속하기」 그대로다. `App.test.tsx` · `RequireAuth.test.tsx` 가 이 이름으로 찾는다.
- **Roboto 는 `@fontsource/roboto` 패키지(npm `dependencies`)로 저장소에 함께 배포하고, 500 굵기 하나만 불러온다**(예: `@fontsource/roboto/500.css`). Pretendard 를 `pretendard` 패키지로 불러오는 것과 같은 방식이다. 버전은 npm 이 고르는 최신(`npm install` 기본 — `^` 범위)을 쓴다.
- 이 버튼의 Roboto · 구글 색(`#747775` · `#1F1F1F` · 로고 4색)은 **이 버튼에만** 쓴다. 테마 토큰(`@theme`)에 넣지 않는다 — 넣으면 다른 화면에서 쓸 수 있는 색이 된다. 임의 값 클래스(`border-[#747775]` 등)나 이 컴포넌트 안의 값으로 둔다.
- 포커스 링은 다른 버튼과 같다(UI_GUIDE 포커스).

### 3. [U1-3] 알림 자리 — 모든 문구가 같은 카드

시안에는 「다시 로그인해 주세요.」(`u1relogin`)만 그려져 있다. **06 §3 [U1-3] 표의 다른 문구(`?error=login_failed` · `?error=not_allowed` · 그 밖의 `error` 값)도 같은 카드에 글자만 바꿔 넣는다.**

- 카드: `bg-surface border border-line rounded-md px-4 py-3 text-base text-ink` · 폭은 [U1-2] 와 같다 · 로그인 영역 아래 24px(`mt-6`)
- **`danger` 색을 쓰지 마라.** 이유: UI_GUIDE 데이터/시맨틱 색상 — `danger` 는 06 §15 막힘 · 실패 문구 · 탈퇴 버튼에만 쓴다. U1 의 문구는 §15 가 아니고, 시안도 `ink` 다. (step 4 는 `text-sm text-danger` 였다 — 이것을 바꾼다)
- **기본 상태(문구 없음)에서는 카드가 보이지 않는다** — 테두리 · 바탕 · 여백이 그려지지 않는다.
- 화면 읽기 프로그램이 문구를 읽는 동작은 step 4 그대로 유지한다: `role="status"` · `aria-live="polite"` · `aria-atomic="true"`, 문구가 있으면 처음 포커스가 문구로 간다(06 §2.11). 문구 우선순위(`?error=` 가 세션 만료보다 먼저)도 그대로다.

### 4. 배치

- 한 단 · 가운데 정렬(06 §2.10). 바깥은 `min-h-screen bg-paper`, 안쪽은 `max-w-3xl px-4` 를 가운데에 두고, 위 여백은 시안 72px · 아래 24px 다.
- [U1-1] 폭은 시안 `max-width:30rem`, [U1-2] · [U1-3] 폭은 `22rem` 이다. 그 밖의 인라인 값은 `docs/design/README.md` 「값 옮기기」 표의 토큰 · Tailwind 값으로 옮긴다.
- 데스크톱(D③)도 같은 한 단을 가운데 둔다 — 데스크톱용 배치를 따로 만들지 않는다.
- 상단 바는 없다(06 §2.3).

### 5. 테스트 — `frontend/src/pages/LoginPage.test.tsx`

TDD 가드: `LoginPage.tsx` 를 고치려면 짝 테스트 `LoginPage.test.tsx` 를 먼저 고친다. 구현만 바꾸고 끝내면 되돌려진다.

기존 테스트의 동작 확인(문구 · `aria-live` · 포커스 · 탭 제목 · 링크 `href` · `?error=` 우선)은 유지하고, 대상이 바뀐 것만 고친다. 더할 것:

- [U1-1] — 「머금」 · 슬로건 · 설명이 보이고, `h1`(level 1)의 이름이 「배운 걸 잊지 않게, 복습이 다음 강의를 바꾸는 학습」 이다. 「머금」 은 제목(heading)이 아니다. 문구가 없을 때 이 `h1` 이 포커스를 받는다.
- [U1-2] — 링크 이름 「Google 계정으로 계속하기」 · `href` 가 `googleLoginUrl()` 과 같다 · 링크 안의 로고 SVG 가 `aria-hidden="true"` 다.
- [U1-3] — 06 §3 표의 네 경우(세션 만료 · `login_failed` · `not_allowed` · 모르는 값)마다 카드에 그 문구가 나온다. 기본 상태에서는 카드가 그려지지 않는다(어떻게 확인할지는 재량 — 예: 카드 요소가 없음).
- 범위 밖 영역이 **없다** — 「처음이면 가입이 함께 진행돼요.」 · 「지금은 정원을 정해 받고 있어요.」 로 시작하는 글 · 「이용약관」 · 「개인정보처리방침」 링크가 화면에 없다.

### 6. 문서

- `docs/UI_GUIDE.md` 타이포그래피의 「**예외 — U1 구글 로그인 버튼**」 문단 끝에 Roboto 를 불러오는 방식을 한 문장으로 적는다: `@fontsource/roboto` 의 500 굵기만 화면 빌드에 함께 넣는다 · 외부 글꼴 서버를 부르지 않는다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

`npm --prefix frontend run build` 도 통과해야 한다(리뷰어는 실행하지 못한다 — Codex 가 확인하고 summary 에 적는다).

## 검증 절차

1. 위 AC 커맨드와 `npm --prefix frontend run build` 를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 의 `frontend/src/` 구조를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가? 새 의존성은 `@fontsource/roboto` 하나뿐인가?
   - `AGENTS.md` 작업 방식(TDD) · `docs/UI_GUIDE.md` 안티패턴을 위반하지 않았는가?
   - 시안 `u1` · `u1relogin` 과 영역 순서 · 문구 · 가장 강조(구글 버튼 하나) · 누르는 자리 크기 · 색 · 글자 크기가 맞는가? 적히지 않은 영역([U1-2] 작은 글씨 두 줄 · [U1-4] 바닥글)을 만들지 않았는가? 기준은 `docs/design/README.md` 「어디까지 맞추나」
3. 결과에 따라 `phases/1-auth/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 바꾼 파일 · 더한 의존성과 버전 · test · lint · build 결과를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 시안이 어긋나 판단이 필요함 · 의존성 설치 실패 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 「처음이면 가입이 함께 진행돼요.」 · 정원 안내(「지금은 정원을 정해 받고 있어요. …」) · [U1-4] 바닥글(이용약관 · 개인정보처리방침 링크)을 만들지 마라. 이유: 06 §16 상 `12-account-public` 에서 만든다 — 지금은 관리자 이메일만 로그인하고(ADR-062) U7 가입 · P1 · P2 화면이 없어 글이 사실과 다르거나 링크가 갈 곳이 없다.
- Google Fonts 등 외부 글꼴 서버의 `<link>` · `@import` 를 넣지 마라. 이유: 시안의 `<helmet>` 은 시안 런타임용이다. 글꼴은 Pretendard 처럼 npm 패키지로 함께 배포한다.
- 구글 버튼 밖에서 Roboto · 구글 색을 쓰지 마라. 테마 토큰에 넣지 마라. 이유: UI_GUIDE — 이 버튼만의 예외다.
- 로그인 시작을 `<button>` + `fetch` · `onClick` 이동으로 바꾸지 마라. 이유: OAuth 시작은 브라우저가 페이지째 이동해야 한다(step 4 · 05 §1.1).
- `frontend/src/components/RequireAuth.tsx` · `frontend/src/pages/PlaceholderPage.tsx` · `frontend/src/App.tsx` · 백엔드(`src/`)를 고치지 마라. 이유: 이 step 은 U1 만 다룬다. 로그인 확인 중 · 실패 화면과 `/` 자리표시는 시안에 그림이 없다.
- U1 문구(`frontend/src/lib/messages.ts` 의 값)를 바꾸지 마라. 이유: 06 §3 표와 같다 — 모양만 바꾸는 step 이다.
- `.env` 파일을 만들거나 읽지 마라. 이유: `AGENTS.md`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라(`LoginPage.test.tsx` 의 대상 변경은 예외 — 위 5).
