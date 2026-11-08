# Step 1: repositories

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/TEST_GUIDE.md` 전체 — **이 phase 의 계약이다.** 특히 §2 · §3 · §4(픽스처 · ID · 레포지토리 인터페이스) · §5 · §7
- `docs/ADR.md` — ADR-079 · ADR-077(같은 도메인 부모는 `@ManyToOne(LAZY)`, 다른 도메인은 ID) · ADR-078(Auditing)
- `AGENTS.md` — 금지 4 · 28 · 31 · 32 · 작업 방식(TDD · 백엔드 테스트 줄)
- `docs/02-domain-data.md` §6.1 · §6.2.1 — 회원 · 커리큘럼 테이블 제약
- step 0 이 만든 `backend/src/test/java/com/mogum/support/` 전체(이전 step summary 에 이름이 있다)
- 레포지토리 5개: `backend/src/main/java/com/mogum/account/repository/UserRepository.java` · `curriculum/repository/CurriculumGroupRepository.java` · `CurriculumStepRepository.java` · `CurriculumRepository.java` · `LessonRepository.java`
- 레포지토리를 쓰는 제품 코드: `account/service/LoginService.java` · `auth/SessionUserFilter.java` · `auth/SecurityConfiguration.java`
- 엔티티: `account/domain/User.java` · `curriculum/domain/*.java`
- 옮길 테스트: `backend/src/test/java/com/mogum/account/repository/UserRepositoryIT.java` · `curriculum/repository/CurriculumRepositoryIT.java` · `common/persistence/AuditedEntityIT.java`
- `backend/src/test/java/com/mogum/ArchitectureTest.java` — 기존 규칙과 위반 픽스처 검사 방식(`assertViolation`) · `archfixture/` · `entityfixture/`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`3a-test-conventions`)는 백엔드 테스트를 `docs/TEST_GUIDE.md` 의 규칙으로 통일한다. **코드가 가이드와 어긋나면 코드를 가이드에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 가이드의 규칙을 고치지 마라.**

이 step 은 레포지토리 층을 맡는다 — 인터페이스를 좁히고, 픽스처를 만들고, 레포지토리 · Auditing 테스트를 `@RepositoryTest` 로 옮긴다.

## 작업

### 1. 레포지토리 인터페이스 (제품 코드)

- 5개 모두 `JpaRepository` 대신 Spring Data `org.springframework.data.repository.Repository<T, Long>` 를 상속한다.
- 선언하는 메서드는 **그 도메인이 쓰는 동작**만이다(TEST_GUIDE §4): 저장 `save` · 조회 `findById` · 이미 있는 주인 확인 조회(`findByIdAndUserId` · `findByIdAndCurriculum_UserId` …) · 제품 코드가 쓰는 것(`findByProviderAndSubject`). 지금 테스트가 쓰는 저장 · 조회는 이 동작에 든다.
- `deleteById` · `deleteAll` · `count` · `findAll` 처럼 **정리 · 집계에만 쓰는 메서드는 선언하지 않는다.** 정리는 `DatabaseCleaner` 가, 집계는 테스트가 SQL(`JdbcTemplate`)로 한다.
- 메서드 시그니처는 `CrudRepository` 의 것과 같게 둔다(`<S extends T> S save(S entity)` · `Optional<T> findById(Long id)`) — Spring Data 가 같은 구현으로 처리한다.
- 인터페이스는 TDD 가드 대상이 아니다. 동작은 아래 레포지토리 테스트가 확인한다.

### 2. 레포지토리 상속을 지키는 ArchUnit 규칙

- `ArchitectureTest` 에 **"제품 코드의 레포지토리는 `JpaRepository` · `CrudRepository` · `ListCrudRepository` · `PagingAndSortingRepository` 계열을 상속하지 않는다"** 규칙을 더한다(TEST_GUIDE §7 넷째 줄). 운영 클래스(`CLASSES`)로 검사한다.
- 위반 픽스처(`JpaRepository` 를 상속한 Spring Data 인터페이스)는 **`com.mogum` 밖의 테스트 패키지**에 둔다(예: 기존 `entityfixture` 처럼). 이유: Spring Data 레포지토리 픽스처가 `com.mogum` 아래 있으면 `@SpringBootTest` 가 레포지토리로 등록해 모든 컨텍스트가 뜨지 않을 수 있다(원칙 22). 기존 위반 검사처럼 그 픽스처가 거절되는지 확인한다.
- 기존 규칙을 무르거나 고치지 마라.

### 3. 픽스처

- `backend/src/test/java/com/mogum/account/domain/UserFixtures.java` · `backend/src/test/java/com/mogum/curriculum/domain/CurriculumFixtures.java`(묶음 · 단계 · 커리큘럼 · 강의를 함께 다뤄도 된다 — 엔티티마다 나눌지는 정한다).
- 정적 팩토리를 불러 **저장하지 않은** 엔티티를 돌려준다. 테스트가 확인하는 값은 인자로 받는다 — 확인하지 않는 값만 기본값으로 채운다(TEST_GUIDE §4).
- ID 를 넣는 도우미(예: `withId(entity, id)`)를 픽스처에 둔다 — `ReflectionTestUtils` 는 픽스처 · Fake 안에서만 쓴다. 아직 바꾸는 도메인 메서드가 없는 상태(회원 `status` · 약관 동의 시각 등)를 넣는 도우미도 필요하면 픽스처에 둔다.

### 4. 테스트 옮기기 — `@RepositoryTest`

- `UserRepositoryIT` → `UserRepositoryTest` · `CurriculumRepositoryIT` → 기능별로 나눈 `Curriculum*RepositoryTest` 여러 개(지금 469줄 · 38개 — 주인 확인 조회 · 제약 · FK 삭제 동작 · LAZY · Auditing 처럼 기능별로) · `AuditedEntityIT` → `AuditedEntityTest`. 옛 파일은 지운다.
- 모두 `@RepositoryTest` 를 붙이고 `PostgresTestConfiguration` 상속 · 클래스별 시계 설정 · `@AfterEach` 정리 · UUID 로 행 가르기를 없앤다. 테스트 데이터는 고정 값 · 픽스처로 쓴다.
- 시계를 옮겨야 하는 검사(`updated_at` 만 바뀐다 등)는 `TestClock` 을 주입받아 `set` · `advance` 로 한다. `Clock` mock 을 쓰지 않는다.
- 형식: TEST_GUIDE §2(영어 camelCase 메서드 · 한국어 `@DisplayName` · 파라미터 테스트 이름) · §3(given/when/then 주석 · `// when` 아래 호출 하나 · 예외는 `// when & then` · AssertJ 만).
- **옮기는 테스트가 지금 확인하는 사실을 빠뜨리지 마라.** 옮긴 뒤 같은 사실을 확인하는 테스트가 있어야 한다. 한 테스트가 여러 동작을 확인하면 나눈다. 빼는 사실이 있으면(옛 정리 방식 자체를 확인하던 단언 등) 무엇을 왜 뺐는지 summary 에 적는다.
- 로그에 원문이 남지 않는지 보는 출력 캡처 검사(`OutputCaptureExtension` — 중복키 오류에 `subject` 원문이 없다 등)는 그대로 유지한다(AGENTS 금지 30).

### 5. 바뀐 인터페이스 때문에 깨지는 다른 테스트

- 레포지토리에서 사라진 메서드(`deleteById` 등)를 쓰는 **이 step 범위 밖의 테스트**는 그 호출만 고친다 — 예: `common/api/RequestValidationIT.java` 의 `users.deleteById(…)` 정리 호출을 `JdbcTemplate` 의 `DELETE` 로 바꾼다. 그 파일의 다른 곳은 고치지 마라(step 4 가 옮긴다).
- 같은 이유로 깨지는 곳이 더 있으면 같은 방식으로 그 호출만 고치고 파일 이름을 summary 에 적는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*RepositoryTest' --tests '*AuditedEntityTest' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 레포지토리 5개가 `Repository<T, Long>` 만 상속하고, 정리 · 집계 메서드를 선언하지 않는가?
   - 옮긴 테스트에 `PostgresTestConfiguration` 상속 · 클래스별 시계 · `@AfterEach` 정리 · UUID 행 가르기 · JUnit `Assertions` 가 남지 않았는가? 모든 테스트 메서드에 `@DisplayName` 이 있는가?
   - 옛 `UserRepositoryIT` · `CurriculumRepositoryIT` · `AuditedEntityIT` 의 사실이 새 테스트에 모두 있는가(뺀 것은 summary 에 이유)?
   - 위반 픽스처가 `com.mogum` 밖에 있는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3a-test-conventions/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 레포지토리별 선언 메서드 · 픽스처 클래스와 도우미 이름 · 새 테스트 파일 이름 · 뺀 사실과 이유 · 범위 밖에서 호출만 고친 파일 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (가이드대로 만들 수 없음 · 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 서비스 · 컨트롤러 · 통합 테스트를 옮기지 마라(「5」 의 호출 고치기만 예외). 이유: step 2~4 의 범위다.
- 테스트만을 위해 레포지토리에 정리 · 집계 메서드를 선언하지 마라. 이유: TEST_GUIDE §4 — Fake 가 커지고 제품 코드가 쓸 수 있는 길이 생긴다.
- 테스트만을 위해 엔티티에 setter · 변경 메서드 · public 생성자를 더하지 마라. 이유: AGENTS 금지 28. ID · 상태는 픽스처 안에서 리플렉션으로 넣는다.
- 테스트 트랜잭션(`@Transactional` 테스트)으로 격리하지 마라. 이유: TEST_GUIDE §5 — 지연 로딩 · flush 오류를 가린다.
- 레포지토리 · 엔티티의 동작(쿼리 · 매핑 · 제약)을 바꾸지 마라. 이유: 이 step 은 상속만 좁힌다.
- 가이드 · ADR 의 규칙 문장을 고치지 마라. 이유: 계약은 설계 커밋에서 정했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
