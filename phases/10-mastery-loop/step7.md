# Step 7: course-report-screen

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/06-screens.md` §2.6(보이지 않는 정보) · §7(코스 화면 — 검토 중 · **숙련도 반영 한 줄**) · §15.2
- `docs/05-api.md` §3(「코스 하나」의 `masteryReport` 줄)
- `docs/design/README.md` 「시안을 쓰는 법」 · 「06 과 다른 곳」(강의마다 「핵심 복습 반영」 · 「메모 목표 연계」 표시 줄)
- 시안: `docs/design/screenshots/08-course-review-mobile.png`(먼저) · `docs/design/screenshots/08-course-review-desktop.png` · `docs/design/html/08-course-review.html` — 「맞춤형 커리큘럼 반영 완료」 상자
- `docs/UI_GUIDE.md`(토큰 · 공통 컴포넌트)
- `docs/ADR.md` — ADR-064 · ADR-090
- step 6 이 만든 것(summary 참고) — 응답 칸 이름 · 싣는 조건
- `frontend/src/api/useCourse.ts` · `frontend/src/pages/CoursePage.tsx` · `frontend/src/pages/CoursePage.test.tsx` · `frontend/src/components/`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

검토 중인 코스 화면에 숙련도가 강의 목록을 어떻게 바꿨는지 한 줄로 보인다(ADR-090 · 06 §7).

**계약 문서는 이 phase 전에 정해 커밋했다(06 §7 · 05 §3). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

- `useCourse.ts` 의 `CourseResponse` 타입에 선택 칸 `masteryReport?: { shortened: number; revisited: number }` 를 더한다(step 6 summary 의 이름을 따른다).
- 코스 화면의 검토 중 영역(지금 「강의는 아직 열 수 없어요 …」가 있는 `코스 검토` 영역)에 한 줄을 보인다 — 문구는 06 §7 그대로:
  「이전 복습 기록을 바탕으로 {shortened}개 개념을 짧게 다뤘고 {revisited}개 개념을 다시 짚었어요.」
- **보이는 조건** — 상태가 검토 중이고 `masteryReport` 가 있고 두 수 중 하나라도 0 보다 클 때. 두 수가 모두 0이거나 칸이 없으면 그 줄을 통째로 보이지 않는다 — 대신하는 안내를 두지 마라(06 §7 · §15.2).
- 모양은 시안의 「맞춤형 커리큘럼 반영 완료」 상자를 방향으로 하되 `UI_GUIDE.md` 의 토큰 · 기존 컴포넌트로 맞춘다(design README 「시안을 쓰는 법」). 시안의 제목 「맞춤형 커리큘럼 반영 완료」를 쓸지는 06 에 없으므로 쓰지 않는다 — 06 §7 의 문구 한 줄만 둔다.
- 학습 중 · 다 마침에서는 보이지 않는다(응답에도 없다).

### 시험 — `frontend/src/pages/CoursePage.test.tsx`

- 검토 중 + `{2, 1}` → 문구가 수와 함께 보인다.
- 검토 중 + `{0, 3}` · `{3, 0}` → 보인다(0 인 수도 문구에 그대로).
- 검토 중 + `{0, 0}` → 보이지 않는다 · 칸 없음 → 보이지 않는다.
- 학습 중 응답 → 보이지 않는다.
- 판정 이름(잘 앎 · 흔들림 · 기록 없음) · 퍼센트가 화면에 없다.
- 응답을 받은 뒤 그려지는 것은 `findBy…` 뒤 `waitFor` 안에서 단언한다(S19 — 효과 결과를 기다린다).

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 06 §7 의 문구 · 보이는 조건(검토 중 · 두 수 중 하나라도 0 보다 큼) 그대로인가?
   - 개념마다 판정 · 판정 이름 · 수치를 보이지 않는가(ADR-090)? 0 / 0 대신 다른 안내를 만들지 않았는가?
   - 시안의 구조(검토 중 영역 안의 상자)를 따르되 공통 토큰 · 컴포넌트를 썼는가? 기준은 `docs/design/README.md` 「시안을 쓰는 법」
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/10-mastery-loop/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 바꾼 파일 · 새 컴포넌트가 있으면 이름 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 강의마다 「핵심 복습 반영」 · 「메모 목표 연계」 표시를 만들지 마라. 이유: design README 「06 과 다른 곳」 — ADR-090 은 개념마다 · 강의마다 판정을 보이지 않는다.
- 시안의 다른 칸(학습 목표 설정 카드 · 예상 시간 · 진행 단계 표시 등)을 더하지 마라. 이유: 06 §7 에 없고 이 TASK 의 범위가 아니다.
- 0 / 0 이나 내역 없음에 안내 문구 · 모구미 이미지를 더하지 마라. 이유: 06 §15.2 — 장치를 두지 않는다.
- 백엔드(`backend/`)를 고치지 마라. 이유: step 6 이 끝냈다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
