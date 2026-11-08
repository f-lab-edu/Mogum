# UI_GUIDE — 화면 구현 기준

이 문서는 `4-account` step 4에서 처음 쓰는 시각 값과 컴포넌트를 정한다. 화면의 내용과 동작은 [06 화면](./06-screens.md), 시안의 적용 기준은 [design README](./design/README.md#시안을-쓰는-법)를 따른다. 뒤 TASK는 아래 기준을 재사용하고 처음 필요한 것만 더한다.

## 1. 고른 기준과 대비

[guide §2](./design/guide.md#2-디자인-토큰-design-tokens)의 연보라 바탕·짙은 보라 차콜·Pretendard를 바탕으로 한다. `DESIGN.md`의 본문·메타데이터와 HTML의 색이 서로 달라, 기본색은 메타데이터의 `#632ce5`로 통일했다. 흰 글자와 6.95:1로 여유가 있고 모구미의 보라색과 어울린다. 밝은 `#7c4dff`도 흰색과 4.81:1이지만 기본 버튼은 더 진한 색을 쓴다. 보조 글자는 guide의 `#756f86`에서 `#6b647a`로 조정했다. 원래 값은 연보라 안내 바탕에서 4.35:1이므로 작은 본문에 부족하다. 쓰지 않는 성공·경고·스트릭 색은 아직 추가하지 않는다.

대비는 sRGB 각 채널을 선형화(`c ≤ 0.04045`면 `c / 12.92`, 아니면 `((c + 0.055) / 1.055)^2.4`)한 뒤 상대 휘도 `L = 0.2126R + 0.7152G + 0.0722B`, `(밝은 L + 0.05) / (어두운 L + 0.05)`로 계산했다. 아래 본문·버튼의 조합은 WCAG AA 4.5:1을 넘는다.

| 글자 / 바탕 | 대비 |
|---|---|
| `text` / `canvas` | 15.97:1 |
| `text` / `surface` | 16.78:1 |
| `text-secondary` / `canvas` | 5.36:1 |
| `text-secondary` / `surface` | 5.63:1 |
| `text-secondary` / `surface-soft` | 5.10:1 |
| `text-secondary` / `surface-muted` | 4.63:1 |
| `on-primary` / `primary` | 6.95:1 |
| `on-primary` / `primary-hover` | 10.19:1 |
| `error` / `surface` | 6.46:1 |
| `google-text` / `surface` | 16.48:1 |

## 2. 토큰

정의는 [frontend/src/index.css](../frontend/src/index.css)의 `@theme`에 둔다. 색은 이 표만 쓰고, Tailwind의 기본 색상 팔레트는 비운다. 임의 값 클래스는 쓰지 않는다. Google SVG 로고의 원래 색·좌표는 그대로 보존한다.

| 색 토큰 (`--color-`) | 값 | 쓰는 곳 |
|---|---|---|
| `canvas` | `#fff7fe` | 화면 바탕 |
| `surface` | `#ffffff` | 로그인 전 화면의 중앙 면·Google 버튼 |
| `surface-soft` | `#fbf0ff` | 정원제·오류·만료 안내 |
| `surface-muted` | `#f1e5f5` | 로딩 줄·disabled 버튼 |
| `border` | `#e9d5ff` | 중앙 면·안내 테두리 |
| `text` | `#1e1b2e` | 제목·본문·안내 |
| `text-secondary` | `#6b647a` | 소개·보조 본문·disabled 글자 |
| `primary` | `#632ce5` | 기본 버튼·서비스 이름·키보드 포커스 |
| `primary-hover` | `#5300b8` | 기본 버튼 hover |
| `primary-depth` | `#39008c` | 기본 버튼 아래 두께 |
| `on-primary` | `#ffffff` | 기본 버튼 글자 |
| `error` | `#ba1a1a` | 로그인 확인 실패 안내 |
| `google-border` | `#747775` | 기존 Google 버튼 테두리 |
| `google-text` | `#1f1f1f` | 기존 Google 버튼 글자 |

| 다른 토큰 | 값 | 쓰는 곳 |
|---|---|---|
| `--font-sans` | `'Pretendard Variable', Pretendard, ui-sans-serif, system-ui, sans-serif` | 한글·영문 공통. 설치된 pretendard의 로컬 파일 |
| `--font-google` | `Roboto, Arial, sans-serif` | Google 버튼. 기존 `@fontsource/roboto/500.css` 로컬 파일 |
| `--text-title` / `--text-title--line-height` | `1.5rem` / `2rem` | 페이지 제목 |
| `--text-body` / `--text-body--line-height` | `1rem` / `1.625rem` | 본문·버튼·안내 |
| `--text-small` / `--text-small--line-height` | `0.875rem` / `1.5rem` | 소개·서비스 이름·정원제 안내 |
| `--text-google` / `--text-google--line-height` | `0.875rem` / `1.25rem` | 기존 Google 버튼 글자 |
| `--tracking-google` | `0.25px` | 기존 Google 버튼 자간 |
| `--spacing` | `0.25rem` | 간격·크기의 공통 단위. `2`=8px, `2.5`=10px, `3`=12px, `4`=16px, `6`=24px, `8`=32px, `11`=44px, `13`=52px, `32`=128px |
| `--spacing-button-press` | `0.1875rem` (3px) | 기본 버튼을 눌렀을 때 내려가는 거리 |
| `--radius-panel` | `1.5rem` (24px) | 로그인 전 화면의 중앙 면 |
| `--radius-control` | `1rem` (16px) | 기본 버튼·안내·로딩 줄 |
| `--radius-google` | `0.25rem` (4px) | 기존 Google 버튼 |
| `--shadow-account` | `0 12px 36px -4px rgb(99 44 229 / 0.08)` | 중앙 면의 옅은 보라 그림자 |
| `--shadow-button` | `0 4px 0 0 var(--color-primary-depth)` | 기본 버튼 아래 두께 |
| `--shadow-button-pressed` | `0 1px 0 0 var(--color-primary-depth)` | 눌린 버튼의 남은 두께 |
| `--container-account` | `27.5rem` (440px) | 로그인 전 중앙 면의 최대 폭 |
| `--container-content` | `48rem` (768px) | 기존 자리 화면의 최대 폭 |
| `--breakpoint-sm` | `40rem` (640px) | 넓은 화면에서 중앙 면 안쪽 여백 24→32px |

글자 굵기는 Tailwind 기본 토큰 `font-medium`(500, Google), `font-semibold`(600), `font-bold`(700), `font-extrabold`(800, 제목)를 쓴다. 포커스 테두리는 2px·바깥 간격 2px이다. `body`의 `word-break: keep-all`과 `overflow-wrap: break-word`를 유지한다. 한국어는 낱말을 지키고 긴 한 덩어리는 화면 안에서 줄바꿈한다. 다크 모드는 두지 않는다.

## 3. 공통 컴포넌트와 로그인 전 틀

| 이름 · 경로 | 모양 · 동작 · 상태 |
|---|---|
| `AccountLayout` · [components/AccountLayout.tsx](../frontend/src/components/AccountLayout.tsx) | `main` 하나. 바탕 위에 흰 중앙 면·24px 모서리·옅은 그림자. 모바일은 한 줄 세로 배치, 바깥 여백 가로 16px·세로 32px, 중앙 면 폭은 최대 440px. 안쪽 여백 24px, 640px부터 32px. 위 바·탭 바·사이드바 없음. 높이는 최소 화면 높이이며 내용이 길면 자연스럽게 스크롤한다. 로그인과 로그인 확인 중·실패에서 사용하며 다음 step의 신청·승인 대기·거절됨·처리방침 자리 화면도 같은 틀을 쓴다. |
| `Mogumi` · [components/Mogumi.tsx](../frontend/src/components/Mogumi.tsx) | 책 읽는 사본 한 장. 가로 128px·비율 유지·가운데 정렬. 대체 글 「보라색 머그컵 안에서 책을 읽는 모구미」. 변형·움직임 없음. |
| `Button` · [components/Button.tsx](../frontend/src/components/Button.tsx) | 기본 보라 버튼 한 종류. 최소 높이 52px·가로 여백 24px·세로 여백 12px·16px 모서리·흰색 굵은 글자·4px 아래 두께. hover는 진한 보라, 누르면 3px 내려가 두께가 1px. `disabled`는 옅은 바탕·보조 글자·그림자 없음·클릭 차단·눌림 없음. `focus-visible`은 보라 2px outline과 2px offset. 기본 `type="button"`, 네이티브 버튼 속성을 전달한다. `RequireAuth`의 다시 시도에 사용한다. |

각 컴포넌트는 같은 폴더의 `{이름}.test.tsx`에 짝 테스트가 있다. 특정 화면만의 변형 prop은 없다.

## 4. 로그인 화면

[LoginPage.tsx](../frontend/src/pages/LoginPage.tsx)는 시안의 중앙 묶음 순서인 모구미 → 서비스 이름·제목·짧은 소개 → 정원제 안내 → Google 로그인을 따른다. 제목과 모구미가 먼저 보이고, 버튼이 유일한 동작이다. 서비스 소개는 강의 만들기와 복습이 다음 강의를 바꾸는 것을 설명한다. 정원제 안내는 로그인 후 신청하고 운영자가 승인한다는 내용이다. 아이콘·배지·장식용 위 바·소개 슬라이드·처리방침 링크는 추가하지 않는다.

Google 링크는 기존 Roboto 500·44px 높이·흰 바탕·1px 테두리·4px 모서리·18px 로고 SVG·「Google 계정으로 계속하기」를 유지한다. 브랜드 색은 별도 토큰으로 옮겼으며 기본 버튼의 그림자·눌림 효과를 쓰지 않는다. 오류·세션 만료 안내는 같은 중앙 면 안의 `role="status"`로 읽고 포커스를 받는다. 안내가 없으면 제목에 포커스를 둔다. `not_allowed` 문구는 step 5까지 유지한다.

## 5. 모구미 사본

원본 `docs/design/assets/mogumi-reading.png`(1312×1199px)는 그대로 둔다. 화면 사본은 `frontend/src/assets/mogumi-reading.png`, 긴 변 384px(384×351px), 투명 배경 PNG다. 128px 표시의 3배 해상도를 확보하고 원본보다 전송량을 줄인다. PNG는 원본의 투명도를 유지하며 macOS 기본 도구로 재생성할 수 있다. Vite가 빌드 때 사본을 해시된 파일로 옮긴다.

저장소 루트에서 실행한 명령:

```bash
mkdir -p frontend/src/assets
sips -Z 384 docs/design/assets/mogumi-reading.png --out frontend/src/assets/mogumi-reading.png
```

## 6. 아직 정하지 않은 것 — 처음 쓰는 TASK가 정한다

아이콘 세트·탭 바·사이드바·위 바·홈 배치·공통 카드·배지·진도 막대·확인 펼침·추가 버튼 모양·애니메이션은 처음 필요한 TASK에서 정한다. 지금의 중앙 면과 안내 문단을 미리 카드 컴포넌트로 만들지 않는다. 다크 모드는 미정 항목이 아니라 MVP에서 제외한 기능이다(ADR-064).
