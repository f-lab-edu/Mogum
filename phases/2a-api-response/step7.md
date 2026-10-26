# Step 7: sonar-record-array

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 작업 방식(TDD 짝 테스트 규칙)
- `backend/src/test/java/com/mogum/common/ApiExceptionHandlerTest.java` — 테스트용 record `SizedValues`와 이를 쓰는 테스트 `Size는_문자열뿐_아니라_컬렉션과_맵과_배열의_상한도_확인한다`
- `backend/src/main/java/com/mogum/common/ApiExceptionHandler.java` — `sizeOf`(문자열 · 컬렉션 · 맵 · 배열의 크기)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

PR #17 의 SonarCloud 품질 게이트가 "새 코드의 신뢰성 등급 C (A 이상 필요)" 로 실패했다. 신뢰성 이슈는 하나뿐이다:

- `java:S6218` (MAJOR · RELIABILITY) — `ApiExceptionHandlerTest.java` 의 `record SizedValues(... String[] array)`. 배열 컴포넌트를 가진 record 는 기본 `equals` · `hashCode` · `toString` 이 배열 내용이 아니라 참조를 쓴다.

이 record 는 step 5 가 `@Size` 가 배열의 상한도 확인하는지 검사하려고 만든 테스트 fixture 다. 비교에 쓰이지 않아 실제 버그는 아니지만 게이트를 막는다.

## 작업

1. `ApiExceptionHandlerTest` 의 `SizedValues` 를 record 에서 일반 `static final class` 로 바꾼다. `list` · `map` · `array` 세 필드는 `final` 로 두고, 지금과 같은 `@Size(min = 1, max = 1)` 제약을 필드에 붙이고, 같은 순서의 생성자를 둔다. 필드 이름은 바꾸지 않는다 — 테스트가 기대하는 `fields` 의 `field` 값(`array` · `list` · `map`)이 그대로여야 한다.
2. 이 fixture 를 쓰는 테스트의 입력 · 기대 JSON · 단언은 바꾸지 않는다. 바꾼 뒤에도 같은 테스트가 같은 이유로 통과해야 한다(배열 · 리스트 · 맵 상한 초과는 `FIELD_INVALID` + `TOO_LONG`, 하한 위반은 `INVALID_REQUEST`).
3. `backend/src` 에 배열 컴포넌트를 가진 record 가 더 없는지 확인한다.

동작은 바뀌지 않는다 — 운영 코드는 건드리지 않는다. 전체 서버 테스트 수도 그대로(342개)다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `backend/src/main` 의 변경이 없는가?
   - `Size는_문자열뿐_아니라_컬렉션과_맵과_배열의_상한도_확인한다` 의 기대 JSON 이 바뀌지 않았는가?
   - `AGENTS.md` 작업 방식을 위반하지 않았는가?
3. 결과에 따라 `phases/2a-api-response/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `backend/src/main` 을 고치지 마라(`sizeOf` 의 배열 분기 포함). 이유: 사용자가 동작을 바꾸지 않고 테스트만 고치기로 했다.
- record 에 `equals` · `hashCode` · `toString` 을 직접 써서 이슈를 없애지 마라. 이유: 비교에 쓰이지 않는 fixture 에 쓰이지 않는 메서드를 더하는 것이다 — 일반 클래스로 바꾸는 것이 더 단순하다.
- 다른 SonarCloud 이슈(`S100` 한국어 테스트 이름 · `S1186` 빈 fixture 메서드 · `S1144` · `S1172` · `S1612` · 화면 `S3776`)를 고치지 마라. 이유: 게이트와 무관하고 사용자가 이번 범위에서 뺐다.
- 문서 · `frontend/` 를 고치지 마라. 이유: 이 step 과 무관하다(테스트 수도 바뀌지 않는다).
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
