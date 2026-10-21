# Step 6: vercel

## 배경 — 이 phase(`2-deploy`)가 하는 일

지금의 앱을 운영에 올린다(`docs/ADR.md` ADR-060 · ADR-072). 화면(`frontend/`)은 **Vercel** 에 정적 파일로 배포한다 — 화면은 `{도메인}`, API 는 `api.{도메인}`(EC2)이다. Vercel 의 Git 연동이 `main` 병합마다 `frontend/` 를 빌드해 배포한다(설정은 사람이 Vercel 화면에서 한다).

화면은 SPA 라 `/login` · `/curriculums/1` 같은 화면 주소에서 **새로고침**하면 Vercel 이 그 경로의 파일을 찾는다 — 없으면 404 다. 그래서 **정적 파일이 없는 주소는 `index.html` 로** 보내야 한다(01 §10 공개 필수 — 화면 주소 새로고침 확인). 이 step 은 그 설정 파일을 만든다.

이전 step 들은 백엔드 운영 파일(`deploy/` · `.github/workflows/deploy.yml`)과 절차서 `deploy/README.md` 를 만들었다.

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 화면 스택 · 작업 방식
- `docs/ADR.md` — ADR-060(화면은 Vercel · API 는 서브도메인 · **Vercel rewrites 로 API 를 대신 전달하지 않는다**) · ADR-072
- `docs/05-api.md` §1(경로 규칙 — JSON API 는 `/api` 접두사, 화면 주소와 겹치지 않게) · §1.1(화면과 API 의 출처 — `VITE_API_BASE_URL` · PR 미리보기에서는 로그인이 안 됨)
- `docs/06-screens.md` §2.3(화면 주소)
- `frontend/package.json` · `frontend/vite.config.ts` · `frontend/src/api/client.ts`
- `deploy/README.md` — 절차서의 기존 절

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

### 1. `frontend/vercel.json`

- 정적 파일(`index.html` · `assets/…` 등 빌드 결과)이 있는 주소는 그 파일을 준다.
- 정적 파일이 **없는** 주소는 모두 `/index.html` 을 준다(주소창의 경로는 그대로 — 리다이렉트가 아니라 rewrite). React Router 가 그 경로를 그린다.
- 그 밖의 설정은 넣지 않는다 — 빌드 명령 · 출력 디렉터리는 Vercel 의 Vite 감지 기본값을 쓴다(절차서에 값만 적는다).

### 2. 테스트 — `frontend/src/vercelConfig.test.ts`

`vercel.json` 을 읽어(`node:fs`) 확인한다 —
- rewrite 가 하나이고 목적지가 `/index.html` 이다.
- 어떤 rewrite · redirect 의 목적지도 **외부 주소(`http://` · `https://`)가 아니다** — Vercel 이 API 를 대신 전달하는 프록시를 만들지 않는다(ADR-060).
- `/api` · `/auth` 를 다른 곳으로 보내는 규칙이 없다.

### 3. 문서

- `deploy/README.md` — 「Vercel(화면)」 절: 프로젝트 가져오기(이 GitHub 저장소 · 루트 디렉터리 `frontend` · 프레임워크 Vite 기본값 · 빌드 `npm run build` · 출력 `dist`) · 운영 환경 변수 `VITE_API_BASE_URL=https://api.{도메인}`(Production 에만 — 미리보기는 다른 사이트라 어차피 로그인이 안 된다, 05 §1.1) · 도메인 `{도메인}` 연결(DNS 레코드는 Vercel 이 안내하는 값) · `main` 이 운영 브랜치 · **요금제 확인 두 가지** — Hobby 의 비상업 조건(01 §10)과 Hobby 가 **조직 소유 저장소**(`f-lab-edu/Mogum`)의 Git 연동을 허용하는지. 둘 다 확인하지 않았으므로 「첫 배포 때 확인」 목록에 적고, 막히면 화면 배포 방식을 사용자가 다시 정한다고 적는다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

`npm --prefix frontend run build` 도 통과해야 한다(리뷰어는 실행하지 못한다 — Codex 가 확인하고 summary 에 적는다).

## 검증 절차

1. 위 AC 커맨드와 `npm --prefix frontend run build` 를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - ADR-060 — Vercel 이 API 를 대신 전달하지 않는가? 화면은 정적 파일 SPA 그대로인가?
   - `AGENTS.md` 작업 방식을 위반하지 않았는가?
3. 결과에 따라 `phases/2-deploy/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — `vercel.json` 규칙 · 테스트 · test/lint/build 결과를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `/api` · `/auth` 를 API 서버로 넘기는 rewrite · 프록시를 만들지 마라. 이유: ADR-060 — 모든 요청이 Vercel IP 로 보여 IP 당 레이트리밋이 깨지고, Vercel 장애가 API 장애가 된다. 브라우저가 `VITE_API_BASE_URL` 로 API 를 직접 부른다.
- 화면 코드(`frontend/src/` 의 컴포넌트 · `api/client.ts` · `vite.config.ts`)를 고치지 마라. 이유: 출처 분리는 `1-auth` 에서 끝났다 — 이 step 은 배포 설정만 한다.
- Next.js · SSR · Vercel 함수를 들이지 마라. 이유: ADR-060 — CSR SPA 그대로다.
- 실제 도메인을 파일에 적지 마라. 이유: 공개 저장소다 — `{도메인}` 자리표시를 쓴다.
- `.env` 파일을 만들거나 읽지 마라. 이유: `AGENTS.md`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
