# Step 4: integration

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/TEST_GUIDE.md` 전체 — **이 phase 의 계약이다.** 특히 §1(통합 — 보안 · 오류 처리 · 핵심 흐름) · §5(컨텍스트를 나누지 않는다 · 공용 테스트 설정 · 이름 있는 묶음) · §6(`RestTestClient`)
- `docs/ADR.md` — ADR-079 · ADR-074 · ADR-075(운영 로그)
- `docs/05-api.md` §1 · §1.1 · §1.2 — 인증 · CORS · 쿠키 도메인 · 오류 형식
- `docs/04-jobs-and-cost.md` §9 · §9.2 — 로그 규칙과 줄 표(로그 테스트가 확인하는 것)
- `AGENTS.md` — 금지 27 · 29 · 30 · 작업 방식(TDD · 백엔드 테스트 줄)
- `.claude/commands/harness.md` 원칙 13(보안 · 오류는 실제 HTTP) · 22(테스트 전용 컴포넌트)
- step 0~3 이 만든 것: `backend/src/test/java/com/mogum/support/`(`@IntegrationTest` · 공용 테스트 설정 · 요청 도우미 · `TestClock` — 이전 step summary 에 이름이 있다) · `account/domain/UserFixtures.java` · `account/service/LoginServiceConcurrencyIntegrationTest.java`
- 옮길 테스트: `backend/src/test/java/com/mogum/auth/SecurityConfigurationIT.java` · `auth/SecurityConfigurationCrossOriginIT.java` · `auth/SecurityConfigurationHealthIT.java` · `auth/OAuthLoginHandlerIT.java` · `common/api/RequestValidationIT.java` · `common/logging/RequestLogFilterIT.java` · `DatabaseSetupIT.java`
- 이 테스트들이 기대는 제품 코드: `auth/*.java` · `common/logging/RequestLogFilter.java` · `config/RequestLogConfiguration.java` · `config/WebProperties.java` · `config/AuthProperties.java` · `backend/src/main/resources/application.yml`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`3a-test-conventions`)는 백엔드 테스트를 `docs/TEST_GUIDE.md` 의 규칙으로 통일한다. **코드가 가이드와 어긋나면 코드를 가이드에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 가이드의 규칙을 고치지 마라.**

이 step 은 앱 전체를 띄우는 테스트 7개를 `@IntegrationTest` 로 옮긴다. 지금 이 클래스들은 각자 다른 `@Import`(테스트 전용 컨트롤러) · `properties` · `@MockitoSpyBean` 을 붙여 컨텍스트가 클래스마다 새로 뜬다. 목표는 **통합 테스트가 컨텍스트 하나를 함께 쓰는 것**이다.

## 작업

### 1. 옮기기

- 7개 모두 `@IntegrationTest` 를 붙이고 이름을 `*IntegrationTest` 로 바꾼다(예: `SecurityConfigurationIT` → `Security*IntegrationTest`). 옛 파일은 지운다. 400줄을 넘는 파일(`SecurityConfigurationIT` 426줄 · `OAuthLoginHandlerIT` 473줄)은 기능별로 나눈다.
- `PostgresTestConfiguration` 상속 · 클래스별 `@Import` · `properties` · 시계 설정 · `@AfterEach` DB 정리 · UUID 행 가르기를 없앤다. 세션 저장소 · `HttpClient` 같은 DB 밖 자원의 정리는 남겨도 된다.
- HTTP 는 `RestTestClient`(step 0 의 요청 도우미)로 **실제 포트**에 보낸다. 실제 포트 테스트 안의 MockMvc(`SecurityConfigurationIT` 의 `MockMvcBuilders.webAppContextSetup`)를 없앤다. 이유: MockMvc 는 서블릿 ERROR 디스패치를 거치지 않는다(원칙 13 · 1-auth step 1).
- 형식: TEST_GUIDE §2 · §3(given/when/then · `// when` 아래 호출 하나 · 예외 `// when & then` · AssertJ 만).

### 2. 테스트 전용 빈 — 공용 테스트 설정으로

- `RequestValidationIT.ValidationController` · `RequestLogFilterIT` 의 `FailureController` · `ErrorDispatchProbe` · `FilterFailure` · `SecurityConfigurationIT.ServletErrors` 등 클래스별 `@Import` 로 붙이던 테스트 전용 빈을 **step 0 의 공용 테스트 설정의 중첩 static 클래스**로 옮긴다. 경로가 겹치지 않게 테스트 전용 경로를 정한다. 공용 설정 안에서는 모든 통합 테스트에 함께 올라온다 — 다른 테스트의 결과(예: 없는 API 의 404 · 보안 규칙)를 바꾸지 않는지 확인한다.
- 이 빈들이 제품 코드의 보안 규칙(어느 경로가 인증을 요구하는가)에 걸려 동작이 달라지면, 제품 코드를 바꾸지 말고 테스트 경로를 그 규칙 안에서 고른다.

### 3. 클래스마다 다른 설정값

지금 클래스마다 다른 값이 넷이다. 아래 순서로 해결하고 고른 방법을 summary 에 적는다.

| 지금 | 값 |
|---|---|
| `OAuthLoginHandlerIT` | `app.web.origin=https://mogum.example` · `app.auth.admin-emails=admin@example.com,changed@example.com` |
| `SecurityConfigurationCrossOriginIT` | `app.web.origin=https://mogum.example` · `app.web.cookie-domain=mogum.example` |
| `RequestLogFilterIT` | `server.tomcat.threads.max=1` · `server.tomcat.threads.min-spare=1`(스레드 재사용 뒤 MDC 가 남지 않는지 보려고) |
| `SecurityConfigurationIT` | 기본값 — 로컬 설정의 세션 쿠키에 `Domain` 이 없다 |

1. **모든 통합 테스트가 같은 값을 써도 되면** `@IntegrationTest` 의 공용 값으로 둔다(예: 출처 · 관리자 목록).
2. **값이 서로 부딪치면**(쿠키 `Domain` 있음 / 없음) 그 사실이 HTTP 를 거쳐야 하는지 본다. 설정 바인딩 · 쿠키 직렬화기 구성처럼 컨텍스트 일부로 볼 수 있으면 `ApplicationContextRunner` 단위 테스트(`*Test`)로 옮긴다.
3. 그래도 다른 값이 꼭 필요하면 **이름 있는 묶음 어노테이션**을 support 에 하나 만들고(예: 쿠키 도메인을 켠 통합 테스트) 그 어노테이션에 이유를 주석으로 적는다(TEST_GUIDE §5). 묶음은 최소로 둔다.

### 4. `@MockitoSpyBean`

- `SecurityConfigurationIT` 의 `MeController` · `ApiErrorController` 스파이와 `SecurityConfigurationHealthIT` 의 `DataSource` 스파이는 컨텍스트를 나눈다. 스파이로 확인하던 사실(예: 컨트롤러가 불리지 않았다 · 헬스가 DB 를 건드리지 않는다)을 **관찰할 수 있는 결과**(응답 · 공용 설정의 테스트 전용 빈 · 출력 캡처 · DB 접근 횟수를 세는 다른 방법)로 바꾼다.
- 바꿀 수 없는 사실만 3 의 방식(이름 있는 묶음)으로 남기고 이유를 적는다.

### 5. `DatabaseSetupIT`

- 지금 확인하는 사실(Flyway 적용 · pgvector · 서로 다른 컨텍스트가 같은 컨테이너를 쓴다)을 유지한다. "다른 컨텍스트" 는 `@IntegrationTest` 와 `@RepositoryTest` 처럼 실제로 쓰는 두 컨텍스트로 확인할 수 있다 — 방법은 정한다. 이름은 가이드에 맞게(`DatabaseSetup*IntegrationTest` 등).

### 6. 지키는 것

- **옮기는 테스트가 지금 확인하는 사실을 빠뜨리지 마라.** 특히 보안(401 JSON · 리다이렉트 없음 · CSRF 403 · 세션에 회원 ID 만 · 7일 만료 · 쿠키 속성 · CORS) · OAuth 가짜 서버의 검사(PKCE `code_verifier` 등 — 원칙 12 · 1-auth step 3) · 로그(줄 내용 · 원문 없음 · MDC 정리)의 사실. 한 테스트가 여러 동작을 확인하면 나눈다. 빼는 사실이 있으면 무엇을 왜 뺐는지 summary 에 적는다.
- 로그 출력 캡처(`OutputCaptureExtension`) 검사는 그대로 둔다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*IntegrationTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 옮긴 테스트에 클래스별 `@Import` · `properties` · `@MockitoBean` · `@MockitoSpyBean` 이 남지 않았는가(남긴 것은 이름 있는 묶음과 이유)?
   - 실제 포트 테스트 안에 MockMvc 가 남지 않았는가?
   - 테스트 전용 컴포넌트가 공용 테스트 설정의 중첩 static 클래스인가(원칙 22)?
   - 옛 7개 파일의 사실이 새 테스트에 모두 있는가(뺀 것은 summary 에 이유)?
   - 고친 테스트에 JUnit `Assertions` 가 남지 않았고, 모든 테스트 메서드에 `@DisplayName` 이 있는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3a-test-conventions/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 새 테스트 파일 이름 · 공용 값으로 둔 설정 · 만든 이름 있는 묶음과 이유 · 스파이를 바꾼 방법 · 뺀 사실과 이유 · 통합 테스트가 쓰는 컨텍스트 수 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (가이드대로 만들 수 없음 · 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 제품 코드(보안 설정 · 필터 · 설정 클래스 · `application.yml`)를 고치지 마라. 이유: 이 step 은 테스트만 옮긴다 — 테스트를 맞추려고 운영 동작을 바꾸면 그 동작이 검증 없이 바뀐다. 고쳐야만 하면 `blocked`.
- 실제 포트 검사를 MockMvc · `@WebMvcTest` 로 바꾸지 마라. 이유: 원칙 13.
- OAuth 가짜 서버가 하던 검사(PKCE 등)를 빼지 마라. 이유: 원칙 12 — 빼면 실제 연동만 깨진다.
- 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 04 §9.2 표에 없는 줄이다.
- 테스트 클래스를 병렬로 돌리지 마라. 이유: TEST_GUIDE §5.
- 가이드 · ADR 의 규칙 문장을 고치지 마라. 이유: 계약은 설계 커밋에서 정했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
