# Step 8: shell-error-fixes

브랜치 리뷰(`/review 6-job-worker`)에서 나온 수정 step 이다. 화면에서 사용자 눈에 보이는 작은 어긋남 두 가지를 고친다.

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/UI_GUIDE.md` §4(계정 화면 — 「연결 실패는 기술 상세 없이 연결 안내를 쓴다」) · §7(승인 뒤 화면의 틀) · §8(기다리는 화면)
- `docs/05-api.md` §1.2(오류 — 묶음 이름과 서버가 정한 문구)
- `docs/design/README.md` 「시안을 쓰는 법」 · `docs/design/screenshots/05-home-desktop.png`(데스크톱에서 위 바 · 사이드바의 로고 위치를 보는 용도)
- `frontend/src/components/AppLayout.tsx` · `frontend/src/components/AppLayout.test.tsx`
- `frontend/src/api/client.ts` · `frontend/src/api/client.test.ts`
- `frontend/src/pages/WaitingPage.tsx` · `frontend/src/pages/AccountPage.tsx` 와 각 `.test.tsx`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

### 1. 데스크톱에서 로고가 두 번 보이지 않게 한다

`AppLayout` 은 1024px(`lg`) 이상에서 왼쪽 사이드바 위에 모구미와 「머금」 로고(`Brand`)를 둔다. 그런데 탭 첫 화면(`title` 없음)의 위 바도 같은 로고를 그려, 데스크톱에서 로고가 두 곳에 보인다.

- 1024px 이상에서는 **위 바의 로고만 숨긴다**(사이드바 로고는 그대로). 1024px 미만에서는 지금처럼 위 바에 로고가 보인다.
- `title` 이 있는 화면(뒤로 가기 + 제목)은 바꾸지 않는다.
- 위 바 자체(높이 56px · 흰 면 · 아래 테두리)는 1024px 이상에서도 그대로 둔다.
- 테스트(`AppLayout.test.tsx`): jsdom 은 너비를 계산하지 않으므로 기존 시험처럼 클래스 · 구조로 확인한다 — 위 바의 로고에 `lg` 에서 숨기는 클래스가 있고, 사이드바의 로고에는 없다.
- `docs/UI_GUIDE.md` §7 의 「위 바」 행에 「1024px 부터 로고는 사이드바에만 둔다」를 적는다.

### 2. 오류 응답에 쓸 문구가 없을 때 영어 대신 공통 연결 안내를 쓴다

`apiFetch` 는 오류 응답 본문이 JSON 이 아니거나 `message` 가 없으면(예: 프록시가 돌려준 502 HTML) `ApiError` 의 메시지를 영어 `'Request failed'` 로 채운다. 성공 응답의 본문 모양이 틀릴 때도 `'Invalid API response'` 다. 「나」 화면(`AccountPage`)은 `ApiError` 면 그 메시지를 그대로 보여서, 이 영어 문구가 사용자에게 보인다.

- `client.ts` 에 공통 연결 안내 문구 상수를 하나 두고 export 한다. 값은 지금 화면이 쓰는 「연결하지 못했어요. 다시 시도해 주세요.」를 그대로 쓴다.
- 서버가 준 `message` 가 없을 때와 성공 응답의 본문 모양이 틀릴 때는 이 상수를 `ApiError` 메시지로 쓴다. 이때 `code` · `status` 는 지금과 같다.
- `WaitingPage.tsx` · `AccountPage.tsx` 에 따로 적힌 같은 문구는 이 상수를 쓰게 바꾼다. 화면의 분기(언제 서버 문구, 언제 연결 안내를 보이는지)는 바꾸지 않는다.
- 테스트:
  - `client.test.ts` — JSON 이 아닌 오류 본문(예: `text/html` 의 502)과 `message` 가 없는 JSON 오류 본문에서 메시지가 공통 안내이고 `code` · `status` 는 그대로인지 본다. 기존의 `'Request failed'` 기대값을 고친다.
  - `AccountPage.test.tsx` — 신청이 JSON 이 아닌 오류 본문으로 실패하면 화면에 영어가 아니라 공통 안내가 보이는지 본다.
- `docs/UI_GUIDE.md` §4 에서 연결 안내를 말하는 문장 옆에, 그 문구가 `client.ts` 의 공통 상수이고 서버 문구가 없을 때 `ApiError` 메시지로도 쓰인다는 사실을 한 문장으로 적는다.

## Acceptance Criteria

```bash
npm --prefix frontend run test   # 화면 테스트 통과
npm --prefix frontend run lint   # ESLint 통과
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD — 테스트를 먼저 고치고 구현한다)을 위반하지 않았는가?
   - 06 · UI_GUIDE 의 보이는 것 · 문구가 맞는가? 적히지 않은 상태 · 문구를 새로 만들지 않았는가?
3. 결과에 따라 `phases/6-job-worker/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요(문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 백엔드(`backend/`)를 고치지 마라. 이유: 이 step 은 화면의 두 가지만 고친다.
- 위 두 가지 밖의 리뷰 권고(폴링 중 한 번 실패하면 멈추는 것 · 뒤로 가기의 대안 · 버리기 뒤 GET 한 번 더 · 다른 탭에서 바뀐 뒤 남는 버튼 · 문서 제목을 「머금」 문자열로 가르는 것)를 고치지 마라. 이유: 브랜치 리뷰에서 뒤 TASK 로 넘기거나 MVP 에서 넘기기로 정했다.
- 새 문구를 지어내지 마라. 이유: 화면 문구는 06 · UI_GUIDE 에 있는 것만 쓴다 — 공통 안내는 지금 화면이 쓰는 문구 그대로다.
- `apiFetch` 의 `code` · `status` · `fields` · `retryAfterSeconds` 동작을 바꾸지 마라. 이유: 화면이 버튼을 고르는 기준이다(05 §1.2).
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
