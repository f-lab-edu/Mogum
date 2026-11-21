# Step 1: checkstyle-fixes

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/CODE_STYLE.md` 전체 — **§1 · §2(이름) · §4(부딪히면 네이버가 이긴다)**
- `docs/ADR.md` — ADR-184
- step 0 이 만든 것: `backend/config/naver/`(설정 · README) · `backend/build.gradle` 의 Spotless · 루트 `.editorconfig`
- `backend/config/naver/naver-checkstyle-rules.xml` — 특히 `SuppressionFilter`(속성 `suppressionFile`) · `SuppressionCommentFilter`(`@checkstyle:off` / `@checkstyle:on`) · `SuppressWithNearbyCommentFilter`
- `backend/src/test/java/com/mogum/ArchitectureTest.java` — 의존 필드 이름 규칙(이름을 바꾸면 이 시험도 통과해야 한다)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

step 0 이 네이버 포매터로 전체를 포맷했다. 이 step 은 **네이버 Checkstyle 규칙을 빌드에 걸고, 포매터가 고치지 못한 위반을 고친다.** 2026-11-18 Claude 가 포맷 전 코드에 Checkstyle 14.4.0 으로 돌렸을 때 탭 · import 순서 말고 남은 위반은 `NeedBraces` 243 · `Indentation` 129 · `RightCurly` 120 · `LeftCurly` 104 · `LineLength` 27 · `WhitespaceAround` 7 · `OneStatementPerLine` 6 · `OneTopLevelClass` 2 · `AbbreviationAsWordInName` 2(`AppImageTest` 의 `postgreSQLContainer` · `OpenAiChatProvider` 의 `openAIClient`)였다 — 포맷 뒤에는 줄어 있을 것이다. **동작은 바꾸지 않는다.**

**계약 문서는 이 phase 전에 정해 커밋했다. 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장 · 네이버 설정을 고치지 마라.**

## 작업

### 1. Checkstyle — `backend/build.gradle`

- Gradle `checkstyle` 플러그인 · `toolVersion = '14.4.0'` — 2026-11-18 Claude 가 이 버전으로 네이버 규칙을 오류 없이 읽어 검사한 것을 확인했다(`java -DsuppressionFile=… -jar checkstyle-14.4.0-all.jar -c naver-checkstyle-rules.xml backend/src`).
- `configFile = file('config/naver/naver-checkstyle-rules.xml')` · `configProperties = [suppressionFile: …/naver-checkstyle-suppressions.xml]` · `maxWarnings = 0` · `ignoreFailures = false` — 위반이 하나라도 있으면 빌드가 실패한다.
- `checkstyleMain` · `checkstyleTest` 가 `check` 에 걸리는 기본 동작을 그대로 둔다.

### 2. 위반을 고친다 — 동작은 그대로

- 고치고 나서 **반드시 `spotlessApply` 를 다시 돌린다** — 손으로 고친 줄도 포매터 결과가 기준이다. 끝에 `spotlessCheck` 와 Checkstyle 이 **함께** 통과해야 한다.
- `NeedBraces` — `if` · 반복문 본문에 중괄호를 더한다. 조건 · 본문은 그대로.
- `OneTopLevelClass` — 파일 하나에 최상위 클래스 둘이면 나눈다(테스트 픽스처면 같은 패키지의 새 파일로). 이름 · 패키지 · 가시성은 그대로.
- `OneStatementPerLine` · `WhitespaceAround` · `LineLength` · `Indentation` · `LeftCurly` · `RightCurly` — 포매터가 남긴 것만 고치고, 다시 `spotlessApply` 해 포매터와 Checkstyle 이 서로 되돌리지 않는 모양을 찾는다.
- **이름(`AbbreviationAsWordInName` 등)** — CODE_STYLE §4 대로 연속 대문자를 줄인다: `postgreSQLContainer` → `postgreSqlContainer` · `openAIClient` → `openAiClient`. 지역 · 필드 · 매개변수 이름만이다.
    - **타입(클래스 · 인터페이스 · record · enum) 이름이나 공개 API(경로 · JSON 칸 · 설정 키) 이름을 바꿔야 하는 위반이 나오면 고치지 말고 `blocked`** 로 멈춰 목록을 적어라 — 사용자가 정한다.
- **포매터와 Checkstyle 이 서로 부딪혀 둘 다 통과시킬 수 없는 곳**(예: 텍스트 블록 안의 스페이스 들여쓰기가 `[indentation-tab]` 에 걸림)은 네이버 설정이 이미 둔 **`// @checkstyle:off` · `// @checkstyle:on` 주석**으로 그 범위만 감싼다. 설정 파일(suppressions 포함)을 고치지 마라. 감싼 곳의 목록과 까닭을 summary 에 적는다. 텍스트 블록 · 문자열의 **값**은 바꾸지 마라.
- **120자를 넘는 문자열 · 텍스트 블록(`LineLength`)** — 2026-11-21 사용자 결정. `LineLength` 는 네이버 규칙에서 `TreeWalker` 밖(Checker 직속)이라 `@checkstyle:off` 로 끌 수 없다(이전 시도의 `blocked_reason`). **값이 한 글자도 바뀌지 않게 줄만 나눈다:**
    - 일반 문자열 — 리터럴을 `"…" + "…"` 로 나눈다(`@DisplayName` 같은 주석 값도 상수식이라 같다).
    - 텍스트 블록 — 나눌 자리의 줄 끝에 `\`(줄 이어 쓰기 이스케이프)를 둔다. 줄바꿈이 들어가지 않는다. 이어지는 줄의 들여쓰기가 블록의 공통 들여쓰기보다 깊으면 그만큼 공백이 값에 들어가니 주의한다. `\` 앞의 공백은 값에 남는다.
    - 공백 대신 줄바꿈으로 나누기(SQL 의미는 같지만 값이 바뀜)는 쓰지 않는다.
    - 나눈 리터럴마다 **값이 나누기 전과 같은지** 저장소에 남기지 않는 임시 검사로 확인하고(방법은 정한다 — 예: 나누기 전후 클래스 파일의 문자열 상수 비교), 나눈 곳 수와 결과를 summary 에 적는다.

### 3. 검증

- 서버 전체 테스트가 통과하고 테스트 수가 step 0 뒤와 같다(파일을 나눠 테스트 클래스가 옮겨도 메서드 수는 같다).
- Checkstyle 위반 0 · `spotlessCheck` 통과.
- 바꾼 것을 종류별로 센다 — 중괄호를 더한 곳 · 나눈 파일 · 바꾼 이름 · 줄을 나눈 문자열 · 텍스트 블록(값 같음 확인 결과) · `@checkstyle:off` 로 감싼 곳 — summary 에 적는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend spotlessCheck
./backend/gradlew -p backend build   # Testcontainers — Docker 필요. check 에 Checkstyle 이 걸린다
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - Checkstyle 14.4.0 · 네이버 규칙 그대로 · `maxWarnings = 0` 인가? 설정 파일을 고치지 않았는가?
   - 고친 것이 중괄호 · 파일 나누기 · 지역 · 필드 이름 · 값을 지키는 문자열 줄 나누기 · 포매터 재적용뿐인가? 타입 · 공개 API 이름을 바꾸지 않았는가?
   - 줄을 나눈 문자열 · 텍스트 블록의 값이 나누기 전과 같은가(`"…" + "…"` · 줄 끝 `\` 만 썼는가)?
   - `@checkstyle:off` 로 감싼 곳이 꼭 필요한 범위뿐이고 summary 에 까닭이 있는가?
   - 테스트 수가 같은가? ArchUnit 이름 규칙이 그대로 통과하는가?
3. 결과에 따라 `phases/7a-naver-style/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: Checkstyle 설정 · 고친 종류별 수 · 감싼 곳 · 서버 테스트 수(전후 같음)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (타입 · 공개 API 이름 변경이 필요 · 포매터와 Checkstyle 을 주석으로도 맞출 수 없음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 네이버 설정 파일(규칙 · suppressions · 포매터)을 고치지 마라. 이유: 설정 그대로 따르기로 했다(ADR-184). 부딪히면 코드를 고치거나 설정이 이미 둔 주석 필터를 쓴다.
- 타입 · 공개 API 이름을 바꾸지 마라. 이유: 사용자가 정한다 — `blocked`.
- 조건 · 로직 · 문자열 값을 바꾸지 마라(값을 지키는 줄 나누기만 된다). 이유: 동작을 바꾸지 않는 phase 다.
- CI 워크플로 · 문서(`docs/`)를 고치지 마라. 이유: step 2 의 범위다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
