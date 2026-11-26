# Step 5: test-names

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/TEST_GUIDE.md` §1(종류 — 통합은 실제 포트 HTTP) · §2(이름 — 대상 클래스 이름으로 시작해 TDD 가드의 짝 규칙 `Foo*Test.java` 에 맞춘다)
- `docs/07-testing.md` §1(「지운 뒤의 늦은 결과」 · 「지우기 범위」 칸의 근거 — 시험 클래스 이름)
- `backend/src/test/java/com/mogum/course/repository/JpaCourseDeletionRepositoryIntegrationTest.java`
- `backend/src/test/java/com/mogum/account/service/WithdrawServiceIntegrationTest.java`
- `backend/src/test/java/com/mogum/course/controller/CourseControllerIntegrationTest.java` · `backend/src/test/java/com/mogum/account/controller/MeControllerIntegrationTest.java`(같은 패키지의 실제 HTTP 통합 시험이 쓰는 방식)
- `backend/src/test/java/com/mogum/card/repository/CardRepositoryTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

phase 의 step 0 · 2 가 실제 HTTP 로 전체 흐름(보안 필터 → 컨트롤러 → 서비스 → DB)을 보는 시험을 리포지토리 · 서비스 이름의 클래스에 두었다. 이름이 대상과 어긋나 07 §1 의 근거 칸이 틀린 곳을 가리킨다. **시험의 내용 · 단언은 바꾸지 않고 자리와 이름만 옮긴다.** 제품 코드(`backend/src/main`)는 바꾸지 않는다.

## 작업

### 1. 코스 지우기 범위 시험 옮기기

- `JpaCourseDeletionRepositoryIntegrationTest` 의 두 시험 중 **`deletesCourseContentWhilePreservingMemoryAndAccounting`**(`DELETE /api/courses/{id}` 를 실제 HTTP 로 보내 지우기 범위를 확인한다)을 `backend/src/test/java/com/mogum/course/controller/CourseControllerDeletionIntegrationTest.java` 로 옮긴다.
- **`locksEveryRoadmapJobBeforeReturning`**(리포지토리 메서드 `lockRoadmapJobs` 를 직접 부른다)은 리포지토리 시험이라 `JpaCourseDeletionRepositoryIntegrationTest` 에 그대로 둔다.
- 옮기면서 쓰지 않게 된 필드 · import 는 두 파일에서 지운다.

### 2. 탈퇴 HTTP 시험 옮기기

- `WithdrawServiceIntegrationTest` 의 **`withdrawsAllOwnedDataAndSessions`** · **`rejectsUnapprovedOrAnonymousWithdrawal`**(둘 다 실제 HTTP · 세션 · 쿠키를 본다)과 그 둘만 쓰는 보조 메서드(`withCsrf` 등)를 `backend/src/test/java/com/mogum/account/controller/WithdrawControllerIntegrationTest.java` 로 옮긴다.
- **`rollsBackAllDeletionTogether`**(서비스를 직접 불러 롤백을 본다)는 `WithdrawServiceIntegrationTest` 에 그대로 둔다. 두 클래스가 함께 쓰는 준비 코드가 있으면 각자 필요한 만큼만 갖는다 — 새 공용 픽스처 클래스를 만들지 마라.

### 3. `CardRepositoryTest` import

- 완전한 이름으로 쓴 `org.springframework.transaction.PlatformTransactionManager` · `org.springframework.transaction.support.TransactionTemplate` · `java.time.Duration` · `java.util.List` 를 import 로 바꾼다. 같은 파일의 다른 시험과 같은 표기다.

### 4. 문서 — 구현으로 정해진 사실만

- `docs/07-testing.md` §1 — 「지운 뒤의 늦은 결과」 · 「지우기 범위」 칸의 시험 클래스 이름을 옮긴 결과에 맞춘다. 지우기 범위의 근거에 `CourseControllerDeletionIntegrationTest` · `WithdrawControllerIntegrationTest` 를 넣고, 범위를 더 이상 보지 않는 클래스 이름은 뺀다. 칸의 규칙 문장은 고치지 마라.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.course.*' --tests 'com.mogum.account.*' --tests 'com.mogum.card.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 옮긴 시험의 `@DisplayName` · given/when/then · 단언이 옮기기 전과 같은가? 시험 수가 줄지 않았는가(전체 서버 시험 수 전후를 센다)?
   - `backend/src/main` 을 바꾸지 않았는가?
   - 새 클래스 이름이 TEST_GUIDE §2 의 이름 규칙(`{대상}…IntegrationTest` · `@IntegrationTest`)을 따르는가?
   - `./backend/gradlew -p backend spotlessApply` 로 포맷했는가?
3. 결과에 따라 `phases/12-delete-withdraw/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 옮긴 시험과 새 클래스 이름 · 서버 테스트 수(전후 — 같아야 한다)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 시험의 단언 · 준비 데이터 · `@DisplayName` 을 바꾸지 마라. 이유: 자리와 이름만 옮기는 정리다 — 바꾸면 리뷰가 옮김과 변경을 구분하지 못한다.
- 제품 코드(`backend/src/main`)와 화면(`frontend/`)을 바꾸지 마라. 이유: 화면은 step 6 범위이고, 이 step 은 시험 정리다.
- 클래스마다 `@Import` · 새 `@TestConfiguration` 을 더하지 마라. 이유: 컨텍스트가 클래스마다 새로 뜬다(TEST_GUIDE §5).
- 07 §1 의 규칙 문장 · 다른 문서를 고치지 마라. 이유: 이 step 은 근거 칸의 클래스 이름만 맞춘다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
