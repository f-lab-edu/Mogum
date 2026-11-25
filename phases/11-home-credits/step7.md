# Step 7: profile-tab

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/06-screens.md` §2.6 · **§13(내 정보 탭 — 보이는 것 · 할 수 있는 것 · 두지 않는 것)** · §14(처리방침을 여는 곳)
- `docs/05-api.md` §3(「나」 구현 줄 — step 2 가 크레딧 칸을 더했다) · §4(로그아웃)
- `docs/design/README.md` 「그리지 않은 것」(내 정보 탭은 시안이 없다 — 06 의 표대로 만든다) · 「시안을 쓰는 법」
- `docs/UI_GUIDE.md` — §2 토큰 · §3 공통 컴포넌트 · §4.1(계정 화면 구성 · `AccountIdentity`) · §7(승인 뒤 화면의 틀) · step 6 이 더한 홈 절
- `docs/ADR.md` — ADR-059 · ADR-083 · ADR-097 · ADR-113
- step 2 · 6 이 만든 것(summary 참고) — 「나」의 크레딧 칸 이름 · 홈의 카드 모양 컴포넌트
- `frontend/src/App.tsx`(`/profile` 이 지금 `PlaceholderPage`) · `frontend/src/api/useMe.ts` · `frontend/src/pages/AccountPage.tsx`(로그아웃 동작 · `['me']` 캐시 정리 방식) · `frontend/src/components/AccountIdentity.tsx` · `frontend/src/pages/PlaceholderPage.tsx`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

내 정보 탭(`/profile`)은 지금 제목만 있는 자리 화면이다. 이 step 이 06 §13 의 탭을 만든다. 탈퇴는 12 지우기 · 탈퇴 TASK 에서 더한다.

**계약 문서는 이 phase 전에 정해 커밋했다(06 §13). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

- `frontend/src/pages/ProfilePage.tsx`(+ `ProfilePage.test.tsx`)를 만들어 `/profile` 에 둔다(`AppLayout` 안 · 탭 첫 화면이라 `title` 없이 본문에서 제목 「내 정보」를 준다 — UI_GUIDE §7).
- 보이는 것(06 §13), 위에서부터:
  1. **어느 구글 계정인지** — 이름 · 이메일. 기존 `AccountIdentity` 를 쓴다.
  2. **남은 크레딧** — 숫자(예: 「남은 크레딧 18」).
  3. **다음에 채워지는 날** — 서버가 준 `yyyy-MM-dd` 를 한국어 날짜로 보인다(예: 「12월 2일에 채워져요」). 문자열을 `Date` 로 바꿔 브라우저 시간대로 계산하지 마라 — 날짜가 하루 밀릴 수 있다. 문자열의 연 · 월 · 일을 그대로 쓴다.
- 할 수 있는 것: **로그아웃**(지금 `AccountPage` 의 로그아웃과 같은 요청 · 캐시 정리 · `/login` 이동 — 같은 코드를 함께 쓰도록 옮겨도 된다) · **처리방침 보기**(`/privacy`).
- 「나」는 이미 `RequireAuth` 가 읽어 outlet context 로 넘긴다 — 같은 데이터를 쓴다. 이 탭을 열 때 크레딧이 최신이도록 `['me']` 를 다시 읽는다(만들기로 크레딧을 쓴 뒤 탭에 오면 바뀐 값이 보여야 한다). 방식은 재량.
- `useMe.ts` 의 `Me` 타입에 크레딧 선택 칸을 더한다(step 2 summary 의 이름). 칸이 없으면(승인 전 — 이 탭에는 오지 않는다) 크레딧 줄을 보이지 않는다.
- `PlaceholderPage` 가 더 이상 쓰이지 않으면 파일과 그 시험을 지우고 UI_GUIDE §7 의 그 줄을 지운다(이 step 의 변경으로 고아가 된 코드).

### 시험 — `frontend/src/pages/ProfilePage.test.tsx`

- 이름 · 이메일 · 남은 크레딧 · 다음에 채워지는 날이 보인다.
- 날짜 `2026-12-02` → 「12월 2일」을 포함한다 — 테스트 환경 시간대를 바꿔도(`process.env.TZ` 등 · 가능하면) 같다.
- 로그아웃을 누르면 로그아웃 요청 뒤 `/login` 으로 간다 · 요청 중 버튼이 막힌다.
- 처리방침 링크가 `/privacy` 다.
- 스트릭 · 학습 통계 · 탈퇴 버튼이 없다.
- 탭을 열면 「나」를 다시 읽는다(가짜 응답의 크레딧이 바뀌면 바뀐 값).
- `App.test.tsx` 의 `/profile` 시험을 새 화면에 맞춘다.

### 문서 — 구현으로 정해진 사실만

- `docs/UI_GUIDE.md` — 내 정보 탭 절을 새로 더한다(경로 · 배치 · 날짜 표시 방식 · 다시 읽기). §7 의 「홈·내 정보는 제목 한 줄의 자리」 줄을 사실대로 고친다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 06 §13 의 보이는 것 · 할 수 있는 것 · 두지 않는 것과 같은가?
   - 날짜를 브라우저 시간대로 바꾸지 않는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/11-home-credits/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 새 파일 · 지운 파일 · 내 정보 탭 주소(step 8 의 링크가 쓴다) · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 탈퇴 버튼 · 확인을 만들지 마라. 이유: SCHEDULE §3 상 12 지우기 · 탈퇴 TASK 에서 만든다(서버 API 도 아직 없다).
- 스트릭 · 학습 통계 · 로그인 기기 목록 · 원격 로그아웃을 두지 마라. 이유: 06 §13 두지 않는 것 · ADR-064 · ADR-097.
- 크레딧 기록(언제 얼마 썼는지) · 종류별 크레딧 값 · 한 주 몫을 보이지 마라. 이유: ADR-113 — 남은 크레딧과 다음에 채워지는 날만.
- 「지금은 만들 수 없음」 안내를 만들지 마라. 이유: step 8 의 범위다.
- 서버(`backend/`)를 고치지 마라. 이유: 「나」 응답은 step 2 에서 정했다. 맞지 않으면 `blocked`.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
