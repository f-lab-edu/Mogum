# Step 8: unbundle-frontend

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/ADR.md` ADR-060(화면은 Vercel 에 정적 파일로 — ADR-007 의 jar 하나를 대체)
- `AGENTS.md` — "화면" 줄 · 명령어
- `docs/ARCHITECTURE.md` — 디렉토리 구조의 `build.gradle` · `src/main/resources/` · `frontend/`
- `phases/0-skeleton/step6.md` — 이 step 이 걷어낼 것을 넣은 step
- 이전 step 산출물: `build.gradle` · `src/test/java/com/mogum/FrontendBundleIT.java` · `frontend/package.json` · `frontend/vite.config.ts`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

설계가 바뀌었다. 화면은 jar 에 넣지 않고 Vercel 에 정적 파일로 따로 배포한다(ADR-060). step 6 에서 넣은 "프론트 빌드 결과를 jar 의 `static/` 에 넣기" 를 걷어낸다. 화면 코드 자체는 그대로 둔다.

### 1. `build.gradle`

- step 6 에서 넣은 `npmCi` · `frontendBuild` 태스크, `processResources` 의 `static` 복사, `verifyFrontendBundle` 태스크와 `check` 의 의존을 지운다.
- 결과: `./gradlew test` · `./gradlew build` 가 node · npm 을 부르지 않는다. 만든 jar 에 `static/` 이 없다.
- 나머지 설정(의존성 · 툴체인 · `test` 설정)은 건드리지 않는다.

### 2. `src/test/java/com/mogum/FrontendBundleIT.java`

- 지운다. 이유: 앱이 더는 `GET /` 에 화면을 내려주지 않으므로 검증할 대상이 없다.

### 3. 화면은 npm 으로 따로 통과해야 한다

- `frontend/` 의 빌드 · 테스트 · lint 는 아래 AC 의 npm 명령으로 그대로 통과해야 한다. `frontend/vite.config.ts` 의 개발 proxy(`/api` · `/auth` → `localhost:8080`)는 유지한다 — 개발 때는 화면과 API 를 같은 출처로 쓴다.

### 4. 문서

- `AGENTS.md` 명령어(`./gradlew test` · `./gradlew build` · `npm run build` 설명)와 `docs/ARCHITECTURE.md` 디렉토리 구조는 이미 바뀐 설계로 고쳐 두었다. 작업 뒤 실제 동작과 맞는지 확인하고, 다르면 그 줄만 실제에 맞춘다.

## Acceptance Criteria

```bash
./gradlew build
./gradlew build --dry-run          # 출력에 npmCi · frontendBuild · verifyFrontendBundle 이 없어야 한다
npm --prefix frontend run build
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 패키지 구조(`core` ← `api` · `worker` · `infra`)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/0-skeleton/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `frontend/src` 를 고치지 마라. 이유: API 주소 · `credentials: 'include'` · `XSRF-TOKEN` 헤더를 다루는 fetch 래퍼는 `1-auth` 에서 만든다.
- CORS · Spring Security 설정을 넣지 마라. 이유: 화면과 API 의 출처 분리 설정(docs/05-api.md §1.1)은 `1-auth` 에서 인증과 함께 만든다.
- Vercel 설정 파일(`vercel.json` 등)을 만들지 마라. 이유: 화면 주소 새로고침 처리와 API 주소 환경 변수는 `5-deploy` 에서 배포와 함께 정한다.
- 앱이 `GET /` 나 화면 주소에 무언가를 돌려주게 만들지 마라. 이유: API 서버는 화면을 내려주지 않는다(ADR-060).
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라(`FrontendBundleIT` 삭제는 이 step 의 작업이다).
