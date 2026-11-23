# Step 11: sonar-scroll-tabindex

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/UI_GUIDE.md` §12(강의 자료 본문 — 가로 스크롤 규칙)
- `AGENTS.md` — 작업 방식(TDD — `frontend/src/…/foo.tsx` 의 짝은 같은 폴더의 `foo.test.tsx`)
- 대상: `frontend/src/components/MaterialBody.tsx` · `frontend/src/components/MaterialBody.test.tsx`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

PR #36 의 SonarCloud 품질 게이트가 신뢰성 등급 C 로 실패했다. 화면 이슈는 `typescript:S6845`(MEDIUM) 셋이다 — `MaterialBody.tsx:62 · 71 · 73` 의 그림 · 코드 블록 · 표 스크롤 영역에 단 `tabIndex={0}`(「상호작용 요소가 아닌 곳에 tabIndex 를 두지 마라」). 키보드로 가로 스크롤하려고 둔 것이지만, 사용자가 **`tabIndex` 를 지우기로** 정했다(2026-11-24) — 모바일 우선 화면이라 키보드로 블록을 가로 스크롤하는 경우가 드물고, 규칙을 코드로 통과시켜 다음 PR 에서 다시 걸리지 않게 한다.

## 작업

- 세 곳의 `tabIndex={0}` 을 지운다. `role="region"` · `aria-label` 은 그대로 둔다 — 화면 읽기 프로그램이 영역 이름을 읽는다.
- 포커스를 받지 않게 되므로 그 영역의 포커스 테두리 클래스(`focus-visible:…`)가 쓸모없어지면 함께 지운다. 가로 스크롤(`overflow-x-auto` · `min-w-0` · `max-w-full`)은 그대로다.
- 시험에서 이 영역이 포커스를 받는다고 단언하던 곳이 있으면 「영역 이름은 있고 `tabindex` 는 없다」로 바꾼다. 시험을 먼저 고친다.
- `docs/UI_GUIDE.md` §12 의 「키보드 포커스를 받는다」 문장을 지금 사실로 고친다 — 「`role="region"` 과 이름만 두고 `tabIndex` 는 두지 않는다(SonarCloud `typescript:S6845` · 사용자 결정 2026-11-24)」.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `MaterialBody.tsx` 에 `tabIndex` 가 남지 않았는가? 가로 스크롤 · 영역 이름 · 렌더러 보안 설정(원본 HTML 없음 · Mermaid `strict` · KaTeX `trust` 끔)이 그대로인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/8-material-cards/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 다른 화면 · 컴포넌트를 고치지 마라. 이유: 이 step 은 Sonar 이슈 세 줄만 고친다.
- eslint · Sonar 억제 주석으로 피하지 마라. 이유: 지우기로 정했다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
