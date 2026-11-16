# Step 1: test-naming-a

## 읽어야 할 파일

먼저 아래 파일들을 읽고 이번 phase 의 규칙과 이유를 파악하라:

- `docs/CODE_STYLE.md` §3(의존 필드 이름) · §5(import) — 이 step 은 테스트 코드 일부에 적용한다
- `docs/ADR.md` 의 ADR-180
- `docs/TEST_GUIDE.md` §2 · §3 · §4(이름 · 본문 · Fake) — 예시 코드가 이미 새 이름(`userRepository`)을 쓴다
- 이전 step 의 summary — 운영 코드에서 바꾼 필드 이름 대응
- `backend/src/test/java/com/mogum/ArchitectureTest.java` — step 0 이 더한 운영 코드 필드 이름 규칙

## 작업

이 phase 는 **동작을 바꾸지 않는 코드 스타일 정리**다. 이 step 은 아래 경로의 테스트 코드에서 **이름 · import** 만 바꾼다.

대상 경로(이 step 범위):

- `backend/src/test/java/com/mogum/account/`
- `backend/src/test/java/com/mogum/auth/`
- `backend/src/test/java/com/mogum/common/`
- `backend/src/test/java/com/mogum/config/`
- `backend/src/test/java/com/mogum/learning/`
- `backend/src/test/java/com/mogum/` 바로 아래 파일(`ArchitectureTest.java` 등 — 하위 디렉터리 제외)

### 1. 필드 이름

테스트 클래스의 필드를 `docs/CODE_STYLE.md` §3 대로 타입 이름으로 바꾼다. 쓰는 곳도 함께 바꾼다.

- `@Autowired` 필드 — `@Autowired UserRepository users` → `userRepository`, `JdbcTemplate jdbc` → `jdbcTemplate`, `MockMvcTester mvc` → `mockMvcTester`.
- Fake 필드 — 대신하는 타입의 이름을 쓴다. `FakeUserRepository users` → `userRepository`.
- 테스트 대상 · 협력 객체를 담은 필드(타입이 `java.*` 가 아닌 것) — `LoginService service` → `loginService`.
- 같은 타입이 둘이면 역할을 앞에 붙이고 타입 이름으로 끝낸다.
- 그대로 둘 것: 타입이 `java.*` 인 필드 · 원시형 필드 · 상수(static final) · 지역 변수 · 람다 매개변수 · 테스트 메서드 이름 · `@DisplayName`.

### 2. import

- 코드 안의 완전 경로 타입(`java.util.List.of(…)` · `new java.util.ArrayList<…>()` · `com.mogum.support.TestSessions` 등)을 import 로 바꾼다. 같은 단순 이름의 두 타입이 한 파일에 필요할 때만 하나를 완전 경로로 둔다.
- 와일드카드 import(static 포함)를 명시 import 로 바꾼다.
- 이 step 이 손대는 파일의 import 순서를 §5 의 세 묶음으로 맞춘다.

### 3. summary

`summary` 에 바꾼 파일 수와 대표 대응 몇 개, 와일드카드 · 완전 경로를 없앤 파일 수를 적는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다 — 테스트 수가 바뀌지 않았는지 이전과 비교한다(테스트를 지우거나 건너뛰게 하지 않았는지).
2. 체크리스트를 확인한다:
   - 대상 경로의 `@Autowired` · Fake · 테스트 대상 필드가 타입 이름인가?
   - 대상 경로에 와일드카드 import · 코드 안 완전 경로 타입이 남지 않았는가(같은 이름 충돌 예외 제외)?
   - 바꾼 것이 이름 · import 뿐인가?
3. 결과에 따라 `phases/6a-code-style/index.json` 의 해당 step 을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 대상 경로 밖 파일을 고치지 마라. 이유: 나머지 테스트는 step 2 범위다. 운영 코드는 step 0 에서 끝났다.
- 빈 줄을 더하거나 줄을 나누지 마라 — 이름이 길어져 120자를 넘는 줄도 그대로 둔다. 이유: step 4 ~ 6 이 공백만 바꾸고 그 diff 로 "공백만 바뀌었다" 를 증명한다.
- 테스트의 검증 내용을 바꾸지 마라 — 단언 · given 데이터 · 테스트 추가 · 삭제 · 통합 없이 이름 · import 만. 이유: 이 phase 는 스타일 정리다.
- `ArchitectureTest` 의 규칙을 테스트 코드로 넓히지 마라. 이유: step 2 가 나머지 테스트까지 고친 뒤 켠다 — 지금 켜면 step 2 범위의 테스트가 걸린다. 이 파일은 필드 이름 · import 만 고친다.
- `docs/` · `AGENTS.md` 를 고치지 마라. 규칙과 코드가 맞지 않으면 blocked 로 멈춰라.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
