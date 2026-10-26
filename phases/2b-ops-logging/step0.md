# Step 0: common-split

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — **패키지** 줄 · 금지 목록 29 · 작업 방식(TDD 짝 테스트 규칙)
- `docs/04-jobs-and-cost.md` §2 — `common` 을 주제별 하위 패키지(`common.api` · `common.time` · `common.logging`)로 나누는 규칙과 패키지 표의 `common` 행(이미 고쳐 두었다)
- `docs/ADR.md` ADR-075 의 「`common` 하위 패키지」 항목
- `docs/ARCHITECTURE.md` — 디렉터리 트리의 `common/` 줄
- `backend/src/main/java/com/mogum/common/` 전체(클래스 9개)
- `backend/src/test/java/com/mogum/common/` 전체(테스트 6개)
- `backend/src/test/java/com/mogum/ArchitectureTest.java` — `common` 클래스를 import 한다

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

`common` 은 지금 클래스 9개가 한 패키지에 평평하게 있다. 이 phase(`2b-ops-logging`)는 여기에 로그 장치를 더하기 전에 `common` 을 주제별 하위 패키지로 나누기로 했다(ADR-075 · 04 §2). **이 step 은 옮기기만 한다** — 이름 · 시그니처 · 동작 · 테스트 내용은 바뀌지 않는다. 로그 장치(`common.logging`)는 다음 step 이 만든다.

이 브랜치는 아직 병합되지 않은 PR #17(`2a-api-response`) 위에 쌓여 있다. PR #17 리뷰로 2a 의 같은 파일이 바뀌면 이 브랜치에 merge 해야 하므로, 옮기기 외의 변경이 섞이지 않아야 합치기 쉽다.

## 작업

### 1. 옮길 곳

| 새 패키지 | main 클래스 | 테스트 |
|---|---|---|
| `com.mogum.common.api` | `ApiResponse` · `ErrorCode` · `ApiException` · `ApiError` · `FieldViolation` · `ApiErrorResponses` · `ApiExceptionHandler` · `ApiErrorController` | `ApiErrorControllerTest` · `ApiErrorResponsesTest` · `ApiExceptionHandlerTest` · `ApiResponseTest` · `RequestValidationIT` |
| `com.mogum.common.time` | `SeoulClock` | `SeoulClockTest` |

- 경로는 `backend/src/main/java/com/mogum/common/{api,time}/` 와 `backend/src/test/java/com/mogum/common/{api,time}/` 다. 테스트는 대응하는 구현과 같은 하위 패키지에 둔다(04 §2).
- **테스트를 먼저 옮기고 구현을 옮긴다.** 이유: TDD 가드(`scripts/tdd_guard.py`)는 구현 파일을 쓸 때 같은 패키지에 짝 테스트(`Foo*Test.java` · `Foo*IT.java`)가 있어야 허용한다. 9개 중 짝 테스트가 필요한 클래스(`ApiResponse` · `ApiErrorResponses` · `ApiExceptionHandler` · `ApiErrorController` · `SeoulClock`)는 모두 위 표에 짝이 있다.
- 파일 내용은 `package` 선언과 import 만 바뀐다.

### 2. 참조 고치기

- 이 클래스들을 쓰는 곳(`account` · `auth` · `config` · 테스트 · `ArchitectureTest` 등)의 import 를 고친다. 문자열로 패키지 이름을 쓰는 곳(`com.mogum.common` — ArchUnit 의 패키지 문자열 · 스캔 설정 · `@Import` 등)이 있는지 저장소에서 찾아 같이 고친다.
- 옮긴 뒤 같은 패키지라 보이던 package-private 멤버가 안 보여 컴파일 오류가 나면, 공개 범위를 넓히기 전에 두 클래스가 위 표대로 같은 하위 패키지에 있는지부터 확인한다. 표대로인데도 넓혀야 하면 `blocked` 로 사유를 적는다.

### 3. 문서의 클래스 위치

- `docs/ARCHITECTURE.md` 디렉터리 트리의 `common/` 줄을 하위 패키지 셋으로 바꾼다. `api/` · `time/` 은 위 표의 클래스, `logging/` 은 "요청 ID · 접근 줄(`2b-ops-logging` step 1 에서 만든다)" 로 적는다.
- 저장소에서 옛 패키지 이름으로 클래스를 가리키는 곳(`common.ErrorCode` · `common.ApiResponse` 처럼 `common.` 뒤에 클래스 이름이 오는 표기)을 찾아 새 이름으로 고친다 — 예: `docs/05-api.md` §1.2 의 "code 는 `common.ErrorCode` 한 곳". `docs/04-jobs-and-cost.md` §2 · `AGENTS.md` 는 이미 고쳐 두었다.
- **고치지 않는 곳**: `docs/ADR.md` 의 이미 끝난 결정 본문(ADR-074 등 — 결정 당시의 기록이다), `phases/` 의 지난 step 파일 · 리뷰 · 출력 파일, `phases/HARNESS-BACKLOG.md` 의 지난 기록.

## Acceptance Criteria

```bash
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `backend/src/main/java/com/mogum/common/` 바로 아래에 `.java` 파일이 없는가(04 §2 — `common` 바로 아래에는 클래스를 두지 않는다)?
   - 서버 테스트 수가 옮기기 전과 같은가? 옮기기 전에 `./backend/gradlew -p backend test` 로 센 수를 summary 에 함께 적는다(2a 끝 기준 342개).
   - 옮긴 파일의 변경이 `package` 선언 · import 뿐인가? 테스트의 기대값 · 단언이 바뀌지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/2b-ops-logging/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 옮긴 클래스의 새 패키지와 테스트 수(전후)를 넣는다(다음 step 이 import 에 쓴다)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 클래스 이름 · 메서드 시그니처 · 동작 · 공개 범위를 바꾸지 마라. 이유: 옮기기만 하는 step 이어야 PR #17(2a)에 리뷰 수정이 들어올 때 이 브랜치에 합치기 쉽다.
- `common.logging` 패키지나 로그 코드를 만들지 마라. 이유: step 1 의 범위다.
- ArchUnit 규칙을 더하거나 고치지 마라 — import 와 패키지 문자열만 고친다. 이유: 하위 패키지 규칙은 04 §2 의 문서 규칙으로 둔다.
- `docs/ADR.md` 의 기존 ADR 본문 · `phases/` 의 지난 파일을 고치지 마라. 이유: 결정 · 실행 당시의 기록이다.
- `frontend/` 를 고치지 마라. 이유: 이 step 과 무관하다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
