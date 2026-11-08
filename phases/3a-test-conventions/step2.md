# Step 2: domain-service

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/TEST_GUIDE.md` 전체 — **이 phase 의 계약이다.** 특히 §1(서비스는 단위 + Fake · DB 동작이 규칙이면 통합) · §2 · §3 · §4(Fake · mock 쓰는 곳 · 시계)
- `docs/ADR.md` — ADR-079 · ADR-071(정적 팩토리)
- `docs/02-domain-data.md` §6.1 — 로그인 규칙. **항목 2 「이메일 검증 · 관리자 허용 목록 검사는 DB 접근 전에 수행한다」**
- `AGENTS.md` — 금지 27 · 28 · 작업 방식(TDD · 백엔드 테스트 줄)
- step 0 · 1 이 만든 것: `backend/src/test/java/com/mogum/support/` · `account/domain/UserFixtures.java` · `curriculum/domain/CurriculumFixtures.java` · 레포지토리 5개(이전 step summary 에 이름이 있다)
- 제품 코드: `backend/src/main/java/com/mogum/account/service/LoginService.java` · `ExternalAccount.java` · `LoginResult.java` · `auth/SessionUserFilter.java` · `account/domain/User.java` · `curriculum/domain/*.java`
- 옮길 테스트: `backend/src/test/java/com/mogum/account/domain/UserTest.java` · `curriculum/domain/CurriculumGroupTest.java` · `CurriculumStepTest.java` · `CurriculumTest.java` · `LessonTest.java` · `account/service/LoginServiceTest.java` · `account/service/LoginServiceIT.java` · `auth/SessionUserFilterTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`3a-test-conventions`)는 백엔드 테스트를 `docs/TEST_GUIDE.md` 의 규칙으로 통일한다. **코드가 가이드와 어긋나면 코드를 가이드에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 가이드의 규칙을 고치지 마라.**

이 step 은 도메인 · 서비스 층(과 레포지토리 대역을 쓰는 인증 필터)을 맡는다. 지금 로그인 서비스는 mock 단위 테스트(`LoginServiceTest`)와 실제 DB 테스트(`LoginServiceIT`)가 같은 시나리오를 두 번 쓴다 — 이것을 가이드의 나눔으로 정리한다.

## 작업

### 1. `FakeUserRepository`

- `backend/src/test/java/com/mogum/account/repository/FakeUserRepository.java` — `UserRepository` 를 구현한다. 메모리 `Map` 에 담고 **인터페이스에 선언된 메서드만** 구현한다(TEST_GUIDE §4).
- 저장할 때 ID 가 없으면 채운다 — `UserFixtures` 의 ID 도우미를 쓴다(리플렉션은 픽스처 · Fake 안에서만).
- `findByProviderAndSubject` 는 실제 쿼리와 같은 뜻(제공자와 subject 가 둘 다 같다 · 대소문자 구분)으로 찾는다.
- 저장된 수를 테스트가 볼 수 있는 메서드는 Fake 에 둬도 된다(인터페이스에는 없다).

### 2. 도메인 테스트

- `UserTest` · `CurriculumGroupTest` · `CurriculumStepTest` · `CurriculumTest` · `LessonTest` 를 TEST_GUIDE §2 · §3 형식으로 고친다. 팩토리 자체가 검증 대상이므로 `// when` 에서 팩토리를 직접 부른다. 같은 타입 인자가 이어지는 팩토리는 채운 값을 getter 로 모두 확인한다(AGENTS 금지 28) — AssertJ `extracting` 등으로 묶어도 된다.

### 3. `LoginServiceTest` — 단위(Fake) + 문서 규칙만 mock

- 기본은 `FakeUserRepository` 로 결과 상태를 확인한다(회원이 만들어졌는지 · 같은 ID 인지 · 저장된 수).
- **허용 검사가 DB 접근 전이라는 사실(02 §6.1 항목 2)** — 미인증 · 목록 밖 · 빈 목록에서 레포지토리를 전혀 부르지 않는다 — 은 호출 여부가 규칙이므로 mock 레포지토리(`@Mock` · `MockitoExtension`)와 `then(users).shouldHaveNoInteractions()` 로 확인한다(TEST_GUIDE §4 mock 셋째).
- 역할 계산(`roleFor`)처럼 레포지토리와 무관한 검사는 Fake 든 mock 이든 레포지토리를 건드리지 않는 쪽으로 쓴다.

### 4. `LoginServiceIT` 나누기

- 각 테스트가 확인하는 사실을 아래 기준으로 옮기고 `LoginServiceIT` 를 지운다.
    - **서비스 규칙**(거절하면 회원을 만들지 않는다 · 첫 로그인은 ACTIVE ADMIN · 재로그인은 같은 ID · 다른 제공자의 같은 subject 는 다른 회원) → `LoginServiceTest`(Fake).
    - **DB 가 지키는 사실**(외부 식별자가 컬럼에 저장된다 · 가입 시각이 `Clock` 의 시각이다 · (provider, subject) unique) → step 1 의 `UserRepositoryTest` 에 이미 있으면 옮기지 않고, 없으면 거기에 더한다.
    - **JPA 의 변경 감지 · 커밋에 기대는 사실**(재로그인이 회원 행 — 이메일 · 역할 · 상태 · 약관 · 가입 시각 · 외부 식별자 — 을 바꾸지 않는다) → Fake 로는 커밋되지 않은 변경을 볼 수 없으므로 통합 테스트에 둔다.
    - **동시 첫 로그인은 unique 제약이 회원 하나만 남긴다** → `backend/src/test/java/com/mogum/account/service/LoginServiceConcurrencyIntegrationTest.java`(`@IntegrationTest`). 두 요청이 조회를 함께 지나게 하는 지금 방식(`CyclicBarrier` · 조회만 가로채는 대역)은 유지해도 된다 — 다만 `@MockitoSpyBean` 등으로 컨텍스트 설정을 더하지 마라(TEST_GUIDE §5). 지금처럼 서비스를 `new` 로 만들고 실제 레포지토리를 감싼 대역을 넘기는 방식은 컨텍스트를 바꾸지 않는다.
    - 회원 행 보존(위 셋째 줄)도 같은 통합 테스트 클래스 또는 `LoginService*IntegrationTest` 에 둔다.
- 모든 사실이 어딘가에 남아야 한다. 어디로 옮겼는지 summary 에 적는다.

### 5. `SessionUserFilterTest`

- mock `UserRepository` 를 `FakeUserRepository` 로 바꾼다. 회원 ID 주입은 `UserFixtures` 의 도우미를 쓴다 — 테스트 본문의 `setAccessible` 을 없앤다.
- 필터가 "레포지토리를 부르지 않는다" 를 규칙으로 확인하는 테스트가 있으면 그것만 mock 으로 둔다.
- `SecurityContextHolder` · `MDC` 를 지우는 정리(`@AfterEach`)는 DB 정리가 아니므로 유지한다.

### 6. 형식

- 고친 · 새로 쓴 모든 테스트는 TEST_GUIDE §2(영어 camelCase 메서드 · 한국어 `@DisplayName` · 파라미터 테스트 이름) · §3(given/when/then · `// when` 아래 호출 하나 · 예외 `// when & then` · AssertJ 만) · §4(BDDMockito `given` · `then`)를 따른다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*LoginService*' --tests '*SessionUserFilterTest' --tests 'com.mogum.account.domain.*' --tests 'com.mogum.curriculum.domain.*'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `LoginServiceIT` 의 사실이 모두 새 위치에 있는가(summary 의 대응표와 대조)?
   - mock 레포지토리가 "DB 접근 전 거절" 처럼 호출 여부가 규칙인 테스트에만 남았는가?
   - Fake 가 인터페이스에 없는 동작을 제품 코드 쪽에 요구하지 않는가? 테스트 본문에 리플렉션이 없는가?
   - 고친 테스트에 JUnit `Assertions` · Mockito `when`/`verify` 가 남지 않았고, 모든 테스트 메서드에 `@DisplayName` 이 있는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3a-test-conventions/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — `FakeUserRepository` 경로 · `LoginServiceIT` 사실별 옮긴 곳 · 새 통합 테스트 이름 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (가이드대로 만들 수 없음 · 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 제품 코드(`LoginService` · `SessionUserFilter` · 엔티티)를 고치지 마라. 이유: 이 step 은 테스트만 옮긴다. 테스트하기 어려우면 `blocked` 로 사유를 남긴다.
- "DB 접근 전 거절" 을 Fake 의 상태(회원 수 0)로만 확인하지 마라. 이유: 02 §6.1 항목 2 는 조회조차 하지 않는다는 규칙이다 — 회원이 없다는 것으로는 조회했는지 알 수 없다.
- 동시성 · 변경 감지에 기대는 사실을 Fake 로 흉내 내지 마라. 이유: TEST_GUIDE §1 — DB 가 어떻게 잠그고 커밋하는지가 규칙인 것은 통합 테스트로 본다.
- 컨트롤러 · 다른 통합 테스트 · 남은 단위 테스트를 옮기지 마라. 이유: step 3~5 의 범위다.
- 가이드 · ADR 의 규칙 문장을 고치지 마라. 이유: 계약은 설계 커밋에서 정했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
