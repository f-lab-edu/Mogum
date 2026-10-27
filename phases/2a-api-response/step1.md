# Step 1: success-envelope

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 **29**, 작업 방식(TDD 짝 테스트 규칙)
- `docs/05-api.md` **§1 「응답 형식」 문단** · §1.1 의 `GET /me` 예시와 응답 범위 줄 · §1.2 첫 문단(오류는 감싸지 않는다)
- `docs/ADR.md` ADR-074
- `docs/ARCHITECTURE.md` · `docs/04-jobs-and-cost.md` §2(패키지 표의 `common` 행)
- step 0 산출물: `backend/src/main/java/com/mogum/common/` 의 `ErrorCode` · `ApiException` · `ApiError` · 오류 응답 경로 클래스 · `ApiExceptionHandler` · `ApiErrorController`
- `backend/src/main/java/com/mogum/account/controller/MeController.java` · `backend/src/main/java/com/mogum/account/dto/MeResponse.java`
- 테스트: `backend/src/test/java/com/mogum/account/controller/MeControllerTest.java` · `backend/src/test/java/com/mogum/auth/SecurityConfigurationIT.java`(`현재_사용자_응답은_정해진_다섯_필드만_포함한다` 등 `/api/me` 본문을 보는 테스트) · `backend/src/test/java/com/mogum/ArchitectureTest.java`(규칙 + 위반 fixture 확인 패턴 `assertViolation`)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

**본문이 있는 성공 응답을 `{ "data": … }` 한 겹으로 감싼다**(05 §1 · ADR-074). 지금 컨트롤러는 `MeController`(`GET /api/me`) 하나다. 이 step 은 감싸는 타입 · 그 규칙을 지키게 하는 ArchUnit 규칙 · 직렬화 규약 확인을 만든다. 화면(`frontend/`)은 step 3 에서 고친다 — 이 step 뒤 브랜치 안에서는 잠시 화면과 API 의 모양이 어긋난다. 그대로 둔다.

### 1. `common.ApiResponse<T>`

```java
public record ApiResponse<T>(T data) {
    public static <T> ApiResponse<T> of(T data) { … }   // data 는 null 이 아니다
}
```

- `data` 는 **항상 객체**다(05 §1) — 응답 DTO record 를 넣는다. 목록은 DTO 안의 이름 있는 필드에 둔다(`{ "cards": [ … ] }`). 최상위에는 `data` 하나뿐이다.
- 오류 본문(`ApiError`)은 감싸지 않는다.

### 2. 컨트롤러가 직접 감싼다

- `MeController.me` 는 `ApiResponse<MeResponse>` 를 반환한다 → 실제 본문 `{ "data": { "email", "status", "role", "termsAgreed", "waitlistPosition" } }`. `MeResponse` 의 필드는 바꾸지 않는다.
- **`ResponseBodyAdvice` 등으로 응답을 자동으로 감싸지 마라.** 이유: 오류 · actuator 헬스 · ERROR 디스패치 · `204` 를 걸러야 하고, 감싸는지가 컨트롤러 코드에 보이지 않는다(05 §1).
- 상태 규약(05 §1) — 조회 · 변경 `200`, 잡 적재 `202`(`@ResponseStatus(HttpStatus.ACCEPTED)` 또는 `ResponseEntity`), 삭제 · 로그아웃 `204`(본문 없음 — `void` + `@ResponseStatus(HttpStatus.NO_CONTENT)` 또는 `ResponseEntity<Void>`). 지금 `202` · `204` 컨트롤러는 없다 — 규칙(아래 3)이 허용하는 모양만 정한다.

### 3. ArchUnit 규칙 — 컨트롤러 반환 타입

`backend/src/test/java/com/mogum/ArchitectureTest.java` 에 규칙을 더한다.

- 대상: `@RestController` 가 붙은 운영 클래스의 요청 매핑 메서드(`@RequestMapping` 이나 그것을 메타 어노테이션으로 가진 `@GetMapping` · `@PostMapping` … 이 붙은 메서드). **`common.ApiErrorController` 는 뺀다** — ERROR 디스패치 응답이라 `ApiError` 를 반환한다.
- 허용하는 반환 타입: `ApiResponse<…>` · `ResponseEntity<ApiResponse<…>>` · `ResponseEntity<Void>` · `void`.
- **제네릭 인자까지 검사한다** — 원시 `ResponseEntity` 만 보면 `ResponseEntity<MeResponse>` 가 통과한다. ArchUnit 1.5.1 의 `JavaMethod.getReturnType()`(제네릭 정보를 담은 `JavaType`)으로 볼 수 있는지 확인해서 쓰고, 볼 수 없으면 리플렉션 `Method.getGenericReturnType()` 으로 보완한다.
- **위반 fixture 로 규칙이 실제로 잡는지 확인한다** — 기존 `assertViolation` 패턴. 최소한 `MeResponse` 를 바로 반환하는 메서드 · `ResponseEntity<MeResponse>` 를 반환하는 메서드가 위반이고, 허용하는 네 모양은 통과하는 것을 확인한다.
- **fixture 는 `ArchitectureTest` 의 중첩 static 클래스로 둔다.** 이유: `@RestController` fixture 를 `src/test/java/com/mogum` 아래 최상위 클래스로 두면 `@SpringBootTest` 의 컴포넌트 스캔에 들어가 모든 컨텍스트 테스트에 섞인다(HARNESS-BACKLOG S3). Spring Boot 4.1.1 의 `TestTypeExcludeFilter` 는 바깥 클래스에 JUnit `@Test`(`@Testable`) 메서드가 있으면 그 중첩 클래스도 스캔에서 뺀다(`spring-boot-test-4.1.1-sources.jar` 의 `TestTypeExcludeFilter.match` 로 확인했다). 기존 `archfixture` 패키지의 fixture 는 스프링 어노테이션이 없어서 그곳에 두었다 — 이 fixture 는 다르다.
- 규칙이 운영 클래스 전체(`CLASSES`)에서 통과하는지(`MeController` 포함) 확인한다.

### 4. 직렬화 규약 확인 — 05 §1

앱이 실제로 쓰는 `ObjectMapper` 빈(Spring Boot 가 설정한 것)으로 확인한다. 직접 `new` 한 매퍼로 확인하지 마라 — 이유: Boot 가 설정한 직렬화 기능과 다를 수 있다.

- `ApiResponse` 안 DTO 의 값이 없는 필드는 빠지지 않고 `null` 로 나간다(`"waitlistPosition": null`).
- `Instant` 필드는 ISO-8601 UTC 문자열로 나간다(예: `Instant.parse("2026-12-14T15:00:00Z")` → `"2026-12-14T15:00:00Z"`, 숫자 타임스탬프가 아님). Jackson 3 · Boot 4.1.1 의 기본값이 그렇게 나가는지 확인하고, 아니면 `application.yml` 에 설정을 더해 맞춘다. 확인한 결과(기본값이었는지 · 설정을 더했는지)를 summary 에 적는다.
- enum 은 대문자 문자열(`"ACTIVE"`)로 나간다.
- 오류 본문(`ApiError`)의 선택 필드는 step 0 대로 없으면 키가 빠진다 — 성공 DTO 와 규칙이 다르다는 것을 테스트 이름에 드러낸다.

이 확인에 쓰는 테스트 전용 DTO 는 테스트 클래스 안의 중첩 record 로 둔다.

### 5. 테스트 — TDD

TDD 가드: 고치는 구현 파일(`MeController`)의 짝 테스트(`MeControllerTest`)도 이 step 에서 고친다. `ApiResponse` 는 record 라 짝 테스트를 요구하지 않지만 위 4 의 확인이 그 역할을 한다.

- `MeControllerTest` — 반환값의 `data()` 가 지금과 같은 다섯 값을 담는다.
- `SecurityConfigurationIT` 의 `/api/me` 본문 테스트 — 최상위가 `data` 하나이고 그 안이 다섯 필드뿐이다(`STRICT`). `jsonPath("$.termsAgreed")` 같은 경로는 `$.data.termsAgreed` 로 고친다. **오류 응답(401 · 403 · 404 · 405 · 500)은 감싸지 않은 `{code, message}` 그대로임**을 기존 테스트가 계속 확인하게 둔다.
- `/api/me` 응답을 읽는 다른 테스트(`grep -rn "api/me" backend/src/test` 로 찾는다)도 새 모양에 맞춘다.

### 6. 문서

- `docs/ARCHITECTURE.md` 의 `common/` 줄 · `docs/04-jobs-and-cost.md` §2 패키지 표의 `common` 행 — `ApiResponse`(성공 응답 감싸기)를 더한다.
- `docs/ADR.md` ADR-074 의 **검증** 줄 — step 0 이 더한 항목 뒤에 이 step 이 확인한 것을 한 항목으로 더한다(ArchUnit 규칙 · `Instant` 직렬화 확인 결과 · 서버 테스트 수). "아직 수행하지 않았다(`2a-api-response`)" 문구는 지우지 않는다 — step 3 이 끝나면 phase 리뷰에서 정리한다.
- 05 §1 「응답 형식」과 구현이 어긋나게 되면 코드를 계약에 맞춘다. 계약대로 만들 수 없으면 `blocked` 로 사유를 적는다 — 05 를 임의로 고치지 마라.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 패키지 구조(도메인별 패키지 · 그 안은 `controller` · `service` · `repository` · `domain` · `dto` · `common` 은 평평하게)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가?
   - `AGENTS.md` 금지 목록(특히 29) · 작업 방식(TDD)을 위반하지 않았는가?
   - 새 ArchUnit 규칙이 위반 fixture 를 실제로 잡는가? fixture 가 다른 `@SpringBootTest` 컨텍스트에 섞이지 않는가?
3. 결과에 따라 `phases/2a-api-response/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — `ApiResponse` 사용법 · ArchUnit 규칙 이름과 허용 반환 타입 · fixture 위치 · `Instant` 직렬화 확인 결과를 넣는다(step 2 · 3 이 이어서 쓴다)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `ResponseBodyAdvice` 등으로 응답을 자동으로 감싸지 마라. 이유: 위 2 — 오류 · 헬스 · ERROR 디스패치 · `204` 까지 감싸게 되고 감싸는지가 코드에 보이지 않는다.
- 오류 본문(`ApiError`)을 `data` 로 감싸거나 오류 형식을 바꾸지 마라. 이유: 05 §1.2 — 오류는 `{ code, message }` 그대로다.
- `MeResponse` 의 필드를 더하거나 빼지 마라. 이유: 05 §1.1 의 `1-auth` 응답 범위다.
- Bean Validation · `fields` · 새 `ErrorCode` 를 더하지 마라. 이유: step 2 의 범위다.
- 감싼 형식과 감싸지 않은 형식을 함께 받거나 돌려주는 호환 장치를 만들지 마라. 이유: ADR-074 트레이드오프 — 공개 전이라 두지 않는다.
- 테스트 전용 `@RestController` 를 `src/test/java/com/mogum` 아래 최상위 클래스로 만들지 마라. 이유: HARNESS-BACKLOG S3(위 3).
- `src/test/resources/application.yml` 을 만들지 마라. 이유: `src/main/resources/application.yml` 을 통째로 가린다.
- `frontend/` 를 고치지 마라. 이유: step 3 의 범위다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
