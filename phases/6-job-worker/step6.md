# Step 6: app-shell

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/06-screens.md` §2.1(형태 — 아래 탭 바 · 위에 제목과 뒤로 가기 · 모바일 우선) · §2.2(화면 목록과 지도) · §2.3(탭 바 — **이 step 의 계약이다**) · §2.6 · §4(홈 — 이 step 은 자리만) · §15.3
- `docs/design/README.md` — 「시안을 쓰는 법」 · 「공통 틀」 · 「06 이 막은 것」 · 「모구미 이미지」
- `docs/design/guide.md` §1.2(로고) · §3(다섯 탭 아이콘 — 인라인 SVG) · §4(반응형 틀) · §4.3
- 시안(틀을 보는 용도 — 홈의 내용은 만들지 않는다): `docs/design/screenshots/05-home-mobile.png` · `05-home-desktop.png` · `docs/design/html/05-home.html` · `docs/design/screenshots/07-waiting-mobile.png` · `07-waiting-desktop.png`(탭 안 화면의 위 바 · 뒤로 가기)
- `docs/UI_GUIDE.md` 전체 — 토큰 · 공통 컴포넌트 · §4.1 주소 표 · §6(이 step 이 정할 것)
- `docs/README.md` 「정한 것」 2026-11-15 첫 줄 — 틀을 이 TASK 에서 만든다
- 지금 화면 코드: `frontend/src/App.tsx` · `components/RequireAuth.tsx` · `components/AccountLayout.tsx` · `components/Button.tsx` · `components/Mogumi.tsx` · `pages/AccountPage.tsx` · `pages/PlaceholderPage.tsx` · `index.css` · `api/useMe.ts` · 각 짝 테스트

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

승인된 사람이 보는 모든 화면의 **틀**을 만든다 — 다음 step 의 기다리는 화면이 이 틀 안에 처음 들어간다. 탭의 내용은 뒤 TASK 가 채운다(홈 · 내 정보는 홈 · 크레딧 TASK, 코스 · 로드맵 탭은 강의 목록 TASK, 복습 탭은 복습 TASK). 이 step 에서 다섯 탭은 모두 자리 화면이다(README 2026-11-15).

**계약 문서(06 · design README · UI_GUIDE 의 정한 것)는 이 phase 전에 정해 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 06 의 규칙 문장을 고치지 마라.**

## 작업

### 1. 틀 — `frontend/src/components/`

이름은 예시다(`AppLayout` 등). 지금의 토큰 · `Mogumi` · 포커스 규칙을 쓰고, 새로 필요한 토큰만 더한다.

- **1024px 미만** — 위 바(높이 56px)와 아래에 고정된 다섯 탭 바. 본문이 탭 바에 가리지 않게 아래 여백을 둔다.
- **1024px 이상** — 왼쪽 폭 256px 사이드바(로고 + 다섯 탭)와 위 바. 아래 탭 바는 없다.
- **다섯 탭** — 홈 · 코스 · 로드맵 · 복습 · 내 정보, 이 순서(06 §2.3). 아이콘은 `docs/design/guide.md` §3 의 인라인 SVG 다섯 개를 그대로 쓴다 — 아이콘 라이브러리를 더하지 마라. 지금 탭은 눈에 띄게(색 · 굵기) 표시하고 `aria-current="page"` 를 단다. 탭은 링크다(같은 브라우저 탭 안에서 바뀐다 — 06 §2.3).
- **위 바** — 탭 첫 화면은 로고(모구미 + 「머금」 — guide §1.2), 탭에서 들어간 화면(기다리는 화면 등)은 **뒤로 가기 + 그 화면의 제목**(06 §2.1). 화면이 제목 · 뒤로 가기를 틀에 넘기는 방법을 정한다.
- 키보드로 탭 · 뒤로 가기에 닿고, 포커스 테두리는 공통 규칙(UI_GUIDE §2)을 쓴다. 터치 영역은 최소 44px.

### 2. 주소 — `frontend/src/App.tsx`

- 승인된 사람(`APPROVED`)의 화면만 이 틀 안에 둔다. 신청 전 · 신청 · 거절됨은 지금처럼 계정 화면으로 보낸다 — 지금 `/` 의 상태 분기(`AccountPage`)를 깨지 마라.
- 다섯 탭의 주소를 정한다 — 홈은 `/` 그대로(처음 열리는 탭은 늘 홈 — 06 §2.3). 다섯 모두 자리 화면이다(제목 한 줄 정도 — 지금 `PlaceholderPage` 를 고치거나 대신해도 된다).
- 정한 주소를 UI_GUIDE §4.1 주소 표에 더한다.

### 3. UI_GUIDE

- §6 에서 이 step 이 정한 것(아이콘 세트 · 탭 바 · 사이드바 · 위 바)을 빼고, 새 절에 모양 · 동작 · 상태 · 주소를 적는다(§3 표처럼 컴포넌트 이름 · 경로와 함께). 새 토큰은 §2 표에 더한다.

### 4. 테스트(Vitest + Testing Library — 짝 규칙은 `AGENTS.md` 작업 방식)

- 틀이 다섯 탭을 순서대로 링크로 보이고, 지금 탭에 `aria-current="page"` 가 있다.
- 탭 첫 화면은 로고, 들어간 화면은 뒤로 가기 + 제목을 보인다. 뒤로 가기가 이전 화면으로 간다.
- 승인된 사람은 `/` 에서 틀 안의 홈 자리 화면을, 신청 전 · 신청 · 거절됨은 지금처럼 계정 화면을 본다(기존 `AccountPage` · `App` 테스트가 그대로 통과한다).
- 포커스 · 효과의 결과는 `waitFor` 안에서 단언한다 — `findBy…` 직후에는 `useEffect` 가 아직 돌지 않았을 수 있다(`4-account` 에서 6번 중 1번 실패한 일이 있다).
- 너비 분기(1024px)는 jsdom 이 레이아웃을 계산하지 않으므로 클래스 · 구조로 본다 — 확인한 범위를 summary 에 적는다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 06 §2.1 · §2.3 과 design README 「공통 틀」의 구조(모바일 위 바 + 아래 탭 바, 1024px 이상 사이드바 + 위 바 · 탭 다섯의 순서)가 맞는가?
   - 아이콘이 guide §3 의 SVG 다섯 개이고 라이브러리를 더하지 않았는가?
   - 「06 이 막은 것」(검색창 · 티어 · 사이드바 위젯 · 시스템 정보)이 없는가?
   - 승인 전 화면의 흐름이 그대로인가?
   - 토큰 밖의 색 · 임의 값 클래스가 없는가(UI_GUIDE §2)?
3. 결과에 따라 `phases/6-job-worker/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 틀 컴포넌트 이름 · 경로와 화면이 제목 · 뒤로 가기를 넘기는 방법(step 7 이 쓴다) · 다섯 탭의 주소 · 새 토큰 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 홈의 내용(오늘 복습 · 스트릭 · 이어서 배우기 · 할 일 · 만드는 중 · 새로 배우기 입구)을 만들지 마라. 이유: SCHEDULE §3 상 홈 · 크레딧 TASK 에서 만든다.
- 위 바의 스트릭 위젯을 만들지 마라. 이유: 스트릭은 홈 · 크레딧 TASK 에서 만든다.
- 코스 · 로드맵 · 복습 · 내 정보 탭의 내용을 만들지 마라. 이유: SCHEDULE §3 상 강의 목록 · 복습 · 홈 · 크레딧 TASK 에서 만든다.
- 검색창 · 티어 · 사이드바 위젯(「오늘의 목표」 · 「오늘의 AI 미션」) · 버전 표기를 만들지 마라. 이유: design README 「06 이 막은 것」.
- 아이콘 라이브러리(Material Symbols 등)를 더하지 마라. 이유: guide §3 의 SVG 하나로 맞춘다(design README 「시안을 쓰는 법」).
- 다크 모드 · 「홈 화면에 추가」(PWA)를 넣지 마라. 이유: 06 §2.1 · ADR-064.
- 06 · design README 의 규칙 문장을 고치지 마라. 이유: phase 전에 정했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
