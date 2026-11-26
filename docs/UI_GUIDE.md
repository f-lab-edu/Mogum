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
| `surface` | `#ffffff` | 로그인 전 화면의 중앙 면·Google 버튼·앱 위 바·탭 바·사이드바 |
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
| `--container-content` | `48rem` (768px) | 승인 뒤 틀의 본문 최대 폭 |
| `--breakpoint-sm` | `40rem` (640px) | 넓은 화면에서 중앙 면 안쪽 여백 24→32px |
| `--breakpoint-lg` | `64rem` (1024px) | 아래 탭 바에서 왼쪽 사이드바로 바뀌는 경계 |

글자 굵기는 Tailwind 기본 토큰 `font-medium`(500, Google), `font-semibold`(600), `font-bold`(700), `font-extrabold`(800, 제목)를 쓴다. 포커스 테두리는 2px·바깥 간격 2px이다. `body`의 `word-break: keep-all`과 `overflow-wrap: break-word`를 유지한다. 한국어는 낱말을 지키고 긴 한 덩어리는 화면 안에서 줄바꿈한다. 다크 모드는 두지 않는다.

## 3. 공통 컴포넌트와 로그인 전 틀

| 이름 · 경로 | 모양 · 동작 · 상태 |
|---|---|
| `AccountLayout` · [components/AccountLayout.tsx](../frontend/src/components/AccountLayout.tsx) | `main` 하나. 바탕 위에 흰 중앙 면·24px 모서리·옅은 그림자. 모바일은 한 줄 세로 배치, 바깥 여백 가로 16px·세로 32px, 중앙 면 폭은 최대 440px. 안쪽 여백 24px, 640px부터 32px. 위 바·탭 바·사이드바 없음. 높이는 최소 화면 높이이며 내용이 길면 자연스럽게 스크롤한다. 로그인·로그인 확인 중·실패·신청·승인 대기·거절됨·처리방침 자리 화면이 같은 틀을 쓴다. |
| `Mogumi` · [components/Mogumi.tsx](../frontend/src/components/Mogumi.tsx) | 책 읽는 사본 한 장. 기본 가로 128px·비율 유지·가운데 정렬. 대체 글 「보라색 머그컵 안에서 책을 읽는 모구미」. 로고 크기(40px)는 §7. 움직임 없음. |
| `Button` · [components/Button.tsx](../frontend/src/components/Button.tsx) | 기본 `variant="primary"`는 보라·흰색 굵은 글자·4px 아래 두께. hover는 진한 보라, 누르면 3px 내려가 두께가 1px. `variant="secondary"`는 보조 동작용으로 안내 바탕·보조 글자·옅은 테두리이며 hover는 `surface-muted`, 아래 두께는 없다. 둘 다 최소 높이 52px·가로 여백 24px·세로 여백 12px·16px 모서리. `disabled`는 옅은 바탕·보조 글자·그림자 없음·클릭 차단·눌림 없음. `focus-visible`은 보라 2px outline과 2px offset. 기본 `type="button"`, 네이티브 버튼 속성을 전달한다. 신청·다시 시도는 기본형, 계정 전환·로그아웃은 보조형을 쓴다. |
| `AccountIdentity` · [components/AccountIdentity.tsx](../frontend/src/components/AccountIdentity.tsx) | 신청·승인 대기·거절됨·내 정보 탭에서 공유하는 계정 영역. 선택 prop `title`은 기본 「신청 계정」이며 내 정보 탭은 「구글 계정」을 넘긴다. 보이는 `h2` 제목을 `aria-labelledby`로 영역 이름에 연결한다. 안내 바탕·옅은 테두리·16px 모서리·안쪽 여백 16px. 이름과 구글 이메일을 `dl`로 세로 표시한다. 긴 값은 자르지 않고 줄바꿈하며 사진·입력 칸은 없다. |
| `FailureNotice` · [components/FailureNotice.tsx](../frontend/src/components/FailureNotice.tsx) | 무엇이 안 됐는지·안내·전달받은 동작을 함께 담는 `role="alert"` 영역. 안내 바탕·옅은 테두리·16px 모서리·안쪽 여백 16px·요소 사이 12px. 제목은 굵은 오류색, 안내는 작은 본문이며 동작은 공통 `Button`·링크를 쓴다. |
| `CreationBlockedNotice` · [components/CreationBlockedNotice.tsx](../frontend/src/components/CreationBlockedNotice.tsx) | `11-home-credits` step 8의 공통 만들 수 없음 안내. 같은 파일의 `isCreationBlockedError`가 두 거절 code를 가르고, 내용은 [06 §2.5](./06-screens.md#25-실패--오류)를 따른다. `FailureNotice`와 같은 안내 바탕(`surface-soft`)·옅은 테두리·16px 모서리·16px 여백·12px 간격이며, 제목은 오류색 대신 굵은 기본 글자색(`text`)이다. 동작 직후 생긴 기다림 안내를 방해 없이 읽도록 `role="status"`를 쓴다. 서버 문구는 날짜를 덧붙이지 않고 원문 그대로, 크레딧 부족에만 「남은 크레딧 보기」(`/profile`) 링크를 보인다. 링크는 보라색·밑줄·최소 44px·공통 포커스다. 재시도 안내·버튼은 없다. 새로 배우기·강의 열기·다시 짜기(코스/로드맵)·다음 코스 만들기(로드맵 이대로 시작 포함)에서 공유하며, 강의 열기는 같은 영역에 기존 「코스로 돌아가기」 링크도 둔다. 새 토큰은 없다. |
| `TextField` · [components/TextField.tsx](../frontend/src/components/TextField.tsx) | 한 줄 글 입력·`multiline`이면 4줄 높이의 여러 줄 입력. 굵은 이름과 필수/선택 표시 → 입력 → 선택적인 길이 안내 → 칸 오류 순서. 흰 면·옅은 테두리·16px 모서리·최소 높이 52px·가로 여백 16px·세로 여백 12px. `value`·`onChange`·`required`·`disabled`·`maxCodePoints`·`error`를 받는다. `maxCodePoints`가 있으면 유니코드 코드 포인트 수를 `현재 / 상한자`로 표시하고 초과 시 오류를 알린다. UTF-16 단위인 HTML `maxlength`로 자르지 않는다. 오류는 붉은 테두리·`aria-invalid`·`aria-describedby`로 연결하고, 잠금은 옅은 바탕·보조 글자·네이티브 disabled. 키보드 포커스는 공통 2px 보라 outline·2px offset. |
| `ChoiceGroup` · [components/ChoiceGroup.tsx](../frontend/src/components/ChoiceGroup.tsx) | 이름·필수/선택 표시와 나란한 선택 버튼 묶음. 모바일 2열·640px부터 4열, 간격 12px. 흰 면·옅은 테두리·16px 모서리·최소 높이 52px·안쪽 여백 가로 16px/세로 12px. 선택은 안내 바탕·보라 글자/테두리/2px ring·채운 원 표시로 구분한다. 미선택은 빈 원, hover는 안내 바탕. `options`·`value`·`onChange`·`required`·`disabled`·`error`를 받으며 기본 선택을 만들지 않는다. 네이티브 radio와 같은 name, 이름 있는 `fieldset role="radiogroup"`으로 단일 선택·키보드 이동을 지원한다. 포커스는 버튼 모양 영역에 공통 outline, 잠금은 네이티브 disabled·옅은 바탕·보조 글자, 오류는 묶음에 `aria-invalid`·`aria-describedby`로 연결한다. |
| `CheckboxField` · [components/CheckboxField.tsx](../frontend/src/components/CheckboxField.tsx) | 안내 바탕·옅은 테두리·16px 모서리·안쪽 여백 16px. 24px 네이티브 checkbox와 굵은 이름은 최소 높이 44px의 클릭 영역, 선택적 `description`은 그 아래 작은 보조 글자·12px 간격이며 `aria-describedby`로 연결한다. `checked`·`onChange`·`disabled`를 받는다. 선택은 기존 `primary` accent, 키보드 포커스는 공통 outline, 잠금은 네이티브 disabled. |
| `FieldError` · [components/FieldError.tsx](../frontend/src/components/FieldError.tsx) | 작은 오류색 글자·`role="alert"`. 서버 `fields`의 칸별 `reason`에는 별도 문구가 없으므로 `REQUIRED`는 「필수 입력이에요.」, `TOO_LONG`은 「글자 수 상한을 넘었어요.」, `INVALID_VALUE`는 「보기에서 골라 주세요.」, `NOT_ALLOWED`는 「이 칸은 비워 주세요.」로 설명한다. 모르는 reason은 「입력을 확인해 주세요.」이며 원래 code/reason은 노출하지 않는다. 오류 응답의 `message`는 `FailureNotice`에서 원문 그대로 함께 보인다. |
| `StatusBadge` · [components/StatusBadge.tsx](../frontend/src/components/StatusBadge.tsx) | 상태 글을 `children`으로 받는 공통 표시. 안내 바탕·보라색 작은 굵은 글자·16px 모서리·가로 여백 12px/세로 4px. 색과 함께 상태 이름을 항상 보인다. 코스 화면·코스 탭은 검토 중·학습 중·다 마침, 로드맵 화면은 로드맵 상태와 코스별 짜기 전·만드는 중·실패·만들어짐을 같은 모양으로 표시한다. |
| `ProgressBar` · [components/ProgressBar.tsx](../frontend/src/components/ProgressBar.tsx) | `label`·`completed`·`total`을 받는다. 굵은 이름과 보라색 `완료 수 / 전체` 글 아래 12px 높이·16px 모서리의 옅은 바탕에 실제 비율만큼 보라색을 채운다. 이름·최솟값 0·현재값·전체를 `role="progressbar"`의 ARIA 속성으로 연결한다. 전체가 0이면 채우지 않는다. 코스 화면·로드맵 화면에서 실제 학습 진도에만 쓴다. |
| `InlineConfirmation` · [components/InlineConfirmation.tsx](../frontend/src/components/InlineConfirmation.tsx) | 지우기·탈퇴에서 재사용할 확인 펼침. 보조형 입구 아래에 안내 바탕·옅은 테두리·16px 모서리·16px 여백으로 제목·삭제 범위·기본형 확인/보조형 취소를 펼친다. 자세한 상태·포커스 규격은 §15. |

각 컴포넌트는 같은 폴더의 `{이름}.test.tsx`에 짝 테스트가 있다. `Mogumi`의 공통 크기는 §7을 따른다.

## 4. 로그인 화면

[LoginPage.tsx](../frontend/src/pages/LoginPage.tsx)는 시안의 중앙 묶음 순서인 모구미 → 서비스 이름·제목·짧은 소개 → 정원제 안내 → Google 로그인을 따른다. 제목과 모구미가 먼저 보이고, 버튼이 유일한 동작이다. 서비스 소개는 강의 만들기와 복습이 다음 강의를 바꾸는 것을 설명한다. 정원제 안내는 로그인 후 신청하고 운영자가 승인한다는 내용이다. 아이콘·배지·장식용 위 바·소개 슬라이드·처리방침 링크는 추가하지 않는다.

Google 링크는 기존 Roboto 500·44px 높이·흰 바탕·1px 테두리·4px 모서리·18px 로고 SVG·「Google 계정으로 계속하기」를 유지한다. 브랜드 색은 별도 토큰으로 옮겼으며 기본 버튼의 그림자·눌림 효과를 쓰지 않는다. 오류·세션 만료 안내는 같은 중앙 면 안의 `role="status"`로 읽고 포커스를 받는다. 안내가 없으면 제목에 포커스를 둔다. 콜백의 `error`는 값에 관계없이 공통 로그인 실패 안내를 쓴다.

### 4.1 화면 주소와 계정 화면 구성

`4-account` step 5의 계정 주소와 `6-job-worker` step 6의 탭·step 7의 작업과 결과 주소는 다음과 같다. 상태 흐름과 화면 내용은 [06 §2.2 · §3](./06-screens.md#3-로그인-전-화면), 통신 계약은 [05 §3 · §4](./05-api.md#3-읽기--화면마다-한-번)를 따른다.

| 주소 | 화면 · 표시 조건 |
|---|---|
| `/login` | 기존 로그인. 상태 읽기 없음 |
| `/apply` | 신청 · `NOT_APPLIED` |
| `/waiting-approval` | 승인 대기 · `APPLIED` |
| `/rejected` | 거절됨 · `REJECTED` |
| `/` | `APPROVED`의 홈(`HomePage`, 제목 「머금」). 처음 열리는 탭. 스트릭과 홈 다섯 칸(§16) |
| `/new` | `APPROVED`의 새로 배우기(`NewLearningPage`). 위 바 제목 「새로 배우기」·뒤로 가기. 받기 성공 시 `/jobs/{id}`로 이동 |
| `/courses` | `APPROVED`의 코스 탭(`CoursesPage`). 제목·상태의 목록 행에서 코스 화면으로 이동(§11) |
| `/roadmaps` | `APPROVED`의 로드맵 탭(`RoadmapsPage`). 제목·설명·진행 한 줄의 목록 행에서 로드맵 화면으로 이동(§11) |
| `/review` · `/review?courseId={id}` | `APPROVED`의 복습 탭(`ReviewPage`). 전체 또는 해당 코스의 오늘 복습할 카드(§15) |
| `/profile` | `APPROVED`의 내 정보 탭(`ProfilePage`). 계정·남은 크레딧·다음 채움 날짜·로그아웃·처리방침(§17) |
| `/jobs/:jobId` | `APPROVED`의 기다리는 화면. 위 바 제목 「기다리는 화면」·뒤로 가기 |
| `/courses/:courseId` | `APPROVED`의 코스 화면(`CoursePage`). 위 바 제목 「코스」·뒤로 가기. 검토 중·학습 중·다 마침을 같은 주소에서 표시(§9) |
| `/roadmaps/:roadmapId` | `APPROVED`의 로드맵 화면(`RoadmapPage`). 위 바 제목 「로드맵」·뒤로 가기. 검토 중·진행 중·다 마침을 같은 주소에서 표시(§10) |
| `/courses/:courseId/lessons/:lessonId` | `APPROVED`의 강의 화면(`LessonPage`). 위 바 제목 「강의」·뒤로 가기(§13) |
| `/courses/:courseId/done` | `APPROVED`의 축하 화면(`CelebrationPage`). 위 바 제목 「축하 화면」·뒤로 가기(§14) |
| `/privacy` | 개인정보처리방침 자리. 로그인·상태 읽기 없이 제목·준비 중·돌아가기만 표시 |

계정 화면 세 개는 [AccountPage.tsx](../frontend/src/pages/AccountPage.tsx)에서 상태별로 구성한다. `/`와 계정 주소를 열 때 「나」를 읽어 현재 상태의 주소로 보낸다. 모르는 상태는 기존 불러오기 실패·다시 시도로 처리하며 값을 노출하지 않는다. 승인 대기에 폴링·새로고침 버튼을 두지 않는다. `ACCOUNT_NOT_APPROVED`를 받으면 「나」를 다시 읽어 현재 상태를 반영한다.

새로 배우기는 [NewLearningPage.tsx](../frontend/src/pages/NewLearningPage.tsx)에서 안내·모구미 묶음 → 흰 입력 면 순서로 구성한다. 입력 면은 24px 모서리·옅은 테두리·안쪽 여백 24px(640px부터 32px), 입력 묶음 간격 32px이며 §3의 공통 컴포넌트를 쓴다. 내용과 보기는 [06 §5](./06-screens.md#5-새로-배우기) · [03 §1.2](./03-generation.md#12-입력--새로-배우기), 길이와 보내는 칸은 [05 §2](./05-api.md#2-만드는-작업--받기-하나--다시-읽기)를 따른다. 필수 입력과 길이 상한을 만족해야 만들기를 누를 수 있다. 요청 중 입력·선택·만들기를 잠그고, 같은 순간의 중복 제출도 한 번만 보낸다. [useJob.ts](../frontend/src/api/useJob.ts)의 `receiveJob(NewLearningRequest)`가 공통 `apiFetch`로 받기를 보내며 자동 재시도하지 않는다. 두 만들 수 없음 거절은 §3의 `CreationBlockedNotice`, 그 밖의 실패는 `FailureNotice`에 서버 문구 원문과 재제출 안내를 보인다. 입력·선택을 보존하며 만들기를 다시 누를 수 있다. 새 토큰은 추가하지 않았다.

시안의 핵심 순서를 맞춰 모구미·제목 → 계정 → 저장 정보 안내 → 신청·계정 전환으로 구성한다. 승인 대기는 제목 뒤에 알림 없음 안내를 먼저 두고, 계정 → 삭제 요청 안내 → 처리방침·로그아웃으로 이어진다. 거절됨은 제목 → 계정 → 남은 정보·삭제 요청 안내 → 처리방침·로그아웃 순서다. 처리방침 링크는 안내 영역에 두며 보라 글자·밑줄·최소 높이 44px·공통 포커스 outline을 쓴다. 처리방침의 돌아가기는 `/`에서 현재 상태를 다시 읽는 방식이다.

제목은 진입 시 포커스를 받고 문서 제목도 바꾼다. 신청·로그아웃 요청 중에는 화면의 동작 버튼을 잠근다. 신청 성공 응답은 「나」 캐시에 반영하고, 로그아웃 성공은 캐시를 지운다. 동작 실패는 무엇이 안 됐는지·서버 문구 원문·다시 시도를 `role="alert"`와 함께 표시한다. 연결 실패는 기술 상세 없이 연결 안내를 쓴다. 「연결하지 못했어요. 다시 시도해 주세요.」는 [api/client.ts](../frontend/src/api/client.ts)의 공통 상수 `CONNECTION_ERROR_MESSAGE`이며, 서버 문구가 없거나 성공 응답의 본문 모양이 틀릴 때 `ApiError` 메시지로도 쓴다. 처리방침 본문·요약·운영자 연락처는 넣지 않는다.

## 5. 모구미 사본

원본 `docs/design/assets/mogumi-reading.png`(1312×1199px)는 그대로 둔다. 화면 사본은 `frontend/src/assets/mogumi-reading.png`, 긴 변 384px(384×351px), 투명 배경 PNG다. 128px 표시의 3배 해상도를 확보하고 원본보다 전송량을 줄인다. PNG는 원본의 투명도를 유지하며 macOS 기본 도구로 재생성할 수 있다. Vite가 빌드 때 사본을 해시된 파일로 옮긴다.

저장소 루트에서 실행한 명령:

```bash
mkdir -p frontend/src/assets
sips -Z 384 docs/design/assets/mogumi-reading.png --out frontend/src/assets/mogumi-reading.png
```

## 6. 아직 정하지 않은 것 — 처음 쓰는 TASK가 정한다

공통 카드·추가 버튼 모양·애니메이션은 처음 필요한 TASK에서 정한다. 홈 배치·홈 페이지 안의 영역 틀(`HomeSection` — 공통 컴포넌트가 아니다)은 `11-home-credits` step 6에서 §16으로 정했다. 확인 펼침은 `9-card-review` step 3에서 §3·§15의 `InlineConfirmation`으로 정했다. 상태 표시·진도 막대는 `7-course-planning` step 8에서 §3의 공통 컴포넌트로 정했다. 글 입력·여러 줄 입력·선택 버튼 묶음·체크·칸 오류는 `7-course-planning` step 7에서 §3의 공통 컴포넌트로 정했다. 지금의 중앙 면과 안내 문단을 미리 카드 컴포넌트로 만들지 않는다. 다크 모드는 미정 항목이 아니라 MVP에서 제외한 기능이다(ADR-064).

## 7. 승인 뒤 화면의 틀

`6-job-worker` step 6에서 정한다. 주소는 §4.1, 화면 구분과 탭 순서는 [06 §2.1~2.3](./06-screens.md#21-형태와-모양의-원칙)을 따른다. 홈은 `11-home-credits` step 6에서 §16의 화면으로, 내 정보는 step 7에서 §17의 화면으로 채웠다. 코스·로드맵 탭은 같은 TASK의 step 9에서 §11의 목록으로 채웠다. 나머지 내용은 해당 TASK에서 채운다.

| 이름 · 경로 | 모양 · 동작 · 상태 |
|---|---|
| `AppLayout` · [components/AppLayout.tsx](../frontend/src/components/AppLayout.tsx) | `RequireAuth`가 전달한 `Me` outlet context를 읽어 `APPROVED`만 표시한다. 승인 전에는 `/`의 기존 계정 상태 분기를 거친다. 본문은 `children`으로 받고 틀이 `main` 하나를 제공한다. 바탕·기본 글꼴·색은 §2 공통 토큰. 본문은 가로 16px·위 24px 여백, 최대 폭 768px·가운데 정렬이며 자연스럽게 스크롤한다. 1024px부터 왼쪽에 사이드바 폭만큼 256px 여백, 본문 가로·아래 여백 32px. |
| 아래 탭 바 · `AppLayout` 안 | 1024px 미만에서 화면 아래 고정, 흰 면·위 테두리·높이 80px·안쪽 여백 8px. 다섯 링크가 같은 폭으로 아이콘 위·글자 아래 배치된다. 본문 아래 96px 여백을 두어 가림을 막는다. |
| 사이드바 · `AppLayout` 안 | 1024px부터 왼쪽 고정·폭 256px·전체 높이·흰 면·오른쪽 테두리·안쪽 여백 16px. 위에 모구미와 「머금」 로고, 아래 다섯 링크를 세로로 둔다. 아래 탭 바는 숨긴다. |
| 탭 링크 · `AppLayout` 안 | 홈·코스·로드맵·복습·내 정보 순서. React Router `NavLink`로 같은 브라우저 탭에서 이동한다. 현재 탭은 `aria-current="page"`·보라 글자·굵은 글자·`surface-soft` 바탕, 나머지는 보조 글자·600 굵기이며 hover는 안내 바탕. 홈은 `/`에서만 활성, 다른 탭은 해당 주소의 하위 화면에서도 활성이다. 터치 영역 최소 44px, 공통 2px 포커스 outline·2px offset. |
| 위 바 · `AppLayout` 안 | 높이 56px·흰 면·아래 테두리·스크롤 위에 붙는 바. 기본(탭 첫 화면)은 40px 모구미와 「머금」 로고. 1024px 부터 로고는 사이드바에만 둔다. 화면이 `title`을 넘기면 뒤로 가기 버튼과 해당 제목(`h1`)을 보인다. 뒤로 가기는 `navigate(-1)`로 브라우저의 이전 화면으로 간다. 버튼은 최소 44×44px·공통 포커스 outline. 제목은 진입 시 포커스를 받고 문서 제목을 `제목 · 머금`으로 바꾼다. |
| 탭 아이콘 · `AppLayout` 안 | [guide §3](./design/guide.md#3-표준-내비게이션-5대-탭-아이콘-navigation-icons)의 홈·학사모·경로 노드·카드 덱·프로필 인라인 SVG를 그대로 사용한다. 24×24px·`currentColor`·선 두께 2, `aria-hidden`·`focusable="false"`로 장식 처리하며 접근 가능한 이름은 링크의 글자다. 아이콘 라이브러리는 없다. |
| `Mogumi` · [components/Mogumi.tsx](../frontend/src/components/Mogumi.tsx) | 기본 `size="page"`는 기존 128px·가운데 정렬. `size="logo"`는 위 바·사이드바의 40px 표시다. 같은 로컬 사본·대체 글·원래 비율을 유지한다. |

뒤 TASK의 탭에서 들어간 화면은 `RequireAuth` 아래에서 `<AppLayout title="기다리는 화면">…본문…</AppLayout>`처럼 사용한다. 탭 첫 화면은 `title` 없이 감싸고 본문에서 제목을 제공한다. 승인 전 화면과 처리방침은 기존 `AccountLayout`을 유지한다.

너비 분기는 jsdom에서 실제 레이아웃을 계산하지 않으므로 `lg`(1024px) 표시·숨김, `w-64` 사이드바, `h-14` 위 바, 본문 여백의 클래스·구조를 시험한다. 실제 브라우저의 너비별 배치·가림·포커스 테두리 렌더링을 검증한 것으로 보지는 않는다.

## 8. 기다리는 화면

`6-job-worker` step 7의 [WaitingPage.tsx](../frontend/src/pages/WaitingPage.tsx)는 §4.1의 작업 주소에서 §7의 `AppLayout`을 쓴다. 표시와 동작의 계약은 [06 §6](./06-screens.md#6-기다리는-화면), API와 다시 읽는 간격은 [05 §2](./05-api.md#2-만드는-작업--받기-하나--다시-읽기)가 출처다.

모구미 → 만드는 대상 → 상태 한 줄 → 떠나도 된다는 안내 순서다. 흰 중앙 면은 최대 440px·24px 모서리·옅은 테두리·안쪽 여백 24px(640px부터 32px)이며, 대상 제목을 24px·800 굵기로 가장 강조한다. 새로 배우기의 수준·목적은 제목 뒤에 작은 보조 글자로 표시하며 [02 §8.7](./02-domain-data.md#87-값-목록)의 화면 글·기타 입력을 쓴다. 나머지 종류는 대상 제목을 쓴다. 모르는 수준·목적은 생략한다. 상태는 보라색 굵은 본문, 떠나도 된다는 안내는 중앙 면 아래의 안내 바탕·16px 여백이다. 기존 토큰과 모구미 사본을 재사용한다.

| 상태 | 화면 동작 |
|---|---|
| 읽는 중 | 불러오는 중이라는 한 줄을 `role="status"`로 표시 |
| 기다림·만드는 중 | 대상 아래 실제 작업 상태 한 줄을 `role="status"`로 표시. 간격 읽기로 갱신하며 안내 문구·종류별 상태 문구는 06 §6을 그대로 사용 |
| 실패 | 대상 아래 `FailureNotice`로 실패한 것·서버 문구·동작을 함께 표시. `JOB_TEMPORARY_FAILURE`는 다시 시도·버리기, `JOB_INPUT_FAILURE`는 버리기만. 모르는 묶음은 서버 문구와 홈으로 링크를 표시 |
| 성공 | 응답 `result`의 종류·ID로 §4.1의 결과 주소를 골라 `replace`로 이동. 뒤로 가면 기다리는 화면이 다시 열리지 않음 |
| 없는 작업·연결 실패·모르는 상태/종류/결과 | 읽지 못했다는 안내와 다시 시도·홈으로를 함께 표시. 서버 오류는 서버 문구, 연결·해석 실패는 기술 상세 없는 공통 안내를 사용 |

다시 시도·버리기 요청 중에는 두 버튼을 잠근다. 동작 실패는 별도 `FailureNotice`에서 무엇이 안 됐는지와 서버 문구(연결 실패는 공통 안내)를 보이고 원래 동작을 다시 할 수 있다. 버리기 성공은 현재 홈으로 이동하며, 종류별 돌아갈 화면은 뒤 TASK에서 연결한다. 같은 작업 주소의 ID가 바뀌면 동작 상태도 새로 시작한다. API 훅 [useJob.ts](../frontend/src/api/useJob.ts)는 공통 `apiFetch`를 쓰고, 동작 성공 시 진행 중인 읽기를 취소한 뒤 해당 작업 캐시를 교체하거나 지운다. 읽기 오류 뒤에는 수동으로 다시 읽을 수 있으며, 창 포커스·재연결로 따로 읽지 않는다.

진행 단계·퍼센트·남은 시간·대기 순서·오래 걸릴 때 안내·기다리는 동안 복습·결과 자리·취소·크레딧·운영 정보는 두지 않는다. 모르는 응답 칸과 값은 화면에 그대로 내보내지 않는다. 위 바 제목의 포커스·문서 제목은 `AppLayout`이 관리하며, 성공 이동·뒤로 가기·폴링 중단/재개·버튼 잠금·확장 응답·실패 안내는 API 대역과 가짜 타이머로 시험한다.

## 9. 코스 화면

`7-course-planning` step 8의 [CoursePage.tsx](../frontend/src/pages/CoursePage.tsx)는 §7의 `AppLayout` 안에서 [06 §7](./06-screens.md#7-코스-화면)의 상태별 내용을 표시한다. 시안의 제목 영역 → 상태별 주요 안내·동작 → 강의 목록 순서다. 영역 사이 간격은 24px, 흰 면은 24px 모서리·옅은 테두리·안쪽 여백 24px(640px부터 32px)이며 새 토큰은 추가하지 않았다. 제목은 24px·800 굵기이고, 제목 영역에는 §3의 상태 표시와 책 읽기 모구미 사본을 쓴다. 모바일에서는 세로로, 640px부터 제목과 모구미를 나란히 둔다. 다 마침은 모구미를 앞에 두며 모바일에서는 가운데 정렬한다.

| 상태 | 구성 |
|---|---|
| 검토 중 | 제목 영역 → 강의를 아직 열 수 없다는 안내와 다시 짜기(보조형)·이대로 시작(기본형) → 강의 목록. 다시 짜기 입력은 같은 안내 영역 안에 펼친다. |
| 학습 중 | 제목 영역 안의 진도 막대 → 보라색 면·흰 글자·굵은 제목으로 강조한 다음에 열 강의 → 강의 목록. 다음 강의의 목록 행도 보라 테두리로 구분한다. |
| 다 마침 | 제목 영역에 완료 안내·`Asia/Seoul`의 한국 날짜·로드맵 코스일 때만 로드맵 화면 링크 → 강의 목록. |
| 짜기 전 | 코스 내용 대신 소속 로드맵 화면으로 가는 링크만 제공한다. |
| 읽는 중·읽기 실패 | 읽는 중 한 줄은 `role="status"`. 404·연결 실패·모르는 상태는 `FailureNotice`에 읽지 못한 것·서버 문구(연결 실패는 공통 안내)·다시 시도·홈으로를 표시한다. |

강의 목록은 이름 있는 `ol`이며 각 행은 순서·제목 → 한 줄 설명 → 다룰 개념의 이름·정의 순서다. 행은 흰 면·24px 모서리·옅은 테두리·24px 여백(640px부터 32px), 행 사이 16px 간격이다. 개념은 안내 바탕·16px 모서리·작은 글자이며 완료한 강의는 「학습 완료」로 표시한다. 학습 중·다 마침의 강의 제목은 학습 완료했거나 `nextLessonId`인 경우에만 강의 화면 링크다. 그 뒤의 강의는 「앞 강의를 학습 완료하면 열 수 있어요.」를 보이며 링크를 두지 않는다. 검토 중에는 모두 링크가 없다. 보라색 다음에 열 강의 영역의 제목도 같은 강의를 여는 링크이며, 흰 글자·밑줄·흰 포커스 outline을 쓴다.

코스 제목 고치기는 §3의 `TextField`와 저장·취소 버튼을 제목 영역에 펼친다. 편집 중에는 「코스 제목 고치기」 버튼을 비활성화해 입력을 보존하고, 취소·저장 성공으로 닫으면 다시 활성화한다. 보이는 이름만 바뀐다고 안내하며 공백·100 코드 포인트 초과는 저장을 막고 서버의 `title` 칸 오류는 해당 입력과 연결한다. 다시 짜기는 선택적인 「고칠 점」 여러 줄 입력·500 코드 포인트 길이 안내·요청·취소 버튼을 펼친다. 펼침 버튼의 `aria-expanded`는 펼침 상태와 맞추고, `aria-controls`는 입력이 펼쳐져 DOM에 있을 때만 붙인다. 두 펼침은 입력으로 포커스를 옮기며, 제목 저장·취소와 다시 짜기 취소는 원래 버튼으로 돌아간다. 요청 중에는 화면의 입력·동작 버튼을 잠그고 같은 순간의 중복 제출도 막는다. 다시 짜기의 두 만들 수 없음 거절은 §3의 `CreationBlockedNotice`로 보이며, 그 밖의 실패는 서버 문구 원문과 다시 누를 동작을 알린다. 입력은 보존한다.

[api/useCourse.ts](../frontend/src/api/useCourse.ts)의 `readCourse`·`startCourse`·`updateCourseTitle`은 공통 `apiFetch`를 쓰며, `useCourse`는 코스 하나를 `['courses', courseId]`에 캐시한다. 시작·제목 고치기의 `useCourseAction`은 성공 뒤 진행 중인 읽기를 취소하고 응답 전체로 같은 코스 캐시를 바꾼다. 시작해도 주소를 유지하고 1강을 열지 않는다. 다시 짜기는 [api/useJob.ts](../frontend/src/api/useJob.ts)의 `receiveJob(ReplanCourseRequest)`로 `REPLAN_COURSE`·`courseId`·선택 `feedback`만 보내며, 빈 피드백은 생략하고 서버가 반환한 작업의 `/jobs/{id}`로 이동한다. 이미 있는 작업을 반환해도 같은 경로다. 읽기·쓰기는 자동 재시도하지 않으며, 읽기 실패는 다시 시도 버튼으로 다시 읽는다.

`9-card-review` step 4의 「이 코스 복습하기 · {장수}장」은 강의 목록 아래에 둔다. `CourseResponse.todayReviewCount`가 0보다 큰 학습 중·다 마침 코스에서만 보이며, 0이거나 검토 중·짜기 전이면 입구와 안내를 두지 않는다. 기존 `PageLink`의 보라색 굵은 글자·밑줄·최소 높이 44px·공통 포커스 모양으로 표시하고 새 토큰은 없다. 제목·진도 → 가장 강조한 다음에 열 강의 → 강의 목록의 순서를 유지한다. 같은 브라우저 탭에서 `/review?courseId={코스 ID}`로 이동하며 장수는 코스 읽기 응답을 그대로 쓴다.

`10-mastery-loop` step 7은 `useCourse.ts`의 `CourseResponse`에 선택 칸 `masteryReport`를 더하고, `CoursePage`의 「코스 검토」 영역 첫 문단에 숙련도 반영 한 줄을 표시한다. 문구·표시 조건은 [06 §7](./06-screens.md#7-코스-화면), 응답은 [05 §3](./05-api.md#3-읽기--화면마다-한-번)을 따른다. 시안의 연보라 상자는 기존 `surface-soft` 바탕·`radius-control` 모서리·16px 여백·작은 보조 글자로 맞췄다. 새 컴포넌트·토큰은 없다. 짝 테스트는 수 조합별 표시·숨김과 시작 뒤 줄이 사라지는 동작을 `findBy…` 뒤 `waitFor` 안에서 확인한다.

상태별 내용·시작 후 같은 화면 유지·다시 짜기 본문과 이동·버튼 잠금·제목 저장/칸 오류·한국 날짜·조건부 로드맵 링크·순서에 따른 강의 링크·금지 정보 부재를 API 대역으로 시험한다. 복습 입구의 상태·장수별 표시와 강의 목록 뒤 배치를 확인하고, `App.test.tsx`에서는 실제 라우터로 학습 중·다 마침의 복습 링크를 눌러 해당 코스의 거른 읽기를 부르는지 확인한다. 포커스와 효과 결과는 `waitFor` 안에서 확인한다. 브라우저에서 실제 너비별 렌더링을 검증한 것으로 보지는 않는다.

`12-delete-withdraw` step 3은 단일 코스의 검토 중·학습 중·다 마침에서 강의 목록과 「이 코스 복습하기」 뒤에 보조형 「코스 지우기」를 둔다. 로드맵의 코스·짜기 전에는 입구가 없다. 공통 `InlineConfirmation`의 확인 제목은 「코스를 지울까요?」, 확인 버튼은 「지우기」이며 안내는 [06 §7의 상태별 문구](./06-screens.md#7-코스-화면)를 그대로 쓴다. 가장 강조한 다음 강의·이대로 시작보다 아래에 두고 새 토큰·컴포넌트는 추가하지 않았다. 삭제 요청 중 확인·취소·제목 편집·시작·다시 짜기의 입력과 버튼을 잠그며, 기존 `useRef` 잠금으로 같은 순간의 두 번 제출도 한 번만 보낸다. 실패하면 `FailureNotice`에 서버 문구(연결 실패는 공통 안내)를 보이고 확인 펼침을 보존해 다시 누를 수 있다. 오류 code는 표시하지 않는다.

`useCourseDeletion(courseId)`는 본문 없는 `DELETE /api/courses/{id}`를 보내며 자동 재시도하지 않는다. 204 성공 뒤 진행 중인 해당 코스·코스 목록·홈 읽기를 취소하고, 해당 코스 상세 캐시를 제거한 뒤 코스 목록·홈 캐시를 `refetchType: 'none'`으로 무효화한다. 정리를 마치면 `/courses`로 `replace` 이동하므로 지운 코스를 다시 읽지 않는다. 짝 테스트는 상태별 입구·정확한 안내·배치·취소와 포커스·DELETE 한 번·요청 잠금·실패 보존과 재시도·캐시 정리·replace 이동을 확인한다. 훅 테스트는 경로 인코딩·메서드·CSRF·204·진행 중 읽기 취소와 늦은 응답 차단·즉시 재조회 없음·실패 시 캐시 보존을 확인하고, `App.test.tsx`는 실제 라우터로 삭제 뒤 코스 탭이 열리고 지운 코스가 목록에서 빠지며 상세를 다시 읽지 않는지 확인한다.

## 10. 로드맵 화면

`7-course-planning` step 9의 [RoadmapPage.tsx](../frontend/src/pages/RoadmapPage.tsx)는 §7의 `AppLayout` 안에서 [06 §8](./06-screens.md#8-로드맵-화면)의 상태별 내용을 표시한다. 제목·설명·모구미 영역 → 검토 동작(검토 중만) → 로드맵 계획 순서다. 제목 영역·코스 행은 흰 면·24px 모서리·옅은 테두리·안쪽 여백 24px(640px부터 32px)이며 영역 사이 24px, 코스 행 사이 16px 간격이다. 제목은 24px·800 굵기다. 새 공통 컴포넌트·토큰 없이 §3의 `StatusBadge`·`ProgressBar`·`TextField`·`Button`·`FailureNotice`와 모구미 사본을 재사용한다.

| 상태 | 구성·동작 |
|---|---|
| 검토 중 | 로드맵 상태·제목·설명 다음에 보조형 다시 짜기·기본형 이대로 시작. 시작은 순서 1의 코스를 받으며, 다시 짜기는 선택적인 고칠 점 여러 줄 입력·500 코드 포인트 안내·요청·취소를 펼친다. 두 받기 모두 성공하면 반환된 작업의 `/jobs/{id}`로 이동한다. |
| 진행 중 | 제목 영역 아래 `ProgressBar`로 마친 코스 수 / 전체 코스 수를 표시한다. 계획의 각 행에는 서버 진행 값에 대응하는 §3의 상태 표시를 쓰고, 만들어진 코스는 검토 중·학습 중·다 마침도 글로 보인다. 만들어짐은 코스 화면, 만드는 중·실패는 서버가 준 작업 ID의 기다리는 화면으로 가는 링크를 둔다. |
| 다 마침 | 진행 중과 같은 계획·진행 표시와 모든 코스를 마쳤다는 안내. 다음 코스 만들기는 없다. |
| 읽는 중·읽기 실패 | 읽는 중 한 줄은 `role="status"`. 404·연결 실패·모르는 상태는 `FailureNotice`에 서버 문구(연결 실패는 공통 안내)·다시 시도·홈으로를 표시한다. |

계획은 이름 있는 `ol`에 왼쪽 2px 연결선·16px 안쪽 여백(640px부터 24px)을 두어 순서를 보인다. 행은 진행 상태 → 순서·코스 제목 → 설명 → 개념의 이름·정의 → 동작 순서다. 짜기 전은 다른 진행과 같은 `StatusBadge`의 「짜기 전」으로 표시하고 이동 링크를 두지 않는다. 학습 중 코스와 서버가 지정한 다음 코스의 행은 보라 테두리로 강조한다. `nextCourse`가 있으면 해당 행에 기본형 「다음 코스 만들기」를 두고, `enabled: false`이면 잠금 안내를 `aria-describedby`로 연결한다. 활성화되면 서버가 준 코스를 받아 기다리는 화면으로 이동한다. 다음 코스의 자격을 화면에서 다시 계산하지 않는다. 완료 비율의 숫자·진도율 문구는 추가하지 않는다.

검토 중에만 코스 제목 고치기를 각 행에 펼친다. 공백·100 코드 포인트 초과는 저장을 막고 보이는 이름만 바뀐다고 안내한다. 서버 칸 오류는 입력과 연결하며 실패 문구와 입력을 보존한다. 제목·피드백 펼침은 입력으로 포커스를 옮기고, 제목 저장·취소와 다시 짜기 취소는 원래 버튼으로 돌아간다. 요청 중에는 입력·동작 버튼을 잠그고 같은 순간의 중복 제출도 한 번만 보낸다. 만들기 받기의 두 만들 수 없음 거절은 §3의 `CreationBlockedNotice`로 보이며, 그 밖의 거절은 문구 원문과 다시 누를 동작을 알린다.

[api/useRoadmap.ts](../frontend/src/api/useRoadmap.ts)의 `useRoadmap`은 공통 `apiFetch`로 로드맵 하나를 읽어 `['roadmaps', roadmapId]`에 캐시한다. §9의 `useCourseAction`은 제목 저장·시작 성공 시 진행 중인 코스 목록·소속 로드맵 읽기도 취소한 뒤, 기존 목록 행과 로드맵 계획 행의 제목·코스 상태를 응답으로 맞춘다. 계획 설명·개념·다른 행은 보존한다. 로드맵의 받기는 `receiveJob(PlanRoadmapCourseRequest | ReplanRoadmapRequest)`로 대상과 선택 피드백만 보내며 빈 피드백은 생략한다. 읽기·쓰기는 자동 재시도하지 않는다.

상태별 표시·첫 코스/다음 코스 받기·작업 이동·서버 거절·다시 짜기·제목 저장/칸 오류·요청 잠금·캐시 갱신·늦은 읽기의 덮어쓰기 차단·금지 정보 부재를 API 대역으로 시험한다. 포커스와 효과 결과는 `waitFor` 안에서 확인하며, 실제 브라우저의 너비별 렌더링을 검증한 것으로 보지는 않는다.

`12-delete-withdraw` step 3은 검토 중·진행 중·다 마침 모두 로드맵 계획 뒤에 보조형 「로드맵 지우기」를 둔다. 공통 `InlineConfirmation`의 확인 제목은 「로드맵을 지울까요?」, 확인 버튼은 「지우기」이며 안내는 [06 §8의 상태별 문구](./06-screens.md#8-로드맵-화면)를 그대로 쓴다. 이대로 시작·다음 코스 만들기보다 아래에 두고 새 토큰·컴포넌트는 추가하지 않았다. 삭제 요청 중 확인·취소·코스 제목 편집·시작·다시 짜기·다음 코스 만들기의 입력과 버튼을 잠그고 기존 `useRef` 잠금으로 같은 순간의 중복 제출을 막는다. 실패하면 `FailureNotice`의 서버 문구(연결 실패는 공통 안내)와 확인 펼침·계획을 보존해 다시 누를 수 있다. 오류 code는 표시하지 않는다.

`useRoadmapDeletion(roadmapId)`는 소속 코스 ID 목록을 받아 본문 없는 `DELETE /api/roadmaps/{id}`를 보내며 자동 재시도하지 않는다. 204 성공 뒤 해당 로드맵·로드맵 목록·코스 목록·홈과 소속 코스들의 진행 중 읽기를 취소하고, 해당 로드맵과 그 모든 코스(짜기 전 포함)의 상세 캐시를 제거한다. 로드맵 목록·코스 목록·홈 캐시를 `refetchType: 'none'`으로 무효화한 뒤 `/roadmaps`로 `replace` 이동한다. 다른 로드맵·코스 상세 캐시는 유지한다. 짝 테스트는 세 상태의 입구·정확한 안내·배치·취소와 포커스·DELETE 한 번·입력과 동작 잠금·실패 보존과 재시도·소속 코스 캐시까지 정리·replace 이동을 확인한다. 훅 테스트는 경로 인코딩·메서드·CSRF·204·읽기 취소와 늦은 응답 차단·즉시 재조회 없음·실패 시 캐시 보존을, `App.test.tsx`는 실제 라우터에서 삭제 뒤 로드맵 탭이 열리고 상세를 다시 읽지 않는지 확인한다.

## 11. 코스 탭·로드맵 탭

`7-course-planning` step 9의 [CoursesPage.tsx](../frontend/src/pages/CoursesPage.tsx)·[RoadmapsPage.tsx](../frontend/src/pages/RoadmapsPage.tsx)는 제목 없는 §7의 `AppLayout` 안에 본문 제목 → 목록 순서로 배치한다. 제목은 24px·800 굵기·아래 간격 24px이며 진입 시 포커스와 문서 제목을 갱신한다. 목록은 서버 순서를 유지하는 이름 있는 `ul`, 행 사이는 16px이다. 행 전체가 같은 브라우저 탭에서 상세 주소로 이동하는 링크이며 흰 면·24px 모서리·옅은 테두리·24px 여백·보라색 hover 테두리·공통 포커스 outline을 쓴다.

코스 행은 굵은 제목과 §3의 상태 표시를 나란히 두며 좁은 화면·긴 제목은 줄바꿈한다. 로드맵 행은 굵은 제목 → 설명 → 작은 보라 글자의 진행 한 줄로 세로 배치한다. 완료 상태의 진행 문구는 「다 마침」이다. 비었을 때는 각 탭에 「아직 만든 코스가 없어요」·「아직 만든 로드맵이 없어요」 한 줄을 보인다. 읽는 중은 상태 안내, 실패는 `FailureNotice`와 다시 시도로 표시하며 실패를 빈 목록으로 보이지 않는다. 새로 배우기 입구·거르기·정렬·진도 막대·다음 강의·로드맵의 코스 목록은 두지 않는다.

`useCourses`·`useRoadmaps`가 공통 `apiFetch`로 목록을 읽어 각각 `['courses']`·`['roadmaps']`에 캐시한다. 자동 재시도·창 포커스·재연결에 따른 별도 읽기는 없으며 실패 뒤 버튼으로 다시 읽는다. 행 구성·이동·빈 목록·오류 후 재시도·금지 동작 부재·제목 포커스와 문서 제목을 API 대역으로 시험한다.

## 12. 강의 자료 본문

[components/MaterialBody.tsx](../frontend/src/components/MaterialBody.tsx)의 기본 내보내기 `MaterialBody`는 `body: string` 하나를 받는다. 강의 화면 틀·제목·동작·API 없이 본문만 그린다. 표현 수단과 오류 처리의 계약은 [03 §3](./03-generation.md#3-강의-자료와-카드) · [06 §9](./06-screens.md#9-강의-화면)를 따른다.

`react-markdown ^10.1.0`·`remark-gfm ^4.0.1`·`remark-math ^6.0.0`·`rehype-katex ^7.0.1`·`katex ^0.19.1`·`mermaid ^12.3.0`·`rehype-highlight ^7.0.2`를 쓴다. KaTeX는 CSS와 렌더러 버전이 같도록 npm `overrides.katex: "$katex"`로 하위 의존성도 맞춘다. 문법 강조는 전체 언어 묶음보다 작은 기본 37개 언어만 포함하는 `rehype-highlight`로 정했다. 언어 표시가 있을 때만 강조하고 자동 감지는 끈다. 모르는 언어·언어 없는 코드는 원문으로 남긴다.

원본 HTML은 `skipHtml`로 버리며 `rehype-raw`는 쓰지 않는다. 마크다운 이미지는 대체 글만 보이고 `img`를 만들지 않는다. 링크는 `react-markdown` 기본 URL 검사를 유지하며, 제거된 주소는 글로만 남긴다. 허용된 링크는 새 탭과 `rel="noopener noreferrer"`로 연다. KaTeX는 `trust: false`이며, 문법 오류는 `rehype-katex`의 `throwOnError: false` 대체 렌더링으로 오류 표시를 그대로 남긴다.

Mermaid는 해당 코드 블록이 처음 나타날 때만 동적 `import()`한다. 브라우저의 모듈 로딩 Promise를 공유하며 `securityLevel: 'strict'`·`startOnLoad: false`로 직접 그린다. 그림을 그릴 때마다 모듈 카운터를 늘려 `material-diagram-1` 형태의 id와 별도 렌더 컨테이너를 쓴다. 보안 컨텍스트가 필요 없어 휴대폰의 HTTP 개발 서버에서도 그릴 수 있고, 여러 그림·StrictMode·본문 변경·재마운트에서 충돌하거나 이전 결과가 덮어쓰지 않게 한다. `suppressErrorRendering: true`로 임시 오류 SVG 삽입을 막고, 실패한 자리에는 라이브러리의 오류 메시지를 HTML이 아닌 글로 표시한다. 다시 시도·수정 없이 나머지 본문을 유지하며, 부모가 같은 본문으로 다시 렌더되어도 실패한 그림을 다시 그리지 않는다.

글은 §2의 `text-body`(16px/26px), 큰 제목은 `text-title`(24px/32px)·800 굵기, 작은 제목은 `text-body`·700 굵기다. 코드·표는 `text-small`(14px/24px), 최상위 블록 간격은 `space-y-6`(24px), 목록은 `space-y-2`(8px)·왼쪽 24px이다. 코드와 인용은 `surface-soft` 바탕·16px 여백·`radius-control`, 인용 왼쪽 테두리와 강조는 `primary`를 쓴다. 코드 강조도 기존 글자·보라색 토큰만 쓰며 다크 테마는 없다.

컨테이너는 `min-w-0`·`max-w-full`, 긴 글은 `overflow-wrap: anywhere`로 줄바꿈한다. 코드·표·그림은 각각 최대 폭 안의 `overflow-x-auto` 영역이며 `role="region"`과 이름만 두고 `tabIndex`는 두지 않는다(SonarCloud `typescript:S6845` · 사용자 결정 2026-11-24). 코드 줄과 표의 내용 너비, Mermaid SVG의 `viewBox`에서 얻은 실제 너비를 유지해 블록 안에서 가로 스크롤한다. SVG 너비는 본문 데이터에서 얻은 크기이며 고정 UI 토큰이 아니다. 블록 수식은 `.katex-display` 자체에서만 가로 스크롤한다. 이 규칙은 화면 크기에 관계없이 같다.

step 5에서 로컬 Vite의 컴포넌트를 실제 Chromium으로 390px·1280px에서 수동 확인했다. 확인 항목은 긴 코드·표·그림·블록 수식의 내부 스크롤과 페이지 너비 보존이다. 깨진 Mermaid의 오류 메시지·KaTeX 오류 표시·뒤 본문이 함께 남는 것도 확인했다. step 6의 강의 화면 연결 검증은 §13에 적었다.

짝 테스트 [MaterialBody.test.tsx](../frontend/src/components/MaterialBody.test.tsx)는 `vi.hoisted`로 만든 `initialize`·`render` 대역을 `vi.mock('mermaid', () => ({ default: { initialize, render } }))`로 돌려준다. 모듈을 정적으로 import하지 않아 팩토리 호출로 지연 로딩도 관찰하며, 효과 결과는 `waitFor` 안에서 단언한다. 뒤 강의 화면 테스트도 이 대역을 사용할 수 있다.

## 13. 강의 화면

`8-material-cards` step 6의 [LessonPage.tsx](../frontend/src/pages/LessonPage.tsx)는 `/courses/:courseId/lessons/:lessonId`에서 §7의 `AppLayout`(제목 「강의」)을 쓴다. 흰 면·24px 모서리·옅은 테두리·안쪽 여백 24px(640px부터 32px) 안에서 순서가 포함된 강의 제목(「1강 · 변수」) → §12의 `MaterialBody` → 완료 영역 순서다. 제목은 24px·800 굵기, 영역 사이 간격은 24px이고 새 토큰은 없다. 화면 내용과 동작의 규칙은 [06 §9](./06-screens.md#9-강의-화면)를 따른다.

| 읽은 상태 | 구성 · 동작 |
|---|---|
| 자료 있음·학습 완료 전 | 제목·본문과 공통 `Button`의 「학습 완료」. 바로 위에 「학습 완료는 되돌릴 수 없어요. 다시 열어 읽을 수 있어요.」를 보이고 `aria-describedby`로 버튼과 연결한다. |
| 자료 있음·학습 완료 | 같은 제목·본문과 「학습 완료한 강의예요.」 표시. 완료 버튼은 없다. |
| 자료 없음·작업 있음 | 상태와 관계없이 `/jobs/{id}`로 `replace` 이동한다. 실패한 작업의 동작도 기존 기다리는 화면에서 처리한다. |
| 자료·작업 없음 | 「강의 자료를 요청하고 있어요.」를 `role="status"`로 보이고 `receiveJob({type: 'MATERIAL', lessonId})`를 호출한다. 받은 작업으로 `replace` 이동한다. |
| 받기의 두 만들 수 없음 거절 | §3의 `CreationBlockedNotice`와 기존 「코스로 돌아가기」 링크를 같은 영역에 보인다. |
| 읽기·그 밖의 받기 거절 | `FailureNotice`에 실패한 동작·서버 문구 원문(연결 실패는 공통 안내)·「코스로 돌아가기」 링크를 보인다. 오류 code는 표시하지 않는다. |

[api/useLesson.ts](../frontend/src/api/useLesson.ts)의 `useLesson`은 공통 `apiFetch`로 강의 하나를 읽어 `['lessons', lessonId]`에 캐시한다. 읽기·받기·완료는 자동 재시도하지 않는다. 최신 읽기가 끝난 뒤 자료·작업 분기를 결정하므로 기다리는 화면에서 돌아왔을 때 낡은 작업 캐시로 다시 이동하지 않는다. 받기 직전에 `useRef`의 요청 여부를 기록하고 TanStack Query mutation으로 요청 상태를 표시한다. React StrictMode의 효과 재실행에서도 받기를 한 번만 보내며, 주소가 바뀌면 `App`의 `key={pathname}`으로 요청 상태를 새로 시작한다. 받기 결과의 이동은 렌더링에서 처리해 화면을 떠난 뒤 늦은 응답이 이동시키지 않는다.

완료 중에는 버튼을 잠그고 `useRef`로 같은 순간의 중복 제출도 막는다. 성공하면 진행 중인 강의·코스·로드맵 읽기를 취소하고 강의 캐시를 지우며 코스·로드맵 읽기 캐시(각각 목록과 상세)를 무효화한다. 코스 다 마침 여부와 관계없이 `refetchType: 'none'`으로 즉시 다시 읽지 않고 해당 화면을 열 때 읽는다. 응답의 `courseCompleted`가 참이면 `/courses/{courseId}/done`, 아니면 `/courses/{courseId}`로 `replace` 이동한다. 코스 화면은 새 진도와 다음 강의를 읽으며, 다음 강의를 저절로 열지 않는다. 완료 실패는 본문과 버튼을 보존하며 서버 문구를 표시한다.

짝 테스트는 `fetch` 대역과 §12의 Mermaid 대역으로 본문·수식·그림·상태별 분기·StrictMode 받기 한 번·거절 문구·완료 중 잠금·실패·캐시 정리·교체 이동을 확인한다. `App.test.tsx`는 실제 라우터로 코스 → 첫 강의의 자료 받기 → 기다리는 화면의 성공 결과 → 본문 → 완료 → 새 진도의 코스 또는 축하 화면을 확인한다. API만 대역으로 둔 로컬 Vite 화면을 실제 Chromium의 390px·1280px에서 수동 확인했다. 두 너비 모두 문서 너비가 화면 너비와 같고, 긴 코드·표·블록 수식·Mermaid는 각각 내부 스크롤을 유지했다. 깨진 Mermaid·KaTeX의 오류 표시와 뒤 본문 유지, 축하 화면의 배치·「홈으로」 이동도 확인했다.

## 14. 축하 화면

[CelebrationPage.tsx](../frontend/src/pages/CelebrationPage.tsx)는 `/courses/:courseId/done`에서 §7의 `AppLayout`(제목 「축하 화면」)을 쓴다. 최대 폭 440px·가운데 정렬의 흰 면·24px 모서리·옅은 테두리·안쪽 여백 24px(640px부터 32px)에 §5의 책 읽기 모구미 사본 → 「{코스 제목} 코스를 마쳤어요. 고생했어요!」라는 문구 하나 → 공통 `Button`의 「홈으로」 순서다. 간격은 24px, 문구는 24px·800 굵기이며 새 토큰은 없다.

코스 제목은 `useCourse`의 코스 읽기로 가져온다. 읽는 중에는 상태 한 줄, 읽기 실패는 서버 문구(연결 실패는 공통 안내)와 「홈으로」를 `FailureNotice`에 표시한다. 단일 코스와 로드맵의 코스는 같은 구성이다. 「홈으로」를 누르면 바로 `/`로 가며 자동 이동은 없다. 축하 모구미는 [design README의 이미지 표](./design/README.md#모구미-이미지)에 요청된 상태를 유지한다. 짝 테스트는 코스 제목·모구미 사본·유일한 본문 버튼·홈 이동·읽기 실패를 확인한다.

## 15. 복습 탭

`9-card-review` step 3의 [ReviewPage.tsx](../frontend/src/pages/ReviewPage.tsx)는 제목 없는 §7의 `AppLayout` 안에서 본문 제목 「복습」 → 거른 코스 제목(해당할 때만) → 남은 장수 → 카드 지우기 입구·확인 펼침 → 질문 카드 순서로 배치한다. 시안의 카드·평가 버튼을 가장 강조하며 새 토큰은 없다. 영역 사이 간격은 24px, 질문 카드는 흰 면·24px 모서리·옅은 테두리·24px 여백(640px부터 32px), 질문은 24px·800 굵기다. 질문·답은 줄바꿈을 보존하는 글로 표시한다. 화면 내용·동작의 계약은 [06 §11](./06-screens.md#11-복습-탭), 통신은 [05 §3·§4](./05-api.md#3-읽기--화면마다-한-번)를 따른다.

| 상태 | 구성·동작 |
|---|---|
| 질문 | 현재 카드를 포함한 남은 장수와 질문, 전체 폭의 기본형 「답 보기」. 답·평가 버튼은 아직 없다. |
| 답 펼침 | 질문 아래 안내 바탕·16px 모서리·16px 여백의 답 → 평가 버튼 넷. 모바일에서는 2×2, 640px부터 한 줄 4열·12px 간격이고 모든 버튼은 공통 `Button`의 최소 높이 52px다. 「좋음」은 기본형, 나머지는 보조형이며 등급 이름 아래 다음 간격을 작은 글자로 보인다. |
| 받은 목록 끝 | 오늘 목록을 다시 읽는다. 돌아온 카드가 있으면 새 목록 첫 질문부터 이어 보고, 없으면 카드 없음으로 표시한다. 남은 수는 새 목록에서 늘어날 수 있다. |
| 카드 없음 | 흰 면·24px 모서리·가운데 정렬·24px 간격으로 §5의 책 읽기 모구미 사본 → 「오늘 복습할 카드가 없어요」. 코스로 거르면 「이 코스는 오늘 복습할 카드가 없어요」와 「전체 복습하기」 버튼(`/review`)을 보인다. 축하 모구미는 [design 이미지 표](./design/README.md#모구미-이미지)의 요청 상태를 유지하고 책 읽기 사본으로 자리를 채운다. |
| 읽는 중·읽기 실패 | 새 읽기가 끝나기 전에는 옛 목록을 숨기고 `role="status"` 안내를 보인다. 실패는 `FailureNotice`에 무엇이 안 됐는지·서버 문구(연결 실패는 공통 안내)·「다시 시도」를 보인다. 코스로 거른 읽기의 실패에는 「전체 복습하기」도 있다. |
| 평가·지우기 요청 중·실패 | 요청 중에는 평가·답 보기·지우기·확인·취소 버튼을 잠그고 `useRef`로 같은 순간의 두 번 제출도 막는다. 성공하면 다음 질문으로 가고 남은 수가 하나 준다. 실패는 서버 문구와 현재 질문·답·확인 펼침을 보존해 같은 동작을 다시 할 수 있다. 일정 밖 평가·사라진 카드는 문구를 남긴 채 목록을 다시 읽는다. 오류 code는 표시하지 않는다. |

[lib/formatReviewInterval.ts](../frontend/src/lib/formatReviewInterval.ts)의 `formatReviewInterval(seconds)` 한 곳에서 초 단위 정수를 문구로 바꾼다. 간격이 1시간 미만이면 분, 1일 미만이면 시간, 30일 미만이면 일, 365일 미만이면 달(30일), 그 이상이면 년(365일)을 쓴다. 고른 단위로 나눈 값을 정수 반올림(`Math.round`)하며 최소 1이다. **반올림한 수가 다음 단위의 크기(60분 · 24시간 · 30일 · 12달)에 닿으면 다음 단위의 「1」로 쓴다** — 「60분」 · 「24시간」 · 「30일」 · 「12달」은 나오지 않는다. 따라서 0초는 「1분」, 90초는 「2분」, 3,599초는 「1시간」, 3,600초는 「1시간」, 86,399초는 「1일」, 30일 바로 전은 「1달」, 30일은 「1달」, 365일 바로 전은 「1년」, 365일은 「1년」이다. 실제 복습 일정·서버 숫자는 바꾸지 않는다.

공통 `InlineConfirmation`은 모달 없이 같은 화면에 펼치는 제어형 컴포넌트다. `open`·`onOpenChange`·`onConfirm`·`disabled`와 입구·제목·안내·확인 버튼 문구를 받으므로 뒤 코스 지우기·탈퇴 TASK가 삭제 범위를 바꿔 재사용할 수 있다. 입구의 `aria-expanded`는 상태와 같고 `aria-controls`는 펼친 동안만 있으며, 펼친 영역은 제목·안내와 연결한 이름 있는 `section`이다. 펼치면 취소에 포커스를 두고 취소하면 입구로 돌린다. 요청 중 확인·취소도 잠근다. 복습에서는 「카드를 지울까요?」·「이 카드와 이 카드의 복습 기록이 사라져요. 되돌릴 수 없어요. 카드에 연결된 개념은 남아요.」·「지우기」/「취소」를 쓴다. 숙련도는 아직 화면에 없으므로 문구에 넣지 않는다. 확인을 펼친 동안 평가·답 보기도 잠그며, 취소는 카드·답을 유지한다. 화면 진입 시 본문 제목과 문서 제목을 갱신하고, 답을 펼치면 답 제목, 다음 카드로 가면 새 질문에 포커스를 둔다.

[api/useReview.ts](../frontend/src/api/useReview.ts)의 `useTodayReview`는 공통 `apiFetch`로 `['reviews', 'today', courseId]`를 읽으며 전체 복습의 `courseId`는 `null`이다. 목록은 `gcTime: 0`으로 화면을 떠나면 캐시에서 제거하고, 매 진입에 서버에서 다시 읽는다. 읽는 중에는 캐시된 카드를 먼저 표시하지 않는다. `App`은 `pathname + search`를 키로 써 `/review?courseId={id}`의 쿼리가 바뀌어도 목록·답 펼침·동작 상태를 새로 시작한다. 아래 복습 탭은 `/review`로 가므로 거르기가 없어진다. `useReviewAction`은 평가·지우기를 보내고, 성공하면 진행 중인 `['courses']` 읽기를 취소한 뒤 목록·상세 캐시를 모두 무효화한다. `refetchType: 'none'`으로 해당 화면을 열 때 새 수를 읽으며 즉시 다시 읽지 않는다. 읽기·쓰기는 자동 재시도하지 않고 창 포커스·재연결에 따른 별도 읽기도 없다.

짝 테스트는 `fetch` 대역으로 질문·답·평가 본문·남은 수·목록 끝 재조회·다시 돌아온 카드·요청 잠금·동시 제출 한 번·실패 보존·일정 밖/사라진 카드 재조회·확인 취소/삭제·코스 거르기·재진입·읽기 재시도·금지 정보 부재를 확인한다. 공통 확인의 포커스·간격 함수 경계·진행 중 코스 읽기 취소와 지연 응답 차단도 시험한다. `App.test.tsx`는 실제 라우터로 탭 진입·재진입·거른 복습에서 탭으로 전체 복습 전환을 확인한다. 390px의 2열 버튼은 클래스와 최소 크기를 검사했으며 실제 브라우저의 너비별 렌더링을 검증한 것으로 보지는 않는다.

## 16. 홈

`11-home-credits` step 6의 [HomePage.tsx](../frontend/src/pages/HomePage.tsx)는 승인된 사람의 `/`에서 `AccountPage`가 제목 없는 §7의 `AppLayout` 안에 표시한다. 본문 제목 「머금」은 진입 시 포커스를 받고 문서 제목은 「홈 · 머금」이다. 내용·문구·이동의 계약은 [06 §4](./06-screens.md#4-홈), 응답은 [05 §3](./05-api.md#3-읽기--화면마다-한-번)을 따른다.

모바일·데스크톱 모두 제목 → 스트릭 → 오늘 복습 → 이어서 배우기 → 할 일 → 만드는 중 · 실패 → 새로 배우기 순서의 한 열이다. 영역 사이 간격은 24px이며 새 토큰·공통 컴포넌트 파일은 없다. 페이지 내부 `HomeSection`이 읽기 상태와 영역 틀을 공유한다. 스트릭은 별도 제목 없이 안내 바탕·16px 모서리·16px 여백에 굵은 보라 문구로 표시한다. 나머지 읽기 영역은 흰 면·24px 모서리·옅은 테두리·24px 여백(640px부터 32px)이며 오늘 복습만 보라 테두리·24px의 굵은 제목과 카드 수로 강조한다. 복습 시작과 새로 배우기 링크는 기존 홈 입구의 기본 `Button` 토큰·모양·포커스·눌림 효과를 유지하고, 복습 시작은 모바일에서 전체 폭이다.

이어서 배우기는 코스 제목·다음 강의 제목을 한 링크에 표시한다. 서버가 다음 강의를 생략한 경우에도 이동 주소를 만들지 않고 칸을 숨긴다. 할 일·작업은 서버 순서의 이름 있는 `ul`이며 각 행 전체가 링크다. 행은 16px 모서리·옅은 테두리·16px 여백·16px 행 간격·보라 hover 테두리와 공통 포커스를 쓴다. 제목 뒤에 할 일은 기다리는 동작 한 줄, 작업은 §3의 `StatusBadge`로 기다리는 화면과 같은 대기·생성 문구 또는 「만들지 못했어요」를 표시한다. 만들기 실패의 이유·동작은 기다리는 화면에서 다룬다. 모르는 종류·상태는 원래 값을 노출하지 않고 해당 항목을 생략한다.

오늘 복습은 0장이어도 카드 없음 문구와 함께 보이며 복습 시작은 없다. 이어서 배우기·할 일·작업은 비면 제목과 칸을 함께 숨긴다. 스트릭과 새로 배우기는 항상 남는다. 각 영역은 읽는 중 상태 한 줄, 읽기 실패는 `FailureNotice`의 실패한 것·서버 문구(연결 실패는 공통 안내)와 `Button`의 「다시 시도」를 표시한다. 실패한 영역만 다시 읽고 나머지 영역은 유지한다.

[api/useHome.ts](../frontend/src/api/useHome.ts)의 `useHomeReviews`·`useHomeStreak`·`useHomeContinueLearning`·`useHomeTodos`·`useHomeJobs`는 공통 `apiFetch`로 각 홈 경로를 읽는다. 키는 `['home', 'reviews' | 'streak' | 'continue-learning' | 'todos' | 'jobs']`로 나뉜다. `gcTime: 0`·`staleTime: 0`·`refetchOnMount: 'always'`로 떠나면 캐시를 지우고 재진입에 새로 읽는다. 자동 재시도·폴링·창 포커스·재연결 읽기는 없다. 기존 작업 하나·코스·복습 목록 쿼리와도 키를 공유하지 않는다.

짝 테스트는 스트릭 세 문구·0장/3장·첫 승인 홈·칸 순서·대상 링크·항목 순서·각 영역의 서버/연결 오류와 해당 영역만 다시 읽기·개별 로딩·모르는 값 생략·금지 정보 부재를 API 대역으로 확인한다. 훅 시험은 독립 경로·키·자동 재시도 없음·간격/포커스/재연결 읽기 없음·재진입 읽기를 확인한다. `App.test.tsx`·`AccountPage.test.tsx`는 승인된 `/`에서 실제 홈과 틀·제목 포커스·문서 제목을 확인하고, 다른 탭에는 스트릭이 없는지 검사한다. 응답 뒤 화면 단언은 `findBy…` 뒤 `waitFor` 안에서 수행한다. 실제 브라우저의 너비별 렌더링을 검증한 것으로 보지는 않는다.

## 17. 내 정보 탭

`11-home-credits` step 7의 [ProfilePage.tsx](../frontend/src/pages/ProfilePage.tsx)는 승인된 사람의 `/profile`에서 제목 없는 §7의 `AppLayout` 안에 표시한다. 본문 제목 「내 정보」는 진입 시 포커스를 받고 문서 제목은 「내 정보 · 머금」이다. 내용 계약은 [06 §13](./06-screens.md#13-내-정보-탭), 크레딧 응답은 [05 §3](./05-api.md#3-읽기--화면마다-한-번)을 따른다. `PlaceholderPage`와 짝 테스트는 사용처가 없어 제거했다.

한 열로 제목 → 계정·크레딧 면 → 로그아웃 → 탈퇴 → 처리방침 보기 순서이며 영역 사이 간격은 24px이다. 홈과 같은 흰 면·24px 모서리·옅은 테두리·24px 여백(640px부터 32px)을 쓴다. 면 안에는 §3의 `AccountIdentity`로 이름·구글 이메일을 표시하고, 그 아래 「남은 크레딧 {숫자}」를 굵은 보라 제목 크기로, 「{월}월 {일}일에 채워져요」를 보조 글자로 표시한다. 날짜는 서버 `yyyy-MM-dd` 문자열을 나누어 월·일의 앞 0만 없애며 `Date`나 브라우저 시간대 변환을 쓰지 않는다. `time`의 `dateTime`에는 서버 날짜 원문을 둔다. 선택 `credit` 칸이 없으면 크레딧 영역 전체를 숨긴다. 새 토큰·공통 컴포넌트 파일은 없다.

`RequireAuth`의 `Me` outlet context를 표시하고, 탭 진입·재진입 때 `['me']`만 정확히 무효화해 활성 쿼리를 다시 읽는다. 캐시가 신선해도 서버를 다시 읽으며 바뀐 잔액·날짜는 같은 context로 반영한다. 읽기 중과 이미 받은 「나」의 다시 읽기 실패에는 이전 값과 화면을 유지하며 별도 안내를 더하지 않는다. 처음 읽기가 실패해 보일 데이터가 없을 때만 기존 오류 화면과 다시 시도를 보인다. `UNAUTHORIZED`는 데이터가 있어도 기존 로그인 흐름으로 가며, 승인 상태 변경과 모르는 상태는 기존 인증 흐름이 처리한다.

로그아웃은 보조형 `Button`이다. 기존 계정 화면과 같은 본문 없는 `POST /api/auth/logout` 성공 뒤 `['me']` 쿼리 취소 → 캐시 제거 → `/login`으로 replace 이동 순서다. 요청 중 버튼을 잠그고, 실패하면 `FailureNotice`에 실패한 동작·서버 문구(연결 실패는 공통 안내)·다시 시도를 보인다. 처리방침 링크는 `/privacy`이며 보라 글자·밑줄·최소 44px·공통 포커스를 쓴다. 현재 스트릭·학습 통계·기기 목록·원격 로그아웃·크레딧 기록·종류별 값·한 주 몫·만들 수 없음 안내는 두지 않는다.

`12-delete-withdraw` step 4는 로그아웃과 처리방침 사이에 공통 `InlineConfirmation`의 보조형 입구 「탈퇴」를 둔다. 확인 제목은 「탈퇴할까요?」, 확인 버튼은 「탈퇴하기」이며 안내는 [06 §13의 탈퇴 확인 문구](./06-screens.md#13-내-정보-탭)를 그대로 쓴다. 본문 없는 `POST /api/account/withdraw`가 204로 성공하면 `cancelQueries()`로 모든 진행 중 읽기를 취소하고, `removeQueries()`로 「나」·코스·카드를 포함한 쿼리 캐시를 모두 제거한 뒤 `/login`으로 replace 이동한다. 완료 안내나 완료 화면은 없다. 로그아웃·탈퇴 요청 중에는 입구·확인·취소·로그아웃(실패 뒤 다시 시도 포함)을 잠그며, 공유 `useRef` 잠금으로 같은 순간의 중복 제출과 두 동작의 교차 제출을 막는다. 탈퇴 실패는 `FailureNotice`에 실패한 동작·서버 문구(연결 실패는 공통 안내)를 보이고 확인 펼침과 캐시를 보존해 「탈퇴하기」로 다시 시도한다. 오류 code는 표시하지 않는다.

짝 테스트는 계정·잔액·날짜 순서·제목 포커스·문서 제목·시간대별 동일 날짜·크레딧 생략·처리방침 주소·금지 정보 부재·로그아웃 잠금/성공/실패/재시도/캐시 제거·탭 진입과 재진입의 새 잔액을 API 대역과 실제 인증 context로 확인한다. 탈퇴의 입구·순서·계약 문구·취소/포커스·본문 없는 POST 한 번·동시 제출/교차 제출·요청 잠금·서버/연결 실패 보존과 재시도·모든 읽기 취소 후 전체 캐시 제거·늦은 읽기 결과 차단·replace 이동도 확인한다. 탭 재진입의 연결·서버 재조회가 재시도까지 실패해도 이전 잔액과 화면을 유지하는지도 확인한다. `RequireAuth.test.tsx`는 처음 읽기 실패·다시 시도 잠금·데이터가 있을 때의 `UNAUTHORIZED`·다시 읽은 모르는 상태도 확인한다. `App.test.tsx`는 `/profile`의 실제 내용과 앱 틀에 더해 탈퇴 뒤 로그인으로 replace 이동·뒤로 가기/내 정보 재진입 시 이전 화면 부재·「나」 재조회 401의 로그인 흐름을 확인한다. 실제 브라우저의 너비별 렌더링을 검증한 것으로 보지는 않는다.
