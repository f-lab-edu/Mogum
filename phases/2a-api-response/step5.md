# Step 5: field-errors

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 29(사람이 입력하는 칸의 `UserInput` 표시), 작업 방식(TDD 짝 테스트 규칙 · "바꾸면 깨지는 계약은 넓힐 수 있는 모양으로 두고, 후속 기능은 미리 만들지 않는다")
- `docs/05-api.md` §1.2 — 「필드」 항목(`fields` 는 `FIELD_INVALID` 에만), 「입력 검증」 항목 전체, 표의 `INVALID_REQUEST` · `FIELD_INVALID` 행
- `docs/ADR.md` ADR-074 — 「입력 검증」 결정과 대체 표기, 「검증」의 「백엔드 요청 검증」 항목
- `backend/src/main/java/com/mogum/common/ErrorCode.java` · `ApiError.java` · `ApiErrorResponses.java` · `ApiExceptionHandler.java`
- `backend/src/test/java/com/mogum/common/RequestValidationIT.java` · `ApiExceptionHandlerTest.java` · `ApiErrorResponsesTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

step 2 는 "위반이 모두 `@Size` 면 `400 STRUCTURAL_LIMIT` + 필드 이름 목록(`"fields": ["topic"]`)" 으로 만들었다. 브랜치 리뷰 뒤 사용자가 이 형식을 바꾸기로 했다 — 후속 기능(학습 플랜의 숫자 · 범위 입력)이 들어오면 이름 목록으로는 어느 칸이 왜 틀렸는지 싣지 못하고, 제약 종류(`Size`)로 사용자 오류를 추론해 화면이 만드는 값의 `@Size` · `@Size(min)` 위반까지 사용자 오류가 된다. 문서(05 §1.2 · ADR-074 · AGENTS 29 · 02 · 06)는 이미 새 형식으로 고쳐 두었다. **이전 step 요약에 있는 `STRUCTURAL_LIMIT` · 이름 목록 `fields` 는 이 step 이 대체한다.**

## 작업

새 응답 모양:

```json
{ "code": "FIELD_INVALID", "message": "입력을 확인해 주십시오",
  "fields": [ { "field": "purpose", "reason": "TOO_LONG" }, { "field": "topic", "reason": "REQUIRED" } ] }
```

1. `ErrorCode.STRUCTURAL_LIMIT` 를 `FIELD_INVALID(HttpStatus.BAD_REQUEST, "입력을 확인해 주십시오")` 로 바꾼다. 운영 · 테스트 코드에 `STRUCTURAL_LIMIT` 가 남지 않게 한다(`grep -rn STRUCTURAL_LIMIT backend/src` 결과 없음).
2. 사용자 입력 표시를 만든다 — `backend/src/main/java/com/mogum/common/UserInput.java`: `public interface UserInput extends jakarta.validation.Payload {}`. 사람이 입력하는 칸의 제약에 `payload = UserInput.class` 로 붙인다.
3. 칸별 위반 값을 만든다 — `backend/src/main/java/com/mogum/common/FieldViolation.java`: `public record FieldViolation(String field, Reason reason)`, 안에 `public enum Reason { TOO_LONG, REQUIRED }`. JSON 은 `{"field":"topic","reason":"TOO_LONG"}` 이다.
4. `ApiError.fields` 를 `List<FieldViolation>` 으로 바꾼다. `@JsonInclude(NON_NULL)` 은 그대로 둔다.
5. `ApiErrorResponses` 의 `fields` 인자를 `List<FieldViolation>` 으로 바꾼다(`of` · `write` 둘 다). 규칙:
   - `FIELD_INVALID` 이고 목록이 비어 있지 않을 때만 싣는다. 다른 code 에 넘긴 `fields` 는 지금처럼 버린다.
   - 같은 원소는 한 번만 싣는다. `field` 의 문자열 순, 같은 칸이면 `reason` 이름의 문자열 순으로 정렬한다(enum 선언 순서에 기대지 않는다). 예: `topic` 이 `REQUIRED` · `TOO_LONG` 둘이면 그 순서다.
6. `ApiExceptionHandler.handleMethodArgumentNotValid` 를 바꾼다. 위반마다 아래를 모두 만족해야 사용자 오류이고, **모든 위반이 사용자 오류일 때만** `FIELD_INVALID` + `fields` 다. 하나라도 아니면 `INVALID_REQUEST` 이고 `fields` 를 싣지 않는다.
   - `FieldError` 다(특정 칸이 없는 객체 단위 위반은 아니다).
   - `ConstraintViolation` 을 감싸고 있다(`error.contains(ConstraintViolation.class)` → `unwrap`). 손으로 만든 `FieldError` 처럼 감싸지 않았으면 사용자 오류가 아니다.
   - 제약 선언의 payload 에 `UserInput` 이 있다(`getConstraintDescriptor().getPayload()`).
   - `reason` 으로 바뀐다: `@NotBlank` · `@NotNull` → `REQUIRED`. `@Size` 는 값의 크기가 `max` 를 넘었을 때만 `TOO_LONG`. 그 밖의 제약과 `@Size` 의 `min` 위반은 대응이 없으므로 사용자 오류가 아니다.
   - `field` 는 `FieldError.getField()` 다(요청 본문의 필드 경로 — 지금은 최상위 이름).
   - 위 API 는 이 저장소가 쓰는 버전에 있다 — `spring-context` 7.0.9 `ObjectError.contains(Class)` · `unwrap(Class)`, `jakarta.validation-api` 3.1.1 `ConstraintDescriptor.getPayload()` · `@Size.payload()` (`javap` 로 확인).
7. 테스트를 먼저 고치고 쓴다.
   - `RequestValidationIT`(실제 HTTP · 로그인 세션 · CSRF · STRICT JSON — 지금 구조 유지). 중첩 static fixture DTO 는 사람 칸(`UserInput` 표시)과 화면이 만드는 칸(표시 없음)을 함께 갖도록 바꾸고, 아래를 확인한다:
     - 정상 요청 → `200 {data}`
     - 한 칸 길이 초과 → `FIELD_INVALID` + `[{topic, TOO_LONG}]`
     - 두 칸 위반 → 정렬된 원소 둘(여러 번 보내도 같은 순서)
     - 사람 칸이 빈 문자열 · 공백만 · 키 없음 → `REQUIRED`
     - 공백만으로 상한을 넘김 → 같은 칸의 `REQUIRED` · `TOO_LONG` 둘
     - 한글 경계 · 이모지의 UTF-16 길이(기존 두 테스트 유지)
     - 표시 없는 칸의 위반만 · 표시된 위반과 표시 없는 위반이 섞임 → `INVALID_REQUEST`, `fields` 키 없음
     - 표시된 `@Size(min = …)` 의 하한 위반 → `INVALID_REQUEST`(`TOO_LONG` 이 아니다)
     - 잘못된 JSON · 타입 불일치 → `INVALID_REQUEST`(기존 유지)
     - 모든 오류 응답에 입력 원문이 없고 `Retry-After` 가 없다(기존 `assertError` 의 검사 유지)
   - `ApiExceptionHandlerTest` — 손으로 만든 `FieldError` 는 `ConstraintViolation` 을 감싸지 않아 사용자 오류가 아니다. 사용자 오류 경로는 실제 검증기(예: `LocalValidatorFactoryBean` · `SpringValidatorAdapter`)가 만든 위반으로 확인하고, 손으로 만든 `FieldError` · 객체 단위 위반이 섞이면 `INVALID_REQUEST` 임을 확인한다.
   - `ApiErrorResponsesTest` — `FieldViolation` 목록의 중복 제거 · 정렬, 빈 목록 · `null` 이면 키 생략, `FIELD_INVALID` 가 아닌 code 면 버림, 서블릿 `write` 도 같은 규칙.
8. `docs/ADR.md` ADR-074 「검증」의 「백엔드 요청 검증」 항목을 이 step 의 결과로 다시 쓴다(위 확인 항목 · 전체 서버 테스트 수). 그 항목의 이전 문장 중 `STRUCTURAL_LIMIT` · 이름 목록에 대한 것은 지우고, 의존성 추가 · fixture `@Import` · ArchUnit `REQUEST_BODY_REQUIRES_VALID` 에 대한 사실은 남긴다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `grep -rn STRUCTURAL_LIMIT backend/src` 의 결과가 없는가?
   - 사용자 오류를 제약 종류만으로 가르지 않고 `UserInput` 표시로 가르는가? 표시 없는 `@Size` 위반이 `INVALID_REQUEST` 인가?
   - 오류 응답이 `ApiErrorResponses` 한 경로로만 나가는가(AGENTS 금지 29)?
   - `AGENTS.md` 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/2a-api-response/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `reason` 값을 더하지 마라(`OUT_OF_RANGE` · `TOO_SHORT` · `INVALID_FORMAT` 등). 이유: 05 §1.2 · AGENTS 작업 방식 — 모양은 넓힐 수 있게 두되 값은 지금 쓰는 것만 둔다. 그 제약을 쓰는 후속 TASK 가 더한다.
- 제약 종류만 보고 사용자 오류로 바꾸지 마라(예: 표시 없는 `@Size` 를 `TOO_LONG` 으로). 이유: 화면이 만드는 값의 위반은 화면 버그다(05 §1.2).
- 응답에 입력 원문 · 상한 값(`max` 등) · 제약 메시지 · 거부된 값을 싣지 마라. 이유: 05 §1.2 — 원문은 싣지 않고, 상한은 화면이 이미 안다.
- `FIELD_INVALID` 가 아닌 code 에 `fields` 를 싣지 마라. `ApiErrorResponses` 의 버리는 동작을 유지한다. 이유: 05 §1.2.
- `ApiException` · `ApiErrorResponses` 의 `retryAfterSeconds` 양수 검사를 하나로 합치지 마라. 이유: 브랜치 리뷰에서 각자 막도록 그대로 두기로 했다(step 4).
- 보안 핸들러 · 다른 오류 경로 · 전역 Jackson 설정을 바꾸지 마라. 이유: 이 step 은 입력 검증 오류의 모양만 바꾼다.
- `frontend/` 를 고치지 마라. 이유: 다음 step(6) 범위다.
- ADR-074 「검증」의 「백엔드 요청 검증」 항목 말고는 문서를 고치지 마라(「검증」 첫 줄의 브랜치 리뷰 결과도 그대로 둔다). 이유: 05 · 06 · 02 · AGENTS · ADR 결정 문구는 이미 고쳐 두었고, 브랜치 리뷰 결과는 리뷰 때 고친다. 문서와 코드가 맞지 않으면 문서를 고치지 말고 `blocked` 로 멈춘다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
