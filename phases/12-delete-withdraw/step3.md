# Step 3: delete-screens

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/06-screens.md` §2.5 · §2.7(되돌릴 수 없는 동작) · §7(코스 화면 — 「지울 때」의 상태별 문구) · §8(로드맵 화면 — 「지울 때」의 상태별 문구 · 검토 중에도 지운다) · §12(코스 탭 · 로드맵 탭)
- `docs/05-api.md` §1.2 · §4(step 0 · 1 이 적은 「코스 지우기」 · 「로드맵 지우기」 구현 줄 · `COURSE_DELETE_NOT_ALLOWED`)
- `docs/ADR.md` — ADR-026 · ADR-027 · ADR-082 · ADR-100 · ADR-190
- `docs/design/README.md`(「시안을 쓰는 법」 · 「06 과 다른 곳」) · `docs/design/screenshots/09-course-learning-mobile.png` · `docs/design/screenshots/11-roadmap-mobile.png` · `docs/design/html/09-course-learning.html` · `docs/design/html/11-roadmap.html`(지우기 확인 블록 — 문구 · 자리의 방향만 본다)
- `docs/UI_GUIDE.md` §3(공통 컴포넌트 — `InlineConfirmation` · `FailureNotice` · `Button`) · §9 · §10 · §11 · §15(복습 탭 — 카드 지우기의 확인 펼침 · 요청 잠금 · 실패 보존)
- step 0 · 1 이 만든 것(summary 참고) — 두 경로 · `204` · 거절 code
- `frontend/src/pages/CoursePage.tsx` · `frontend/src/pages/RoadmapPage.tsx` · 각 `*.test.tsx`
- `frontend/src/api/useCourse.ts` · `frontend/src/api/useRoadmap.ts` · `frontend/src/api/useHome.ts` · `frontend/src/api/client.ts` · 각 짝 테스트
- `frontend/src/components/InlineConfirmation.tsx` · `frontend/src/pages/ReviewPage.tsx`(카드 지우기에서 확인 펼침을 쓴 방식)
- `frontend/src/App.tsx` · `frontend/src/App.test.tsx`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

코스 화면에 코스 지우기(단일 코스만), 로드맵 화면에 로드맵 지우기를 둔다. 서버(step 0 · 1)는 이미 있다. **확인에서 무엇이 사라지고 무엇이 남는지 알린다**(06 §2.7) — 문구는 06 §7 · §8 에 상태별로 정해 두었다.

**계약 문서는 이 phase 전에 정해 커밋했다. 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 코스 화면 — 코스 지우기

- **단일 코스**(`roadmapId` 가 없다)의 검토 중 · 학습 중 · 다 마침에 둔다. 로드맵의 코스 · 짜기 전에는 두지 않는다(ADR-027).
- 공통 `InlineConfirmation` 을 쓴다 — 입구 「코스 지우기」(보조형), 확인 버튼 「지우기」. 안내 문구는 06 §7 「지울 때」의 상태별 문장을 **그대로** 쓴다(검토 중 / 학습 중 · 다 마침). 확인 제목은 짧게 정하고 UI_GUIDE 에 적는다.
- 자리는 시안의 방향(화면 아래쪽, 강의 목록 · 「이 코스 복습하기」 뒤)을 따른다. 가장 강조한 영역(다음에 열 강의 · 「이대로 시작」)보다 눈에 띄지 않게 둔다.
- `DELETE /api/courses/{id}` 성공(`204`)하면 — 진행 중인 그 코스 · 코스 목록 · 홈 읽기를 취소하고, 그 코스 캐시를 지우고, 코스 목록 · 홈 캐시를 무효화한 뒤 **`/courses` 로 replace 이동**한다(ADR-082). 지운 코스를 다시 읽지 않게 한다(지운 뒤 404 를 읽어 실패 화면이 잠깐 보이지 않게).
- 요청 중에는 확인 · 취소 · 화면의 다른 동작 버튼을 잠그고 같은 순간의 두 번 제출은 한 번만 보낸다(`ReviewPage` 와 같은 방식). 실패하면 `FailureNotice` 에 서버 문구(연결 실패는 공통 안내)를 보이고 확인 펼침을 보존해 다시 누를 수 있게 한다. 오류 code 는 보이지 않는다.

### 2. 로드맵 화면 — 로드맵 지우기

- **검토 중 · 진행 중 · 다 마침 모두** 둔다(ADR-190).
- 1 과 같은 `InlineConfirmation` — 입구 「로드맵 지우기」, 확인 「지우기」. 안내 문구는 06 §8 「지울 때」의 상태별 문장을 그대로(검토 중 / 진행 중 · 다 마침).
- 자리는 로드맵 계획 뒤. 검토 중의 「이대로 시작」 · 진행 중의 「다음 코스 만들기」보다 눈에 띄지 않게 둔다.
- `DELETE /api/roadmaps/{id}` 성공하면 — 진행 중인 그 로드맵 · 로드맵 목록 · 코스 목록 · 홈 읽기를 취소하고, 그 로드맵 캐시와 **그 로드맵 코스들의 코스 캐시**를 지우고, 로드맵 목록 · 코스 목록(로드맵의 코스가 코스 탭에서 빠진다) · 홈 캐시를 무효화한 뒤 **`/roadmaps` 로 replace 이동**한다(ADR-082).
- 요청 잠금 · 실패 보존은 1 과 같다.

### 3. 시험(Vitest + Testing Library — API 대역)

- `CoursePage.test.tsx` — 상태별로 입구가 보이는지(단일 코스의 검토 중 · 학습 중 · 다 마침은 보임, 로드맵의 코스 · 짜기 전은 없음) · 상태별 안내 문구가 06 문장과 같은지 · 취소하면 아무 요청도 없는지 · 확인하면 `DELETE` 한 번 · 성공 뒤 `/courses` 이동과 캐시 정리 · 요청 중 잠금과 동시 클릭 한 번 · 실패 문구 보존과 다시 시도. 포커스 · 효과 결과는 `waitFor` 안에서 단언한다.
- `RoadmapPage.test.tsx` — 세 상태 모두 입구 · 상태별 문구 · 성공 뒤 `/roadmaps` 이동과 캐시 정리(그 코스들의 캐시 포함) · 잠금 · 실패 보존.
- 훅을 `useCourse.ts` · `useRoadmap.ts` 에 더하면 그 짝 테스트에 경로 · 메서드 · 캐시 정리를 시험한다.
- `App.test.tsx` — 실제 라우터로 코스 화면에서 지우면 코스 탭이 열리고 지운 코스가 목록에 없는 것, 로드맵 화면에서 지우면 로드맵 탭이 열리는 것.

### 4. 문서 — 구현으로 정해진 사실만

- `docs/UI_GUIDE.md` §9 · §10 — 지우기의 자리 · 확인 제목 · 요청 잠금 · 성공 뒤 캐시 정리와 이동 · 시험 범위. 새 토큰 · 컴포넌트가 없으면 없다고 적는다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
npm --prefix frontend run build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 06 §7 · §8 의 상태별 문구가 그대로인가? 로드맵의 코스 · 짜기 전에는 코스 지우기가 없는가?
   - 검토 중 로드맵에도 지우기가 있는가(ADR-190)?
   - 성공 뒤 목록 탭으로 replace 이동하고, 지운 것을 다시 읽어 실패 화면이 보이지 않는가?
   - 공통 `InlineConfirmation` 을 썼고 모달 · 새 확인 컴포넌트를 만들지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가? 시스템 정보(오류 code 등)를 보이지 않는가(금지 20)?
3. 결과에 따라 `phases/12-delete-withdraw/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 바꾼 파일 · 더한 훅 · 캐시 정리 대상 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 시안의 지우기 모달(`delete-modal`)을 옮기지 마라. 이유: 확인은 공통 `InlineConfirmation` 하나다(UI_GUIDE §15 · 사용자 결정 README 2026-11-26).
- 로드맵의 코스 하나만 지우는 입구를 만들지 마라. 이유: 06 §8 · §12 「두지 않는 것」(ADR-027).
- 코스 탭 · 로드맵 탭 목록에 지우기를 두지 마라. 이유: 06 §12 — 지우기는 코스 화면 · 로드맵 화면에만 있다.
- 글자를 입력해야 풀리는 확인을 만들지 마라. 이유: 사용자 결정(README 2026-11-26).
- 탈퇴를 만들지 마라. 이유: step 4 에서 만든다.
- 시안의 메모 고치기 · 퀴즈 · 스트릭 등 「06 이 막은 것」을 더하지 마라. 이유: design README 「06 이 막은 것」.
- 계약 문서(06 문구 · ADR · `AGENTS.md` 금지 목록 · README)를 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
