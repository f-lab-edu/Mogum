# 코드 스타일 — 백엔드 Java

> 백엔드 Java 코드(`backend/src/main` · `backend/src/test`)를 쓸 때 본다. **이 규칙의 단일 출처다.** 결정의 이유는 [ADR-184](./ADR.md)(줄 배치 — 네이버 컨벤션) · [ADR-180](./ADR.md)(이름 · Lombok).
> 화면(`frontend/`) · 하네스(`scripts/`)는 이 문서를 적용하지 않는다 — 화면은 ESLint 다.
> 테스트 본문 · 이름 · 데이터 규칙은 [TEST_GUIDE](./TEST_GUIDE.md)에 있다. 둘이 겹치면 테스트 쪽은 TEST_GUIDE 가 맞다.

## 1. 줄 배치 — 네이버 핵데이 Java 컨벤션을 그대로 따른다

**줄 배치 · 공백 · 중괄호 · import 순서는 [네이버 캠퍼스 핵데이 Java 코딩 컨벤션](https://naver.github.io/hackday-conventions-java/)(`naver/hackday-conventions-java`)을 설정 파일 그대로 따른다**(ADR-184). 손으로 정한 줄 배치 규칙은 두지 않는다 — **포매터가 만든 모양이 기준이다.**

- **도구가 고치고 검사한다** — 손으로 맞추지 않는다. Java 를 고친 뒤 커밋 전에 포매터를 돌린다.
    - 고치기: `./backend/gradlew -p backend spotlessApply` — 네이버 Eclipse 포매터 설정 · import 순서 · 줄 끝 공백 · 파일 끝 줄바꿈
    - 검사: `spotlessCheck`(포맷) · `checkstyleMain` · `checkstyleTest`(네이버 Checkstyle 규칙) — CI 가 PR 마다 돌린다
    - Spotless 8.12.0 · Checkstyle 14.4.0은 `backend/build.gradle` 에 설정되어 있다. 네이버 설정 파일과 출처 README는 `backend/config/naver/` 에 있다.
    - 현재 텍스트 블록 안 공백 들여쓰기 보존 · 포매터와 Checkstyle이 충돌하는 구간(빈 선언·블록, 단항 연산자·switch 간격, switch 인자 들여쓰기, 애노테이션 선언 중괄호 간격)은 사유 주석과 `@checkstyle:off` / `@checkstyle:on` 으로 감쌌다.
- **설정 파일을 고치지 않는다** — 네이버 저장소의 커밋 `e12658e` 에서 가져온 그대로 쓴다. 규칙과 코드가 부딪히면 코드를 고친다.
- 큰 줄기(자세한 것은 네이버 가이드): 하드 탭 들여쓰기(탭 1개 = 4칸) · 이어지는 줄은 한 단계(탭 1개) 더 · 한 줄 120자 · K&R 중괄호 · `if` · 반복문은 언제나 중괄호 · 연산자 앞과 `.` 앞에서 끊기 · import 는 static → `java.` → `javax.` → `org.` → `net.` → `com.*` → 그 밖 순서로 묶음 사이 빈 줄 · 와일드카드 import 금지(static 은 허용) · 한 줄에 문장 하나.
- **IDE** — IntelliJ 는 `backend/config/naver/naver-intellij-formatter.xml` 을 Settings → Editor → Code Style → Java → Import Scheme 으로 가져온다. 저장소 루트의 `.editorconfig`(네이버 것 그대로)는 인코딩(UTF-8) · 줄바꿈(LF) · 파일 끝 줄바꿈만 정한다 — 탭 들여쓰기는 IntelliJ 설정과 포매터가 맞춘다.
    - 원본 `.editorconfig` 의 `*.bat` 설정은 CRLF이므로 편집기에서 `backend/gradlew.bat` 를 저장하면 줄바꿈이 CRLF로 바뀔 수 있다.

## 2. 이름 — 의존 필드는 타입 이름 그대로

- **주입받는 의존 필드는 타입 단순 이름을 camelCase 로 쓴다** — `RoadmapRepository roadmapRepository` · `LoginService loginService` · `TransactionTemplate transactionTemplate` · `ObjectMapper objectMapper` · `EntityManager entityManager`. 줄임말(`tx` · `em` · `repo`) · 역할 이름(`roadmaps` · `login` · `reads`)을 쓰지 않는다.
- **같은 타입이 둘 이상이면 역할을 앞에 붙이고 타입 이름으로 끝낸다** — `transactionTemplate` · `releaseTransactionTemplate`.
- 타입 이름만으로 무엇을 담았는지 모르는 범용 타입(`FilterRegistrationBean<RequestLogFilter>`)도 역할을 앞에 붙인다 — `requestLogFilterRegistrationBean`. 추상 타입은 `Abstract` 를 떼고 본다 — `AbstractFilterRegistrationBean` → `securityFilterRegistrationBean`.
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

## 3. Lombok · 생성자 · 로거

- **생성자가 final 필드에 대입만 하면 `@RequiredArgsConstructor` 를 쓴다** — 빈이 아닌 클래스(`OAuthLoginHandler` 처럼 설정이 `new` 로 만드는 것)도 같다 — 생성자를 직접 쓰지 않는다. 생성자에서 변환 · 계산을 하면(목록을 `Map` 으로 바꾸기 · `TransactionTemplate` 만들기) 직접 쓴다.
- **로거는 `@Slf4j` 로 둔다**(필드 이름 `log`). `LoggerFactory.getLogger` 를 직접 쓰지 않는다.
- 엔티티는 `@Getter` · `@NoArgsConstructor(access = PROTECTED)` 만 쓴다([AGENTS.md](../AGENTS.md) 「스택」 · 금지 24).
- 그 밖의 Lombok(`@Data` · `@Setter` · `@Builder` · `@Value` · `@AllArgsConstructor` · `@EqualsAndHashCode` · `@ToString` …)은 쓰지 않는다.

## 4. 두 규칙이 부딪히면

- **네이버 규칙이 이긴다** — 예: 의존 필드 이름(§2 — 타입 이름 그대로)과 네이버의 연속 대문자 제한(`AbbreviationAsWordInName` — 연속 대문자 2자까지)이 부딪히면 타입 이름을 쓰되 연속 대문자를 줄인다(`OpenAIClient` → `openAiClient` · `PostgreSQLContainer` → `postgreSqlContainer`).
