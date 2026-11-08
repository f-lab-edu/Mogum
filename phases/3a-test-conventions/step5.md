# Step 5: unit-style

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/TEST_GUIDE.md` 전체 — **이 phase 의 계약이다.** 특히 §2(이름) · §3(본문) · §4(mock · 시계)
- `docs/ADR.md` — ADR-079
- `AGENTS.md` — 작업 방식(TDD · 백엔드 테스트 줄)
- step 0~4 가 만든 것: `backend/src/test/java/com/mogum/support/` 와 이미 옮긴 테스트들(형식의 본보기 — 이전 step summary 에 이름이 있다)
- 옮길 테스트(아래 「작업」 의 목록)와 그 테스트의 대상 제품 코드

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`3a-test-conventions`)는 백엔드 테스트를 `docs/TEST_GUIDE.md` 의 규칙으로 통일한다. **코드가 가이드와 어긋나면 코드를 가이드에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 가이드의 규칙을 고치지 마라.**

이 step 은 남은 단위 테스트를 **형식만** 옮긴다 — 종류(단위)는 그대로이고, 이름 · `@DisplayName` · given/when/then · AssertJ · BDDMockito 로 바꾼다. 테스트가 확인하는 사실은 바꾸지 않는다.

## 작업

### 1. 대상

- `backend/src/test/java/com/mogum/auth/` — `CookieAuthorizationRequestRepositoryTest` · `DiscardingOAuth2AuthorizedClientsTest` · `GoogleAccountMapperTest` · `SecurityConfigurationTest`
- `backend/src/test/java/com/mogum/common/` — `logging/RequestLogFilterTest` · `time/SeoulClockTest`
- `backend/src/test/java/com/mogum/config/` — `AuthPropertiesTest` · `ClockConfigurationTest` · `JpaAuditingConfigurationTest` · `RequestLogConfigurationTest` · `WebPropertiesTest`(step 4 가 `ApplicationContextRunner` 테스트를 더했다면 그것도 형식을 확인한다)
- `backend/src/test/java/com/mogum/deploy/` — `AwsFilesTest` · `BackupFilesTest` · `DeployFilesTest` · `WorkflowFilesTest`
- `backend/src/test/java/com/mogum/learning/FsrsLibraryLearningTest.java`
- `backend/src/test/java/com/mogum/ArchitectureTest.java` — 테스트 메서드 형식만. 규칙 · 위반 픽스처 · 검사 대상(`CLASSES`)은 바꾸지 않는다(테스트 코드 규칙은 step 6).

위 목록 밖에 아직 옛 형식(한국어 메서드 이름 · `@DisplayName` 없음 · JUnit `Assertions`)인 단위 테스트가 있으면 함께 옮기고 summary 에 적는다. `LoginIdentityMigrationIT` · `AppImageIT` 는 step 6 범위다.

### 2. 바꾸는 것

- TEST_GUIDE §2 — 영어 camelCase 메서드 · 한국어 `@DisplayName` · 파라미터 테스트 이름(`@ParameterizedTest(name = …)`).
- TEST_GUIDE §3 — `// given` · `// when` · `// then`(준비가 없으면 given 생략 · 예외는 `// when & then` · `// when` 아래 호출 하나) · AssertJ 만. 한 테스트가 여러 동작을 확인하면 나눈다.
- TEST_GUIDE §4 — mock 은 `@ExtendWith(MockitoExtension.class)` · `@Mock` · BDDMockito. 시계는 `Clock.fixed`. 테스트 본문의 리플렉션(`ReflectionTestUtils` · `setAccessible`)은 픽스처 도우미로 옮긴다.
- 클래스가 크면 기능별로 파일을 나눈다(예: `DeployFilesTest` 428줄). 나눈 파일 이름도 대상 클래스 · 주제로 시작하는 `*Test` 다.
- `deploy` 테스트는 소스 밖 파일(`deploy/` · `.github/workflows/`)을 읽는다 — Gradle `test` 의 입력 선언(`inputs.dir`)은 그대로 둔다(원칙 14).

### 3. 지키는 것

- **옮기는 테스트가 지금 확인하는 사실을 빠뜨리지 마라.** 파일을 나누거나 테스트를 쪼갤 때 단언이 사라지지 않게 한다. 빼는 사실이 있으면 무엇을 왜 뺐는지 summary 에 적는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.auth.*' --tests 'com.mogum.common.*' --tests 'com.mogum.config.*' --tests 'com.mogum.deploy.*' --tests 'com.mogum.learning.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 대상 파일 모두에서 JUnit `Assertions` · Mockito `when`/`verify` · 테스트 본문 리플렉션이 사라졌고, 모든 테스트 메서드에 영어 이름 · `@DisplayName` · given/when/then 이 있는가?
   - 테스트 수가 줄었다면 그 이유가 summary 에 있는가(나눔으로 늘어나는 것은 괜찮다)?
   - `ArchitectureTest` 의 규칙 · 픽스처가 바뀌지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3a-test-conventions/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 옮긴 파일 · 나눈 파일 이름 · 목록 밖에서 함께 옮긴 파일 · 뺀 사실과 이유 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (가이드대로 만들 수 없음 · 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 테스트의 종류를 바꾸지 마라(단위 → 통합 등). 이유: 이 step 은 형식만 옮긴다.
- 제품 코드 · `deploy/` · 워크플로 파일을 고치지 마라. 이유: 이 step 은 테스트 형식만 바꾼다.
- `ArchitectureTest` 의 규칙을 더하거나 무르지 마라. 이유: 테스트 코드 규칙은 step 6 이 모든 이관이 끝난 뒤 켠다.
- 가이드 · ADR 의 규칙 문장을 고치지 마라. 이유: 계약은 설계 커밋에서 정했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
