# Step 0: main-naming

## 읽어야 할 파일

먼저 아래 파일들을 읽고 이번 phase 의 규칙과 이유를 파악하라:

- `docs/CODE_STYLE.md` 전체 — 이번 phase 의 규칙 단일 출처. 이 step 은 §3(의존 필드 이름) · §4(Lombok · 생성자 · 로거) · §5(import)를 운영 코드에 적용한다
- `docs/ADR.md` 의 ADR-180
- `AGENTS.md` 「스택」의 Lombok 줄 · 금지 24(엔티티 생성)
- `docs/TEST_GUIDE.md` §7(빌드가 지키는 것 · 위반 픽스처) · `backend/src/test/java/repositoryfixture/`(컴포넌트 스캔에 섞이지 않게 `com.mogum` 밖에 둔 위반 픽스처의 예)
- `backend/src/test/java/com/mogum/ArchitectureTest.java` — 규칙 · 위반 픽스처 · `assertViolation` 의 기존 모양
- `backend/build.gradle` 의 Lombok 주석(32행 근처)

## 작업

이 phase 는 **동작을 바꾸지 않는 코드 스타일 정리**다. 이 step 은 `backend/src/main/java` 의 **이름 · Lombok · import** 만 바꾼다. 빈 줄 · 줄 나누기는 step 3 이 한다.

### 1. ArchUnit 규칙 — 운영 코드의 의존 필드 이름 (테스트 먼저)

`ArchitectureTest` 에 규칙을 더한다. `docs/CODE_STYLE.md` §3 의 표 첫 줄이 기준이다.

- 대상: `@Component` 로 메타 애노테이션된 클래스(`@Service` · `@Repository` · `@RestController` · `@Configuration` · `@Component` …)의 **static 이 아닌 final 필드**. 지금 step 에서는 운영 코드(`CLASSES`)에만 건다. 테스트 쪽은 step 2 가 넓힌다.
- 맞는 이름: 필드 타입(원시 타입 — 제네릭 인자는 보지 않는다)의 단순 이름을 camelCase 로 바꾼 것과 같거나, 그 단순 이름(대문자로 시작)으로 끝나는 이름(역할 접두 — `releaseTransactionTemplate`).
- 검사하지 않는 필드: 타입이 `java.*` 패키지인 필드(`Clock` · `Map` · `List` · `String` · `ScheduledExecutorService` …)와 원시형(`int` · `long` …).
- 규칙이 걸리는지 보이는 위반 픽스처를 둔다 — 짧은 이름의 final 의존 필드를 가진 `@Component` 클래스 하나, 타입 이름 · 역할 접두 · `java.*` 예외를 모두 지키는 클래스 하나(통과 확인). **픽스처는 `com.mogum` 밖의 `backend/src/test/java/namingfixture/`(패키지 `namingfixture`)에 둔다**(`repositoryfixture` 처럼) — `com.mogum` 안의 `@Component` 는 모든 `@SpringBootTest` 컨텍스트의 컴포넌트 스캔에 섞인다. 그 패키지는 `TEST_CLASSES` 의 제외 조건에도 더한다(step 2 가 테스트 쪽 규칙을 켤 때 픽스처가 걸리지 않게).
- 기존 규칙 테스트처럼 `@DisplayName`(한국어) · given/when/then 을 따른다(`docs/TEST_GUIDE.md`).

### 2. 운영 코드의 의존 필드 이름 바꾸기

`backend/src/main/java` 의 모든 클래스(빈이 아닌 `OAuthLoginHandler` 같은 클래스 포함)에서 주입받는 의존 필드를 §3 대로 바꾼다. 쓰는 곳 · 직접 쓴 생성자의 매개변수도 같은 이름으로 바꾼다. 예:

| 지금 | 바꾼 뒤 |
|---|---|
| `RoadmapRepository roadmaps` | `roadmapRepository` |
| `GenerationJobRepository jobs` | `generationJobRepository` |
| `LoginService login` | `loginService` |
| `TransactionTemplate tx` · `releaseTx` | `transactionTemplate` · `releaseTransactionTemplate` |
| `EntityManager em` | `entityManager` |
| `CreditProperties credits` · `credit` | `creditProperties` |
| `WorkerProperties properties` | `workerProperties` |
| `ObjectMapper mapper` | `objectMapper` |

- `java.*` 타입 필드(`Map<JobType, JobIntakeHandler> handlers` · `ScheduledExecutorService renewals` · `Clock clock` · `String origin`)는 그대로 둔다.
- 지역 변수 · 람다 매개변수 · record 칸 · 엔티티 필드 · `*Properties` record 의 칸은 바꾸지 않는다.

### 3. Lombok · 로거

- 빈 클래스의 생성자가 final 필드에 **대입만** 하면 생성자를 지우고 `@RequiredArgsConstructor` 를 단다. 생성자에서 변환 · 계산을 하는 클래스(`JobIntakeService` 의 `handlers` Map 변환 · `JobRunner` 의 `TransactionTemplate` 생성 등)는 직접 쓴 생성자를 둔다.
- **`@RequiredArgsConstructor` 는 필드 선언 순서로 생성자 매개변수를 만든다.** 지우는 생성자의 매개변수 순서와 필드 선언 순서가 다르면, 필드 선언을 생성자 매개변수 순서대로 옮겨 생성자 모양을 그대로 둔다. 이유: 테스트 · 설정이 `new …(…)` 로 부르는 곳의 인자 순서가 바뀌는데, 같은 타입이 이어지면 컴파일이 되어 조용히 다른 값이 들어간다. 바꾼 클래스마다 지운 생성자와 생성될 생성자의 매개변수 순서 · 타입이 같은지 확인하고 summary 에 「순서 확인」으로 적는다.
- `LoggerFactory.getLogger` 로 만든 `log` 필드를 지우고 `@Slf4j` 를 단다. 필드 이름은 `log` 그대로다 — 로거 이름(클래스 이름)도 같다.
- `backend/build.gradle` 의 Lombok 주석을 「엔티티의 `@Getter` · `@NoArgsConstructor(access = PROTECTED)`, 대입만 하는 빈 생성자의 `@RequiredArgsConstructor`, 로거의 `@Slf4j`(`docs/CODE_STYLE.md` §4)」로 고친다. 의존성 선언은 바꾸지 않는다.

### 4. import

- 코드 안의 완전 경로 타입(`java.util.Collections.list(…)` · `jakarta.servlet.http.HttpServletRequest` 매개변수 등)을 import 로 바꾼다. 같은 단순 이름의 두 타입이 한 파일에 필요할 때만 하나를 완전 경로로 둔다.
- 와일드카드 import 를 쓰는 파일이 있으면 명시 import 로 바꾼다.
- import 순서는 §5 의 세 묶음이다 — 이 step 이 손대는 파일만 맞춘다.

### 5. summary

`summary` 에 바꾼 필드 이름 대응(`클래스.옛이름 → 새이름`)을 모두 적는다 — step 1 · 2 가 테스트를 고칠 때 이 목록을 본다. Lombok 으로 바꾼 클래스 · 직접 생성자를 둔 클래스와 이유도 적는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*ArchitectureTest'
./backend/gradlew -p backend build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 새 ArchUnit 규칙이 운영 코드에서 통과하고, 위반 픽스처를 거부하는가?
   - 동작이 바뀌지 않았는가 — 바꾼 것이 이름 · Lombok · import 뿐인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/6a-code-style/index.json` 의 해당 step 을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 요약 — 위 5 의 대응 목록 포함"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요(규칙과 코드가 어긋나 판단이 필요한 경우 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 빈 줄을 더하거나 줄을 나누지 마라 — 이름이 길어져 120자를 넘는 줄도 그대로 둔다. 이유: step 3 이 공백만 바꾸고, 그 step 은 "공백만 바뀌었다" 를 diff 로 증명한다. 여기서 섞으면 이 step 의 diff 가 커져 리뷰가 이름 변경을 놓친다.
- 동작을 바꾸지 마라 — 메서드 추출 · 변수 추출 · 로직 정리 · 로그 문구 변경도 하지 않는다. 이유: 이 phase 는 스타일 정리이고, 테스트가 그대로 통과하는 것이 유일한 안전망이다.
- `backend/src/test` 의 테스트를 고치지 마라 — 예외는 `ArchitectureTest` 와 새 위반 픽스처, 그리고 운영 코드 이름 변경으로 컴파일이 깨지는 테스트 코드(있다면 그 줄만). 이유: 테스트의 이름 정리는 step 1 · 2 범위다.
- `docs/CODE_STYLE.md` · `docs/ADR.md` · `AGENTS.md` 를 고치지 마라. 규칙과 코드가 맞지 않으면 코드를 규칙에 맞추고, 못 맞추면 blocked 로 멈춰라. 이유: 규칙은 사용자가 정해 먼저 커밋했다.
- Lombok 의 다른 애노테이션(`@Data` · `@Value` · `@Builder` · `@AllArgsConstructor` …)을 쓰지 마라. 이유: CODE_STYLE §4.
- `frontend/` · `scripts/` 를 건드리지 마라. 이유: 이 phase 범위 밖이다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
