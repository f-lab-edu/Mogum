# Step 0: error-path

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 21 · 23 · **29**, 작업 방식(TDD 짝 테스트 규칙)
- `docs/05-api.md` §1 「응답 형식」 문단 · **§1.2(에러 — 필드 · `message` · code 를 두는 곳 · 표)** · §1.3
- `docs/ADR.md` ADR-074
- `docs/ARCHITECTURE.md` · `docs/04-jobs-and-cost.md` §2(패키지 표의 `common` 행)
- 지금의 오류 처리: `backend/src/main/java/com/mogum/common/ApiError.java` · `ApiErrorController.java` · `ApiExceptionHandler.java` · `NotFoundException.java`
- `backend/src/main/java/com/mogum/auth/SecurityConfiguration.java` — `writeError` · 인증 진입점(401) · 접근 거부 처리(CSRF 403 · 404) · 미등록 `/auth/{id}` 의 `sendError(404)`
- 테스트: `backend/src/test/java/com/mogum/common/ApiExceptionHandlerTest.java` · `ApiErrorControllerTest.java` · `backend/src/test/java/com/mogum/auth/SecurityConfigurationIT.java`(실제 HTTP 도우미 `http` · `assertError`, `MeController` · `ApiErrorController` spy) · `SecurityConfigurationCrossOriginIT.java` · `backend/src/test/java/com/mogum/support/TestSessions.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

이 phase(`2a-api-response`)는 API 응답 · 오류 · 입력 검증의 공통 규약을 도메인 API 가 생기기 전에 정한다. 이 step 은 **오류 응답을 만드는 경로를 하나로** 합친다. 지금은 세 곳이 따로 만든다 — MVC 예외(`ApiExceptionHandler`) · 컨테이너 ERROR 디스패치(`ApiErrorController`) · Spring Security(`SecurityConfiguration.writeError` 가 code 문자열과 JSON 을 직접 쓴다). 도메인 예외는 `NotFoundException` 하나라 다른 code 를 실을 수 없다. 성공 응답 감싸기는 step 1, 입력 검증은 step 2, 화면은 step 3 이다.

**응답 모양은 바꾸지 않는다.** 이 step 뒤에도 지금의 모든 오류 응답(상태 · `code` · `message` · 헤더)이 바이트 단위까지는 아니어도 JSON 으로 같아야 한다. 기존 테스트가 그대로 통과하는 것이 그 확인이다.

### 1. `common.ErrorCode` — code 를 두는 한 곳

```java
public enum ErrorCode {
    UNAUTHORIZED, CSRF_INVALID, NOT_FOUND, METHOD_NOT_ALLOWED, INVALID_REQUEST, INTERNAL_ERROR;
    // 각 상수: HTTP 상태 · 기본 message. 응답의 code 문자열은 상수 이름 그대로
}
```

| 상수 | HTTP | 기본 message(지금 문구 그대로) |
|---|---|---|
| `UNAUTHORIZED` | 401 | 로그인이 필요합니다 |
| `CSRF_INVALID` | 403 | 요청을 확인할 수 없습니다 |
| `NOT_FOUND` | 404 | 찾을 수 없습니다 |
| `METHOD_NOT_ALLOWED` | 405 | 지원하지 않는 요청 방식입니다 |
| `INVALID_REQUEST` | 400 | 요청을 확인할 수 없습니다 |
| `INTERNAL_ERROR` | 500 | 요청을 처리할 수 없습니다 |

- 이 여섯 개만 둔다. 05 §1.2 표의 다른 code 는 그 code 를 처음 만드는 TASK 가 더한다(`STRUCTURAL_LIMIT` 는 step 2).
- `INVALID_REQUEST` · `INTERNAL_ERROR` 의 상태는 `ApiException` 으로 던질 때의 상태다. **MVC 표준 예외 · 컨테이너 오류는 원래 HTTP 상태를 유지한다**(05 §1.2 — 415 는 `415 INVALID_REQUEST`, 503 은 `503 INTERNAL_ERROR`). 지금 `ApiError.forStatus` 의 상태 → code 규칙(404 → `NOT_FOUND` · 405 → `METHOD_NOT_ALLOWED` · 5xx → `INTERNAL_ERROR` · 나머지 4xx → `INVALID_REQUEST`)을 그대로 쓴다.

### 2. `common.ApiException` — 도메인 오류를 던지는 한 가지 방법

- `RuntimeException` 하위. `ErrorCode` 를 담고, **선택적으로 `retryAfterSeconds`(양의 정수 초)** 를 담는다. 생성자 · 정적 팩토리 모양은 재량.
- 예외 메시지(`getMessage()`)는 응답에 쓰지 않는다 — 응답 `message` 는 `ErrorCode` 의 기본 문구다. 이유: 05 §1.2 — 예외 메시지 · 요청 원문을 응답에 싣지 않는다.
- **`NotFoundException` 은 지우고 `new ApiException(ErrorCode.NOT_FOUND)`(또는 같은 뜻의 팩토리)로 바꾼다.** 지금 쓰는 곳은 `ApiExceptionHandlerTest` 하나다. 이유: 404 를 내는 방법이 둘이면 뒤 TASK 가 어느 쪽을 쓸지 갈린다.

### 3. `common.ApiError` — 오류 본문

- 필드: `code`(문자열) · `message` · `retryAfterSeconds`(없으면 `null`). **값이 없는 선택 필드는 JSON 에 키를 싣지 않는다**(05 §1.2 — `{"code":"NOT_FOUND","message":"찾을 수 없습니다"}` 에 `"retryAfterSeconds": null` 이 붙으면 안 된다). `fields` 는 step 2 가 더한다 — 이 step 에서 만들지 않는다.
- 오류 본문은 성공 응답처럼 감싸지 않는다(05 §1).

### 4. 오류 응답을 쓰는 한 경로

`ErrorCode`(또는 표준 오류의 원래 상태) + 선택적 `retryAfterSeconds` → HTTP 응답(상태 · `Content-Type: application/json` · `Retry-After` 헤더 · `ApiError` 본문)으로 바꾸는 일을 `common` 의 한 곳에 둔다. 클래스 이름 · 모양은 재량이다(예: `ApiErrorResponses` 의 `ResponseEntity` 를 만드는 메서드 + 서블릿 응답에 쓰는 메서드).

- **`retryAfterSeconds` 가 있으면 같은 값(초, 정수)을 `Retry-After` 헤더에도 싣는다.** 없으면 헤더도 없다.
- `ApiExceptionHandler` — `@ExceptionHandler(ApiException.class)` 를 이 경로로 처리한다. MVC 표준 예외(`createResponseEntity`)는 지금처럼 원래 상태와 헤더(405 의 `Allow` 등)를 유지한다. 처리되지 않은 예외는 지금처럼 `500 INTERNAL_ERROR` 와 기존 error 로그 한 줄이다.
- `ApiErrorController`(ERROR 디스패치) — 지금처럼 컨테이너 상태만 읽고 이 경로로 쓴다. 상태 속성이 없으면 500 이다.
- `SecurityConfiguration` — 401 진입점 · CSRF 403 · 로그인한 사용자의 거부 404 가 **`ErrorCode` 로 이 경로를 쓴다.** `writeError` 와 그 안의 code · message 문자열 리터럴을 지운다. 직렬화에는 지금처럼 Spring 이 관리하는 `ObjectMapper` 빈을 쓴다(직접 `new` 하지 않는다 — 이유: MVC 가 쓰는 직렬화 설정과 어긋난다). 미등록 `/auth/{id}` 의 `response.sendError(404)` 는 그대로 둔다 — ERROR 디스패치가 이 경로로 응답한다. `SessionUserFilter` 는 삭제된 회원의 세션에서 이 401 진입점을 부르므로 진입점만 바꾸면 함께 맞는다(필터에 오류 JSON 을 따로 쓰지 않는다).
- 상태 · code · 문구 · 헤더 · 로그인 리다이렉트 없음(`Location` 없음) 같은 **지금의 보안 응답 동작은 바꾸지 않는다.**

### 5. 테스트 — TDD

TDD 가드: 새 구현 파일은 같은 패키지에 `{클래스명}*Test.java` 또는 `{클래스명}*IT.java` 가 먼저 있어야 쓰기가 허용된다(enum · record · 예외는 제외). **고치는 구현 파일(`ApiExceptionHandler` · `ApiErrorController` · `SecurityConfiguration`)마다 그 짝 테스트도 이 step 에서 고친다** — 구현만 바꾸면 가드가 되돌린다.

- 단위 — `ApiException(NOT_FOUND)` → `404` · `{"code":"NOT_FOUND","message":"찾을 수 없습니다"}`(키 두 개뿐, `STRICT` 비교). `retryAfterSeconds` 를 담은 `ApiException` → 본문 `retryAfterSeconds` 와 `Retry-After` 헤더가 같은 값. 없으면 헤더 · 키 모두 없음. 표준 예외의 원래 상태 유지(405 `Allow` · 415 → `415 INVALID_REQUEST`). 새 경로 클래스를 만들면 그 짝 테스트.
- **실제 HTTP(`RANDOM_PORT`)** — 이유: MockMvc 는 서블릿 ERROR 디스패치와 실제 보안 필터 · 컨테이너 동작을 거치지 않는다(1-auth step 1 에서 `/error` 가 막힌 것을 MockMvc 가 놓쳤다). `SecurityConfigurationIT` 의 `http` · `assertError` 도우미와 `MeController` spy 를 써서:
  - spy 가 `retryAfterSeconds` 를 담은 `ApiException` 을 던지면 실제 응답의 상태 · `Retry-After` 헤더 · 본문(`STRICT`)이 맞다. 지금 enum 에는 `Retry-After` 를 쓰는 code 가 없으니 테스트는 아무 code 에나 붙여 장치만 확인한다 — 운영 코드는 05 §1.2 표에서 `Retry-After` 가 적힌 code 에만 쓴다.
  - 401 · `CSRF_INVALID` 403 · 로그인한 사용자의 없는 경로 404 · 400 · 405 · 500 본문이 각각 `{code, message}` 두 키뿐이다(`STRICT`). 기존 IT 가 code 만 보는 곳이 있으면 본문 전체를 보게 고친다.
- 기존 `SecurityConfigurationIT` · `SecurityConfigurationCrossOriginIT` · `OAuthLoginHandlerIT` 가 그대로 통과한다.
- 테스트 DB 는 JVM 에서 공유된다 — 사용자 · 세션 값이 다른 테스트와 겹치지 않게 한다.

### 6. 문서

- `docs/ARCHITECTURE.md` 의 `common/` 줄 · `docs/04-jobs-and-cost.md` §2 패키지 표의 `common` 행 — `NotFoundException` 을 지우고 이 step 의 클래스(`ErrorCode` · `ApiException` · `ApiError` · 오류 응답 경로 클래스 · `ApiExceptionHandler` · `ApiErrorController`)로 고친다.
- `docs/ADR.md` ADR-074 의 **검증** 줄 — "아직 수행하지 않았다" 뒤에 이 step 이 확인한 것을 한 줄로 더한다(날짜 · 무엇을 실제 HTTP 로 확인했는지 · 서버 테스트 수). 다른 step 이 이어 쓰므로 "아직 수행하지 않았다(`2a-api-response`)" 문구는 지우지 말고 "백엔드 오류 경로: …" 처럼 항목으로 더한다.
- 05 §1.2 의 계약과 구현이 어긋나게 되면 코드를 계약에 맞춘다. 계약대로 만들 수 없으면 `blocked` 로 사유를 적는다 — 05 를 임의로 고치지 마라.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 패키지 구조(도메인별 패키지 · `common` 은 평평하게 · `generation` 은 `worker` · `config` 만 참조)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가?
   - `AGENTS.md` 금지 목록(특히 29) · 작업 방식(TDD)을 위반하지 않았는가?
   - 보안 설정 · 핸들러에 code · message 문자열 리터럴이나 직접 만든 `ApiError` 가 남아 있지 않은가?
3. 결과에 따라 `phases/2a-api-response/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — `ErrorCode` · `ApiException`(retryAfter 를 담는 방법) · 오류 응답 경로 클래스 이름과 그 메서드 · 보안 핸들러가 쓰는 방법을 넣는다(step 1 · 2 가 이어서 쓴다)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 05 §1.2 표의 다른 code(`STRUCTURAL_LIMIT` · `RATE_LIMITED` · `TERMS_REQUIRED` …)를 `ErrorCode` 에 넣지 마라. 이유: enum 에는 구현된 code 만 둔다(05 §1.2) — 그 code 를 처음 만드는 TASK 가 더한다.
- 성공 응답 감싸기(`ApiResponse`) · Bean Validation · `fields` 를 만들지 마라. 이유: step 1 · step 2 의 범위다.
- 지금의 오류 상태 · code · message 문구 · 헤더를 바꾸지 마라. 이유: 운영 중인 `1-auth` 계약이고 화면이 이 값을 읽는다.
- `ApiException` 처리에 로그를 더하거나 로그 수준 · 문구를 바꾸지 마라. 처리되지 않은 예외의 기존 error 로그는 유지한다. 이유: 로그 규칙은 다음 TASK `2b-ops-logging` 에서 정한다.
- 응답에 예외 메시지 · 스택 · 요청 원문을 싣지 마라. 이유: 05 §1.2.
- 테스트 전용 `@RestController` · `@Component` · `@Entity` 를 `src/test/java/com/mogum` 아래 최상위 클래스로 만들지 마라. 이유: `@SpringBootTest` 의 스캔 범위라 모든 컨텍스트 테스트에 섞인다(HARNESS-BACKLOG S3). 이 step 은 기존 `MeController` spy 로 충분하다.
- `src/test/resources/application.yml` 을 만들지 마라. 이유: `src/main/resources/application.yml` 을 통째로 가린다.
- `frontend/` 를 고치지 마라. 이유: step 3 의 범위다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
