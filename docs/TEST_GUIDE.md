# 테스트 코드 작성 가이드 — 백엔드

> 백엔드 테스트 코드를 쓸 때 본다 — 종류 · 이름 · 본문 · 데이터 · 격리 · 요청 도구. **이 규칙의 단일 출처다**(요약 입구가 아니다). 무엇을 검증해야 하는지(장애 주입 · 규칙 테스트 · 용량 측정)는 [07](./07-testing.md)에 있다.
> 2026-11-08 사용자 결정([ADR-179](./ADR.md)). 기존 테스트 이관은 `3a-test-conventions`에서 **2026-11-08 완료했다. §7의 네 규칙(JUnit `Assertions` 금지 · 픽스처/Fake 밖 리플렉션 금지 · `IT` 이름 금지 · 제품 레포지토리 상속 제한)을 위반 픽스처 검증과 함께 ArchUnit으로 빌드에 걸었다.** 화면(`frontend/`) 테스트는 화면 작업이 늘어날 때 따로 정한다.
>
> `3a` step 4에서 이관한 통합 테스트는 실제 포트 `RestTestClient`와 공용 컨텍스트를 사용한다. 현재 공용 값은 출처 `https://mogum.example` · 쿠키 도메인 `mogum.example` · Tomcat 스레드 최대/최소 1이다(`4-account` step 0에서 관리자 허용 목록 속성을 지웠다). 세션 쿠키 직렬화는 `SecurityConfigurationCookieTest`의 `ApplicationContextRunner`에서 두 도메인 설정을 확인한다. 로컬 XSRF 쿠키 발급/만료의 Domain 생략은 실제 필터 응답을 확인해야 하므로 `LocalCookieIntegrationTest` 묶음 하나만 도메인을 비워 **통합 컨텍스트는 총 2개**다(레포지토리 슬라이스 제외). 실패용 컨트롤러 · ERROR 관찰 필터 · DB 연결 실패 대역 · Google 가짜 서버는 공용 테스트 설정의 중첩 static 클래스에 있고, ERROR 관찰은 테스트 응답 헤더로 디스패치 종류와 실행 중 MDC의 요청 ID가 응답 요청 ID와 같은지 확인한다. 스파이와 클래스별 설정은 제거했다.

> `3a` step 5에서 인증 · 로그/시계 · 설정 · 배포 파일 · FSRS 학습 · `ArchitectureTest`의 단위 테스트 형식을 이관했다. 배포 파일 검사는 `DeployFilesTest` · `DeployFilesJournalTest` · `DeployFilesScriptTest`, 백업은 `BackupFilesTest` · `BackupFilesScriptTest`, 워크플로는 `WorkflowFilesTest` · `WorkflowFilesScriptTest`로 나눴다. 설정 바인딩의 레포지토리 대역은 기존 `FakeUserRepository`를 쓴다. 기존 검증 사실과 아키텍처 규칙 · 위반 픽스처 · 제품 클래스 검사 범위는 유지했다.

## 1. 종류 — 무엇으로 무엇을 보나

| 종류 | 붙이는 것 | 대상 | 보는 것 | 보지 않는 것(다른 종류가 본다) |
|---|---|---|---|---|
| **단위** | 없음 · 대역이 있으면 `@ExtendWith(MockitoExtension.class)` | 도메인 · 서비스 · 필터 · 정책. 설정 바인딩은 `ApplicationContextRunner` | 규칙 · 분기 · 결과 상태 | DB · HTTP |
| **컨트롤러 슬라이스** | `@WebMvcTest(대상 컨트롤러)` · `MockMvcTester` | 컨트롤러 | 요청 바인딩 · `@Valid` · 응답 JSON 모양 · 상태 코드 · `ApiException` 이 오류 응답으로 바뀌는 것 | 인증 · CSRF · 401/403 · 서블릿 ERROR 디스패치 — **보안 필터를 끄고 로그인한 회원만 넣는다**. `@WebMvcTest` 는 `SecurityConfiguration` 의 필터 체인 `@Bean` 을 올리지 않아 Boot 기본 보안이 걸리기 때문이다 |
| **레포지토리** | `@RepositoryTest` | Spring Data 레포지토리 · `EntityManager` 쿼리 | 쿼리 · 매핑 · 제약 · 지연 로딩 · Auditing 시각 | 서비스 규칙 |
| **통합** | `@IntegrationTest` | 핵심 흐름 · 보안 · 오류 처리 · **DB 동작이 곧 규칙인 서비스** | 실제 포트 HTTP · 실제 커밋 | — |
| **컨테이너 단독** | 없음 — 공유 컨테이너(`PostgresTestConfiguration.POSTGRES`)만 쓴다 | 마이그레이션처럼 Spring 없이 DB 가 필요한 것 | 자기 스키마를 만들고 끝나면 지운다 | — |

- 서비스는 단위 테스트(Fake)가 기본이다. advisory lock · `SKIP LOCKED` · 조건부 `UPDATE` 의 `0 rows` · 꺼낸 횟수(`claim_count`) · unique 제약 경합처럼 **DB 가 어떻게 잠그고 몇 행을 바꿨는지가 규칙인 것**(AGENTS 금지 8~11 · 14 · 16)은 Fake 로 흉내 내지 않고 통합 테스트로 확인한다.
- 보안 필터 · 오류 처리는 실제 포트로 확인한다 — MockMvc 는 서블릿 ERROR 디스패치를 거치지 않는다(1-auth step 1).
- 서버 이미지 검사(`imageTest`)는 위 종류 밖이며 기본 `test` 에서 뺀다(`AGENTS.md` 명령어의 `imageTest`).

## 2. 이름

- **클래스** — `@IntegrationTest` 만 `*IntegrationTest`, 나머지는 모두 `*Test`. `IT` 약어는 쓰지 않는다. 대상 클래스 이름으로 시작해 TDD 가드의 짝 규칙(`Foo*Test.java`)에 맞춘다 — `LoginService` → `LoginServiceTest` · `LoginServiceConcurrencyIntegrationTest`.
- **메서드** — 영어 camelCase 로 동작을 서술한다(`rejectsUnverifiedEmailBeforeQueryingDatabase`). `should` 접두 · 밑줄 · `대상메서드_상황_결과` 틀은 쓰지 않는다.
- **`@DisplayName`** — 모든 테스트 메서드에 한국어 문장으로 단다. 파라미터 테스트는 `@ParameterizedTest(name = …)` 로 값이 보이게 한다.
- **`@Nested` 는 쓰지 않는다.** 클래스가 커지면 기능별로 파일을 나눈다(`CourseSchemaRepositoryTest` · `CardSchemaRepositoryTest`). 겹치는 `@BeforeEach` 는 given 일부를 본문 밖으로 숨긴다.

## 3. 본문

```java
@Test
@DisplayName("재로그인은 기존 회원 ID를 돌려주고 회원을 새로 만들지 않는다")
void returnsExistingUserIdOnRelogin() {
    // given
    var existing = users.save(UserFixtures.user("admin@example.com"));

    // when
    var result = loginService.login(account(existing.getSubject(), "admin@example.com"));

    // then
    assertThat(result).isEqualTo(new LoginResult.Success(existing.getId()));
    assertThat(users.count()).isEqualTo(1);
}
```

- `// given` · `// when` · `// then` 주석으로 나눈다. 준비할 것이 없으면 `// given` 을 생략한다.
- 예외는 `// when & then` 하나로 `assertThatThrownBy` 를 쓴다.
- `// when` 아래에는 **검증하려는 호출 하나만** 둔다. 앞 단계(첫 로그인 등)는 given 으로 옮기고, 확인할 동작이 둘이면 테스트를 나눈다.
- 주석 뒤에 짧은 설명을 붙여도 된다(`// then — 운영과 같이 지연 로딩이 터진다`).
- **단언은 AssertJ 만 쓴다** — JUnit `Assertions` 는 쓰지 않는다. JSON 은 `MockMvcTester` 의 JSON 단언이나 Spring 테스트의 `JsonCompareMode.STRICT` 비교로 본다.

## 4. 테스트 데이터 · 대역

- **픽스처** — `{엔티티}Fixtures`(테스트 쪽의 엔티티와 같은 패키지 — `account/domain/UserFixtures`). 정적 팩토리를 불러 **저장하지 않은** 엔티티를 돌려주고, 저장은 테스트가 레포지토리 · Fake 로 한다. **테스트가 확인하는 값은 인자로 받아 본문에 보이게 하고** 픽스처 기본값에 숨기지 않는다.
- **ID · 상태** — 팩토리 · 도메인 메서드로 만들 수 없는 값(ID · 아직 바꾸는 메서드가 없는 상태)만 픽스처 · Fake 안에서 `ReflectionTestUtils` 로 넣는다. 테스트 본문에서는 리플렉션을 쓰지 않는다. 그 상태를 바꾸는 도메인 메서드가 생기면 그것을 쓴다.
- **레포지토리 인터페이스** — Spring Data 의 `Repository<T, ID>` 만 상속하고 **그 도메인이 쓰는 동작만 선언한다** — 저장 · 조회 · 주인 확인 조회(`save` · `findById` · `findByIdAndUserId` …)처럼 제품 코드가 쓰거나 그 도메인의 생성 · 조회 흐름이 쓸 것이다. `JpaRepository` · `CrudRepository` 계열을 상속하지 않는다. 정리 · 집계에만 쓰는 메서드(`deleteById` · `deleteAll` · `count` · `findAll`)는 선언하지 않는다 — 정리는 §5 의 정리기가 하고, 집계는 테스트가 SQL 로 센다.
- **Fake** — `Fake{레포지토리}`(테스트 쪽의 레포지토리와 같은 패키지 — `account/repository/FakeUserRepository`). 메모리 `Map` 에 담고 인터페이스에 선언된 메서드만 구현한다. 저장할 때 ID 를 채운다. Auditing 시각은 비어 있으므로 시각은 레포지토리 테스트가 본다. 그 레포지토리를 쓰는 단위 테스트가 생길 때 만든다.
- **mock** — `@ExtendWith(MockitoExtension.class)` · `@Mock` · BDDMockito(`given(…).willReturn(…)` · `then(mock).should(…)`). 쓰는 곳은 셋이다.
    - 우리 시스템 경계 밖 — LLM · 임베딩 클라이언트 · Google OAuth 등
    - `@WebMvcTest` 의 컨트롤러가 기대는 서비스 — `@MockitoBean`
    - 호출 여부 · 순서 자체가 규칙인 곳 — 예: 로그인 허용 검사는 DB 접근 전(`LoginServiceTest`) → `then(users).shouldHaveNoInteractions()`
    - 레포지토리는 마지막 경우가 아니면 mock 하지 않고 Fake 를 쓴다.
- **시계** — 단위 테스트는 `Clock.fixed(…)`. 컨텍스트 테스트(`@RepositoryTest` · `@IntegrationTest`)는 공용 `TestClock` 하나를 쓴다 — 정해진 시각에서 시작하고 테스트가 `set` · `advance` 로 옮기며, 정리기가 테스트마다 처음 시각으로 되돌린다. 클래스마다 시계 설정을 따로 두지 않는다.

## 5. DB 격리 · 컨텍스트

- **테스트는 트랜잭션을 걸지 않는다** — 테스트 롤백(`@Transactional` 테스트 · `@DataJpaTest` 기본값)을 쓰지 않는다. 테스트 전체가 트랜잭션 하나면 지연 로딩이 늘 성공하고(금지 4 · 27), `findById` 가 1차 캐시를 돌려주며, flush 시점 제약 위반이 숨고, 다른 스레드가 데이터를 보지 못한다.
- **테스트마다 `DatabaseCleaner` 가 `flyway_schema_history` 를 뺀 모든 테이블을 비운다.** UUID 로 행을 가르거나 자식부터 지우는 `@AfterEach` 를 쓰지 않는다 — 고정 값으로 테스트 데이터를 쓴다. DB 를 공유하므로 테스트 클래스를 병렬로 돌리지 않는다. 2026-11-08 현재 7개 앱 테이블에 각 1행을 넣고 PostgreSQL 17 공유 JDBC 연결에서 방식별 30회 예열 후 200회를 교대로 측정한 결과(카탈로그 조회 · 정리 · 커밋 포함, 데이터 준비 제외), 테스트당 `DELETE` 2.660ms · `TRUNCATE CASCADE` 2.525ms여서 `TRUNCATE CASCADE`를 선택했다.
- **`@RepositoryTest`** — `@DataJpaTest` · 테스트 DB 교체 끔 · 테스트 트랜잭션 끔 · `TestClock` · Auditing 설정 · 정리기를 묶은 테스트 전용 어노테이션.
- **`@IntegrationTest`** — `@SpringBootTest(webEnvironment = RANDOM_PORT)` · 공유 컨테이너 · `TestClock` · 공용 테스트 설정 · 정리기를 묶은 테스트 전용 어노테이션.
- **컨텍스트를 나누지 않는다** — 클래스마다 `@Import` · `properties` · `@MockitoBean` · `@MockitoSpyBean` 을 더하면 Spring 이 컨텍스트를 새로 띄운다. 그래서 더하지 않는다.
    - 테스트 전용 빈(검증용 · 실패용 컨트롤러 등)은 공용 `@TestConfiguration` 의 중첩 static 클래스로 두어 모든 통합 테스트에 함께 올린다. Spring Boot 4.1.1 의 `TestTypeExcludeFilter` 는 `@TestConfiguration` 클래스와 그 중첩 클래스를 컴포넌트 스캔에서 뺀다(`spring-boot-test-4.1.1-sources.jar` 의 `match` 로 확인).
    - 다른 설정값이 꼭 필요한 테스트는 이름 있는 묶음 어노테이션을 따로 만들고 이유를 그 어노테이션에 적는다.
    - `@Entity` · Spring Data `Repository` 픽스처는 `com.mogum` 밖 패키지에 둔다 — `ddl-auto: validate` 라 테이블 없는 엔티티 하나로 모든 컨텍스트가 뜨지 않는다(`.claude/commands/harness.md` 원칙 22).

## 6. 요청 도구

- 컨트롤러 슬라이스 — `MockMvcTester`.
- 통합 — `RestTestClient` 로 실제 포트에 보낸다. 세션 쿠키 · `XSRF-TOKEN` 처리는 `support` 의 도우미가 맡는다. 실제 포트 테스트 안에서 MockMvc 를 쓰지 않는다.

## 7. 빌드가 지키는 것(ArchUnit)

- 테스트 코드에서 JUnit `Assertions` 를 쓰지 않는다.
- `ReflectionTestUtils` · `setAccessible` 은 픽스처(`*Fixtures`) · Fake(`Fake*`) 안에서만 쓴다.
- 테스트 클래스 이름이 `IT` 로 끝나지 않는다.
- 제품 코드의 레포지토리는 `JpaRepository` · `CrudRepository` 계열을 상속하지 않는다.

규칙마다 위반 픽스처(`archfixture` · `repositoryfixture`)로 실제로 걸리는지 `ArchitectureTest` 가 확인한다.
