# Step 6: query-keys

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/UI_GUIDE.md` §9 · §10(코스 · 로드맵 지우기의 캐시 정리 — step 3 이 적은 것)
- `frontend/src/api/useCourse.ts` · `frontend/src/api/useRoadmap.ts` · `frontend/src/api/useHome.ts` · 각 짝 테스트
- `frontend/src/pages/ProfilePage.tsx`(탈퇴 · 로그아웃의 캐시 제거)
- 쿼리 키를 쓰는 곳 — `grep -rn "courseKey\|coursesKey\|roadmapKey\|roadmapsKey" frontend/src` 결과 전부

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

`useCourse.ts` 는 `useRoadmap.ts` 의 `roadmapKey` 를, `useRoadmap.ts` 는 `useCourse.ts` 의 `courseKey` · `coursesKey` 를 값으로 import 해 두 모듈이 서로를 참조한다. 지금은 함수 안에서만 읽어 문제가 없지만, 모듈 최상위에서 쓰면 초기화 순서 문제가 난다. **쿼리 키 상수를 한 파일로 모아 서로 참조를 없앤다. 동작(키 값 · 캐시 정리 순서)은 바꾸지 않는다.**

## 작업

### 1. `frontend/src/api/queryKeys.ts`

- `courseKey(courseId)` · `coursesKey` · `roadmapKey(roadmapId)` · `roadmapsKey` 를 이 파일로 옮긴다. 값은 지금과 같다(예: `['courses', courseId] as const`).
- `useCourse.ts` · `useRoadmap.ts` 와 이 키를 쓰는 다른 파일은 `queryKeys.ts` 에서 import 한다. 옮긴 뒤 `useCourse.ts` 와 `useRoadmap.ts` 사이에 **값 import 가 없어야 한다**(`import type` 은 괜찮다).
- `useHome.ts` 의 `['home', …]` 처럼 한 파일 안에서만 쓰는 키는 옮기지 않는다 — 이번 범위는 서로 참조를 만든 네 키다.
- 다른 파일이 `useCourse` · `useRoadmap` 에서 이 키를 import 하고 있었다면 함께 바꾼다. 기존 export 를 다시 내보내는(re-export) 별칭은 두지 않는다.

### 2. 시험(Vitest)

- `frontend/src/api/queryKeys.test.ts` — 네 키의 값(배열 모양)을 확인한다. TDD 가드의 짝 규칙(`queryKeys.ts` → `queryKeys.test.ts`)에 맞춘 이름이다 — 이 파일을 먼저 쓴다.
- 기존 `useCourse.test.tsx` · `useRoadmap.test.tsx` · 화면 시험은 고치지 않고 통과해야 한다(import 경로만 바뀐 경우는 예외).

### 3. 문서

- 바꿀 문서는 없다 — UI_GUIDE 는 키를 둔 파일을 적지 않았다. 적혀 있으면 그 줄만 맞춘다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
npm --prefix frontend run build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `useCourse.ts` 와 `useRoadmap.ts` 가 서로를 값으로 import 하지 않는가?
   - 키 값 · 캐시 정리 동작이 그대로인가? 화면 테스트 수가 줄지 않았는가(전후)?
   - `AGENTS.md` 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/12-delete-withdraw/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 새 파일 · import 를 바꾼 파일 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 키 값 · 캐시 정리 순서 · 화면 동작을 바꾸지 마라. 이유: 구조만 바꾸는 정리다.
- 지우기 실패 안내를 확인 펼침을 다시 열 때 지우는 등 다른 개선을 더하지 마라. 이유: 사용자가 그대로 두기로 정했다.
- 백엔드(`backend/`)를 바꾸지 마라. 이유: step 5 범위다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
