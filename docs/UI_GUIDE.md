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
| `AccountIdentity` · [components/AccountIdentity.tsx](../frontend/src/components/AccountIdentity.tsx) | 신청·승인 대기·거절됨에서 공유하는 「신청 계정」 영역. 안내 바탕·옅은 테두리·16px 모서리·안쪽 여백 16px. 이름과 구글 이메일을 `dl`로 세로 표시한다. 긴 값은 자르지 않고 줄바꿈하며 사진·입력 칸은 없다. |
| `FailureNotice` · [components/FailureNotice.tsx](../frontend/src/components/FailureNotice.tsx) | 무엇이 안 됐는지·안내·전달받은 동작을 함께 담는 `role="alert"` 영역. 안내 바탕·옅은 테두리·16px 모서리·안쪽 여백 16px·요소 사이 12px. 제목은 굵은 오류색, 안내는 작은 본문이며 동작은 공통 `Button`·링크를 쓴다. |
| `TextField` · [components/TextField.tsx](../frontend/src/components/TextField.tsx) | 한 줄 글 입력·`multiline`이면 4줄 높이의 여러 줄 입력. 굵은 이름과 필수/선택 표시 → 입력 → 선택적인 길이 안내 → 칸 오류 순서. 흰 면·옅은 테두리·16px 모서리·최소 높이 52px·가로 여백 16px·세로 여백 12px. `value`·`onChange`·`required`·`disabled`·`maxCodePoints`·`error`를 받는다. `maxCodePoints`가 있으면 유니코드 코드 포인트 수를 `현재 / 상한자`로 표시하고 초과 시 오류를 알린다. UTF-16 단위인 HTML `maxlength`로 자르지 않는다. 오류는 붉은 테두리·`aria-invalid`·`aria-describedby`로 연결하고, 잠금은 옅은 바탕·보조 글자·네이티브 disabled. 키보드 포커스는 공통 2px 보라 outline·2px offset. |
| `ChoiceGroup` · [components/ChoiceGroup.tsx](../frontend/src/components/ChoiceGroup.tsx) | 이름·필수/선택 표시와 나란한 선택 버튼 묶음. 모바일 2열·640px부터 4열, 간격 12px. 흰 면·옅은 테두리·16px 모서리·최소 높이 52px·안쪽 여백 가로 16px/세로 12px. 선택은 안내 바탕·보라 글자/테두리/2px ring·채운 원 표시로 구분한다. 미선택은 빈 원, hover는 안내 바탕. `options`·`value`·`onChange`·`required`·`disabled`·`error`를 받으며 기본 선택을 만들지 않는다. 네이티브 radio와 같은 name, 이름 있는 `fieldset role="radiogroup"`으로 단일 선택·키보드 이동을 지원한다. 포커스는 버튼 모양 영역에 공통 outline, 잠금은 네이티브 disabled·옅은 바탕·보조 글자, 오류는 묶음에 `aria-invalid`·`aria-describedby`로 연결한다. |
| `CheckboxField` · [components/CheckboxField.tsx](../frontend/src/components/CheckboxField.tsx) | 안내 바탕·옅은 테두리·16px 모서리·안쪽 여백 16px. 24px 네이티브 checkbox와 굵은 이름은 최소 높이 44px의 클릭 영역, 선택적 `description`은 그 아래 작은 보조 글자·12px 간격이며 `aria-describedby`로 연결한다. `checked`·`onChange`·`disabled`를 받는다. 선택은 기존 `primary` accent, 키보드 포커스는 공통 outline, 잠금은 네이티브 disabled. |
| `FieldError` · [components/FieldError.tsx](../frontend/src/components/FieldError.tsx) | 작은 오류색 글자·`role="alert"`. 서버 `fields`의 칸별 `reason`에는 별도 문구가 없으므로 `REQUIRED`는 「필수 입력이에요.」, `TOO_LONG`은 「글자 수 상한을 넘었어요.」, `INVALID_VALUE`는 「보기에서 골라 주세요.」, `NOT_ALLOWED`는 「이 칸은 비워 주세요.」로 설명한다. 모르는 reason은 「입력을 확인해 주세요.」이며 원래 code/reason은 노출하지 않는다. 오류 응답의 `message`는 `FailureNotice`에서 원문 그대로 함께 보인다. |

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
| `/` | `APPROVED`의 홈 자리(`PlaceholderPage`, 제목 「머금」). 처음 열리는 탭. 「새로 배우기」 링크 하나로 `/new`에 진입 |
| `/new` | `APPROVED`의 새로 배우기(`NewLearningPage`). 위 바 제목 「새로 배우기」·뒤로 가기. 받기 성공 시 `/jobs/{id}`로 이동 |
| `/courses` | `APPROVED`의 코스 탭 자리 |
| `/roadmaps` | `APPROVED`의 로드맵 탭 자리 |
| `/review` | `APPROVED`의 복습 탭 자리 |
| `/profile` | `APPROVED`의 내 정보 탭 자리 |
| `/jobs/:jobId` | `APPROVED`의 기다리는 화면. 위 바 제목 「기다리는 화면」·뒤로 가기 |
| `/courses/:courseId` | 성공 결과의 코스 자리. 위 바 제목 「코스」·뒤로 가기만 표시 |
| `/roadmaps/:roadmapId` | 성공 결과의 로드맵 자리. 위 바 제목 「로드맵」·뒤로 가기만 표시 |
| `/courses/:courseId/lessons/:lessonId` | 성공 결과의 강의 자리. 위 바 제목 「강의」·뒤로 가기만 표시 |
| `/privacy` | 개인정보처리방침 자리. 로그인·상태 읽기 없이 제목·준비 중·돌아가기만 표시 |

계정 화면 세 개는 [AccountPage.tsx](../frontend/src/pages/AccountPage.tsx)에서 상태별로 구성한다. `/`와 계정 주소를 열 때 「나」를 읽어 현재 상태의 주소로 보낸다. 모르는 상태는 기존 불러오기 실패·다시 시도로 처리하며 값을 노출하지 않는다. 승인 대기에 폴링·새로고침 버튼을 두지 않는다. `ACCOUNT_NOT_APPROVED`를 받으면 「나」를 다시 읽어 현재 상태를 반영한다.

새로 배우기는 [NewLearningPage.tsx](../frontend/src/pages/NewLearningPage.tsx)에서 안내·모구미 묶음 → 흰 입력 면 순서로 구성한다. 입력 면은 24px 모서리·옅은 테두리·안쪽 여백 24px(640px부터 32px), 입력 묶음 간격 32px이며 §3의 공통 컴포넌트를 쓴다. 내용과 보기는 [06 §5](./06-screens.md#5-새로-배우기) · [03 §1.2](./03-generation.md#12-입력--새로-배우기), 길이와 보내는 칸은 [05 §2](./05-api.md#2-만드는-작업--받기-하나--다시-읽기)를 따른다. 필수 입력과 길이 상한을 만족해야 만들기를 누를 수 있다. 요청 중 입력·선택·만들기를 잠그고, 같은 순간의 중복 제출도 한 번만 보낸다. [useJob.ts](../frontend/src/api/useJob.ts)의 `receiveJob(NewLearningRequest)`가 공통 `apiFetch`로 받기를 보내며 자동 재시도하지 않는다. 실패하면 `FailureNotice`에 서버 문구 원문과 재제출 안내를 보이고, 입력·선택을 보존하며 만들기를 다시 누를 수 있다. 새 토큰은 추가하지 않았다.

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

홈 배치·공통 카드·배지·진도 막대·확인 펼침·추가 버튼 모양·애니메이션은 처음 필요한 TASK에서 정한다. 글 입력·여러 줄 입력·선택 버튼 묶음·체크·칸 오류는 `7-course-planning` step 7에서 §3의 공통 컴포넌트로 정했다. 지금의 중앙 면과 안내 문단을 미리 카드 컴포넌트로 만들지 않는다. 다크 모드는 미정 항목이 아니라 MVP에서 제외한 기능이다(ADR-064).

## 7. 승인 뒤 화면의 틀

`6-job-worker` step 6에서 정한다. 주소는 §4.1, 화면 구분과 탭 순서는 [06 §2.1~2.3](./06-screens.md#21-형태와-모양의-원칙)을 따른다. 다섯 탭은 제목 한 줄의 자리이며, 홈에는 `7-course-planning` step 7에서 새로 배우기 입구 하나를 더했다. 나머지 내용은 해당 TASK에서 채운다.

| 이름 · 경로 | 모양 · 동작 · 상태 |
|---|---|
| `AppLayout` · [components/AppLayout.tsx](../frontend/src/components/AppLayout.tsx) | `RequireAuth`가 전달한 `Me` outlet context를 읽어 `APPROVED`만 표시한다. 승인 전에는 `/`의 기존 계정 상태 분기를 거친다. 본문은 `children`으로 받고 틀이 `main` 하나를 제공한다. 바탕·기본 글꼴·색은 §2 공통 토큰. 본문은 가로 16px·위 24px 여백, 최대 폭 768px·가운데 정렬이며 자연스럽게 스크롤한다. 1024px부터 왼쪽에 사이드바 폭만큼 256px 여백, 본문 가로·아래 여백 32px. |
| 아래 탭 바 · `AppLayout` 안 | 1024px 미만에서 화면 아래 고정, 흰 면·위 테두리·높이 80px·안쪽 여백 8px. 다섯 링크가 같은 폭으로 아이콘 위·글자 아래 배치된다. 본문 아래 96px 여백을 두어 가림을 막는다. |
| 사이드바 · `AppLayout` 안 | 1024px부터 왼쪽 고정·폭 256px·전체 높이·흰 면·오른쪽 테두리·안쪽 여백 16px. 위에 모구미와 「머금」 로고, 아래 다섯 링크를 세로로 둔다. 아래 탭 바는 숨긴다. |
| 탭 링크 · `AppLayout` 안 | 홈·코스·로드맵·복습·내 정보 순서. React Router `NavLink`로 같은 브라우저 탭에서 이동한다. 현재 탭은 `aria-current="page"`·보라 글자·굵은 글자·`surface-soft` 바탕, 나머지는 보조 글자·600 굵기이며 hover는 안내 바탕. 홈은 `/`에서만 활성, 다른 탭은 해당 주소의 하위 화면에서도 활성이다. 터치 영역 최소 44px, 공통 2px 포커스 outline·2px offset. |
| 위 바 · `AppLayout` 안 | 높이 56px·흰 면·아래 테두리·스크롤 위에 붙는 바. 기본(탭 첫 화면)은 40px 모구미와 「머금」 로고. 1024px 부터 로고는 사이드바에만 둔다. 화면이 `title`을 넘기면 뒤로 가기 버튼과 해당 제목(`h1`)을 보인다. 뒤로 가기는 `navigate(-1)`로 브라우저의 이전 화면으로 간다. 버튼은 최소 44×44px·공통 포커스 outline. 제목은 진입 시 포커스를 받고 문서 제목을 `제목 · 머금`으로 바꾼다. |
| 탭 아이콘 · `AppLayout` 안 | [guide §3](./design/guide.md#3-표준-내비게이션-5대-탭-아이콘-navigation-icons)의 홈·학사모·경로 노드·카드 덱·프로필 인라인 SVG를 그대로 사용한다. 24×24px·`currentColor`·선 두께 2, `aria-hidden`·`focusable="false"`로 장식 처리하며 접근 가능한 이름은 링크의 글자다. 아이콘 라이브러리는 없다. |
| `Mogumi` · [components/Mogumi.tsx](../frontend/src/components/Mogumi.tsx) | 기본 `size="page"`는 기존 128px·가운데 정렬. `size="logo"`는 위 바·사이드바의 40px 표시다. 같은 로컬 사본·대체 글·원래 비율을 유지한다. |
| `PlaceholderPage` · [pages/PlaceholderPage.tsx](../frontend/src/pages/PlaceholderPage.tsx) | `main`을 중복하지 않는다. 기본 홈 제목은 「머금」·문서 제목은 「홈 · 머금」, 제목 아래 24px 간격으로 「새로 배우기」 링크 하나를 둔다. 링크는 기본 Button과 같은 토큰·모양·포커스·눌림 효과를 쓴다. 다른 탭은 `title`을 받고 제목만 보인다. 제목 진입·변경 시 포커스와 문서 제목을 갱신한다. |

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
