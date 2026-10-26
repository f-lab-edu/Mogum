# Step 2: request-validation

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 스택 줄 · 금지 목록 **29**, 작업 방식(TDD 짝 테스트 규칙)
- `docs/05-api.md` §1 「응답 형식」 · **§1.2 의 「필드」 · 「입력 검증」 항목과 표의 `INVALID_REQUEST` · `STRUCTURAL_LIMIT` 행**
- `docs/02-domain-data.md` §6.2 의 "입력 길이" 줄(주제 100 · 목적 200 · 피드백 500자 — 실제 DTO 는 이 phase 에서 만들지 않는다)
- `docs/06-screens.md` §15.1 의 `STRUCTURAL_LIMIT` 행(화면이 `fields` 로 칸을 고른다)
- `docs/ADR.md` ADR-074
- step 0 · 1 산출물: `backend/src/main/java/com/mogum/common/`(`ErrorCode` · `ApiException` · `ApiError` · 오류 응답 경로 클래스 · `ApiExceptionHandler` · `ApiResponse`) · `backend/src/test/java/com/mogum/ArchitectureTest.java`(step 1 의 컨트롤러 반환 타입 규칙과 중첩 fixture)
- `backend/build.gradle`
- 테스트 지원: `backend/src/test/java/com/mogum/auth/SecurityConfigurationIT.java`(실제 HTTP 도우미 · 세션 · CSRF 토큰 다루는 법) · `backend/src/test/java/com/mogum/support/TestSessions.java` · `PostgresTestConfiguration.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

**요청 본문 검증의 공통 장치**를 만든다 — Bean Validation · 위반을 `STRUCTURAL_LIMIT` / `INVALID_REQUEST` 로 바꾸는 규칙 · `@Valid` 누락을 막는 ArchUnit 규칙. 지금 본문을 받는 제품 API 는 없다(첫 사용은 `6-curriculum-gen` 의 `POST /curriculum-requests`). 그래서 이 step 은 테스트 전용 컨트롤러로 장치를 검증한다.

### 1. 의존성

- `backend/build.gradle` 에 `implementation 'org.springframework.boot:spring-boot-starter-validation'` 을 더한다. **버전을 적지 않는다** — Spring Boot 4.1.1 BOM 이 관리한다(Maven Central 에 `spring-boot-starter-validation` 4.1.1 이 있음을 `maven-metadata.xml` 로 확인했다).

### 2. `STRUCTURAL_LIMIT` 와 `fields`

- `ErrorCode` 에 `STRUCTURAL_LIMIT`(400)를 더한다. 기본 message 는 재량(기존 문구처럼 "~습니다" 체, 예: "입력 길이를 확인해 주세요"). 화면은 이 문구를 띄우지 않는다(05 §1.2).
- `ApiError` 에 `fields`(문자열 목록)를 더한다. **`STRUCTURAL_LIMIT` 에만 싣고, 없으면 키를 싣지 않는다**(step 0 의 선택 필드 규칙). 오류 응답 경로 클래스도 `fields` 를 받게 넓힌다.

### 3. 검증 실패 → code

`ApiExceptionHandler` 에서 `@Valid @RequestBody` 검증 실패(`MethodArgumentNotValidException` — `ResponseEntityExceptionHandler.handleMethodArgumentNotValid`)를 처리한다.

- **위반이 모두 길이 상한(`@Size`)이면 `400 STRUCTURAL_LIMIT` + `fields`** — 넘친 칸 전부, 중복 없이, 실행마다 순서가 같게(예: 이름순). 칸 이름은 **요청 JSON 의 필드 이름**이다 — 지금 DTO 는 record 컴포넌트 이름이 곧 JSON 이름이다(이 phase 에서 `@JsonProperty` 로 이름을 바꾸지 않는다).
- **다른 위반(`@NotBlank` · `@NotNull` 등 필드 위반이나 객체 단위 위반)이 하나라도 섞이면 `400 INVALID_REQUEST`, `fields` 없음.** 이유: 화면이 미리 막는 입력이 온 것이라 화면 버그로 본다(05 §1.2).
- 형식 오류(잘못된 JSON · 타입 불일치)는 지금처럼 `400 INVALID_REQUEST` 다(이미 `HttpMessageNotReadableException` 경로).
- `@RequestParam` · `@PathVariable` 의 메서드 검증(`HandlerMethodValidationException`)은 따로 바꾸지 않는다 — 지금처럼 표준 예외 경로의 원래 상태 + `INVALID_REQUEST` 다. 이 phase 에서 그런 매개변수 검증을 쓰지 않는다.
- **응답에 거절된 입력 값 · 제약 메시지 · 필드 경로 외의 상세를 싣지 마라.** 이유: 05 §1.2 — 요청 원문을 응답에 싣지 않는다.
- 글자 수는 `@Size` 가 세는 대로(`String.length()` — UTF-16 단위) 둔다. 코드 포인트로 세는 자체 제약을 만들지 마라. 이유: 05 §1.2 — 화면의 글자 수 표시와 같은 방식으로 정했다.

### 4. ArchUnit 규칙 — `@RequestBody` 에는 `@Valid`

`ArchitectureTest` 에 규칙을 더한다.

- 대상: step 1 규칙과 같은 대상(`@RestController` 운영 클래스의 요청 매핑 메서드, `common.ApiErrorController` 제외).
- `@RequestBody` 가 붙은 매개변수에는 `jakarta.validation.Valid` 가 함께 있어야 한다. 이유: `@Valid` 를 빼먹으면 예외 없이 검증이 통째로 건너뛰어진다(ADR-074).
- **위반 fixture 로 실제로 잡는지 확인한다** — `@Valid` 없는 `@RequestBody` 매개변수를 가진 메서드는 위반, `@Valid` 가 있으면 통과. fixture 는 step 1 처럼 `ArchitectureTest` 의 중첩 static 클래스로 둔다(같은 이유 — HARNESS-BACKLOG S3).
- 지금 운영 클래스에는 `@RequestBody` 가 없으므로 운영 검사에는 `allowEmptyShould(true)` 가 필요할 수 있다 — 기존 규칙들의 쓰임을 따른다.

### 5. 테스트 — TDD · 실제 HTTP

TDD 가드: 고치는 구현 파일(`ApiExceptionHandler` · 오류 응답 경로 클래스)의 짝 테스트도 이 step 에서 고친다.

- 단위(`ApiExceptionHandlerTest`) — `@Size` 위반만 → `STRUCTURAL_LIMIT` + `fields`. 두 칸 → 두 칸(순서 고정). 다른 위반 섞임 → `INVALID_REQUEST` · `fields` 키 없음. 기존 테스트는 그대로 통과.
- **실제 HTTP(`RANDOM_PORT`) + 로그인 세션 + CSRF 토큰** — 이유: 보안 필터(인증 · CSRF)를 거친 뒤의 검증 응답을 확인한다(MockMvc 는 실제 필터 체인 · 컨테이너를 거치지 않는다 — HARNESS-BACKLOG S6).
  - **테스트 전용 컨트롤러는 그 IT 클래스의 중첩 static `@RestController` 로 만들고 `@Import` 로 그 컨텍스트에만 붙인다.** 이유: 최상위 클래스로 두면 모든 `@SpringBootTest` 의 스캔에 들어간다(S3). 중첩 클래스는 `TestTypeExcludeFilter` 가 스캔에서 뺀다(step 1 에 적은 확인). 이 컨트롤러도 규약을 지킨다 — `ApiResponse` 반환 · `@Valid @RequestBody`.
  - 테스트 전용 DTO 는 작은 한도로 둔다(예: `@NotBlank @Size(max = 5) String topic` · `@NotBlank @Size(max = 10) String purpose`). 경로는 `/api/` 아래(인증 필요)로 둔다.
  - 확인할 경우:
    - 정상 → `200` · `{"data": …}`.
    - 한 칸 초과 → `400` · `{"code":"STRUCTURAL_LIMIT","message":…,"fields":["topic"]}`(`STRICT`, 키 세 개뿐).
    - 두 칸 초과 → `fields` 두 칸, 순서 고정.
    - 한도 정확히(한글 5자) → 통과, 한 자 더 → 초과. 한글 1자를 1자로 센다.
    - 빈 값 → `400 INVALID_REQUEST`, `fields` 없음.
    - 초과 + 다른 칸 누락 → `400 INVALID_REQUEST`, `fields` 없음.
    - 잘못된 JSON → `400 INVALID_REQUEST`.
    - 모든 오류 응답 본문에 보낸 입력 값(예: 넘친 문자열)이 들어 있지 않다.
- 테스트 DB 는 JVM 에서 공유된다 — 사용자 · 세션 값이 다른 테스트와 겹치지 않게 한다.

### 6. 문서

- `AGENTS.md` 첫 부분의 **스택** 줄에 Bean Validation(`spring-boot-starter-validation` — 요청 DTO 검증)을 더한다. 다른 줄은 고치지 않는다.
- `docs/ADR.md` ADR-074 의 **검증** 줄 — 앞 step 이 더한 항목 뒤에 이 step 이 확인한 것을 한 항목으로 더한다(실제 HTTP 로 확인한 경우 · 서버 테스트 수). "아직 수행하지 않았다(`2a-api-response`)" 문구는 지우지 않는다.
- 05 §1.2 와 구현이 어긋나게 되면 코드를 계약에 맞춘다. 계약대로 만들 수 없으면 `blocked` 로 사유를 적는다 — 05 를 임의로 고치지 마라.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 패키지 구조(도메인별 패키지 · `common` 은 평평하게)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가 — 더한 의존성이 `spring-boot-starter-validation` 하나뿐인가?
   - `AGENTS.md` 금지 목록(특히 29) · 작업 방식(TDD)을 위반하지 않았는가?
   - 새 ArchUnit 규칙이 위반 fixture 를 실제로 잡는가? 테스트 전용 컨트롤러 · fixture 가 다른 `@SpringBootTest` 컨텍스트에 섞이지 않는가?
3. 결과에 따라 `phases/2a-api-response/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 의존성 · `STRUCTURAL_LIMIT` · `fields` 규칙(순서 포함) · ArchUnit 규칙 이름 · 테스트 전용 컨트롤러 위치를 넣는다(step 3 이 화면의 `fields` 파싱에 쓴다)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 실제 제품 API(`POST /api/curriculum-requests` · 다시 짜기 · 복습 평가 등)나 그 요청 DTO 를 만들지 마라. 이유: 각 도메인 TASK(`6` · `8` …)의 범위다 — 이 step 은 장치만 만든다.
- 서비스 · 도메인 계층에 `@Validated` 메서드 검증을 넣지 마라. 이유: 이 phase 의 규약은 컨트롤러의 `@Valid @RequestBody` 다(05 §1.2).
- 코드 포인트로 세는 자체 길이 제약을 만들지 마라. 이유: 05 §1.2 — 글자 수는 UTF-16 단위로 정했다.
- 응답에 거절된 값 · 제약 메시지를 싣지 마라. 이유: 05 §1.2.
- `ErrorCode` 에 `STRUCTURAL_LIMIT` 외의 code 를 더하지 마라. 이유: 구현된 code 만 둔다.
- `spring-boot-starter-validation` 외의 의존성을 더하지 마라. 이유: ADR-074 가 정한 범위다.
- 테스트 전용 `@RestController` 를 `src/test/java/com/mogum` 아래 최상위 클래스로 만들지 마라. 이유: HARNESS-BACKLOG S3.
- `src/test/resources/application.yml` 을 만들지 마라. 이유: `src/main/resources/application.yml` 을 통째로 가린다.
- `frontend/` 를 고치지 마라. 이유: step 3 의 범위다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
