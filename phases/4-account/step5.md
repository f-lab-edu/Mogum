# Step 5: pre-login-screens

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/06-screens.md` §2.2(화면 목록과 **흐름도** — 로그인 → 신청 → 승인 대기 · 거절됨 · 홈) · §2.5(실패 · 오류) · §2.6(보이지 않는 정보) · **§3(로그인 전 화면 — 신청 · 승인 대기 · 거절됨 줄과 그 아래 줄들)** · §14(개인정보처리방침 — 여는 곳)
- `docs/05-api.md` §1.1 · §1.2(`ACCOUNT_NOT_APPROVED` 줄) · §3(「나」 — 경로 · 칸) · §4(신청 — 경로 · 응답 · **「신청은 한 사람에 한 번이다」** 줄)
- `docs/ADR.md` — ADR-058(거절 — 이유 · 다시 신청 없음) · ADR-059(신청 중 · 거절됨은 운영자에게 요청해 지운다 — 방법은 처리방침에) · ADR-062(저장하는 정보 — 구글 이메일 · 이름) · ADR-091 ~ ADR-094(로그인 전 화면들)
- `docs/UI_GUIDE.md` — step 4 가 정한 토큰 · 공통 컴포넌트 · 로그인 전 화면 틀 · 모구미. **이것을 쓰고, 새로 필요한 것만 더한다**
- `docs/design/README.md` 「시안을 쓰는 법」 · 「06 과 다른 곳」(**「06 이 막은 것」의 「프로필 이미지 URL」 · 처리방침 예시 문구**, **「06 에 없는 것」** 표)
- 시안(모바일 먼저): `docs/design/screenshots/02-apply-mobile.png` · `02-apply-desktop.png` · `docs/design/html/02-apply.html` · `03-waiting-approval-mobile.png` · `03-waiting-approval-desktop.png` · `html/03-waiting-approval.html` · `04-rejected-mobile.png` · `04-rejected-desktop.png` · `html/04-rejected.html` · `19-privacy-mobile.png`(틀만 본다)
- step 4 가 만든 것: `frontend/src/index.css` · `frontend/src/components/` · `frontend/src/pages/LoginPage.tsx`
- 지금 화면 코드: `frontend/src/App.tsx` · `frontend/src/api/useMe.ts`(옛 `Me` 타입) · `frontend/src/api/client.ts`(`ApiErrorCode` 의 옛 code · `apiFetch` · `googleLoginUrl`) · `frontend/src/api/queryClient.ts` · `frontend/src/components/RequireAuth.tsx` · `frontend/src/lib/messages.ts` · `frontend/src/lib/authNavigation.ts` · `frontend/src/pages/PlaceholderPage.tsx` · 각 테스트 파일
- 서버 쪽 사실은 step 1 · 2 의 summary 와 `backend/src/main/java/com/mogum/account/controller/` 에서 확인한다(경로 · 칸 이름)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`4-account`)는 계정을 신청 · 승인 · 거절됨 흐름으로 바꾼다. 서버는 step 0 ~ 3 에서 끝났다 — 「나」가 `NOT_APPLIED` · `APPLIED` · `APPROVED` · `REJECTED` 와 `email` · `name` 을 주고, 신청 API 가 있고, 승인 전 사람은 「나」 · 신청 · 로그아웃만 쓴다. step 4 가 토큰 · 공통 컴포넌트 · 로그인 화면을 만들었다.

이 step 은 로그인 뒤 상태별로 가는 화면 — **신청 · 승인 대기 · 거절됨** — 과 **처리방침 자리 화면**을 만든다. 홈은 아직 없다(SCHEDULE §3 상 TASK 11) — 승인된 사람은 지금의 임시 화면(`PlaceholderPage`)으로 간다.

## 작업

### 1. 「나」 타입 · 옛 code 걷기

- `frontend/src/api/useMe.ts` — `Me` 를 서버의 「나」에 맞춘다(`status`: `NOT_APPLIED` · `APPLIED` · `APPROVED` · `REJECTED` · `email` · `name`). 모르는 값 · 칸은 무시한다(05 §1 · ADR-145).
- `frontend/src/api/client.ts` 의 `ApiErrorCode` — 서버에 없는 옛 code `TERMS_REQUIRED` · `ACCOUNT_WAITLISTED` 를 지운다. `RATE_LIMITED` 도 서버에 없지만 이 step 의 화면과 무관하면 그대로 두고 summary 에 적는다.
- `frontend/src/lib/messages.ts` 의 `notAllowed` 와 `LoginPage` 의 `not_allowed` 분기를 지운다 — 서버는 step 1 부터 그 오류를 보내지 않는다.

### 2. 상태별 라우팅

- 06 §2.2 흐름도와 06 §3 「다시 들어오면 상태에 따라 홈 · 승인 대기 · 거절됨으로 간다」를 따른다 — 신청 전 → 신청, `APPLIED` → 승인 대기, `REJECTED` → 거절됨, `APPROVED` → 임시 화면(`PlaceholderPage`).
- 화면 주소는 네가 정한다. 다른 상태의 주소로 직접 들어오면 지금 상태의 화면으로 보낸다(예: 승인된 사람이 신청 주소를 열면 임시 화면으로).
- 로그인 안 함(`401`)은 지금처럼 로그인 화면으로 간다. `RequireAuth` 의 확인 중 · 불러오기 실패 상태는 유지한다.

### 3. 화면 — 06 §3 표가 기준이다

로그인 전 화면 틀(step 4)을 쓴다. 시안의 구조(영역 순서 · 가장 눈에 띄는 것)를 따르고, 보이는 것 · 할 수 있는 것 · 두지 않는 것은 06 §3 표를 따른다.

| 화면 | 꼭 담을 것(06 §3) |
|---|---|
| 신청 | 어느 구글 계정으로 신청하는지(이메일 · 이름) · 「신청하면 구글 이메일 · 이름을 저장해요」 · **신청**(누르면 승인 대기로) · **다른 계정으로 로그인** · **처리방침 보기** |
| 승인 대기 | 승인을 기다린다는 것 · 승인돼도 따로 알리지 않으니 다시 들어와야 한다는 것 · 신청한 계정 · 신청을 지우려면 처리방침에 적힌 방법으로 운영자에게 요청한다는 것 · **처리방침 보기** · **로그아웃** |
| 거절됨 | 거절됐다는 것 · 신청한 계정 · 신청 정보(구글 이메일 · 이름)가 남아 있다는 것과 지우려면 처리방침에 적힌 방법으로 운영자에게 요청한다는 것 · **처리방침 보기** · **로그아웃** |
| 처리방침 자리 | 제목 「개인정보처리방침」과 「준비 중」 안내 · 돌아가기. 본문은 쓰지 않는다 |

- **신청** — 누르면 신청 API 를 부르고, 요청 중에는 버튼을 막는다(05 §4 — 연타 대비). 성공하면 「나」를 새로 읽고(응답이 「나」 모양이면 그것으로 캐시를 바꿔도 된다) 승인 대기로 간다. 실패하면 06 §2.5 대로 무엇이 안 됐는지와 다시 시도를 보인다 — 문구는 서버가 준 `message` 를 그대로 보인다(05 §1.2).
- **다른 계정으로 로그인** — 로그아웃한 뒤 구글 로그인을 다시 연다(`googleLoginUrl()` 로 이동). 서버가 매번 계정을 고르게 한다(05 §1.1) — 화면이 따로 할 일은 없다.
- **로그아웃** — `POST /api/auth/logout` 뒤 로그인 화면으로. 「나」 캐시를 비운다.
- **처리방침 보기** — 처리방침 자리 화면(`/privacy`)으로. 06 §14 — 신청 · 승인 대기 · 거절됨에서 연다. 로그인 화면에는 두지 않는다. 이 주소는 로그인 · 상태를 확인하지 않는다(서버 읽기가 없는 화면 글이다 — 05 §3).
- 버튼 모양 등 step 4 에 없는 공통 컴포넌트가 필요하면 `frontend/src/components/` 에 짝 테스트와 함께 더하고 `docs/UI_GUIDE.md` 에 적는다. 화면 하나만을 위한 변형을 만들지 마라.
- 모구미는 step 4 의 컴포넌트를 쓴다. 다른 모습이 필요해 보여도 `mogumi-reading` 을 쓴다.

### 4. 시험(Vitest · Testing Library)

- 상태 넷마다 `/` 에서 맞는 화면으로 가고, 다른 상태의 주소로 들어오면 지금 상태의 화면으로 간다.
- 신청 — 누르면 API 를 한 번 부르고 요청 중 버튼이 막힌다 · 성공하면 승인 대기 · 실패하면 서버 문구와 다시 시도.
- 다른 계정으로 로그인 — 로그아웃을 부른 뒤 구글 로그인 주소로 간다.
- 로그아웃 — 부른 뒤 로그인 화면.
- 각 화면에 06 §3 의 꼭 담을 것이 있고, 「두지 않는 것」(신청의 입력 칸 · 동의 체크 · 승인 대기의 새로고침 버튼 · 거절됨의 거절 이유 · 다시 신청)이 없다.
- 처리방침 자리 — 로그인 없이 열린다.

### 5. 문서

- `docs/UI_GUIDE.md` — 이 step 이 더한 컴포넌트 · 화면 주소 목록을 적는다.
- `docs/06-screens.md` 는 고치지 않는다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 06 §3 의 보이는 것 · 할 수 있는 것 · 두지 않는 것과 시안의 구조(영역 순서 · 가장 강조)가 맞는가? 06 에 적히지 않은 상태 · 영역을 만들지 않았는가?
   - 상태별 라우팅이 06 §2.2 흐름도와 맞는가?
   - 신청 버튼이 요청 중 막히는가? 오류 문구가 서버 문구 그대로인가? 시스템 정보(오류 code 등)가 화면에 없는가(06 §2.6)?
   - step 4 의 토큰 · 컴포넌트를 썼고, 새 컴포넌트는 UI_GUIDE 에 있는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가? 기준은 `docs/design/README.md` 「시안을 쓰는 법」
3. 결과에 따라 `phases/4-account/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 화면 주소 목록 · 새 컴포넌트 · 지운 옛 code · 문구 · 남긴 `RATE_LIMITED` 여부 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (06 과 시안이 부딪혀 고를 수 없음 · 서버 응답이 05 와 다름 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 처리방침 본문을 쓰지 마라 — 시안의 처리방침 문구 · 요약 펼침도 옮기지 마라. 이유: SCHEDULE §3 상 공개 준비 TASK(13)에서 쓴다 · design README 「06 이 막은 것」.
- 저장하는 정보에 「프로필 이미지」를 넣지 마라. 이유: design README 「06 이 막은 것」 — 저장하는 것은 구글 이메일 · 이름뿐이다(ADR-062).
- 거절됨의 위 메뉴 · 「문의하기」 · 「신청 일시」를 만들지 마라. 이유: design README 「06 에 없는 것」 — 06 에 옮기기 전에는 만들지 않는다.
- 운영자의 연락처(이메일 · 전화 등)를 지어내 넣지 마라. 이유: 요청 방법은 처리방침에 적는다(ADR-059) — 화면은 「처리방침에 적힌 방법으로」라고만 안내한다.
- 홈 · 탭 바 · 사이드바 · 내 정보 탭 · 탈퇴 · 크레딧 표시를 만들지 마라. 이유: SCHEDULE §3 상 TASK 11 · 12 에서 만든다. 승인된 사람은 지금의 임시 화면으로 간다.
- 승인 대기에 새로고침 버튼 · 상태를 주기적으로 다시 읽는 장치를 넣지 마라. 이유: 06 §3 두지 않는 것 — 다시 들어오면 상태를 읽는다.
- 신청 화면에 입력 칸 · 동의 체크를 넣지 마라. 이유: 06 §3 · ADR-092.
- 서버(`backend/`) · `deploy/` 를 고치지 마라. 서버 응답이 05 와 다르면 `blocked` 로 멈춰라. 이유: 이 step 범위 밖이다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
