# Step 7: sonar-record-array

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/TEST_GUIDE.md` §2 · §3(이름 · 본문 형식) · §7(빌드가 지키는 것)
- `backend/src/test/java/com/mogum/account/repository/LoginIdentityMigrationTest.java` — 특히 `private record LegacyData(…, byte[] attribute, …)` 와 그 값을 쓰는 곳(`legacy.attribute()` 를 단언하는 줄)
- step 6 summary — 이 파일을 옮긴 방식

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

PR #23 에서 SonarCloud 품질 게이트가 실패했다. 이 step 은 그중 하나를 고친다.

- **`java:S6218`(버그 · MAJOR)** — `LoginIdentityMigrationTest.java:159` 의 `record LegacyData` 가 배열 칸(`byte[] attribute`)을 가진다. record 가 만드는 `equals` · `hashCode` · `toString` 은 배열의 내용이 아니라 참조로 비교한다. 지금은 `LegacyData` 끼리 비교하는 곳이 없어 오작동은 없지만, 이 지적 하나로 새 코드의 신뢰성 등급이 C 가 되어 게이트(A)를 넘지 못한다.
- 다른 지적(`DatabaseCleaner` 의 `java:S2077`)은 Claude 가 `sonar-project.properties` 에서 이유를 적어 그 파일에만 껐다. 이 step 의 범위가 아니다.

## 작업

- `LegacyData` 가 배열을 record 칸으로 들지 않게 고친다. 방법은 정한다 — 예: 배열 대신 비교 가능한 값으로 담기, 배열이 필요한 곳에서만 따로 들기, record 가 아닌 작은 클래스로 바꾸기. `equals` · `hashCode` · `toString` 을 손으로 재정의하는 방식은 쓰지 마라 — 이 테스트에는 그 비교가 필요 없다.
- 지금 이 테스트가 확인하는 사실을 그대로 유지한다. 특히 **이전한 뒤 `spring_session_attributes.attribute_bytes` 의 바이트가 이전 전과 같다**는 단언은 내용(바이트 하나하나)으로 비교해야 한다.
- 같은 파일이나 이 step 이 건드리는 코드에 다른 record 배열 칸이 있으면 함께 고친다. 다른 파일은 고치지 않는다.
- 문서는 바꾸지 않는다 — 이 step 은 문서에 적힌 사실을 바꾸지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*LoginIdentityMigration*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `backend/src/test` 안에 배열 칸을 가진 record 가 이 파일에 더는 없는가? (`grep -rn "record" backend/src/test/java/com/mogum/account/repository/LoginIdentityMigrationTest.java` 로 확인)
   - 세션 속성 바이트의 보존 단언이 내용 비교인가? 지금 확인하는 다른 사실(회원 · 세션 행 보존 · 실제 `/api/me` · 재로그인 · 이전 거절과 롤백)이 그대로 있는가?
   - 테스트 이름 · `@DisplayName` · given/when/then · AssertJ 형식(TEST_GUIDE §2 · §3)을 지키는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3a-test-conventions/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 바꾼 방식 · 유지한 단언 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 제품 코드(`backend/src/main`)와 `build.gradle` 을 고치지 마라. 이유: 지적은 테스트 코드 하나에 있다.
- `sonar-project.properties` 에 이 규칙(`java:S6218`)의 예외를 더하지 마라. 이유: 코드로 바로 고칠 수 있는 지적이라 예외를 둘 근거가 없다.
- `DatabaseCleaner` 를 고치지 마라. 이유: 그 지적은 이유를 적은 예외로 처리했고, 정리 방식은 step 0 의 측정으로 정했다.
- `equals` · `hashCode` · `toString` 을 손으로 재정의하지 마라. 이유: 쓰지 않는 비교를 위한 코드가 는다.
