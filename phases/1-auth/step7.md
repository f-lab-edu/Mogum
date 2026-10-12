# Step 7: wrap-and-focus

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 화면 스택 · 작업 방식(TDD — 화면 짝 테스트 규칙)
- `docs/UI_GUIDE.md` — 포커스(**포커스를 옮겨 받기만 하는 제목 · 문구** 문단) · 타이포그래피(**한국어는 어절 단위로 줄을 바꾼다** 문단). 이 step 이 구현할 규칙이 이 두 문단에 이미 적혀 있다
- `docs/06-screens.md` §2.10(코드 블록 · 표는 그 블록 안에서만 가로 스크롤) · §2.11(영역이 바뀌면 포커스를 옮긴다)
- 이전 step 산출물: `frontend/src/index.css`(테마 토큰 · `@layer base` 의 `body`) · `frontend/src/pages/LoginPage.tsx` · `frontend/src/pages/LoginPage.test.tsx`(step 6 — U1 시안 반영)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

step 6 의 U1 을 실제 브라우저(Chrome · 390px)로 열었을 때 드러난 두 문제를 고친다. 둘 다 앞으로 모든 화면에 걸리므로 **기본 스타일 한 곳**에서 고친다.

1. **한국어가 낱말 중간에서 줄이 바뀐다** — 「배운 걸 잊지 않게, 복습/이 다음 강의를…」 · 「짧/게」 · 「있어/요.」. 브라우저 기본 `word-break: normal` 은 한글을 글자마다 끊을 수 있다.
2. **화면을 열자마자 제목(또는 알림 카드)에 포커스 테두리가 보인다** — `LoginPage` 는 06 §2.11 대로 처음 포커스를 `h1`(문구가 있으면 [U1-3] 문구)로 옮기는데, Chrome 은 코드가 옮긴 포커스에도 `:focus-visible` 을 적용해 `focus-visible:outline-*` 테두리를 그린다. 시안에는 없는 상자다.

### 1. 기본 스타일 — `frontend/src/index.css`

`@layer base` 에 더한다. 테마 토큰 · Pretendard 불러오기 등 기존 내용은 그대로 둔다.

- `body` — `word-break: keep-all` · `overflow-wrap: break-word`. 낱말 중간에서 끊지 않고, 칸보다 긴 한 덩어리(긴 영어 낱말 · 주소)만 끊어 가로로 넘치지 않게 한다.
- `[tabindex="-1"]:focus` — `outline: none`. 포커스를 옮겨 받기만 하는 요소(누르는 것이 아니다)의 테두리를 지운다. 키보드로 누를 수 있는 버튼 · 링크(`tabIndex` 가 없거나 0 이상)는 영향받지 않아야 한다 — 그들의 `focus-visible:outline-*` 는 그대로 보인다.

### 2. U1 — `frontend/src/pages/LoginPage.tsx`

- `h1` 과 [U1-3] 알림 문구(`role="status"` 인 `p`)에서 `focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent` 를 뺀다. 이유: Tailwind 유틸리티는 기본 스타일보다 우선해서, 남겨 두면 1 의 규칙을 덮어 테두리가 그대로 그려진다(UI_GUIDE 포커스 — 이 요소에는 포커스 클래스를 붙이지 않는다).
- 구글 로그인 링크의 포커스 클래스는 **그대로 둔다** — 키보드로 누르는 요소다.
- 포커스를 옮기는 동작(문구가 없으면 `h1`, 있으면 문구 · `tabIndex={-1}`)과 `role` · `aria-live` · `aria-atomic` 은 바꾸지 않는다.

### 3. 테스트 — `frontend/src/pages/LoginPage.test.tsx`

TDD 가드: `LoginPage.tsx` 를 고치려면 짝 테스트를 먼저 고친다.

- `h1` 과 [U1-3] 알림 문구에 `focus-visible:outline-2` 클래스가 없다. 처음 포커스가 그 요소로 가는 기존 확인은 유지한다.
- 구글 로그인 링크에는 `focus-visible:outline-2` 클래스가 있다(키보드 포커스 링 유지).

`index.css` 는 TDD 가드의 검사 대상이 아니고, jsdom 테스트는 `index.css` 를 불러오지 않아 계산된 스타일로 확인할 수 없다. CSS 파일을 글자로 읽어 비교하는 테스트는 쓰지 마라 — 1 은 `npm --prefix frontend run build` 결과 CSS 에 두 규칙이 들어갔는지 확인하고 summary 에 적는다. 실제 화면은 step 이 끝난 뒤 사람이 브라우저로 확인한다.

### 4. 문서

`docs/UI_GUIDE.md` 의 두 문단은 이미 이 규칙을 적고 있다. 구현이 문단과 다르게 가야 하면(예: 규칙을 다른 선택자로 써야 함) 문단도 같이 고친다. 그렇지 않으면 문서는 고치지 않는다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

`npm --prefix frontend run build` 도 통과해야 한다(리뷰어는 실행하지 못한다 — Codex 가 확인하고, 빌드된 CSS 에 `word-break:keep-all` · `overflow-wrap:break-word` · `[tabindex="-1"]:focus` 규칙이 있는지와 함께 summary 에 적는다).

## 검증 절차

1. 위 AC 커맨드와 `npm --prefix frontend run build` 를 실행하고, 빌드된 CSS(`frontend/dist/assets/*.css`)에서 1 의 규칙을 찾는다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가? 새 의존성이 없는가?
   - `AGENTS.md` 작업 방식(TDD) · `docs/UI_GUIDE.md` 포커스 · 타이포그래피 규칙과 맞는가?
   - 누를 수 있는 요소(구글 로그인 링크 · `RequireAuth` 의 「다시 시도」 버튼)의 포커스 링이 그대로인가?
3. 결과에 따라 `phases/1-auth/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 바꾼 파일 · 빌드 CSS 에서 찾은 규칙 · test · lint · build 결과를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `*:focus` · `:focus-visible` 전체에서 테두리를 지우지 마라. 이유: 키보드로 누르는 버튼 · 링크의 포커스 링은 언제나 보여야 한다(UI_GUIDE 포커스). 지우는 대상은 `tabindex="-1"` 인 요소뿐이다.
- 포커스를 옮기는 동작을 없애거나 `tabIndex={-1}` 을 빼지 마라. 이유: 06 §2.11 — 화면 읽기 프로그램 · 키보드 사용자가 길을 잃지 않게 하는 장치다. 바꾸는 것은 테두리 모양뿐이다.
- `word-break: break-all` · `white-space` 를 바꾸지 마라. 이유: `break-all` 은 영어 낱말까지 글자마다 끊는다. 코드 블록은 줄을 바꾸지 않고 그 안에서 스크롤한다(06 §2.10).
- 줄바꿈 · 포커스 규칙을 컴포넌트마다 클래스(`break-keep` 등)로 붙이지 마라. 이유: 모든 화면에 걸리는 규칙이라 기본 스타일 한 곳에 둔다(UI_GUIDE).
- `frontend/src/components/RequireAuth.tsx` · `frontend/src/pages/PlaceholderPage.tsx` · `frontend/src/App.tsx` · 백엔드(`src/`)를 고치지 마라. 이유: 이 step 은 기본 스타일과 U1 만 다룬다.
- `.env` 파일을 만들거나 읽지 마라. 이유: `AGENTS.md`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
