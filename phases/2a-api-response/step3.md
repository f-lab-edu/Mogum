# Step 3: api-client

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 화면 스택 줄 · 금지 목록 23 · **29**, 작업 방식(TDD 짝 테스트 규칙 — `frontend/src/…/foo.ts` → 같은 폴더의 `foo.test.ts`)
- `docs/05-api.md` **§1 「응답 형식」 문단** · **§1.2(필드 · `message` 는 화면에 띄우지 않음 · 모르는 code · 표)** · §1.1 의 "화면과 API 의 출처" 항목
- `docs/06-screens.md` §2.6(공통 상태 — code 별 화면 동작) · §15.1(막힘 문구 — 이 step 에서 매핑하지 않는다, 아래 금지사항)
- `docs/ADR.md` ADR-074
- 백엔드가 이 phase 에서 정한 것: `backend/src/main/java/com/mogum/common/ErrorCode.java`(지금 나오는 code 목록) · `ApiError.java`(`retryAfterSeconds` · `fields`) · `ApiResponse.java` · `backend/src/main/java/com/mogum/account/controller/MeController.java`(`GET /api/me` → `{ "data": { … } }`)
- 화면 코드: `frontend/src/api/client.ts` · `queryClient.ts` · `useMe.ts` · `frontend/src/components/RequireAuth.tsx` · `frontend/src/lib/messages.ts` · `frontend/src/lib/authNavigation.ts`
- 화면 테스트: `frontend/src/api/client.test.ts` · `queryClient.test.ts` · `useMe.test.tsx` · `frontend/src/components/RequireAuth.test.tsx` · `frontend/src/App.test.tsx`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

백엔드는 step 1 부터 본문이 있는 성공 응답을 `{ "data": … }` 로 감싸고, 오류는 `{ code, message }` 에 해당할 때만 `retryAfterSeconds` · `fields` 를 붙인다(05 §1 · §1.2). 이 step 은 **화면의 API 클라이언트를 그 계약에 맞추고, 오류 분기를 HTTP 상태가 아니라 `code` 로 바꾼다.** 지금은 상태로 분기해서, 앞으로 `503 GLOBAL_BUDGET_EXHAUSTED` 같은 도메인 오류가 생기면 서버 오류로 보고 재시도한다(ADR-074). **화면 모양 · 문구 · 화면 흐름은 바꾸지 않는다.**

### 1. `apiFetch` — 성공 응답 벗기기

- 본문이 있는 성공 응답은 JSON 을 읽고 **`data` 를 돌려준다.** 호출하는 쪽(`apiFetch<Me>('/me')`)의 타입과 사용법은 지금 그대로다.
- `204` · `HEAD` 는 지금처럼 `undefined` 다.
- **성공 상태인데 JSON 이 아니거나 `data` 가 객체가 아니면 실패로 다룬다** — `ApiError`(그 HTTP 상태 · code `INTERNAL_ERROR`)를 던진다. 이유: API 주소가 빠진 빌드에서 Vercel 이 `index.html` 을 `200` 으로 돌려주는 경우 등을 우리 응답으로 착각하지 않게(ADR-074). `data` 는 늘 객체다(05 §1) — 배열 · `null` · 원시값이면 실패다.
- 요청 쪽(출처 · `credentials: 'include'` · CSRF 헤더)은 바꾸지 않는다.

### 2. `ApiError` — 오류 본문 그대로 담기

- 필드: `status` · `code` · `message` · `retryAfterSeconds?`(숫자) · `fields?`(문자열 배열). 본문의 값이 그 타입일 때만 담고, 아니면 없는 것으로 둔다(서버를 믿되 타입이 틀린 값으로 화면이 깨지지 않게).
- `code` 타입 — 백엔드 `ErrorCode` 에 있는 code 와 화면이 이미 이름으로 다루는 code(`RATE_LIMITED` · `TERMS_REQUIRED` · `ACCOUNT_WAITLISTED` 등 — `grep` 으로 찾는다)를 문자열 유니온으로 두고, **모르는 code 문자열도 그대로 담는다**(타입이 막지 않게). 뒤 TASK 가 code 를 더하면 이 유니온에 더한다.
- JSON 이 아닌 오류 응답(프록시 · 배포 중 게이트웨이 오류 등)은 지금처럼 상태로 code 를 정한다(401 → `UNAUTHORIZED` · 5xx → `INTERNAL_ERROR` · 나머지 → `INVALID_REQUEST`).
- 서버 `message` 는 디버깅용이다 — 화면에 띄우지 않는다(05 §1.2). 지금도 띄우지 않는다. 그대로 둔다.

### 3. 분기를 `code` 로

- 로그인으로 보내는 분기(`queryClient.ts` 의 `onError` · `RequireAuth.tsx` 의 대기 표시 조건)를 `status === 401` 이 아니라 **`code === 'UNAUTHORIZED'`** 로 바꾼다.
- `RequireAuth` 의 `RATE_LIMITED` 분기는 지금처럼 code 로 둔다.
- **재시도(`retryApiQuery`)는 네트워크 실패(`fetch` 가 거절 — `ApiError` 가 아닌 오류)와 `INTERNAL_ERROR` 일 때만**, 지금처럼 최대 2회다. 그 밖의 모든 `ApiError` — 모르는 code 포함, 상태가 5xx 라도 code 가 다른 것(예: `503` + `GLOBAL_BUDGET_EXHAUSTED`) — 는 재시도하지 않는다. 변경 요청(`mutations`)은 지금처럼 재시도하지 않는다.

### 4. 테스트 — TDD

TDD 가드: 고치는 구현 파일(`client.ts` · `queryClient.ts` · `RequireAuth.tsx` …)마다 같은 폴더의 짝 테스트(`client.test.ts` …)도 이 step 에서 고친다.

- `client.test.ts` — 성공 `{ data: {...} }` → `data` 를 돌려준다. `204` → `undefined`. 성공인데 JSON 아님 · `data` 없음 · `data` 가 배열 → `ApiError` `INTERNAL_ERROR`(그 상태). 오류 본문의 `retryAfterSeconds` · `fields` 를 담는다. 타입이 틀린 `retryAfterSeconds`(문자열) · `fields`(숫자 배열)는 담지 않는다. 모르는 code 를 그대로 담는다. JSON 이 아닌 오류 응답은 상태로 code 를 정한다(기존 테스트 유지).
- `queryClient.test.ts` — 재시도 표: 네트워크 실패 · `INTERNAL_ERROR` 는 재시도하고, `NOT_FOUND` · `RATE_LIMITED` · `503` + `GLOBAL_BUDGET_EXHAUSTED` · 모르는 code 는 재시도하지 않는다. `UNAUTHORIZED` 만 로그인 콜백을 부른다(기존 403 테스트 유지).
- `/api/me` 응답을 흉내 내는 모든 테스트(`useMe.test.tsx` · `RequireAuth.test.tsx` · `App.test.tsx` …)의 성공 본문을 `{ data: { … } }` 로 바꾼다. 화면에 보이는 결과는 지금과 같아야 한다.

### 5. 문서

- `docs/ADR.md` ADR-074 의 **검증** 줄 — 앞 step 이 더한 항목 뒤에 이 step 이 확인한 것을 한 항목으로 더한다(화면 테스트 수 · 재시도 규칙). "아직 수행하지 않았다(`2a-api-response`)" 문구는 지우지 않는다 — phase 리뷰에서 정리한다.
- 05 §1 · §1.2 와 구현이 어긋나게 되면 코드를 계약에 맞춘다. 계약대로 만들 수 없으면 `blocked` 로 사유를 적는다 — 05 를 임의로 고치지 마라.

## Acceptance Criteria

```bash
npm --prefix frontend run test    # Vitest
npm --prefix frontend run lint    # ESLint
npm --prefix frontend run build   # tsc --noEmit 타입 검사 + Vite 빌드
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `AGENTS.md` 화면 스택(React · TypeScript strict · TanStack Query · Vitest)을 벗어나지 않았는가 — 새 의존성이 없는가?
   - `AGENTS.md` 금지 목록(특히 23 · 29) · 작업 방식(TDD)을 위반하지 않았는가?
   - HTTP 상태로 분기하는 곳이 `apiFetch` 의 JSON 아닌 오류 대체 규칙 말고 남아 있지 않은가?
   - 화면 모양 · 문구가 바뀌지 않았는가?
3. 결과에 따라 `phases/2a-api-response/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — `apiFetch` 의 성공 · 실패 규칙 · `ApiError` 필드 · code 유니온 위치 · 재시도 규칙을 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 06 §15 의 code 별 막힘 문구 매핑(쿼터 · 킬스위치 · `STRUCTURAL_LIMIT` 칸 문구 등)을 만들지 마라. 이유: 그 code 를 처음 만드는 TASK(`6` · `10` …)가 그 화면과 함께 붙인다 — 지금은 그 code 가 나오지 않는다.
- 감싼 응답과 감싸지 않은 응답을 함께 받는 호환 코드를 넣지 마라. 이유: ADR-074 트레이드오프 — 공개 전 사용자 한 명이라 두지 않는다.
- 서버 `message` 를 화면에 띄우지 마라. 이유: 05 §1.2 — 화면 문구는 06 이 정한다.
- 화면 모양 · 문구 · 라우팅을 바꾸지 마라. 이유: 이 step 은 API 클라이언트만 다룬다.
- 새 npm 의존성을 더하지 마라. 이유: 필요 없다 — `fetch` · TanStack Query 로 충분하다.
- `backend/` 를 고치지 마라. 이유: step 0 ~ 2 에서 끝났다. 백엔드와 맞지 않는 것을 찾으면 `blocked` 로 적는다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
