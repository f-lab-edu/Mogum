# Step 2: log-privacy

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 **30**(로그) · 작업 방식(TDD 짝 테스트 규칙)
- `docs/04-jobs-and-cost.md` **§9.2 로그**(특히 「예외」 · 「로그인」 · 「남기지 않는 것」)
- `docs/05-api.md` §1.1 — 콜백 실패 분류(`not_allowed` · `login_failed`) · 로그인 전 요청 쿠키(verifier 를 로그에 남기지 않는다) · §1.2(오류 응답에 원문을 싣지 않는다)
- `docs/ADR.md` ADR-075
- `backend/src/main/java/com/mogum/auth/OAuthLoginHandler.java` · `GoogleAccountMapper.java` · `CookieAuthorizationRequestRepository.java` · `SecurityConfiguration.java`
- `backend/src/main/java/com/mogum/account/service/LoginService.java` · `LoginResult.java`
- `backend/src/main/java/com/mogum/common/api/ApiExceptionHandler.java`
- step 1 산출물: `backend/src/main/java/com/mogum/common/logging/` · 그 로그 캡처 테스트(로그를 기다려 고르는 방법)
- `backend/src/main/resources/application.yml`
- 테스트: `backend/src/test/java/com/mogum/auth/OAuthLoginHandlerIT.java`(가짜 구글 서버 · 로그인 흐름) · `backend/src/test/java/com/mogum/common/api/RequestValidationIT.java` · `backend/src/test/java/com/mogum/account/repository/UserRepositoryIT.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

"남기지 않는 것"(AGENTS 30)을 지키는지 확인하고 막는다. 로그인 결과 줄을 더한다. 계약은 04 §9.2 가 이미 정했다 — **어긋나면 코드를 문서에 맞추고, 문서대로 만들 수 없으면 `blocked` 로 사유를 적는다. 04 §9.2 의 규칙을 임의로 고치지 마라.**

### 1. 로그인 결과 줄 — `auth`

- **성공**: `INFO` 한 줄 + 회원 `users.id`.
- **실패**: 화면으로 보내는 분류(`not_allowed` · `login_failed`)와 원인 분류 한 단어를 `INFO` 또는 `WARN` 한 줄로 남긴다.
    - 사용자 행동(허용 목록 밖 · 이메일 미인증 · 사용자 취소 · 요청 쿠키 없음/손상/만료 · `state` 불일치)은 `INFO`.
    - 구글과의 통신 실패(토큰 교환 · 사용자 정보 조회 실패)는 `WARN`.
    - 원인은 지금 코드가 이미 가진 정보로 가른다 — `LoginResult.RejectionReason`, `OAuth2AuthenticationException` 의 `OAuth2Error.getErrorCode()` 처럼 **프레임워크 · 우리 코드가 주는 분류 코드**만 쓴다. `getDescription()` · 예외 메시지 · URI 는 쓰지 않는다(구글 응답 · 요청 값이 섞일 수 있다).
    - 지금 정보로 가를 수 없는 원인은 하나의 분류(예: `unknown`)로 남긴다. 원인을 가르려고 보안 흐름(검사 순서 · 예외 · 리다이렉트)을 바꾸지 마라.
    - 실제로 만든 원인 분류 목록과 각 수준을 04 §9.2 「로그인」 항목에 적는다.
- **남기지 않는다**: 이메일 · `subject` · `code` · `state` · verifier · 쿠키 · 토큰 · 세션 번호.

### 2. DB 오류 상세 — PgJDBC `logServerErrorDetail=false`

- 중복 키 같은 Postgres 오류는 예외 메시지에 `Detail: Key (…)=(…)` 로 실제 값을 싣고, Hibernate 는 이 메시지를 `ERROR` 로그로 찍는다. PgJDBC 연결 속성 `logServerErrorDetail=false` 로 값을 뺀다(04 §9.2).
- 설정은 `application.yml` 의 데이터소스 속성으로 건다(예: Hikari 의 data source properties). **DB 주소(`spring.datasource.url` · 운영 `DB_URL`)를 바꾸지 않는다** — 운영 주소는 `deploy/compose.yaml` 이 준다.
- Spring Boot 4.1.1 이 관리하는 PgJDBC 버전에 이 속성이 있는지 확인해서 쓴다(`org.postgresql.PGProperty`). 없으면 `blocked` 로 멈추고 버전과 확인한 방법을 적는다.

### 3. 4xx 에서 프레임워크가 찍는 줄

- 아래 4 의 4xx 요청에서 접근 줄 말고 `WARN` · `ERROR` 줄이 나오면, 그 로거를 설정으로 끄거나 처리 경로에서 찍지 않게 한다. 어느 로거를 왜 껐는지 04 §9.2 「예외」 항목에 한 줄로 적는다.
- 처리되지 않은 예외의 기존 `ERROR` + 스택(`ApiExceptionHandler.unexpected`)은 그대로 둔다.

### 4. 테스트 — 표식 문자열 · TDD

짝 규칙: 고친 구현 파일마다 같은 패키지의 짝 테스트(`OAuthLoginHandler` → `OAuthLoginHandlerIT` 등)도 고친다. 새 IT 는 그 테스트가 다루는 구현의 패키지에 둔다.

요청마다 고유한 **표식 문자열**(예: `LEAKCHECK-` + 무작위)을 넣고, **로그 출력 전체**(step 1 의 캡처 방법)에 표식이 없음을 확인한다. 각 경우는 응답 상태도 함께 단언해 그 경로를 실제로 탔는지 보인다.

- 잘못된 JSON 본문(표식이 든 깨진 JSON) → 400
- 입력 검증 위반(표식이 든 값으로 길이 초과) · 타입 불일치(표식이 든 값) → 400 — `RequestValidationIT` 의 테스트 전용 컨트롤러로 만들 수 있는 경로만
- 로그인(가짜 구글 서버 — `OAuthLoginHandlerIT` 방식):
    - 허용 목록 밖 계정(표식 이메일) → `not_allowed` 줄이 있고 이메일 · 표식이 없다, `INFO`
    - `state` 불일치(표식 `state`) · 토큰 교환 실패(표식 `code`) → `login_failed` 줄과 원인 분류가 있고 표식이 없다, 수준이 1 의 규칙과 같다
    - 성공 → 줄에 그 회원 `users.id` 가 있고 이메일 · `subject` 가 없다
- 중복 키(`UserRepositoryIT`) — 같은 `(provider, subject=<표식>)` 회원을 두 번 저장해 unique 위반을 낸다. 예외 메시지 사슬에 제약 이름은 있고 표식은 없으며, 로그 출력에도 표식이 없다.
- 4xx(401 · CSRF 403 · 404 · 405 · 400) 요청마다 접근 줄 말고 `WARN` · `ERROR` 줄이 없다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 로그인 흐름의 동작(리다이렉트 주소 · 쿠키 · 거절 조건 · 세션)이 바뀌지 않았는가 — 기존 `OAuthLoginHandlerIT` 의 단언이 그대로 통과하는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가 — 의존성을 더하지 않았는가?
   - `AGENTS.md` 금지 목록(특히 30) · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/2b-ops-logging/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 로그인 원인 분류 목록, `logServerErrorDetail` 을 건 설정 키와 확인한 PgJDBC 버전, 끈 로거, 표식 테스트의 위치를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 로그인 보안 흐름(검사 순서 · 리다이렉트 주소 · 쿠키 · 거절 조건 · 응답)을 바꾸지 마라. 이유: 로그만 더하는 step 이다.
- 예외 메시지 · `OAuth2Error.getDescription()` · 요청 URI · 이메일을 로그에 넣지 마라. 이유: AGENTS 30 — 구글 응답 · 사용자 값이 섞인다.
- 정규식 등으로 로그를 가리는 변환기 · 레이아웃(마스킹)을 만들지 마라. 이유: AGENTS 30 — 남기지 않는 것이 원칙이고, 가리기는 새 형식을 놓친다. 값이 실리는 경로 자체를 막는다.
- 처리되지 않은 예외의 스택을 지우거나 줄이지 마라. 이유: 원인을 찾을 유일한 기록이다(04 §9.2).
- DB 주소(`spring.datasource.url` · `DB_URL`) · `deploy/` 를 바꾸지 마라. 이유: 운영 주소는 compose 가 주고, Postgres 서버 쪽 설정은 step 3 의 범위다.
- 접근 줄의 모양 · 요청 ID 동작을 바꾸지 마라. 이유: step 1 에서 정했다.
- `src/test/resources/application.yml` 을 만들지 마라. 이유: `src/main/resources/application.yml` 을 통째로 가린다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
