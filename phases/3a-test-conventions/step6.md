# Step 6: test-rules

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/TEST_GUIDE.md` 전체 — **이 phase 의 계약이다.** 특히 §1(컨테이너 단독) · §2 · §7(빌드가 지키는 것)
- `docs/ADR.md` — ADR-079(트레이드오프 끝의 "아직 수행하지 않았다")
- `docs/07-testing.md` §1.0(`imageTest`) · §1.2(계정 항목 문단의 테스트 클래스 이름 · ArchUnit 행)
- `docs/02-domain-data.md` §6.1 — `LoginIdentityMigrationIT` 를 가리키는 줄
- `docs/04-jobs-and-cost.md` §2 — ArchUnit 규칙 · 위반 픽스처 위치
- `AGENTS.md` — 작업 방식 · 명령어(`imageTest` 줄)
- `backend/build.gradle` — `test` 의 `exclude '**/*ImageIT.class'` · `imageTest` 의 `include '**/*ImageIT.class'`
- `backend/src/test/java/com/mogum/ArchitectureTest.java` · `archfixture/` · `entityfixture/` · step 1 의 레포지토리 위반 픽스처
- 옮길 테스트: `backend/src/test/java/com/mogum/account/repository/LoginIdentityMigrationIT.java` · `backend/src/test/java/com/mogum/AppImageIT.java`
- step 0~5 가 만든 것 — 이전 step summary(특히 `PostgresTestConfiguration` · `TestSessions` 를 아직 쓰는 곳)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`3a-test-conventions`)는 백엔드 테스트를 `docs/TEST_GUIDE.md` 의 규칙으로 통일한다. **코드가 가이드와 어긋나면 코드를 가이드에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 가이드의 규칙을 고치지 마라.**

이 step 은 마지막 두 테스트를 옮기고, 모든 이관이 끝났으므로 **테스트 코드 규칙을 빌드에 건다**(TEST_GUIDE §7 의 첫 세 줄 — 넷째 줄 레포지토리 상속은 step 1 이 걸었다). 그리고 이 phase 가 확정한 사실을 문서에 반영한다.

## 작업

### 1. 남은 두 테스트

- `LoginIdentityMigrationIT` → 컨테이너 단독 테스트(TEST_GUIDE §1)로 이름을 바꾼다(`LoginIdentityMigrationTest` 등). 자기 스키마를 만들고 끝나면 지우는 지금 방식을 유지한다. 형식은 TEST_GUIDE §2 · §3.
- `AppImageIT` → 이름을 가이드에 맞게 바꾸고(`IT` 없이), `backend/build.gradle` 의 `test` 제외 패턴과 `imageTest` 포함 패턴을 **함께** 바꾼다. 패턴이 다른 테스트를 잘못 잡지 않게 한다. 형식은 TEST_GUIDE §2 · §3. `imageTest` 는 Docker 이미지를 빌드하므로 AC 로 돌리지 않는다 — 대신 기본 `test` 가 이 클래스를 돌리지 않는지(제외)를 확인한다.
- 옛 지원 코드 정리: 옮긴 뒤 아무도 쓰지 않게 된 `support/TestSessions` · `PostgresTestConfiguration` 의 상속 경로 등은 지운다. 아직 쓰는 곳(컨테이너 단독 테스트의 `POSTGRES` 등)은 남긴다.

### 2. 테스트 코드 ArchUnit 규칙 (TEST_GUIDE §7)

`ArchitectureTest` 에 테스트 클래스를 검사하는 규칙 셋을 더한다. 테스트 클래스는 `ImportOption` 으로 테스트만 읽어 검사한다.

1. 테스트 코드는 JUnit `org.junit.jupiter.api.Assertions` 를 쓰지 않는다.
2. `org.springframework.test.util.ReflectionTestUtils` 와 `setAccessible` 호출은 이름이 `Fixtures` 로 끝나거나 `Fake` 로 시작하는 클래스 안에서만 쓴다. `ArchitectureTest` 자체가 위반 픽스처를 다루며 필요하면 그 이유를 규칙 예외로 적는다.
3. 테스트 클래스 이름이 `IT` 로 끝나지 않는다.

- 규칙마다 **위반 픽스처**로 실제로 걸리는지 확인한다(기존 `assertViolation` 방식). 위반 픽스처는 기존 `archfixture` 처럼 규칙 검사 대상에서 빼고 따로 검사한다 — 픽스처가 전체 검사에 걸려 빌드를 깨지 않게 한다. JUnit `Assertions` 를 쓰는 픽스처 · `IT` 이름 픽스처는 `@Test` 메서드를 두지 않는다(Gradle 이 테스트로 돌리지 않게).
- 위반 픽스처에 Spring 컴포넌트 · `@Entity` · Spring Data 레포지토리를 두지 마라(원칙 22).
- 지금 저장소의 테스트 코드가 세 규칙을 모두 통과해야 한다. 걸리는 곳이 있으면 그 테스트를 가이드에 맞게 고친다 — 규칙을 무르지 마라.

### 3. 문서 (구현으로 정해진 사실만)

- `docs/TEST_GUIDE.md` 머리말 — "**이관 · 규칙 검사(§7)는 아직 수행하지 않았다.**" 를 이 phase 가 한 것(이관 완료 · §7 규칙 넷이 빌드에 걸림 · 날짜)으로 바꾼다. 규칙 문장은 고치지 않는다.
- `docs/ADR.md` ADR-079 트레이드오프 끝 — "**이관 · ArchUnit 테스트 규칙은 아직 수행하지 않았다.**" 를 수행한 사실(날짜 · 서버 테스트 수 · 통합 테스트 컨텍스트 수 · 정리 방식과 숫자 — step 0~5 summary 에서)로 바꾼다. 결정 · 이유 · 대안 본문은 고치지 않는다.
- `docs/07-testing.md` §1.2 — 계정 항목 문단의 옛 테스트 클래스 이름(`LoginServiceIT` · `UserRepositoryIT` · `OAuthLoginHandlerIT` · `LoginIdentityMigrationIT`)을 지금 이름으로 바꾼다. 그 문단의 "2026-10-20 … 179개 통과" 같은 지난 기록은 고치지 않는다.
- `docs/02-domain-data.md` §6.1 — `LoginIdentityMigrationIT` 를 새 이름으로.
- `docs/07-testing.md` §1.0 · `AGENTS.md` 명령어의 `imageTest` 설명에 클래스 이름 · 패턴이 적혀 있으면 맞춘다.
- `docs/ADR.md` 의 다른 ADR "검증" 줄에 적힌 옛 클래스 이름(`LoginServiceIT` · `RequestValidationIT` 등)은 **고치지 않는다** — 그때의 기록이다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*ArchitectureTest' --tests '*LoginIdentityMigration*'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `backend/src/test` 에 이름이 `IT` 로 끝나는 테스트 클래스가 없는가? `@SpringBootTest` 를 직접 붙인 클래스가 없고(`@IntegrationTest` 묶음 안만), 통합 테스트 이름이 모두 `*IntegrationTest` 인가?
   - 새 ArchUnit 규칙 셋이 각자 위반 픽스처를 거절하고, 저장소의 테스트 코드는 통과하는가?
   - 기본 `test` 가 이미지 테스트를 돌리지 않고, `imageTest` 의 포함 패턴이 새 이름을 잡는가(`build.gradle` 에서 확인)?
   - 문서에서 옛 이름을 고친 곳이 「3」 의 목록뿐이고, 지난 기록(ADR 검증 줄 · 날짜가 붙은 숫자)은 그대로인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3a-test-conventions/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 새 이름 · Gradle 패턴 · 규칙 셋과 픽스처 위치 · 지운 지원 코드 · 고친 문서 절 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (가이드대로 만들 수 없음 · 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- ArchUnit 규칙을 무르거나 예외를 넓혀 기존 테스트를 통과시키지 마라. 이유: 규칙을 켜는 목적이 이관 누락을 잡는 것이다 — 걸리면 테스트를 고친다.
- 제품 코드를 고치지 마라(`build.gradle` 의 테스트 패턴만 예외). 이유: 이 step 은 테스트 규칙과 문서 사실만 다룬다.
- `scripts/tdd_guard.py` 등 하네스 코드를 고치지 마라. 이유: 하네스 수정은 `main` 에서 딴 별도 브랜치에서 한다(`CLAUDE.md`). 가드가 `*IT.java` 를 짝 테스트로 받아도 이름 규칙은 ArchUnit 이 막는다.
- 가이드 · ADR 의 규칙 문장을 고치지 마라 — 「3」 의 사실 갱신만 한다. 이유: 계약은 설계 커밋에서 정했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
