# Step 11: course-screen-fixes

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/06-screens.md` §7(코스 화면 — 검토 중의 다시 짜기 · 코스 제목 고치기)
- `docs/UI_GUIDE.md` — `TextField` · `Button` 등 공통 컴포넌트
- 이 phase 의 step 8 · 9 가 만든 것: `frontend/src/pages/CoursePage.tsx` · `CoursePage.test.tsx` · `frontend/src/pages/CoursesPage.test.tsx`
- `AGENTS.md` — 작업 방식(TDD — 짝 테스트 경로)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

phase 브랜치 리뷰(`/review`)에서 나온 화면 수정 셋이다. 보이는 것 · 할 수 있는 것(06 §7)은 바꾸지 않는다.

## 작업

### 1. 제목 고치기 칸이 열린 동안 입력을 잃지 않게 한다

`frontend/src/pages/CoursePage.tsx:102` — 제목 고치기 칸이 열린 동안에도 「코스 제목 고치기」 버튼이 보이고 누를 수 있다. 다시 누르면 `setTitle(data.title)` 이 입력 중이던 글을 말없이 버린다. **칸이 열린 동안에는 그 버튼을 숨기거나 누를 수 없게** 한다(어느 쪽인지 정하고, 칸을 닫는 길 — 취소 · 저장 성공 — 은 그대로 둔다). 닫은 뒤에는 다시 열 수 있다.

### 2. 다시 짜기 펼침 버튼의 `aria-controls`

`frontend/src/pages/CoursePage.tsx:136` — 다시 짜기 입력이 닫혀 있을 때도 버튼의 `aria-controls="course-replan"` 이 DOM 에 없는 id 를 가리킨다. **펼쳤을 때만 붙이거나, 입력을 DOM 에 두고 `hidden` 으로 감춘다.** `aria-expanded` 는 지금처럼 펼침 상태와 맞춘다. 제목 고치기 칸에 같은 문제가 있으면 같은 방식으로 맞춘다.

### 3. 테스트 이름

`frontend/src/pages/CoursesPage.test.tsx:54` — 「코스이 없으면」 → 「코스가 없으면」.

### 4. 테스트 — Vitest + Testing Library

- 제목 고치기 칸에 글을 입력한 뒤 그 버튼을 다시 누를 수 없거나 보이지 않고, 입력한 글이 그대로다. 취소하면 버튼이 다시 보인다(또는 누를 수 있다).
- 다시 짜기 버튼의 `aria-controls` 가 가리키는 요소가 늘 DOM 에 있거나, 닫혀 있을 때는 `aria-controls` 가 없다.
- 포커스 · 효과의 결과는 `waitFor` 안에서 단언한다(HARNESS-BACKLOG S19).

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 06 §7 의 보이는 것 · 할 수 있는 것이 그대로인가? 새 화면 · 버튼을 더하지 않았는가?
   - 열린 제목 고치기 칸의 입력이 버튼 때문에 사라지지 않는가? `aria-controls` 가 없는 id 를 가리키지 않는가?
   - `AGENTS.md` 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/7-course-planning/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 고친 방식 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 코스 화면의 다른 상태 · 버튼 · 문구를 바꾸지 마라. 이유: 이 step 은 리뷰가 짚은 셋만 고친다.
- 새로 배우기 화면의 서버 칸 오류 표시(입력을 고쳐도 다음 제출까지 남는 것)를 바꾸지 마라. 이유: 브랜치 리뷰에서 MVP 에서는 넘어가기로 했다.
- 서버 코드(`backend/`)를 고치지 마라. 이유: 이 step 은 화면만이다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
