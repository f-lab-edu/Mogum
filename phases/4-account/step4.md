# Step 4: ui-foundation

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/06-screens.md` §2.1(형태와 모양의 원칙 — 모바일 우선 · 다크 모드 없음) · §3(로그인 전 화면 — **로그인** 줄) · §15.3(디자인에서 정할 것 — UI_GUIDE)
- `docs/design/README.md` 전체 — 특히 「시안을 쓰는 법」 · **「맞추는 순서」** · 「모구미 이미지」 · 「공통 틀」(로그인 전 화면에는 탭이 없다) · 「06 과 다른 곳」
- `docs/design/guide.md` — §1.2(마스코트) · §2(토큰) · §4(반응형 틀)
- `docs/design/DESIGN.md`
- 시안(모바일 먼저): `docs/design/screenshots/01-login-mobile.png` · `docs/design/screenshots/01-login-desktop.png` · `docs/design/html/01-login.html`. 로그인 전 화면 틀을 정하려고 `02-apply` · `03-waiting-approval` · `04-rejected` 의 모바일 스크린샷도 본다(이 step 에서 그 화면을 만들지는 않는다)
- 모구미: `docs/design/assets/mogumi-reading.png`(약 1MB 원본)
- `docs/ADR.md` — ADR-063(모바일 우선) · ADR-064(다크 모드 없음) · ADR-091(로그인 화면 · 구글 버튼 규칙)
- 지금 화면 코드: `frontend/src/index.css`(옛 토큰 — 종이색 · 남색) · `frontend/src/pages/LoginPage.tsx` · `frontend/src/components/RequireAuth.tsx` · `frontend/src/pages/PlaceholderPage.tsx` · `frontend/src/lib/messages.ts` · 각 테스트 파일 · `frontend/package.json`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`4-account`)는 계정을 신청 · 승인 · 거절됨 흐름으로 바꾼다. step 0 ~ 3 이 서버를 끝냈다.

지금 화면은 옛 설계의 토큰(종이색 · 남색)을 쓴다. 새 시안(Stitch — 보라색 머그컵 · 둥근 카드 · 눌리는 버튼 · 모구미)은 이 phase 에서 처음 코드로 옮긴다. **design README 「맞추는 순서」대로 처음 화면을 만드는 이 step 이 토큰과 이 step 에 필요한 컴포넌트를 정하고 `docs/UI_GUIDE.md` 에 적는다.** 다음 step(신청 · 승인 대기 · 거절됨)과 뒤 TASK 는 그것을 쓰고 새로 필요한 것만 더한다.

## 작업

### 1. 토큰 — `frontend/src/index.css` 의 `@theme`

- 색 · 글꼴 · 글자 크기 · 간격 · 모서리 · 그림자(눌리는 버튼의 두께)를 토큰으로 정한다. 시안의 임의 값(`text-[11px]` · `shadow-[0_3px_0_0_#5835C4]` …)을 그대로 옮기지 마라 — 같은 역할은 토큰 하나다(design README 「시안을 쓰는 법」).
- 색은 세 곳이 다르다(`DESIGN.md` · `guide.md` · HTML 의 `tailwind.config` — design README 「공통 틀」). 하나로 고르고 고른 이유를 UI_GUIDE 에 적는다. 본문 글자와 기본 버튼 글자는 바탕색과의 대비가 WCAG AA(본문 4.5:1)를 넘게 고르고, 계산한 대비 값을 UI_GUIDE 에 적는다.
- 한글 글꼴은 Pretendard(이미 `pretendard` 패키지로 불러온다 — `guide.md` §2.2).
- 옛 토큰(`paper` · `accent` …)은 지우고, 쓰던 곳(`PlaceholderPage` · `RequireAuth` · `LoginPage`)을 새 토큰으로 바꾼다.
- 지금 `body` 의 `word-break: keep-all` · `overflow-wrap: break-word` 는 유지한다 — 한국어가 낱말 중간에서 끊기지 않게 한 것이다(`1-auth` step 7).

### 2. 공통 컴포넌트 — 이 step 이 쓰는 것만

`frontend/src/components/` 아래, 짝 테스트(`{이름}.test.tsx`)와 함께.
- **로그인 전 화면 틀** — 탭 바 · 사이드바 없음(design README 「공통 틀」), 모바일 우선 한 줄 세로 배치, 큰 화면에서는 가운데 폭을 제한한다. 다음 step 의 신청 · 승인 대기 · 거절됨 · 처리방침 자리 화면도 이 틀을 쓴다.
- **모구미** — 그림 하나를 감싼 컴포넌트(대체 글 포함). 화면에 넣는 사본은 원본(약 1MB)을 줄여 `frontend/` 안에 둔다 — 크기 · 형식(예: 긴 변 픽셀 · WebP/PNG)은 네가 정하고 UI_GUIDE 와 design README 「모구미 이미지」의 「규격」 줄 아래에 적는다. 원본 `docs/design/assets/` 는 고치지 마라. 이미지를 줄이는 데 쓴 명령을 UI_GUIDE 에 적는다.
- **버튼** — 아래에 두께가 있는 눌리는 버튼(design README 「따른다」). `RequireAuth` 의 「다시 시도」가 쓴다. 이 step 이 쓰는 모양(기본 하나)만 만들고, 다음 step 이 필요한 모양을 더한다. 눌렸을 때 · 막혔을 때(`disabled`) · 키보드 포커스(`focus-visible`)를 정한다.
- 아이콘 세트는 이 step 에서 고르지 않는다 — 로그인 화면에 필요한 아이콘이 없다(구글 로고는 구글 규칙의 SVG 그대로).

### 3. 로그인 화면 다시 만들기 — `frontend/src/pages/LoginPage.tsx`

- 06 §3 로그인 줄을 따른다 — 보이는 것: 서비스 이름 · 짧은 소개(머금이 무엇인지 · 정원제라는 것). 할 수 있는 것: 구글로 로그인. 두지 않는 것: 넘겨 보는 소개 여러 장 · 처리방침 링크.
- 시안 `01-login` 의 구조(영역 순서 · 가장 눈에 띄는 것)를 따르고, 값은 토큰 · 공통 컴포넌트로 맞춘다. 시안의 예시 문구는 옮기지 않아도 된다 — 06 이 정한 내용을 담는다.
- **구글 버튼은 구글의 버튼 규칙을 따른다**(06 §15.3 · ADR-091) — 지금 버튼(Roboto · 테두리 · 로고 SVG · 「Google 계정으로 계속하기」)의 모양을 유지한다. 눌리는 버튼 토큰을 입히지 마라.
- 지금의 오류 · 세션 만료 안내와 포커스 처리(안내가 있으면 안내로, 없으면 제목으로)는 유지한다. `not_allowed` 문구는 다음 step 이 걷는다 — 이 step 에서는 그대로 둔다.

### 4. `RequireAuth` · `PlaceholderPage`

- 동작은 바꾸지 않고 모양만 새 토큰 · 공통 컴포넌트(버튼)로 바꾼다. 상태별 라우팅은 다음 step 이다.

### 5. `docs/UI_GUIDE.md` 새로 만들기

- 이 step 이 정한 토큰(이름 · 값 · 쓰는 곳) · 공통 컴포넌트(이름 · 경로 · 모양 · 상태) · 로그인 전 화면 틀 · 모구미 사본 규격 · 고른 이유와 대비 값을 적는다. 정하지 않은 것(아이콘 세트 · 탭 바 · 사이드바 · 카드 · 배지 · 진도 막대 …)은 「아직 정하지 않은 것 — 처음 쓰는 TASK 가 정한다」로 적는다.
- `docs/README.md` 의 문서 표 · `AGENTS.md` 의 문서 표에 UI_GUIDE 가 없으면 한 줄씩 더한다(「화면 코드를 쓸 때」). `docs/06-screens.md` §15.3 의 「`UI_GUIDE.md` 를 새로 만들어 담는다」는 그대로 둔다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 색 · 간격 · 모서리 · 그림자가 토큰으로만 쓰였는가? 시안의 임의 값이 남지 않았는가?
   - 로그인 화면이 06 §3 의 보이는 것 · 할 수 있는 것 · 두지 않는 것과 맞고, 시안의 구조(영역 순서 · 가장 강조)를 따르는가? 구글 버튼이 구글 규칙 모양 그대로인가?
   - 공통 컴포넌트가 이 step 이 쓰는 것만인가? 짝 테스트가 있는가?
   - UI_GUIDE 에 고른 값 · 이유 · 대비 값 · 모구미 규격이 있는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가? 기준은 `docs/design/README.md` 「시안을 쓰는 법」
3. 결과에 따라 `phases/4-account/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 토큰 이름 목록 · 고른 기본색과 대비 값 · 컴포넌트 이름과 경로 · 모구미 사본 경로와 규격 · UI_GUIDE 의 절 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (06 과 시안이 부딪혀 고를 수 없음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 신청 · 승인 대기 · 거절됨 · 처리방침 화면과 상태별 라우팅을 만들지 마라. 이유: step 5 의 범위다.
- 탭 바 · 사이드바 · 위 바 · 홈 화면을 만들지 마라. 이유: SCHEDULE §3 상 홈 TASK(11)에서 만든다 — 로그인 전 화면에는 탭이 없다.
- 이 step 이 쓰지 않는 컴포넌트(카드 · 배지 · 진도 막대 · 확인 펼침 …)를 미리 만들지 마라. 이유: 「맞추는 순서」 — 쓰는 화면이 생길 때 그 TASK 가 만든다.
- 다크 모드 · 화면 하나만을 위한 변형을 만들지 마라. 이유: ADR-064 · design README 「맞추는 순서」.
- 로그인 화면에 처리방침 링크 · 넘겨 보는 소개를 넣지 마라. 이유: 06 §3 두지 않는 것.
- 모구미를 새로 그리거나 원본(`docs/design/assets/`)을 고치지 마라. 이유: 모구미 이미지는 사용자가 만든다(design README).
- 외부 주소(Google Fonts · `lh3.googleusercontent.com` 등)의 그림 · 글꼴을 불러오지 마라. 이유: 시안의 외부 주소는 만료된다(design README 「담긴 것」) — 쓰는 파일은 `frontend/` 안에 둔다.
- 서버(`backend/`) · `deploy/` 를 고치지 마라. 이유: 이 step 범위 밖이다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
