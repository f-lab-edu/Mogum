# Step 5: markdown-renderer

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/03-generation.md` §3(표현 수단 — 마크다운 · 코드 · Mermaid `strict` · 수치 그래프 · KaTeX · 이미지 금지)
- `docs/ADR.md` — ADR-022 · ADR-023 · ADR-063 · ADR-117
- `docs/06-screens.md` §2.1 · §9 · §15.3(「작은 화면 · 큰 화면에서 긴 글 · 코드 · 다이어그램」)
- `docs/UI_GUIDE.md` 전체 — 토큰 · 공통 컴포넌트
- `docs/design/README.md` 「시안을 쓰는 법」 · `docs/design/screenshots/12-lecture-mobile.png` · `12-lecture-desktop.png` · `docs/design/html/12-lecture.html`(본문 영역의 글 · 코드 블록 · 표 · 인용 모양만 참고)
- `AGENTS.md` — 스택의 「화면」 줄(react-markdown + KaTeX + mermaid) · 작업 방식(TDD — `frontend/src/…/foo.tsx` 의 짝은 같은 폴더의 `foo.test.tsx`)
- `frontend/package.json` · `frontend/src/components/` · `frontend/src/index.css`(또는 토큰을 둔 곳) · `frontend/vite.config.ts`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

강의 자료 본문(마크다운)을 그리는 **화면 컴포넌트 하나**를 만든다. 강의 화면(step 6)이 이것을 쓴다. 본문은 LLM 이 만든 글이라 믿지 않는 입력으로 다룬다.

## 작업

### 1. 의존성

- `react-markdown` 과 표를 위한 GFM 플러그인, 수식을 위한 math 플러그인 + KaTeX, `mermaid`, 코드 문법 강조 라이브러리 하나를 더한다. 버전은 지금 안정판을 고르고 `package.json` 의 기존 표기(`^`)를 따른다. 문법 강조는 가벼운 것을 고르고, 고른 이유를 summary 에 적는다.
- `AGENTS.md` 의 스택 「화면」 줄을 실제로 더한 라이브러리에 맞춘다(그 줄만).

### 2. 컴포넌트 — `frontend/src/components/` 에 하나(이름은 맞게 — 예: `MaterialBody.tsx`)

- 받는 것 — 마크다운 글 하나.
- 마크다운 — 제목 · 목록 · 강조 · 표 · 인용 · 코드 블록(언어 표시가 있으면 문법 강조) · 인라인 코드 · 링크.
- KaTeX — 인라인 · 블록 수식. **`trust` 를 켜지 마라**. 수식 오류는 KaTeX 의 오류 표시를 그대로 보인다(`throwOnError: false` 등 — ADR-023).
- Mermaid — 언어가 `mermaid` 인 코드 블록을 그림으로. **`securityLevel: 'strict'`** · `startOnLoad: false`. 그리기 실패는 그 자리에 Mermaid 의 오류 표시(또는 오류 메시지)를 그대로 보이고 나머지 본문은 그대로 그린다 — 고치거나 다시 시도하지 않는다(ADR-023). **Mermaid 는 동적 `import()` 로 그 블록이 있을 때만 불러온다** — 강의 화면 첫 번들에 넣지 않는다. 같은 화면에 그림이 여럿이어도 id 가 겹치지 않게 한다.
- **원본 HTML 을 그리지 마라** — `rehype-raw` 등을 쓰지 않는다. 본문 안의 `<script>` · `<img onerror>` 는 글로 보이거나 사라져야 한다. 이미지(`![]()`)는 그리지 않는다(ADR-022) — 그리지 않는 방법을 정한다(예: 대체 글만).
- 링크는 새 탭으로 열고 `rel="noopener noreferrer"`. `javascript:` 주소는 그리지 않는다(react-markdown 기본 처리 확인).
- 모바일 우선 — **긴 코드 · 표 · 그림 · 블록 수식은 그 블록 안에서 가로로 스크롤**하고 화면 전체가 가로로 넘치지 않는다(390px). 글 크기 · 줄 간격 · 블록 간격 · 코드 블록 배경은 UI_GUIDE 토큰으로 — 시안의 임의 값(`text-[11px]` 등)을 옮기지 마라.
- 다크 모드 테마를 더하지 마라(ADR-064).

### 3. 시험 — `frontend/src/components/{이름}.test.tsx`(Vitest + Testing Library)

- 제목 · 목록 · 표 · 코드 블록(문법 강조 표식) · 인라인/블록 수식(KaTeX 결과 요소)이 그려진다.
- `mermaid` 블록이 있으면 Mermaid 를 불러 `strict` 로 그리고, 없으면 불러오지 않는다 — jsdom 은 Mermaid 를 실제로 그리지 못하므로 모듈을 가짜로 바꿔 확인한다. 그리기 실패 때 오류 표시가 보이고 나머지 본문이 남는다.
- `<script>` · `<img src=x onerror=…>` · `javascript:` 링크 · 마크다운 이미지가 실행 · 표시되지 않는다.
- 효과의 결과는 `waitFor` 안에서 단언한다(`findBy…` 직후 단언하면 가끔 실패한다).

### 4. 문서 — 구현으로 정해진 사실만

- `docs/UI_GUIDE.md` — 「강의 자료 본문」 절을 더한다: 컴포넌트 이름 · 라이브러리 · 보안 설정(원본 HTML 없음 · Mermaid `strict` · KaTeX `trust` 끔) · 가로 스크롤 규칙 · 글 크기 토큰.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 원본 HTML 을 그리지 않는가? Mermaid `strict` · KaTeX `trust` 끔인가? 이미지를 그리지 않는가?
   - Mermaid 가 동적 import 로만 들어오는가?
   - 깨진 그림 · 수식이 라이브러리의 오류 표시로 남고 나머지 본문이 그려지는가?
   - 긴 블록이 블록 안에서만 가로 스크롤하는가? 값이 토큰인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/8-material-cards/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 컴포넌트 경로 · props · 더한 라이브러리와 버전 · 시험에서 Mermaid 를 가짜로 바꾸는 방법(step 6 이 쓴다) · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 강의 화면 · 라우트 · API 호출을 만들지 마라. 이유: step 6 의 범위다.
- 서버에서 Mermaid · KaTeX 를 검사하거나 그리지 마라. 이유: ADR-117 — 서버 곁에 Node 프로세스를 두지 않는다.
- 깨진 그림 · 수식을 고치거나 다시 만들기 · 신고 버튼을 만들지 마라. 이유: ADR-023 · ADR-117.
- 시안의 「예상 소요 시간」 · 파일 이름 · 버전 표시를 만들지 마라. 이유: 06 §9 · ADR-089(design README 「06 이 막은 것」).
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
