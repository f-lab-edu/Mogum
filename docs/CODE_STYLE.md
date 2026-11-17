# 코드 스타일 — 백엔드 Java

> 백엔드 Java 코드(`backend/src/main` · `backend/src/test`)를 쓸 때 본다. **이 규칙의 단일 출처다.** 결정의 이유는 [ADR-180](./ADR.md).
> 화면(`frontend/`) · 하네스(`scripts/`)는 아직 정하지 않았다 — 이 문서를 적용하지 않는다.
> 빌드가 지키는 것은 §3 의 필드 이름뿐이다(`ArchitectureTest`). 나머지는 리뷰가 지킨다. 포매터는 두지 않는다.
> 테스트 본문 · 이름 · 데이터 규칙은 [TEST_GUIDE](./TEST_GUIDE.md)에 있다. 둘이 겹치면 테스트 쪽은 TEST_GUIDE 가 맞다.

## 1. 빈 줄 — 사람이 읽는 문맥 단위로 나눈다

**클래스**

- 클래스 선언 줄 바로 다음에는 빈 줄을 두지 않는다.
- 묶음 사이에 빈 줄 하나 — static 상수 묶음 · 인스턴스 필드 묶음 · 생성자 · 메서드 하나하나 · 중첩 타입.
- 같은 묶음 안의 필드 사이에는 빈 줄을 두지 않는다. 단, 애노테이션을 필드 위 따로 된 줄에 쓴 필드(엔티티의 `@Id` · `@Column` …)는 필드마다 빈 줄로 나눈다 — 붙여 두면 애노테이션 줄이 어느 필드에 붙는지 보이지 않는다. `@Autowired UserRepository userRepository;` 처럼 한 줄인 필드는 붙여 둔다.

**메서드 본문**

- 하는 일이 바뀌는 곳에 빈 줄 하나를 둔다 — 준비(값 읽기 · 잠그기) → 검사(가드) → 처리 → 기록 · 결과.
- 이어진 가드(`if (…) return …;` · `if (…) throw …;`) 묶음 뒤에는 빈 줄을 둔다.
- 여러 줄 블록(`if` · `for` · `try` · `switch`)이 앞뒤 문장과 다른 일이면 블록 앞뒤에 빈 줄을 둔다.
- 짧은 메서드(문장 세 줄 이하)는 나누지 않는다.
- 빈 줄을 두 줄 이상 잇지 않는다. 블록을 여는 줄 바로 다음 · 닫는 줄 바로 앞에는 빈 줄을 두지 않는다.
- 주석은 설명하는 코드 바로 위에 두고, 그 주석 앞에 빈 줄을 둔다(블록의 첫 줄이면 두지 않는다).

```java
public JobResult receive(long userId, JobRequest request) {
    creditLedger.lock(userId);
    checkOwner(userId, request);

    var handler = handlers.get(request.type());
    if (handler == null) throw new ApiException(ErrorCode.INVALID_REQUEST);
    handler.check(userId, request);

    checkLimit(userId);
    var job = generationJobRepository.save(create(userId, request));
    creditLedger.consume(userId, job.getId(), cost(job.getType()));
    return result(job);
}
```

**테스트 본문**은 `// given` · `// when` · `// then` 주석으로 나누고 주석 앞에 빈 줄을 둔다([TEST_GUIDE §3](./TEST_GUIDE.md#3-본문)). 한 단계가 길면 그 안에서도 위 규칙대로 나눈다.

## 2. 줄 길이 · 줄 나누기

- **한 줄은 120자를 넘기지 않는다**(들여쓰기 포함). `package` · `import` 줄은 세지 않는다.
- **이어지는 줄은 앞줄 들여쓰기 + 8칸**이다. 여는 괄호 · 앞 인자 위치에 맞추지 않는다 — 이름이 바뀌면 맞춤이 깨진다.
- 의미 단위에서 끊는다.
    - 연산자(`&&` · `||` · `+` · `?` · `:`)는 **앞에서** 끊고 다음 줄 맨 앞에 둔다.
    - 메서드 체인은 `.` 앞에서 끊고, 끊으면 한 줄에 호출 하나씩 둔다.
    - 예외 — 호출 둘이 뜻 하나를 이루는 설정 체인은 그 둘을 한 줄에 둔다. Spring Security 의 `.requestMatchers(…).permitAll()` 처럼 대상과 규칙이 짝이면 짝마다 한 줄이다 — 나누면 어느 규칙이 어느 경로에 걸리는지 읽기 어렵다.
    - 테스트 체인도 같다 — 대상을 고르는 호출과 그 대상에 거는 기대 · 동작이 짝이면 짝마다 한 줄이다. `.expectStatus().isOk()` · Mockito 의 `.given(jobRunner).releaseCurrent()` · ArchUnit 의 `.should().beAnnotatedWith(X)` · `.orShould().beAssignableTo(X)`.
    - 인자 · 매개변수는 쉼표 뒤에서 끊는다. 한 줄에 다 들어가지 않으면 의미가 묶이는 것끼리 둔다.
- 검색할 문구(오류 문구 · 로그 문구 · SQL 조각)를 나눠야만 120자를 지킬 수 있으면 나누지 않고 넘겨도 된다.

```java
boolean includeSucceeded = request.type() == JobType.MATERIAL
        || request.type() == JobType.PLAN_ROADMAP_COURSE;

return generationJobRepository.findById(jobId)
        .filter(job -> job.getUserId() == userId)
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

.authorizeHttpRequests(requests -> requests
        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
        .requestMatchers(HttpMethod.GET, "/api/me").authenticated()
        .anyRequest().denyAll())
```

## 3. 이름 — 의존 필드는 타입 이름 그대로

- **주입받는 의존 필드는 타입 단순 이름을 camelCase 로 쓴다** — `RoadmapRepository roadmapRepository` · `LoginService loginService` · `TransactionTemplate transactionTemplate` · `ObjectMapper objectMapper` · `EntityManager entityManager`. 줄임말(`tx` · `em` · `repo`) · 역할 이름(`roadmaps` · `login` · `reads`)을 쓰지 않는다.
- **같은 타입이 둘 이상이면 역할을 앞에 붙이고 타입 이름으로 끝낸다** — `transactionTemplate` · `releaseTransactionTemplate`.
- 주입받는 매개변수도 같다 — 직접 쓴 생성자와 `@Bean` 메서드의 매개변수(`EntityManager entityManager` · `TestClock testClock`). `java.*` 타입 예외도 같다(`Clock clock`). 빌드는 필드만 검사한다.
- **테스트도 같다** — `@Autowired UserRepository userRepository`. Fake 는 대신하는 타입의 이름을 쓴다 — `FakeUserRepository userRepository`.
- **`java.*` 타입은 예외다** — `Clock clock` · `Map<JobType, JobIntakeHandler> handlers` · `ScheduledExecutorService renewals` 처럼 담은 것을 말하는 이름을 쓴다. 범용 타입은 타입 이름이 내용을 말하지 않는다.
- 이 규칙이 보지 않는 것 — 지역 변수 · 람다 매개변수 · record 칸 · 엔티티 필드 · 설정 값(`*Properties` record 의 칸).

**빌드가 지킨다** — `ArchitectureTest` 가 아래 필드의 이름을 검사한다.

| 대상 | 맞는 이름 |
|---|---|
| `@Component` 계열(메타 애노테이션 포함 — `@Service` · `@Repository` · `@RestController` · `@Configuration` …) 클래스의 static 이 아닌 final 필드 | 타입 단순 이름의 camelCase, 또는 그것으로 끝나는 이름(역할 접두) |
| 테스트 클래스의 `@Autowired` 필드 · 타입 이름이 `Fake` 로 시작하는 필드 | 같음 — `Fake` 접두는 떼고 본다 |

타입이 `java.*` 인 필드는 검사하지 않는다.

## 4. Lombok · 생성자 · 로거

- **생성자가 final 필드에 대입만 하면 `@RequiredArgsConstructor` 를 쓴다** — 빈이 아닌 클래스(`OAuthLoginHandler` 처럼 설정이 `new` 로 만드는 것)도 같다 — 생성자를 직접 쓰지 않는다. 생성자에서 변환 · 계산을 하면(목록을 `Map` 으로 바꾸기 · `TransactionTemplate` 만들기) 직접 쓴다.
- **로거는 `@Slf4j` 로 둔다**(필드 이름 `log`). `LoggerFactory.getLogger` 를 직접 쓰지 않는다.
- 엔티티는 `@Getter` · `@NoArgsConstructor(access = PROTECTED)` 만 쓴다([AGENTS.md](../AGENTS.md) 「스택」 · 금지 24).
- 그 밖의 Lombok(`@Data` · `@Setter` · `@Builder` · `@Value` · `@AllArgsConstructor` · `@EqualsAndHashCode` · `@ToString` …)은 쓰지 않는다.

## 5. import

- **완전 경로 타입을 코드 안에 쓰지 않는다** — `java.util.Collections.list(…)` 가 아니라 `import java.util.Collections;`. 같은 단순 이름의 두 타입이 한 파일에 필요할 때만 하나를 완전 경로로 쓴다.
- **와일드카드 import(`.*`)를 쓰지 않는다** — static import 도 같다. import 만 보고 무엇을 쓰는지 알 수 있어야 한다.
- 순서는 세 묶음, 묶음 사이에 빈 줄 하나 — ① `java.*` 가 아닌 것(알파벳 순) ② `java.*` ③ static import.

## 6. 정했지만 제한하지 않는 것

- **중괄호 없는 `if` 는 조건과 본문이 한 줄에 들어가는 간단한 경우에만 쓴다** — 가드가 아니어도 된다. 조건이나 본문이 두 줄 이상이면 중괄호를 쓴다(`else` 도 같다).

```java
if (handler == null) throw new ApiException(ErrorCode.INVALID_REQUEST);

if (match.isMatch()
        && registrations.findByRegistrationId(match.getVariables().get("registrationId")) == null) {
    return null;
}
```
- **`var`** — 객체는 `var`, 원시값 · `boolean` 은 타입을 쓴다(`long count = …` · `boolean includeSucceeded = …`).
