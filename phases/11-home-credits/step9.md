# Step 9: identity-heading

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/06-screens.md` §13(내 정보 탭 — 「어느 구글 계정인지」) · 신청 · 승인 대기 · 거절됨 화면 절(`AccountIdentity` 를 쓰는 곳)
- `docs/UI_GUIDE.md` — §3 공통 컴포넌트의 `AccountIdentity` 줄 · §17 내 정보 탭
- `frontend/src/components/AccountIdentity.tsx` · `frontend/src/components/AccountIdentity.test.tsx`
- `frontend/src/pages/ProfilePage.tsx` · `frontend/src/pages/ProfilePage.test.tsx`
- `frontend/src/pages/AccountPage.tsx`(신청 · 승인 대기 · 거절됨에서 `AccountIdentity` 를 그린다)
- `frontend/src/App.test.tsx` — 203줄 근처의 시험 이름 「임시 홈의 입구에서 새로 배우기로 가고 받기 뒤 실제 기다리는 화면을 연다」

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

브랜치 리뷰에서 나온 작은 수정 둘이다.

1. `AccountIdentity` 는 보이는 제목(`h2`) 「신청 계정」을 영역 이름으로 쓴다. 신청 · 승인 대기 · 거절됨 화면에서는 맞지만, step 7 이 같은 컴포넌트를 승인된 사람의 내 정보 탭(`/profile`)에도 써서 그곳에서도 「신청 계정」이 보이고 화면 읽기 프로그램도 그렇게 읽는다. 내 정보 탭은 신청이 끝난 사람의 화면이다.
2. step 6 이 홈을 실제 화면으로 바꿨는데 `App.test.tsx` 의 시험 이름에 「임시 홈」이 남아 있다.

## 작업

- `AccountIdentity` 가 제목 문구를 선택 prop 으로 받게 한다(이름 예: `title`). **주지 않으면 지금처럼 「신청 계정」이다** — 신청 · 승인 대기 · 거절됨 화면의 모양 · 문구 · 시험은 바뀌지 않는다. 영역 이름은 계속 그 제목을 가리킨다(`aria-labelledby`).
- `ProfilePage` 는 제목 「구글 계정」을 넘긴다(06 §13 「어느 구글 계정인지」).
- `App.test.tsx` 의 시험 이름 「임시 홈의 입구에서…」를 「홈의 새로 배우기 입구에서…」로 바꾼다. 시험 내용은 바꾸지 않는다.

### 시험

- `AccountIdentity.test.tsx` — prop 없이 그리면 영역 이름 · 제목이 「신청 계정」(지금 시험 유지) · 제목을 주면 그 문구가 영역 이름이자 보이는 제목이다.
- `ProfilePage.test.tsx` — 영역 이름이 「구글 계정」이고 「신청 계정」은 없다(지금 `getByRole('region', { name: '신청 계정' })` 시험을 고친다).

### 문서 — 구현으로 정해진 사실만

- `docs/UI_GUIDE.md` §3 `AccountIdentity` 줄 — 제목 prop(기본 「신청 계정」 · 내 정보 탭은 「구글 계정」)을 적는다. §17 의 계정 표시 문장이 영역 이름을 말하면 함께 맞춘다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 신청 · 승인 대기 · 거절됨 화면의 `AccountIdentity` 가 그대로 「신청 계정」인가? 내 정보 탭만 「구글 계정」인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD — 시험을 먼저 고친다)을 위반하지 않았는가?
3. 결과에 따라 `phases/11-home-credits/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 바꾼 파일 · prop 이름 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `AccountIdentity` 의 모양(바탕 · 테두리 · 여백 · `dl` 구성)과 이름 · 이메일 표시를 바꾸지 마라. 이유: 이 step 은 제목 문구만 고친다.
- `ProfilePage` 의 다른 내용(크레딧 · 날짜 · 로그아웃 · 처리방침 · 다시 읽기)을 바꾸지 마라. 이유: step 7 에서 승인됐다.
- 다른 리뷰 권고(로그아웃 코드 모으기 · 판별 함수 옮기기 · 긴 JSX 줄 나누기 · `HomeSection` 빼기)를 하지 마라. 이유: 브랜치 리뷰에서 그대로 두기로 했다.
- 서버(`backend/`)를 고치지 마라. 이유: 화면 문구만의 수정이다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README · 06)의 규칙을 고치지 마라. 이유: 06 §13 에 맞추는 수정이다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
