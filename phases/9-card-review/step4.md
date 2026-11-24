# Step 4: course-review-entry

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/06-screens.md` §2.2(지도 — 코스 화면 →「이 코스 복습하기」→ 복습 탭) · §7(코스 화면 — 학습 중 · 다 마침의 「이 코스 복습하기」, 「이 코스에 오늘 복습할 카드가 있을 때만 몇 장인지와 함께 보인다」) · §11
- `docs/05-api.md` §3(「코스 하나」 — step 1 이 더한 오늘 복습할 카드 수 칸)
- `docs/ADR.md` — ADR-172
- `docs/design/README.md` — 「시안을 쓰는 법」 · 「06 과 다른 곳」(코스 — 다 마침의 「복습 덱 시작하기」는 「이 코스 복습하기」로 옮겼다)
- 시안(모바일 먼저): `docs/design/screenshots/09-course-learning-mobile.png` · `10-course-done-mobile.png`(데스크톱은 `-desktop.png`) · `docs/design/html/09-course-learning.html` · `10-course-done.html`
- `docs/UI_GUIDE.md` §2 · §3 · §9(코스 화면) · step 3 이 더한 복습 탭 절
- step 1 · 3 이 만든 것(summary 참고) — `CourseResponse` 의 오늘 복습할 카드 수 칸 이름 · 복습 화면의 코스 거르기 주소(`/review?courseId={id}`)
- `frontend/src/pages/CoursePage.tsx` · `CoursePage.test.tsx` · `frontend/src/api/useCourse.ts` · `frontend/src/App.test.tsx`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

코스 화면(06 §7)에 **「이 코스 복습하기」** 를 더한다 — 누르면 복습 탭에서 그 코스의 오늘 복습할 카드만 본다(ADR-172). 숫자는 코스 하나 읽기가 이미 준다(step 1), 받는 쪽 화면은 step 3 이 만들었다.

## 작업

- `CoursePage` 의 학습 중 · 다 마침 상태에 「이 코스 복습하기」를 둔다 — **코스 읽기의 오늘 복습할 카드 수가 0 보다 클 때만**, 몇 장인지와 함께 보인다(예: 「이 코스 복습하기 · 3장」 — 문구 모양은 정한다). 0 이면 버튼도 안내도 두지 않는다.
- 검토 중 · 짜기 전 코스에는 두지 않는다(06 §7 — 「할 수 있는 것」에 없다).
- 누르면 `/review?courseId={코스 ID}` 로 간다(같은 브라우저 탭 · 링크).
- 자리 — 시안 09 · 10 의 복습 진입 자리를 방향으로 삼되, 06 §7 의 영역 순서(다음에 열 강의가 가장 눈에 띈다 — ADR-078)를 해치지 않는다. 공통 `Button` 또는 링크 모양을 쓰고 새 토큰을 만들지 않는다.
- 로드맵 화면 · 코스 탭에는 두지 않는다(ADR-172).

### 시험 — `CoursePage.test.tsx` · `App.test.tsx`

- 학습 중 · 다 마침에서 수 > 0 이면 버튼과 장수가 보이고 주소가 `/review?courseId={id}` · 수가 0 이면 없음 · 검토 중이면 수와 상관없이 없음.
- `App.test.tsx` — 실제 라우터로 코스 화면의 버튼 → 복습 화면이 그 코스로 거른 읽기를 부른다(쿼리 전달).

### 문서 — 구현으로 정해진 사실만

- `docs/UI_GUIDE.md` §9(코스 화면) — 버튼의 자리 · 문구 · 보이는 조건.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 06 §7 대로 학습 중 · 다 마침이고 수가 있을 때만 장수와 함께 보이는가? 시안의 구조(영역 순서 · 가장 강조 — 다음에 열 강의)를 해치지 않는가? 기준은 `docs/design/README.md` 「시안을 쓰는 법」
   - 로드맵 화면 · 코스 탭에 더하지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/9-card-review/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 버튼 문구 · 자리 · 조건 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 예정과 상관없이 코스의 카드를 다 보는 버튼 · 「복습 덱 시작하기」를 만들지 마라. 이유: 06 §7 · ADR-172 — 평가가 FSRS 일정을 바꾼다.
- 다 마친 코스의 성과(달성률 · 소요 시간 · 수료 배지)를 만들지 마라. 이유: design README 「06 과 다른 곳」.
- 코스 지우기 · 숙련도 반영 내역을 만들지 마라. 이유: SCHEDULE §3 상 12 · 10 TASK 에서 만든다.
- 서버 코드(`backend/`)와 복습 화면(`ReviewPage`)의 동작을 고치지 마라. 이유: step 1 · 3 의 범위다. 필요한 칸이 없으면 `blocked`.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
