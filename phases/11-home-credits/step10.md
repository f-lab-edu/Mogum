# Step 10: me-refetch-keep

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `frontend/src/components/RequireAuth.tsx` · `frontend/src/components/RequireAuth.test.tsx`
- `frontend/src/api/useMe.ts` · `frontend/src/api/queryClient.ts`(`retryApiQuery` · `UNAUTHORIZED` 처리 · `['me']` 의 `ACCOUNT_NOT_APPROVED` 예외)
- `frontend/src/pages/ProfilePage.tsx` · `frontend/src/pages/ProfilePage.test.tsx`(step 7 — 탭을 열 때 `['me']` 무효화)
- `docs/UI_GUIDE.md` §17(내 정보 탭 — 「읽기 실패 · 승인 상태 변경은 기존 인증 흐름이 처리한다」) · `RequireAuth` 를 말하는 다른 절
- `docs/06-screens.md` §13

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

브랜치 리뷰 뒤 코드 설명을 쓰다가 찾은 의심이다(시험으로 확인하지 않았다).

`RequireAuth` 는 `me.isError` 면 앱 전체를 「불러오지 못했어요」 화면(`AccountLayout` + 다시 시도)으로 바꾼다. TanStack Query v5 는 이미 데이터가 있어도 **다시 읽기가 실패하면** `status` 를 `error` 로 두므로 `isError` 가 참이 된다. step 7 의 내 정보 탭은 열 때마다 `['me']` 를 무효화해 다시 읽으므로, 일시적인 연결 실패가 재시도까지 모두 실패하면 탭을 여는 것만으로 화면 전체가 오류로 바뀔 수 있다. 창 포커스 때 다시 읽기도 같은 길을 탄다.

사용자 결정(2026-11-26): **이미 받은 「나」가 있으면 다시 읽기 실패에도 화면을 그대로 두고(이전 값 유지), 처음 읽기 실패(데이터 없음)일 때만 지금의 오류 화면을 보인다.** 내 정보 탭에 따로 안내를 더하지 않는다.

## 작업

1. **재현 시험부터 쓴다** — `RequireAuth.test.tsx`(또는 `ProfilePage.test.tsx`)에 「「나」를 한 번 받은 뒤 다시 읽기가 연결 실패 · 서버 오류(재시도까지 모두 실패)로 끝나면, 오류 화면으로 바뀌지 않고 이전 값으로 그린 화면이 그대로다」를 쓴다. 내 정보 탭을 열어 다시 읽게 하는 경로로 재현한다.
   - 이 시험이 고치기 전 코드에서 **실패하는지 먼저 확인한다.** 실패하지 않으면(의심이 틀림) 제품 코드를 바꾸지 않고 시험만 남기고, summary 에 「재현되지 않음 — 이유」를 적고 `completed` 로 끝낸다.
2. 재현되면 `RequireAuth` 를 고친다 — 오류 화면은 **보일 「나」 데이터가 없을 때만** 보인다. 데이터가 있으면 다시 읽기의 실패와 상관없이 outlet 을 이전 데이터로 그린다.
   - 지금 앞에 있는 분기(확인 중 회색 줄 · `UNAUTHORIZED` 면 로그인으로 보내는 동안의 표시)는 **바꾸지 않는다** — 데이터가 있어도 `UNAUTHORIZED` 는 지금처럼 로그인 흐름으로 간다.
   - 데이터의 `status` 가 모르는 값이면 지금처럼 오류 화면이다(데이터 기준 검사는 유지).
   - 다시 읽기가 성공하면 새 값(크레딧 · 상태)이 지금처럼 반영된다.
3. 기존 `RequireAuth.test.tsx` 의 처음 읽기 실패 시험들(네트워크 · 서버 · `RATE_LIMITED` · 모르는 code · 다시 시도 잠금)은 그대로 통과해야 한다.

### 시험

- 처음 읽기 실패 → 지금의 오류 화면 · 다시 시도(기존 시험 유지).
- 받은 뒤 다시 읽기 실패(연결 실패 · 서버 오류) → 오류 화면 없음 · 이전 값의 화면 유지(내 정보 탭이면 이전 잔액).
- 받은 뒤 다시 읽기의 `UNAUTHORIZED` → 지금처럼 로그인 흐름.
- 받은 뒤 다시 읽기 성공 → 새 잔액이 보인다(step 7 시험 유지).
- 응답을 받은 뒤 그려지는 것은 `findBy…` 뒤 `waitFor` 안에서 단언한다(S19).

### 문서 — 구현으로 정해진 사실만

- `docs/UI_GUIDE.md` §17 의 「읽기 실패 · 승인 상태 변경은 기존 인증 흐름이 처리한다」를 사실대로 고친다(다시 읽기 실패는 이전 값 유지 · 처음 읽기 실패만 오류 화면). `RequireAuth` 의 오류 화면 조건을 적은 다른 절이 있으면 함께 맞춘다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 재현 시험이 고치기 전에 실패했는가(summary 에 적는다)? 재현되지 않았으면 제품 코드를 바꾸지 않았는가?
   - 처음 읽기 실패 · `UNAUTHORIZED` · 모르는 상태의 동작이 그대로인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/11-home-credits/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 재현 여부(고치기 전 시험 결과) · 바꾼 조건 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 내 정보 탭 · 다른 화면에 「최신 값을 불러오지 못했어요」 같은 새 안내를 더하지 마라. 이유: 사용자가 이전 값 유지만 골랐고, 06 §13 에 없는 상태다.
- `useMe` 의 재시도 · 다시 읽기 설정(`retry` · `refetchOnWindowFocus` 등)이나 `queryClient.ts` 의 전역 오류 처리를 바꾸지 마라. 이유: 이 step 은 `RequireAuth` 의 오류 화면 조건만 고친다.
- 내 정보 탭의 「탭을 열 때 다시 읽기」를 빼지 마라. 이유: 06 §13 — 만들기로 쓴 크레딧이 탭에서 바로 보여야 한다.
- 서버(`backend/`)를 고치지 마라. 이유: 화면만의 수정이다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README · 06)의 규칙을 고치지 마라. 이유: 이미 정한 화면 계약 안의 수정이다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
