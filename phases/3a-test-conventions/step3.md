# Step 3: web-slice

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/TEST_GUIDE.md` 전체 — **이 phase 의 계약이다.** 특히 §1(컨트롤러 슬라이스 — 보안 필터를 끄고 로그인한 회원만 넣는다) · §2 · §3 · §6(`MockMvcTester`)
- `docs/ADR.md` — ADR-079 · ADR-074(응답 · 오류 형식)
- `docs/05-api.md` §1 · §1.2 — 성공 응답 `{ data }` · 오류 형식 · `code` 목록
- `AGENTS.md` — 금지 29 · 30 · 작업 방식(TDD · 백엔드 테스트 줄)
- step 0~2 가 만든 것: `backend/src/test/java/com/mogum/support/` · `account/domain/UserFixtures.java`(이전 step summary 에 이름이 있다) · `backend/build.gradle` 의 테스트 의존성
- 제품 코드: `backend/src/main/java/com/mogum/account/controller/MeController.java` · `account/dto/` · `common/api/*.java` · `MogumApplication.java`(`@ConfigurationPropertiesScan`)
- 옮길 테스트: `backend/src/test/java/com/mogum/account/controller/MeControllerTest.java` · `common/api/ApiExceptionHandlerTest.java` · `common/api/ApiErrorControllerTest.java` · `common/api/ApiErrorResponsesTest.java` · `common/api/ApiResponseTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`3a-test-conventions`)는 백엔드 테스트를 `docs/TEST_GUIDE.md` 의 규칙으로 통일한다. **코드가 가이드와 어긋나면 코드를 가이드에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 가이드의 규칙을 고치지 마라.**

이 step 은 웹 층의 슬라이스 · 단위 테스트를 맡는다. 실제 포트가 필요한 보안 · 오류 디스패치 · 검증 흐름(`RequestValidationIT` 등)은 step 4 다.

## 작업

### 1. `MeControllerTest` — `@WebMvcTest`

- `@WebMvcTest(MeController.class)` + `MockMvcTester` 로 `GET /api/me` 를 보낸다.
- **보안 필터를 끄고 로그인한 회원만 넣는다**(TEST_GUIDE §1). `@WebMvcTest` 는 `auth.SecurityConfiguration` 의 필터 체인 `@Bean` 을 올리지 않아 Boot 기본 보안이 걸린다 — 기본 보안의 401 · CSRF 는 우리 동작이 아니다. `SecurityConfiguration` 을 `@Import` 하지 마라. 필터를 끄는 방법과 `@AuthenticationPrincipal User` 에 회원을 넣는 방법은 Boot 4.1.1 · Spring Security 7 에서 동작하는 것을 확인해서 쓴다.
- 회원은 `UserFixtures` 로 만든다(필요하면 ID · 약관 동의 시각을 픽스처 도우미로).
- 확인할 것: 지금 `MeControllerTest` 의 사실(공개 필드 · 약관 동의 변환 · `waitlistPosition` null)을 **응답 JSON** 으로 — `{ data: { … } }` 로 감싸이고 필드가 05 와 같다(`JsonCompareMode.STRICT` 또는 `MockMvcTester` 의 JSON 단언).
- 슬라이스에 JPA · DB 가 올라오지 않아야 한다. 컨텍스트가 뜨지 않는 원인이 있으면(예: 설정 클래스가 레포지토리를 요구) 테스트 쪽에서 해결하고, 제품 코드를 고쳐야만 한다면 `blocked`.

### 2. `common.api` 테스트

- `ApiExceptionHandlerTest` · `ApiErrorControllerTest` · `ApiErrorResponsesTest` 를 TEST_GUIDE §2 · §3 형식으로 고친다. 이미 단위 · standalone MockMvc 로 도는 테스트는 그 방식을 유지해도 되며, MockMvc 를 쓰는 곳은 `MockMvcTester` 로 바꾼다.
- `ApiResponseTest` 는 지금 `@SpringBootTest` + Postgres 로 앱 전체를 띄워 `ObjectMapper` 하나를 받는다. **앱 전체를 띄우지 않는 방법**으로 바꾼다 — 확인하는 사실은 Boot 가 구성한 JSON 설정(값이 없는 필드도 `null` 로 싣는다 · `Instant` 는 ISO-8601 UTC 문자열 · 최상위는 `data` 하나)이므로, Boot 의 Jackson 자동 설정을 그대로 올리는 슬라이스(Boot 4.1.1 에서 쓸 수 있는 것을 확인해서)를 쓴다. 손으로 만든 `JsonMapper` 로 바꾸지 마라 — 운영 설정과 어긋나도 통과한다.
- 로그에 입력값이 남지 않는지 보는 표식 문자열 검사가 있으면 유지한다(AGENTS 금지 30).

### 3. 형식

- 고친 모든 테스트는 TEST_GUIDE §2(영어 camelCase 메서드 · 한국어 `@DisplayName` · 파라미터 테스트 이름) · §3(given/when/then · `// when` 아래 호출 하나 · 예외 `// when & then` · AssertJ 만) · §4(mock 은 `MockitoExtension` · BDDMockito)를 따른다.
- **옮기는 테스트가 지금 확인하는 사실을 빠뜨리지 마라.** 한 테스트가 여러 동작을 확인하면 나눈다. 빼는 사실이 있으면 무엇을 왜 뺐는지 summary 에 적는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*MeControllerTest' --tests 'com.mogum.common.api.*'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `MeControllerTest` 가 `@WebMvcTest` + `MockMvcTester` 이고, `SecurityConfiguration` 을 올리지 않으며, DB 없이 도는가?
   - `ApiResponseTest` 가 앱 전체(`@SpringBootTest`) · Postgres 없이 Boot 의 Jackson 설정으로 도는가?
   - 고친 테스트에 JUnit `Assertions` · Mockito `when`/`verify` 가 남지 않았고, 모든 테스트 메서드에 `@DisplayName` 이 있는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3a-test-conventions/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 슬라이스에서 보안 필터를 끈 방법 · 회원을 넣은 방법 · `ApiResponseTest` 에 쓴 슬라이스 · 뺀 사실과 이유 · 서버 테스트 수(전후)(다음 컨트롤러 테스트가 같은 방법을 쓴다)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (가이드대로 만들 수 없음 · 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 컨트롤러 슬라이스에서 인증 · CSRF · 401/403 을 확인하지 마라. 이유: TEST_GUIDE §1 — 보안은 실제 포트의 통합 테스트(step 4)가 본다.
- `RequestValidationIT` · `SecurityConfiguration*IT` · `RequestLogFilterIT` 등 실제 포트 테스트를 옮기거나 MockMvc 로 바꾸지 마라. 이유: step 4 의 범위이고, MockMvc 는 서블릿 ERROR 디스패치를 거치지 않는다(원칙 13).
- 제품 코드를 고치지 마라. 이유: 이 step 은 테스트만 옮긴다.
- 가이드 · ADR 의 규칙 문장을 고치지 마라. 이유: 계약은 설계 커밋에서 정했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
