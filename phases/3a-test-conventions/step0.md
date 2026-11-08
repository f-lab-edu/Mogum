# Step 0: test-support

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/TEST_GUIDE.md` 전체 — **이 phase 의 계약이다.** 특히 §1(종류) · §4(시계) · §5(DB 격리 · 컨텍스트) · §6(요청 도구)
- `docs/ADR.md` — ADR-079(이 phase 의 결정) · ADR-078(Auditing 이 `Clock` 빈을 쓴다)
- `AGENTS.md` — 작업 방식(TDD · 백엔드 테스트 줄) · 명령어
- `backend/build.gradle` — 테스트 의존성 · `Test` 작업 설정
- `backend/src/main/java/com/mogum/config/ClockConfiguration.java` · `config/JpaAuditingConfiguration.java` · `MogumApplication.java`
- `backend/src/main/resources/application.yml` · `backend/src/main/resources/db/migration/`(테이블 목록)
- `backend/src/test/java/com/mogum/support/PostgresTestConfiguration.java` · `support/TestSessions.java`
- `backend/src/test/java/com/mogum/DatabaseSetupIT.java` — 공유 컨테이너를 서로 다른 컨텍스트가 함께 쓰는지 보는 지금의 테스트
- `backend/src/test/java/com/mogum/common/api/RequestValidationIT.java` 의 `setUp` — 세션 쿠키와 `XSRF-TOKEN` 을 손으로 다루는 지금의 방식
- `.claude/commands/harness.md` 원칙 22 — 테스트 전용 컴포넌트를 스캔에 넣지 않는 규칙

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`3a-test-conventions`)는 백엔드 테스트를 `docs/TEST_GUIDE.md` 의 규칙으로 통일하고 기존 테스트 43개를 옮긴다. 규칙(TEST_GUIDE · ADR-079)은 설계 커밋으로 이미 정했다. **코드가 가이드와 어긋나면 코드를 가이드에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 가이드의 규칙을 고치지 마라** — 아래 「6. 문서」 에 적은 사실 갱신만 한다.

이 step 은 뒤 step 들이 쓸 **테스트 지원 코드만** 만든다. 기존 테스트는 옮기지 않는다(step 1~5).

## 작업

모든 지원 코드는 `backend/src/test/java/com/mogum/support/` 에 둔다.

### 1. 테스트 의존성

- `@DataJpaTest` · `@WebMvcTest` · `RestTestClient` 자동 설정에 필요한 테스트 의존성을 `backend/build.gradle` 에 더한다. Spring Boot 4.1.1 의 `spring-boot-test-autoconfigure-4.1.1.jar` 에는 `@DataJpaTest` · `@WebMvcTest` 가 **없다**(확인함) — 기술별 테스트 모듈로 나뉘어 있다. **모듈 이름은 Boot 4.1.1 BOM(`spring-boot-dependencies`)에서 확인해서 쓰고**, 버전은 적지 않는다(BOM 이 관리한다). `@AutoConfigureTestDatabase` · `@AutoConfigureMockMvc` 등 이 phase 가 쓸 어노테이션이 어느 모듈에 있는지도 함께 확인한다.
- AssertJ 는 `spring-boot-starter-test` 에 이미 있다 — 따로 더하지 마라.
- 더한 모듈 이름과 각 모듈에서 쓰는 어노테이션 · 클래스를 summary 에 적는다.

### 2. `TestClock`

- `java.time.Clock` 을 상속한 테스트 시계. 정해진 상수 시각(UTC, 마이크로초 아래 자리 없음)에서 시작한다.
- `set(Instant)` · `advance(Duration)` · 처음 시각으로 되돌리는 메서드를 둔다. `instant()` 는 지금 시각을 돌려주고 스스로 흐르지 않는다.
- 컨텍스트에는 **`clock` 과 다른 이름의 `@Primary` `Clock` 빈**으로 넣는다. 이유: Boot 는 빈 덮어쓰기를 막아 같은 이름이면 컨텍스트가 뜨지 않는다. 이 빈을 담는 `@TestConfiguration` 을 support 에 둔다. Auditing(`JpaAuditingConfiguration`)과 `SeoulClock` 이 이 시계를 쓰게 된다.

### 3. `DatabaseCleaner`

- JUnit 5 확장. **각 테스트는 빈 테이블과 `TestClock` 의 처음 시각에서 시작한다** — 비우는 시점(테스트 앞 · 뒤)은 이 조건을 지키는 쪽으로 정한다.
- 대상은 앱 스키마의 **모든 테이블에서 `flyway_schema_history` 를 뺀 것**이다. 테이블 목록을 코드에 적지 말고 DB 카탈로그에서 읽는다 — 테이블이 늘어도 정리기를 고치지 않게 한다. FK 는 기본 삭제 동작(NO ACTION — AGENTS 금지 31)이므로 순서나 `CASCADE` 를 고려한다.
- 같은 컨텍스트의 `DataSource` · `TestClock` 을 쓴다(Spring 테스트 컨텍스트에서 꺼낸다).
- `TRUNCATE` 와 `DELETE` 중 무엇을 쓸지는 **지금 테이블로 둘 다 재 보고** 빠른 쪽을 고른다. 잰 방법 · 숫자(테스트 하나당 ms)를 summary 와 아래 「6. 문서」 에 적는다.

### 4. 묶음 어노테이션 · 공용 설정

- **`@RepositoryTest`** — `@DataJpaTest` · 테스트 DB 교체 끔(내장 DB 로 바꾸지 않는다) · 테스트 트랜잭션 끔 · `TestClock` · `config.JpaAuditingConfiguration` · 공유 컨테이너 연결 · `DatabaseCleaner` 를 묶는다.
- **`@IntegrationTest`** — `@SpringBootTest(webEnvironment = RANDOM_PORT)` · 공유 컨테이너 연결 · `TestClock` · 공용 테스트 설정 · `DatabaseCleaner` 를 묶는다.
- **공용 테스트 설정** — `@IntegrationTest` 가 늘 올리는 `@TestConfiguration` 클래스 하나. 지금은 비어 있어도 된다 — step 4 가 테스트 전용 컨트롤러 등을 이 클래스의 **중첩 static 클래스**로 옮긴다. Spring Boot 4.1.1 의 `TestTypeExcludeFilter.match` 는 `@TestConfiguration` 클래스와 그 중첩 클래스를 컴포넌트 스캔에서 뺀다(`spring-boot-test-4.1.1-sources.jar` 로 확인함).
- **공유 컨테이너** — 지금 `PostgresTestConfiguration` 은 상속으로 `@ServiceConnection` 을 준다. 묶음 어노테이션은 상속을 줄 수 없으므로 **어노테이션으로 붙일 수 있는 방식**으로 바꾸되 지금 조건을 지킨다: 컨테이너는 테스트 JVM 에 하나 · 서로 다른 컨텍스트가 함께 쓴다 · 컨텍스트가 닫혀도 멈추지 않는다(JVM 종료 뒤 Ryuk 이 정리). 쓸 방식은 Boot 4.1.1 의 동작을 확인해서 고르고, 확인한 근거를 그 클래스의 주석에 적는다. **기존 테스트가 쓰는 `PostgresTestConfiguration` 상속 · `POSTGRES` 정적 필드는 그대로 동작해야 한다** — 기존 테스트는 step 1~6 이 옮긴다.

### 5. 요청 도우미

- `RestTestClient` 를 실제 포트(`@IntegrationTest`)에 묶어 쓰는 도우미. 할 일: 회원 ID 로 세션 쿠키 만들기(지금 `TestSessions` 의 일 — 세션에는 회원 ID 만, AGENTS 금지 27) · `XSRF-TOKEN` 을 받아 상태를 바꾸는 요청에 붙이기. 이름과 모양은 정한다. 기존 `TestSessions` 는 기존 테스트가 쓰므로 지우지 않는다(옮겨진 뒤 step 6 이 정리한다).

### 6. 지원 코드의 테스트

TEST_GUIDE 형식(§2 이름 · `@DisplayName` · §3 given/when/then · AssertJ)으로 쓴다. 확인할 것:

- `@RepositoryTest` 안에서 테스트 트랜잭션이 없다. 저장한 엔티티를 다시 읽어 LAZY 연관을 트랜잭션 밖에서 꺼내면 `LazyInitializationException` 이다(지금 엔티티의 LAZY 연관을 쓴다).
- `@RepositoryTest` 의 저장 시각이 `TestClock` 의 시각이다(Auditing).
- 한 테스트가 넣은 행 · 옮긴 시계가 다음 테스트에 남지 않는다 — 순서가 정해진 두 테스트로 확인한다(`@TestMethodOrder`).
- `@IntegrationTest` 두 클래스가 같은 Spring 컨텍스트를 쓴다 — 확인할 방법을 정해 테스트로 남긴다.
- 요청 도우미로 세션 쿠키를 붙인 `GET /api/me` 가 200 이고, `XSRF-TOKEN` 을 붙인 상태 변경 요청이 CSRF 로 거절되지 않는다(`403 CSRF_INVALID` 가 아니다).
- 정리기가 테이블 목록을 카탈로그에서 읽는다 — `flyway_schema_history` 는 남는다.

### 7. 문서 (구현으로 정해진 사실만)

- `docs/TEST_GUIDE.md` §5 — `DatabaseCleaner` 줄 끝에 고른 방식(`TRUNCATE` / `DELETE`)과 잰 숫자를 한 문장으로 더한다. 규칙 문장은 고치지 않는다.
- `docs/ARCHITECTURE.md` 41줄(`src/test/java/…` 설명) — `support`(묶음 어노테이션 · 정리기 · 테스트 시계 · 요청 도우미)를 더한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 지원 코드가 모두 `backend/src/test/java/com/mogum/support/` 에 있는가? 제품 코드(`backend/src/main`)를 바꾸지 않았는가?
   - 테스트 의존성에 버전을 적지 않았는가(BOM 관리)?
   - 정리기가 테이블 이름을 코드에 나열하지 않는가?
   - 기존 테스트를 하나도 옮기거나 지우지 않았는가 — 기존 테스트가 모두 통과하는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3a-test-conventions/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 더한 테스트 모듈 이름 · 지원 클래스 전체 이름(묶음 어노테이션 · 공용 설정 · 시계 빈 이름 · 정리기 · 요청 도우미) · 공유 컨테이너를 붙이는 방식 · 정리 방식과 잰 숫자 · 서버 테스트 수(전후)를 넣는다(다음 step 이 그대로 쓴다)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (가이드대로 만들 수 없음 · 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 기존 테스트를 옮기거나 고치지 마라. 이유: step 1~6 의 범위다. 공유 컨테이너 방식을 바꾸느라 `PostgresTestConfiguration` 을 고칠 때도 기존 테스트의 상속 · `POSTGRES` 사용이 그대로 동작해야 한다.
- 테스트 클래스를 병렬로 돌리는 설정을 넣지 마라. 이유: DB 를 공유하고 정리기가 모든 테이블을 비운다(TEST_GUIDE §5).
- 정리기가 테이블을 지울 때 `flyway_schema_history` 를 건드리지 마라. 이유: 지우면 다음 컨텍스트의 Flyway 가 스키마를 다시 만들려다 실패한다.
- `Clock` 빈을 `clock` 이라는 이름으로 덮어쓰지 마라. 이유: 빈 덮어쓰기가 막혀 있어 컨텍스트가 뜨지 않는다 — 다른 이름의 `@Primary` 로 넣는다.
- 테스트 전용 `@Component` · `@RestController` 를 공용 테스트 설정의 중첩 밖(`com.mogum` 아래 최상위 클래스)에 두지 마라. 이유: `@SpringBootTest` 의 스캔이 읽어 모든 컨텍스트에 섞인다(원칙 22).
- 제품 코드(`backend/src/main`)를 고치지 마라. 이유: 이 step 은 테스트 지원만 만든다.
- 가이드(`docs/TEST_GUIDE.md`) · ADR 의 규칙 문장을 고치지 마라 — 「7. 문서」 의 사실 갱신만 한다. 이유: 계약은 설계 커밋에서 정했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
