# Step 2: test-naming-b

## 읽어야 할 파일

먼저 아래 파일들을 읽고 이번 phase 의 규칙과 이유를 파악하라:

- `docs/CODE_STYLE.md` §3(의존 필드 이름 — 「빌드가 지킨다」 표 포함) · §5(import)
- `docs/ADR.md` 의 ADR-180
- `docs/TEST_GUIDE.md` §2 · §3 · §4 · §7
- 이전 step 들의 summary — 운영 코드 필드 이름 대응(step 0) · 테스트 일부 정리(step 1)
- `backend/src/test/java/com/mogum/ArchitectureTest.java` — step 0 이 더한 운영 코드 필드 이름 규칙 · 위반 픽스처 위치

## 작업

이 phase 는 **동작을 바꾸지 않는 코드 스타일 정리**다. 이 step 은 나머지 테스트 코드의 **이름 · import** 를 바꾸고, 필드 이름 규칙을 테스트 코드까지 넓힌다.

대상 경로(이 step 범위):

- `backend/src/test/java/com/mogum/card/`
- `backend/src/test/java/com/mogum/course/`
- `backend/src/test/java/com/mogum/deploy/`
- `backend/src/test/java/com/mogum/job/`
- `backend/src/test/java/com/mogum/worker/`
- `backend/src/test/java/com/mogum/support/`
- `backend/src/test/java/com/mogum/archfixture/`
- `backend/src/test/java/repositoryfixture/` · `backend/src/test/java/namingfixture/`
- `ArchitectureTest.java` 는 아래 3 의 규칙만 더한다

### 1. 필드 이름

step 1 과 같은 규칙이다(`docs/CODE_STYLE.md` §3).

- `@Autowired` 필드 · Fake 필드(대신하는 타입 이름 — `FakeGenerationJobRepository generationJobRepository`) · 테스트 대상 · 협력 객체를 담은 필드(타입이 `java.*` 가 아닌 것)를 타입 이름으로 바꾸고 쓰는 곳도 함께 바꾼다.
- `support/` 의 공용 테스트 설정 · 중첩 static 컴포넌트(테스트용 컨트롤러 · 가짜 서버 등)의 final 필드도 같다.
- 위반 픽스처(`archfixture/` · `repositoryfixture/`)는 규칙을 어기는 것이 목적인 파일이다 — 위반을 보이는 이름은 그대로 둔다.
- 그대로 둘 것: `java.*` · 원시형 필드 · 상수 · 지역 변수 · 람다 매개변수 · 테스트 메서드 이름 · `@DisplayName`.

### 2. import

step 1 과 같다 — 완전 경로 타입은 import 로(같은 이름 충돌만 예외), 와일드카드 import(static 포함)는 명시 import 로, 손대는 파일의 순서는 §5 의 세 묶음으로.

### 3. ArchUnit 규칙을 테스트 코드로 넓힌다 (테스트 먼저)

`docs/CODE_STYLE.md` §3 「빌드가 지킨다」 표의 두 줄을 테스트 코드(`TEST_CLASSES`)에도 건다.

- 테스트 코드의 `@Component` 계열(메타 애노테이션 포함 — `@TestConfiguration` · 중첩 `@RestController` 등) 클래스의 static 이 아닌 final 필드 — 운영 코드와 같은 규칙.
- 테스트 클래스의 `@Autowired` 필드 · 타입 단순 이름이 `Fake` 로 시작하는 필드 — 이름은 `Fake` 접두를 뗀 타입 이름의 camelCase 이거나 그것으로 끝난다.
- `java.*` 타입 · 원시형 필드는 검사하지 않는다.
- 새 위반 픽스처(짧은 이름의 `@Autowired` 필드 · Fake 필드)를 step 0 의 픽스처와 같은 `backend/src/test/java/namingfixture/`(`com.mogum` 밖 · `TEST_CLASSES` 제외)에 두고, 규칙이 거부하는지 확인하는 테스트를 더한다.

### 4. 문서

- `docs/TEST_GUIDE.md` §7 「빌드가 지키는 것」 목록에 「테스트의 `@Autowired` · Fake 필드와 테스트 컴포넌트의 final 필드는 타입 이름을 쓴다([CODE_STYLE §3](./CODE_STYLE.md#3-이름--의존-필드는-타입-이름-그대로))」 한 줄을 더하고, 위반 픽스처 패키지 목록에 새 패키지를 더한다.

### 5. summary

`summary` 에 바꾼 파일 수 · 대표 대응 · 새 규칙 이름 · 새 픽스처 경로를 적는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*ArchitectureTest'
./backend/gradlew -p backend build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다 — 테스트 수가 이전보다 줄지 않았는지 본다(새 규칙 테스트만큼 늘어난다).
2. 체크리스트를 확인한다:
   - 테스트 쪽 규칙이 `TEST_CLASSES` 전체에서 통과하고, 새 위반 픽스처를 거부하는가?
   - 대상 경로에 와일드카드 import · 코드 안 완전 경로 타입이 남지 않았는가?
   - 바꾼 것이 이름 · import · 규칙 · 픽스처 · TEST_GUIDE §7 뿐인가?
3. 결과에 따라 `phases/6a-code-style/index.json` 의 해당 step 을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 대상 경로 밖 파일을 고치지 마라 — 예외는 `ArchitectureTest.java`(규칙 · 그 테스트)와 `docs/TEST_GUIDE.md` §7, 그리고 새 규칙에 걸리는 step 1 범위 파일이 있다면 그 필드 이름만. 이유: 운영 코드와 step 1 범위는 끝났다.
- 빈 줄을 더하거나 줄을 나누지 마라. 이유: step 4 ~ 6 이 공백만 바꾸고 그 diff 로 "공백만 바뀌었다" 를 증명한다.
- 테스트의 검증 내용을 바꾸지 마라 — 이름 · import 만. 이유: 이 phase 는 스타일 정리다.
- 새 규칙을 통과시키려고 규칙을 무르지 마라(예외 목록을 늘리기 등). 걸리는 필드는 이름을 고친다. 규칙이 문서와 맞지 않아 판단이 필요하면 blocked 로 멈춰라.
- `docs/CODE_STYLE.md` · `docs/ADR.md` · `AGENTS.md` 를 고치지 마라.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
