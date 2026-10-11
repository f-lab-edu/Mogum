# Step 5: frontend-skeleton

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — "화면" 줄 · 작업 방식(TDD 가드 — `frontend/src/…/foo.tsx` 의 짝 테스트는 같은 폴더의 `foo.test.tsx` 또는 `__tests__/foo.test.tsx`)
- `docs/UI_GUIDE.md` 전체
- `docs/ARCHITECTURE.md` — 디렉토리 구조의 `frontend/` · 상태 관리 표
- `docs/ADR.md` ADR-007 · ADR-023
- `docs/05-api.md` §1 첫머리의 "경로" 규칙(JSON API 는 `/api` 아래)
- `docs/06-screens.md` §2.3(화면 목록 · 주소)

## 작업

`frontend/` 에 화면 뼈대를 만든다. 실제 화면(06)은 각 TASK 에서 만든다.

### 1. 프로젝트

- Vite + React + TypeScript(`strict: true`)
- 의존성: React Router · TanStack Query · Tailwind CSS · Pretendard(npm 패키지로 **자체 호스팅**) · Vitest · Testing Library · jsdom · ESLint
- `package-lock.json` 을 저장소에 남는 파일로 둔다

### 2. scripts (`frontend/package.json`)

- `dev` · `build` · `test` · `lint`
- **`test` 는 한 번 실행하고 끝나는 모드**(`vitest run`)로 둔다. 리뷰어가 `npm --prefix frontend run test` 를 실행한다 — 감시 모드면 끝나지 않는다.

### 3. Tailwind 테마

- 색 토큰은 UI_GUIDE 의 이름과 값 그대로: `paper` · `surface` · `muted` · `line` · `ink` · `ink-body` · `ink-soft` · `ink-faint` · `accent` · `accent-strong` · `accent-subtle` · `ok` · `warn` · `danger` (`bg-paper` · `text-ink-body` 같은 클래스가 나와야 한다)
- `font-sans` = Pretendard, `font-mono` = JetBrains Mono → 시스템 고정폭
- 설정 위치는 쓰는 Tailwind 버전의 방식을 따른다. UI_GUIDE 첫머리의 "`tailwind.config` 의 테마 토큰으로 둔다" 문구가 실제 방식과 다르면 **그 문구만** 맞춘다

### 4. 앱 틀

- `QueryClientProvider` + 라우터
- 경로 `/` 하나에 자리표시 페이지 — `bg-paper` 위에 「머금」 제목 정도. 06 의 화면 내용은 만들지 않는다

### 5. 개발 서버 proxy

- `/api` · `/auth` 를 `http://localhost:8080` 으로 보낸다(05 §1 경로 규칙)

### 6. 테스트

- `/` 를 렌더하면 자리표시 제목이 보인다 — 라우터와 `QueryClientProvider` 를 거친 실제 앱 틀로 렌더한다

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
npm --prefix frontend run build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 디렉토리 구조(`frontend/src/` 의 `pages/` · `components/` · `api/` · `lib/`)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/0-skeleton/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- react-markdown · KaTeX · mermaid 를 넣지 마라. 이유: `6-material-gen` 범위다.
- 전역 상태 관리 라이브러리(Redux · Zustand 등)를 넣지 마라. 이유: ARCHITECTURE 상태 관리 — 서버 상태는 TanStack Query, 로컬 상태는 `useState`.
- 다크 모드와 UI_GUIDE 의 금지 패턴(blur · 그라데이션 글자 · 보라/인디고 · 글로우 등)을 쓰지 마라. 이유: UI_GUIDE · `docs/01-overview.md` §6.
- 글꼴을 외부 CDN 에서 불러오지 마라. 이유: jar 하나로 배포한다(ADR-007) — 화면이 외부 서버에 기대지 않게 한다.
- Gradle 빌드에 연결하지 마라. 이유: step 6 범위다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라(`./gradlew test` 포함).
