# Step 1: request-log

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 **25**(프로세스 메모리 공유 상태) · 29 · **30**(로그) · 작업 방식(TDD 짝 테스트 규칙)
- `docs/04-jobs-and-cost.md` §2(`common.logging`) · §9 · **§9.2 로그**
- `docs/05-api.md` §1 「응답 형식」의 **요청 ID** 항목 · §1.1 의 CORS 줄(노출 헤더에 넣지 않는다)
- `docs/ADR.md` ADR-075
- step 0 에서 옮긴 코드: `backend/src/main/java/com/mogum/common/api/`(특히 `ApiExceptionHandler.unexpected` 의 기존 `ERROR` 로그 · `ApiErrorController`)
- `backend/src/main/java/com/mogum/auth/SecurityConfiguration.java` · `SessionUserFilter.java`
- `backend/src/main/resources/application.yml`
- 테스트 지원: `backend/src/test/java/com/mogum/auth/SecurityConfigurationIT.java`(실제 HTTP · 세션 · CSRF 토큰 다루는 법) · `backend/src/test/java/com/mogum/common/api/RequestValidationIT.java`(테스트 전용 중첩 컨트롤러 + `@Import`) · `backend/src/test/java/com/mogum/support/TestSessions.java` · `PostgresTestConfiguration.java` · `backend/src/test/java/com/mogum/auth/SessionUserFilterTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

요청 ID · 접근 줄 · 로그 줄 모양을 만든다. 계약은 04 §9.2 · 05 §1 이 이미 정했다 — **어긋나면 코드를 문서에 맞추고, 문서대로 만들 수 없으면 `blocked` 로 사유를 적는다. 04 §9.2 · 05 §1 의 규칙을 임의로 고치지 마라.**

### 1. 요청 필터 — `com.mogum.common.logging`

클래스 이름은 정한다(예: `RequestLogFilter`). 요구:

- **모든 필터의 가장 바깥에서 돈다** — Spring Session 의 세션 필터와 Spring Security 필터 체인보다 먼저. 서블릿 필터 순서로 보장하고 테스트로 확인한다. 이유: 401 · 403 처럼 보안 필터가 끝내는 요청도 접근 줄과 요청 ID 를 가져야 한다.
- 요청마다 **서버가 무작위 요청 ID 를 만든다.** 들어온 `X-Request-Id` 헤더는 쓰지 않는다. 형식은 정하고 04 §9.2 에 한 줄로 적는다 — 화면 · 계약은 형식에 기대지 않는다(05 §1).
- 요청 ID 를 MDC `requestId` 에 넣고 **응답 헤더 `X-Request-Id`** 에 싣는다. 컨테이너 ERROR 디스패치를 거친 응답(`sendError` 등)에도 **같은 값의 헤더**가 있어야 하고, ERROR 디스패치 동안 찍힌 로그 줄에도 같은 `requestId` 가 있어야 한다.
- 요청이 끝나면 **`INFO` 접근 줄 정확히 한 줄**: 메서드 · 쿼리를 뺀 경로 · 클라이언트가 받은 최종 상태 · 걸린 시간(ms).
    - ERROR 디스패치를 거쳐도 한 줄이다 — ERROR 디스패치에서 두 번째 줄을 찍지 않는다.
    - 필터 안쪽에서 예외가 밖으로 나가면 상태 500 으로 한 줄 찍고 예외는 그대로 던진다.
- `GET /actuator/health` 는 접근 줄을 남기지 않는다(헤더는 싣는다).
- **요청이 끝나면(ERROR 디스패치 포함) 이 요청이 넣은 MDC 값(`requestId` · `userId`)이 스레드에 남지 않는다.** 이유: Tomcat 은 스레드를 재사용한다 — 남으면 다음 요청 줄에 앞 회원의 ID 가 붙는다(AGENTS 30).
- 요청 수 · 마지막 요청 같은 상태를 필드 · 정적 변수에 두지 마라(AGENTS 25).

### 2. `userId` — `auth.SessionUserFilter`

- 세션에서 회원을 읽어 인증을 만든 뒤 MDC `userId` 에 `users.id` 를 넣는다. 지우는 것은 1 의 필터가 요청 끝에서 한다 — 지우는 곳을 한 곳에 둔다.
- MDC 키 이름(`requestId` · `userId`)은 `common.logging` 의 상수 한 곳에 둔다 — 문자열을 흩뿌리지 않는다. 상수만 담는 클래스를 따로 만들면 TDD 가드가 짝 테스트를 요구한다(interface · record · enum 은 제외) — 필터 클래스의 상수로 두어도 된다.

### 3. 로그 줄 모양 — `application.yml`

- **줄마다 MDC 값을 모두 `키=값` 으로 싣는다** — 키를 이름으로 나열하지 않는다(예: Logback `%X`). 이유: `4-job-queue` 가 `jobId` · `step` 을 더해도 패턴을 고치지 않게 한다(04 §9.2). MDC 가 비어 있으면 줄이 깨지지 않게 둔다.
- **시각은 한국 시간 + 오프셋(`+09:00`)** 이다 — JVM · 컨테이너의 기본 시간대(운영은 UTC)에 기대지 않는다.
- Boot 의 기본 콘솔 패턴을 통째로 다시 쓰기보다 Boot 가 제공하는 설정 키로 바꿀 수 있으면 그것을 쓴다. 실제 키 이름과 기본 패턴은 Spring Boot 4.1.1 의 기본 Logback 설정(`spring-boot` jar 의 `org/springframework/boot/logging/logback/defaults.xml` 등)에서 확인해서 쓴다 — 확인한 근거를 04 §9.2 에 한 줄로 남긴다.

### 4. 테스트 — TDD · 실제 HTTP

짝 규칙: 필터는 `backend/src/test/java/com/mogum/common/logging/{필터 이름}Test.java` 또는 `…IT.java`, `SessionUserFilter` 를 고치면 `SessionUserFilterTest` 도 고친다(구현만 바꾸면 TDD 가드가 되돌린다).

**실제 HTTP(`RANDOM_PORT`) + 로그 출력 캡처**(Boot `OutputCaptureExtension` 등)로 확인한다. 이유: MockMvc 는 서블릿 ERROR 디스패치와 실제 필터 순서를 거치지 않는다(HARNESS-BACKLOG S6).

- 아래 요청마다 **접근 줄이 정확히 하나**이고, 줄의 상태가 받은 상태와 같고, 줄의 `requestId` 와 응답 `X-Request-Id` 가 같다:
    - 비로그인 `GET /api/me` → 401
    - 로그인 `GET /api/me` → 200
    - 로그인했지만 CSRF 헤더 없는 `POST /api/auth/logout` → 403
    - 로그인한 `/api/` 아래 없는 경로 → 404
    - 지원하지 않는 메서드 → 405(기존 405 테스트처럼 인증 · CSRF 를 거친 요청으로)
    - 처리되지 않은 예외 → 500 — 테스트 전용 컨트롤러는 `RequestValidationIT` 처럼 **그 IT 의 중첩 static `@RestController` + `@Import`** 로 둔다(최상위 클래스로 두면 모든 `@SpringBootTest` 스캔에 들어간다 — S3). 규약대로 `ApiResponse` 를 반환하는 모양으로 만든다.
    - 등록되지 않은 `GET /auth/unknown` → `sendError` 를 거친 404(ERROR 디스패치)
- 500 요청의 `ERROR` 스택 줄에도 같은 `requestId` 가 있다.
- 로그인한 요청의 줄에 `userId=<그 회원 id>` 가 있고, 비로그인 요청의 줄에는 `userId` 가 없다. **로그인 요청 뒤 같은 서버에 비로그인 요청을 여러 번 보내** 어느 줄에도 앞 회원의 `userId` 가 붙지 않는다.
- 요청에 `X-Request-Id: <표식>` 을 실어 보내도 응답 헤더 · 줄의 값이 표식이 아니다.
- `GET /auth/google/callback?code=<표식>&state=<표식>` 의 접근 줄과 출력 어디에도 표식이 없다(쿼리 제외).
- `GET /actuator/health` 는 접근 줄이 없고 `X-Request-Id` 헤더는 있다.
- 줄의 시각이 `+09:00` 이다.
- 필터가 Spring Session · Spring Security 필터보다 앞에 등록된다.
- `SessionUserFilterTest` — 회원을 읽으면 MDC `userId` 가 들어간다. 세션 회원이 없어 401 로 끝나는 경로에는 넣지 않는다.

**흔들리지 않게** — 접근 줄은 서버 스레드가 응답을 보낸 뒤 찍힐 수 있다. 응답을 받자마자 단언하지 말고, 그 요청의 줄이 나타날 때까지 상한 있는 짧은 대기로 기다린다. 캡처에는 다른 요청 · 다른 테스트의 줄이 섞이므로 요청마다 응답 `X-Request-Id` 로 그 요청의 줄을 골라 단언한다. 테스트 DB 는 JVM 에서 공유된다 — 회원 · 세션 값이 다른 테스트와 겹치지 않게 한다.

### 5. 문서

- `docs/04-jobs-and-cost.md` §9.2 — 요청 ID 형식 한 줄, 필터 · MDC 키 상수의 클래스 이름, 실제 접근 줄 예시 한 줄(값은 꾸민 값 — 실제 회원 정보를 쓰지 않는다), 3 의 설정 근거 한 줄. 이미 있는 규칙 문장은 고치지 않는다.
- `docs/04-jobs-and-cost.md` §2 패키지 표의 `common.logging` 설명에 클래스 이름을 넣는다.
- `docs/ARCHITECTURE.md` 트리의 `logging/` 줄에 클래스 이름을 넣는다.
- `docs/ADR.md` ADR-075 의 **검증** 줄 — "아직 수행하지 않았다" 문장은 두고, 뒤에 이 step 이 실제 HTTP 로 확인한 것과 서버 테스트 수를 한 항목으로 더한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 새 클래스가 `common.logging` 에 있고 `common` 바로 아래에 클래스가 없는가(04 §2)?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가 — 의존성을 더하지 않았는가?
   - `AGENTS.md` 금지 목록(특히 25 · 30) · 작업 방식(TDD)을 위반하지 않았는가?
   - 접근 줄 · 출력에 요청/응답 본문 · 헤더 · 쿠키 · 쿼리가 없는가?
3. 결과에 따라 `phases/2b-ops-logging/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 필터 · MDC 키 상수의 클래스 이름, 필터 순서 값, 요청 ID 형식, 로그 패턴을 바꾼 설정 키, 로그 캡처 테스트의 위치와 기다리는 방법을 넣는다(다음 step 이 같은 방법으로 로그를 검사한다)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 요청 · 응답 본문, 헤더, 쿠키, 쿼리를 로그에 남기지 마라. 이유: AGENTS 30 — OAuth 콜백의 `code` · `state` 가 쿼리로 온다.
- 접근 줄에 오류 `code` 를 싣지 마라. 이유: 04 §9.2 · ADR-075 — 필요해지면 `10` 이 더한다.
- 로그를 JSON(구조화 로그)으로 바꾸지 마라. 이유: ADR-075 — 사람이 SSM 에서 읽는 평문으로 정했다.
- Tomcat 접근 로그(`server.tomcat.accesslog.*`) · Logback-access 를 켜지 마라. 이유: 쿼리를 그대로 싣고 `requestId` · `userId` 를 모른다 — 접근 줄은 이 필터 한 곳이다(AGENTS 30).
- `X-Request-Id` 를 CORS 노출 헤더(`Access-Control-Expose-Headers`)에 넣지 마라. 이유: 05 §1 — 화면 스크립트는 읽지 않는다.
- 요청 ID 를 오류 응답 본문에 싣지 마라. 이유: 05 §1.2 의 오류 형식은 그대로다.
- 로그인 성공 · 실패 로그, 4xx 에서 프레임워크가 찍는 `WARN` 정리, DB 설정은 하지 마라. 이유: step 2 의 범위다.
- `micrometer-tracing` 등 트레이싱 · 새 의존성을 더하지 마라. 이유: 01 §6 — 분산 트레이싱은 넣지 않는다.
- `src/test/resources/application.yml` 을 만들지 마라. 이유: `src/main/resources/application.yml` 을 통째로 가린다.
- `deploy/` · `frontend/` 를 고치지 마라. 이유: `deploy/` 는 step 3, 화면은 이 phase 범위 밖이다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
