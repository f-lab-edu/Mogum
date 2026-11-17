# Step 8: review-fixes

## 읽어야 할 파일

먼저 아래 파일들을 읽고 이번 phase 의 규칙과 이유를 파악하라:

- `docs/CODE_STYLE.md` 전체 — 특히 이번에 더한 세 가지: §1(애노테이션을 따로 된 줄에 쓴 필드는 필드마다 빈 줄) · §2(테스트 체인도 짝마다 한 줄) · §3(생성자 · `@Bean` 메서드의 주입 매개변수도 타입 이름)
- `docs/ADR.md` 의 ADR-180
- 이전 step 들의 summary
- `phases/6a-code-style/index.json` 의 step 4 · 5 · 6 `review_notes` — 아래 4 가 가리키는 곳의 원문

## 작업

이 phase 는 **동작을 바꾸지 않는 코드 스타일 정리**다. 이 step 은 브랜치 리뷰에서 나온 것을 고친다. 바꿀 수 있는 것은 **공백 · 줄바꿈, 매개변수 이름, import 줄 순서뿐**이다.

대상 경로: `backend/src/main/java` · `backend/src/test/java` 전체.

### 1. 애노테이션을 따로 된 줄에 쓴 필드 사이에 빈 줄 — `docs/CODE_STYLE.md` §1

- step 3 이 「같은 묶음의 필드 사이 빈 줄 없음」을 적용해 엔티티 필드가 붙었다(예: `backend/src/main/java/com/mogum/course/domain/Course.java` 의 `@Id` · `@GeneratedValue` · `private Long id;` 바로 다음 줄이 `@Column(…)`).
- 애노테이션을 필드 위 따로 된 줄에 쓴 필드가 있는 묶음은 필드마다 빈 줄 하나로 나눈다. 엔티티 · `@Embeddable` · `@MappedSuperclass` · 그 밖에 이런 필드가 있는 모든 클래스(운영 · 테스트).
- `@Autowired UserRepository userRepository;` · `@Value("…") int port;` 처럼 애노테이션과 필드가 한 줄인 필드는 붙여 둔다.

### 2. 테스트 체인의 짝을 한 줄로 — `docs/CODE_STYLE.md` §2

- 대상을 고르는 호출과 그 대상에 거는 기대 · 동작이 짝인 체인을 짝마다 한 줄로 잇는다. 예: `.expectStatus()` / `.isOk()` → `.expectStatus().isOk()` · Mockito `.given(jobRunner)` / `.releaseCurrent()`(`backend/src/test/java/com/mogum/worker/WorkerLifecycleTest.java`) · ArchUnit `.should()` / `.beAnnotatedWith(X)` · `.orShould()` / `.beAssignableTo(X)`(`backend/src/test/java/com/mogum/ArchitectureTest.java`).
- 이은 줄이 120자를 넘으면 그 짝의 인자 안에서 쉼표 뒤로 나눈다(+ 8칸).

### 3. 주입 매개변수 이름 — `docs/CODE_STYLE.md` §3

- 직접 쓴 생성자와 `@Bean` 메서드의 주입받는 매개변수를 타입 단순 이름의 camelCase 로 바꾸고, 메서드 본문에서 쓰는 곳도 바꾼다. 예: `EntityManager em` → `entityManager` · `TestClock clock` → `testClock` · `ClientRegistrationRepository registrations` → `clientRegistrationRepository` · `JdbcIndexedSessionRepository sessions` → `jdbcIndexedSessionRepository` · `RestTestClient client` → `restTestClient` · `PlatformTransactionManager transactions`(`JobRunner` 생성자) → `platformTransactionManager`. 대상 경로 전체에서 찾는다 — `config/` · `auth/SecurityConfiguration` · `support/IntegrationTestConfiguration` 등.
- 같은 타입 매개변수가 둘이면 역할을 앞에 붙이고 타입 이름으로 끝낸다.
- 그대로 둘 것: `java.*` 타입(`Clock clock` · `List<…> handlers`) · 원시형 · 설정 값 · 람다 매개변수 · 지역 변수 · 일반 메서드(주입이 아닌)의 매개변수.
- 바꾼 이름이 같은 메서드의 지역 변수 · 다른 매개변수와 겹치면 이 매개변수만 역할 접두를 붙인다. 겹침을 피하려고 다른 이름을 바꾸지 마라.

### 4. 앞 step 이 지나치게 나눈 곳 — 리뷰 권고(`index.json` step 4 · 5 · 6 `review_notes`)

- `backend/src/test/java/com/mogum/ArchitectureTest.java` — `NO_TRANSACTIONAL_METHODS_AROUND_LLM_CALLS` 의 `.resideInAnyPackage("..generation..", "..worker..")` 를 한 줄로.
- `backend/src/test/java/com/mogum/card/repository/CardSchemaRepositoryTest.java` `setFsrs` — 짝인 `state, step, card` 를 한 줄에.
- `backend/src/test/java/com/mogum/common/api/RequestValidationIntegrationTest.java` 645행 근처 `while` — 줄 중간의 `&& System.nanoTime() < deadline` 을 연산자 앞에서 끊어 다음 줄 맨 앞에.
- `backend/src/test/java/com/mogum/common/logging/RequestLogFilterIntegrationTest.java` `followsLoggedInRequests` 의 `for` 본문 — 한 반복의 요청 → 검사를 가른 빈 줄을 뺀다.
- `backend/src/test/java/com/mogum/job/service/JobIntakeServiceIntegrationTest.java` `appliesSuccessDeduplicationByType` — `else` 블록 안 두 줄 단언 사이 빈 줄을 빼고, `if` / `else` 가 끝난 뒤 `assertBalanceMatchesLedger` 앞에 빈 줄을 둔다.
- `backend/src/test/java/com/mogum/deploy/WorkflowFilesScriptTest.java` `run` 의 `for` 본문 · `backend/src/test/java/com/mogum/job/service/JobIntakeServiceTest.java` 의 `.satisfies(entry -> assertThat(entry.getAmount())` / `.isEqualTo(-2))` — 바깥 줄을 나눈 것만으로 120자 안이면 안쪽 짧은 체인은 한 줄로 잇는다.
- `backend/src/test/java/com/mogum/deploy/WorkflowFilesScriptTest.java` 27~29행 · `DeployFilesScriptTest.java` 56~58행의 `@ValueSource(strings = {…})` — 값이 한 줄에 하나만 남거나 줄 길이가 들쭉날쭉하지 않게 의미가 묶이는 것끼리 다시 묶는다.
- `backend/src/test/java/com/mogum/deploy/DeployFilesScriptTest.java` 36~37행 · `WorkflowFilesTest.java` 75~84행 — 안쪽 체인을 나눴으면 바깥 `assertThat(…)` 체인도 같은 방식으로(`.isTrue()` · `.isEqualTo(…)` 를 닫는 괄호 뒤에 붙이지 말고 `.` 앞에서) 끊는다. 같은 파일의 다른 단언과 맞춘다.
- `backend/src/test/java/com/mogum/job/controller/JobControllerIntegrationTest.java` 376~380행 — 텍스트 블록의 닫는 `"""` 와 `.formatted(id)` 를 `""".formatted(id);` 로 잇는다(닫는 `"""` 의 들여쓰기를 바꾸지 마라 — 텍스트 블록의 값이 바뀐다).
- 위와 같은 모양을 다른 곳에서 보면 같이 고친다.

### 5. import 순서

- 아래 파일의 첫 묶음(`java.*` 가 아닌 것)을 알파벳 순으로 맞춘다 — `docs/CODE_STYLE.md` §5. `backend/src/main/java/com/mogum/account/controller/MeController.java` · `common/api/ApiExceptionHandler.java` · `course/domain/Lesson.java` · `course/domain/Material.java` · `course/domain/Course.java` · `course/domain/Roadmap.java`. 그 밖에 순서가 어긋난 파일이 있으면 같이 맞춘다.

### 6. 확인 · summary

- 바꾼 파일마다 `HEAD` 의 내용과 지금 내용을 비교한다(한 줄짜리 Python 스크립트면 된다) — import 줄을 빼고, 3 에서 바꾼 매개변수 이름을 옛 이름으로 되돌린 뒤, 공백 문자를 모두 지운 두 문자열이 같아야 한다. import 줄은 집합이 같아야 한다.
- `summary` 에 1 · 2 · 4 · 5 를 고친 파일 수, 3 의 이름 대응(`클래스.메서드: 옛이름 → 새이름`) 전부, 6 의 비교 결과를 적는다.
- TDD 가드(Stop 훅)가 「구현만 바꾸고 테스트 변경이 없다」고 한 번 막으면, 공백 · 매개변수 이름 · import 순서만 바꿔 동작이 바뀌지 않았다는 근거를 답하고 다시 끝낸다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend build
git diff -w --word-diff=plain '--word-diff-regex=[^[:space:]]' HEAD -- backend/src/main/java backend/src/test/java
```

리뷰어는 두 번째 명령의 `HEAD` 자리에 리뷰 대상 base 커밋을 넣어 실행한다 — 바뀐 글자 표시(`[-…-]` · `{+…+}` — 글자 단위라 이름은 일부 글자만 표시될 수 있다)는 3 의 매개변수 이름(summary 의 대응과 같아야 한다) · import 줄뿐이어야 한다. git 이 빈 줄을 엇갈려 짝지어 같은 줄을 지웠다 다시 넣은 것처럼 보이면(지운 쪽과 넣은 쪽이 공백을 빼면 같다) 실패가 아니다. 그 밖의 글자가 바뀌었으면 반려한다.

## 검증 절차

1. 위 AC 커맨드를 실행한다 — build 가 통과하고, 두 번째 명령의 표시가 위 조건을 지켜야 한다.
2. 체크리스트를 확인한다:
   - 애노테이션을 따로 된 줄에 쓴 필드가 필드마다 빈 줄로 나뉘었는가? 한 줄 필드는 붙어 있는가?
   - 테스트 체인의 짝이 한 줄씩인가 · 120자를 넘는 줄이 새로 생기지 않았는가 · 이어지는 줄이 8칸인가?
   - 주입 매개변수가 타입 이름인가(`java.*` 제외)? 4 의 권고 위치를 모두 고쳤는가?
3. 결과에 따라 `phases/6a-code-style/index.json` 의 해당 step 을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 공백 · 3 의 매개변수 이름 · import 줄 순서 말고는 어떤 글자도 바꾸지 마라 — 필드 이름 · 지역 변수 · 주석 · 조건식 · 문자열 · 중괄호 모두 포함. 이유: 이 phase 는 동작을 바꾸지 않는 정리이고, diff 로 그것을 증명한다.
- 위 1 ~ 5 말고 빈 줄 · 줄 나누기를 다시 고치지 마라. 이유: step 3 ~ 7 의 리뷰를 거쳤다.
- 텍스트 블록(`"""`)의 내용 줄 · 닫는 줄 들여쓰기를 바꾸지 마라. 이유: 문자열 값이 바뀐다.
- 필드 이름 ArchUnit 규칙을 매개변수까지 넓히지 마라. 이유: CODE_STYLE §3 — 빌드는 필드만 검사한다.
- `docs/` · `AGENTS.md` 를 고치지 마라. 규칙과 코드가 맞지 않으면 blocked 로 멈춰라.
- `frontend/` · `scripts/` 를 건드리지 마라.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
