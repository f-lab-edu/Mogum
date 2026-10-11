# Step 6: jar-bundle

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — "화면" 줄 · 명령어(`./gradlew build # 프론트 빌드를 static/ 에 넣고 jar 생성`)
- `docs/ARCHITECTURE.md` — 디렉토리 구조의 `build.gradle` · `src/main/resources/static/`
- `docs/ADR.md` ADR-007
- `.gitignore` — `frontend/node_modules/` · `frontend/dist/` · `src/main/resources/static/`
- 이전 step 산출물: `build.gradle` · `frontend/package.json` · `frontend/` 의 빌드 설정

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

`./gradlew build` 하나로 프론트까지 빌드된 jar 를 만든다(같은 사이트 — 쿠키 세션 · CSRF 가 그대로 돈다).

### 1. Gradle 연결

- `frontend/` 의 `npm ci` · `npm run build` 를 Gradle 태스크로 실행하고, 결과(`frontend/dist`)를 jar 의 `static/` 에 넣는다.
- 시스템에 설치된 node · npm 을 쓴다(Node 를 내려받는 플러그인 설정 없이).
- 입력(`frontend/src` · `frontend/index.html` · 빌드 설정 파일 · `package.json` · `package-lock.json`)이 그대로면 다시 실행하지 않는다 — Gradle 입력 · 출력 선언으로 up-to-date 가 되게 한다.
- 빌드 결과는 저장소에 남지 않는다. 복사 위치는 재량이다(`src/main/resources/static/` 은 이미 `.gitignore` 에 있다).

### 2. 검증 — `./gradlew` 커맨드만으로 확인할 수 있어야 한다

리뷰어는 `./gradlew …` 와 `npm --prefix frontend run …` 만 실행할 수 있다.

- 테스트: 애플리케이션이 `GET /` 에 프론트의 `index.html` 을 돌려준다
- 만든 jar 안에 `static/index.html` 이 들어 있다(테스트 또는 `check` 에 연결된 검증 태스크)
- `./gradlew test` 가 프론트 빌드를 거쳐도 된다 — 단 입력이 그대로면 다시 빌드하지 않는다

## Acceptance Criteria

```bash
./gradlew build
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

- 빌드 결과(`static/` · `frontend/dist`)를 저장소에 남기지 마라. 이유: jar 빌드 때 다시 만든다 — 커밋하면 소스와 어긋난다.
- SPA 딥링크(화면 주소 새로고침 시 `index.html` 로 보내기)를 만들지 마라. 이유: `1-auth` 에서 Spring Security 설정 · `/api` 경로와 함께 만든다.
- `frontend/src` 를 고치지 마라. 이유: 이 step 은 빌드 연결만 한다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라(`npm --prefix frontend run test` 포함).
