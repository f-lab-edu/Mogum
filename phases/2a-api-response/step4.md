# Step 4: error-cleanup

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 29, 작업 방식(TDD 짝 테스트 규칙)
- `docs/05-api.md` §1.2 「필드」 항목(`retryAfterSeconds` · `fields` 는 해당할 때만 · 없으면 키를 싣지 않는다)
- `backend/src/main/java/com/mogum/common/ApiError.java` — 인자 4개 정식 생성자 + 인자 3개 보조 생성자
- `backend/src/main/java/com/mogum/common/ApiErrorResponses.java` — 운영 코드에서 `ApiError` 를 만드는 유일한 곳(인자 4개)
- 인자 3개 생성자를 쓰는 테스트: `backend/src/test/java/com/mogum/common/ApiExceptionHandlerTest.java` · `ApiErrorResponsesTest.java` · `ApiErrorControllerTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

브랜치 리뷰의 정리 한 건이다. step 2 가 `ApiError` 에 `fields` 를 더하면서 기존 `(code, message, retryAfterSeconds)` 생성자를 보조 생성자로 남겼다. 운영 코드는 이 생성자를 쓰지 않고(`ApiErrorResponses` 는 인자 4개 생성자를 쓴다) 테스트만 쓴다. 이 phase 가 만든 쓰이지 않는 코드라 지운다.

1. `ApiError` 의 인자 3개 생성자를 지운다. 레코드의 정식 생성자(`code` · `message` · `retryAfterSeconds` · `fields`)와 두 선택 필드의 `@JsonInclude(NON_NULL)` 은 그대로 둔다.
2. 그 생성자를 쓰던 테스트(위 세 파일 — `grep -rn "new ApiError(" backend/src` 로 다시 확인한다)를 인자 4개 생성자(`fields` 자리에 `null`)로 바꾼다. 테스트가 확인하는 값 · 단언은 바꾸지 않는다.

동작은 바뀌지 않는다 — 응답 JSON · 상태 · 헤더가 모두 지금과 같다. 기존 테스트가 그대로 통과하는 것이 그 확인이다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `grep -rn "new ApiError(" backend/src` 의 결과가 모두 인자 4개인가?
   - `AGENTS.md` 금지 목록(29) · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/2a-api-response/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `ApiException` · `ApiErrorResponses` 의 `retryAfterSeconds` 양수 검사를 하나로 합치지 마라. 이유: 브랜치 리뷰에서 그대로 두기로 했다 — `ApiException` 은 던지는 곳에서, `ApiErrorResponses` 는 필터가 직접 부를 때 각각 막는다.
- `STRUCTURAL_LIMIT` 가 아닌 code 에 넘긴 `fields` 를 버리는 `ApiErrorResponses` 의 동작을 바꾸지 마라. 이유: 05 §1.2 — `fields` 는 `STRUCTURAL_LIMIT` 에만 싣는다. 이 방어 동작은 그대로 둔다.
- `SecurityConfigurationCrossOriginIT` 등 이 정리와 무관한 테스트를 고치지 마라. 이유: 브랜치 리뷰에서 고칠 필요가 없다고 정했다.
- 문서를 고치지 마라. 이유: 문서에 이 생성자에 대한 내용이 없다.
- `frontend/` 를 고치지 마라. 이유: 이 step 과 무관하다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
