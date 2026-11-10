# Step 6: flaky-focus-test

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `frontend/src/pages/AccountPage.test.tsx` — 고칠 테스트
- `frontend/src/pages/AccountPage.tsx` — 46~49줄의 `useEffect` 가 `document.title` 을 바꾸고 제목에 `focus()` 한다
- `frontend/src/pages/LoginPage.test.tsx` · `frontend/src/pages/PrivacyPage.test.tsx` · `frontend/src/components/Button.test.tsx` — 같은 종류의 단언(`toHaveFocus` · `document.title`)이 있는 다른 테스트

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`4-account`)는 step 0 ~ 5 로 끝났다. 브랜치 리뷰에서 화면 테스트를 6번 돌렸는데 1번 실패했다.

- 실패한 테스트: `AccountPage.test.tsx` 의 `test.each` 「%s 화면은 계정과 처리방침 입구를 보여 주고 …」 중 `REJECTED`
- 실패 내용: `expect(title).toHaveFocus()` 에서 포커스가 제목이 아니라 `body` 에 있었다. 나머지 5번은 통과했다.
- 원인: 이 테스트는 `fetch` 응답(「나」)이 비동기로 온 뒤 화면이 그려진다. `findByRole` 은 제목이 DOM 에 생기면 바로 끝난다. 그런데 `useEffect` 의 `focus()` · `document.title` 은 React 가 DOM 을 커밋한 뒤에 따로 돈다. 그래서 효과가 아직 돌지 않은 순간에 단언할 수 있다.
- `LoginPage.test.tsx` · `PrivacyPage.test.tsx` 는 `render` 직후 동기로 단언한다. `render` 는 `act` 로 감싸져 있어 효과까지 끝난 뒤 돌아오므로 같은 문제가 없다.

## 작업

1. `frontend/src/pages/AccountPage.test.tsx` 31~35줄을 고친다. `findByRole` 로 찾은 제목의 포커스와 `document.title` 단언을 `await waitFor(() => { … })` 안에서 기다린다. 두 값 모두 같은 효과가 바꾼다. 나머지 단언(보이는 것 · 없는 것)은 그대로 둔다.
2. 다른 화면 테스트 파일(`frontend/src/**/*.test.tsx`)에서 같은 모양을 찾는다. 모양은 「`findBy…` 또는 `await` 로 비동기 화면을 기다린 직후, 효과가 만드는 결과(`toHaveFocus` · `document.title`)를 바로 단언」이다. 있으면 같은 방식으로 고친다. 동기 `render` 직후의 단언은 문제가 없으니 고치지 않는다. 고친 곳과 그대로 둔 곳을 summary 에 적는다.
3. 고친 뒤 고친 테스트 파일을 따로 20번 돌려 모두 통과하는지 확인한다. 예: `for i in $(seq 20); do npm --prefix frontend run test -- src/pages/AccountPage.test.tsx || break; done`. 돌린 횟수와 결과를 summary 에 적는다.

## Acceptance Criteria

```bash
npm --prefix frontend run test   # 화면 테스트 통과
npm --prefix frontend run lint   # ESLint 통과
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 바뀐 파일이 `*.test.tsx` 뿐인가?
   - 단언을 지우거나 약하게 만들지 않았는가? 포커스 · 제목 단언이 그대로 남아 있고, 기다리기만 더했는가?
   - `AGENTS.md` 금지 목록 · 작업 방식을 위반하지 않았는가?
3. 결과에 따라 `phases/4-account/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 고친 테스트 위치 · 그대로 둔 곳 · 20번 반복 결과 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (테스트만으로 안정되지 않아 제품 코드를 고쳐야 함 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 제품 코드(`frontend/src/**` 의 `*.test.tsx` 가 아닌 파일 · `backend/`)를 고치지 마라. 테스트만으로 안정되지 않으면 `blocked` 로 멈춰라. 이유: 화면 동작은 맞고, 테스트가 효과를 기다리지 않는 것이 문제다.
- 포커스 · `document.title` 단언을 지우거나, `test.retry` · `retry` 옵션 · 시간 늘리기(`setTimeout` · 고정 지연)로 감추지 마라. 이유: 가끔 실패하는 것을 다시 돌려 숨기면 진짜 회귀도 숨는다.
- 관련 없는 테스트를 정리하거나 다시 쓰지 마라. 이유: 이 step 은 효과를 기다리지 않는 단언만 고친다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
