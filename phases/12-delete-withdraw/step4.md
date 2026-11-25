# Step 4: withdraw-screen

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/06-screens.md` §2.7 · §13(내 정보 탭 — 「탈퇴 확인」 문구)
- `docs/05-api.md` §1.1 · §1.2 · §4(step 2 가 적은 「탈퇴」 구현 줄)
- `docs/ADR.md` — ADR-059 · ADR-083 · ADR-084
- `docs/design/README.md`(「그리지 않은 것」 — 내 정보 탭은 시안이 없다. 06 §13 대로 만든다)
- `docs/UI_GUIDE.md` §3(`InlineConfirmation` · `FailureNotice` · `Button`) · §15(확인 펼침 규격) · §17(내 정보 탭 — 로그아웃의 캐시 제거 · 이동 순서)
- step 2 가 만든 것(summary 참고) — 경로 · `204` · 세션을 끝내는 방식
- step 3 이 만든 것(summary 참고) — 지우기 확인을 쓴 방식
- `frontend/src/pages/ProfilePage.tsx` · `frontend/src/pages/ProfilePage.test.tsx`
- `frontend/src/components/InlineConfirmation.tsx` · `frontend/src/components/RequireAuth.tsx` · `frontend/src/api/client.ts` · `frontend/src/api/queryClient.ts`
- `frontend/src/App.tsx` · `frontend/src/App.test.tsx`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

내 정보 탭에서 탈퇴한다(ADR-059). 확인에서 무엇을 지우는지 알리고, 탈퇴하면 로그인 화면으로 바로 간다 — 안내 한 줄 · 완료 화면은 없다(ADR-083). 서버(step 2)는 이미 있다.

**계약 문서는 이 phase 전에 정해 커밋했다. 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 내 정보 탭 — 탈퇴

- 순서는 06 §13 의 할 수 있는 것 순서를 따른다 — 로그아웃 → 탈퇴 → 처리방침 보기.
- 공통 `InlineConfirmation` — 입구 「탈퇴」(보조형), 확인 버튼 「탈퇴하기」. 안내 문구는 06 §13 「탈퇴 확인」의 문장을 **그대로** 쓴다. 확인 제목은 짧게 정하고 UI_GUIDE 에 적는다.
- `POST /api/account/withdraw` 성공(`204`)하면 — 진행 중인 모든 읽기를 취소하고 **쿼리 캐시를 모두 지운 뒤**(탈퇴한 사람의 코스 · 카드 · 「나」가 남지 않게) **`/login` 으로 replace 이동**한다(ADR-083). 로그아웃의 순서(취소 → 제거 → 이동)를 따른다.
- 요청 중에는 탈퇴 확인 · 취소 · 로그아웃 버튼을 잠그고 같은 순간의 두 번 제출은 한 번만 보낸다. 실패하면 `FailureNotice` 에 실패한 동작 · 서버 문구(연결 실패는 공통 안내)를 보이고 확인 펼침을 보존해 다시 누를 수 있게 한다. 오류 code 는 보이지 않는다.

### 2. 시험(Vitest + Testing Library — API 대역)

- `ProfilePage.test.tsx` — 입구 · 순서(로그아웃 → 탈퇴 → 처리방침) · 안내 문구가 06 문장과 같은지 · 취소하면 요청이 없는지 · 확인하면 `POST` 한 번 · 성공 뒤 캐시가 비고 `/login` 으로 replace · 요청 중 잠금(로그아웃 포함)과 동시 클릭 한 번 · 실패 문구 보존과 다시 시도. 포커스 · 효과 결과는 `waitFor` 안에서 단언한다.
- `App.test.tsx` — 실제 라우터로 `/profile` 에서 탈퇴하면 로그인 화면이 열리고, 뒤로 가기 등으로 다시 들어와도 이전 사람의 캐시된 화면이 보이지 않는 것(「나」 다시 읽기가 `401` 이면 로그인 흐름).

### 3. 문서 — 구현으로 정해진 사실만

- `docs/UI_GUIDE.md` §17 — 탈퇴의 자리 · 확인 제목 · 요청 잠금 · 성공 뒤 캐시 비우기와 이동 · 시험 범위. 「탈퇴는 `12` TASK에서 더하며」 문장을 지금 사실로 고친다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
npm --prefix frontend run build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 06 §13 의 탈퇴 확인 문구가 그대로인가?
   - 성공 뒤 캐시를 모두 지우고 `/login` 으로 replace 하는가? 완료 안내 · 완료 화면이 없는가?
   - 공통 `InlineConfirmation` 을 썼고 글자 입력 확인 · 모달을 만들지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/12-delete-withdraw/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 바꾼 파일 · 캐시 정리 방법 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 탈퇴 완료 화면 · 로그인 화면의 「탈퇴했어요」 안내를 만들지 마라. 이유: ADR-083.
- 글자를 입력해야 풀리는 확인 · 모달을 만들지 마라. 이유: 사용자 결정(06 §13 · README 2026-11-26).
- 승인 대기 · 거절됨 화면에 탈퇴를 두지 마라. 이유: ADR-059 — 승인된 사람만 화면에서 탈퇴한다.
- 학습 통계 · 기기 목록 · 원격 로그아웃 · 스트릭을 내 정보 탭에 더하지 마라. 이유: 06 §13 「두지 않는 것」.
- 계약 문서(06 문구 · ADR · `AGENTS.md` 금지 목록 · README)를 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
