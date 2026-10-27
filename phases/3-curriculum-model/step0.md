# Step 0: entity-audit

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — **스택** 줄(JPA Auditing) · **패키지** 줄 · 금지 28 · 32 · 작업 방식(TDD 짝 테스트 규칙)
- `docs/ADR.md` — ADR-071(정적 팩토리)과 그 **갱신(2026-10-27)** 줄 · ADR-078(생성 · 수정 시각 — 이 step 의 계약)
- `docs/02-domain-data.md` — §6 머리의 시각 컬럼 규칙 · §6.1 `users` 표(`created_at` · `updated_at` 행) · §6.1.1 항목 3 · §6.1.2
- `docs/04-jobs-and-cost.md` §2 — `common` 을 주제 하위 패키지로 나누는 규칙 · `domain` 하위 패키지 행 · 패키지 표의 `common` · `config` 행
- `docs/07-testing.md` §1.2 — 「생성 · 수정 시각」 행
- `docs/ARCHITECTURE.md` — 디렉터리 트리의 `config/` · `common/` 줄
- `backend/src/main/java/com/mogum/account/domain/User.java` · `account/service/LoginService.java` · `config/ClockConfiguration.java`
- `backend/src/main/resources/db/migration/` 전체(V1~V6) · `backend/src/main/resources/application.yml`(`spring.jpa.hibernate.ddl-auto: validate`)
- `backend/src/test/java/com/mogum/ArchitectureTest.java` · `config/ClockConfigurationTest.java`(테스트에서 `@Primary` `Clock` 을 넣는 기존 방식) · `support/PostgresTestConfiguration.java`
- `User.join` 을 부르는 테스트 전부: `account/domain/UserTest` · `account/service/LoginServiceTest` · `account/service/LoginServiceIT` · `account/repository/UserRepositoryIT` · `account/controller/MeControllerTest` · `auth/SecurityConfigurationIT` · `auth/SecurityConfigurationCrossOriginIT` · `auth/SessionUserFilterTest` · `common/api/RequestValidationIT` · `common/logging/RequestLogFilterIT`
- `backend/src/test/java/com/mogum/account/repository/LoginIdentityMigrationIT.java` — 옛 버전 DB 를 최신 마이그레이션까지 올리는 테스트

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`3-curriculum-model`)는 커리큘럼 테이블 넷과 엔티티를 만든다(step 1 · 2). 그 전에 이 step 은 **모든 엔티티가 `created_at` · `updated_at` 을 갖고 JPA Auditing 이 `Clock` 빈으로 채운다**는 규칙(ADR-078 · AGENTS 금지 32)을 세우고, 유일한 기존 엔티티 `User` 를 그 규칙에 맞춘다. 계약 문서(ADR-078 · AGENTS · 02 · 04 · 07)는 이미 고쳐 커밋해 두었다. **코드가 문서와 어긋나면 코드를 문서에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문서를 고치지 마라** — 아래 「6. 문서」 에 적은 사실 갱신만 한다.

## 작업

### 1. 공통 상위 클래스

- `com.mogum.common` 아래에 **새 주제 하위 패키지**를 만들고 그 안에 `@MappedSuperclass` 추상 클래스를 둔다. 하위 패키지 이름과 클래스 이름은 04 §2 의 규칙(주제로 나눈다 · 기존 `api` · `time` · `logging` 중 맞는 곳이 없을 때만 새로 만든다 · `common` 바로 아래에는 클래스를 두지 않는다)에 맞게 정한다.
- 필드: `createdAt`(`created_at`, `@CreatedDate`, NOT NULL, 갱신하지 않음) · `updatedAt`(`updated_at`, `@LastModifiedDate`, NOT NULL). 타입은 `java.time.Instant`. Lombok `@Getter` 만 쓴다(ADR-070). setter · 시각을 받는 생성자 · 메서드를 두지 않는다.
- Auditing 리스너(`AuditingEntityListener`)는 이 상위 클래스에 건다 — 엔티티마다 따로 걸지 않는다.
- TDD 가드: `@MappedSuperclass` 는 `@Entity` 가 아니라 검사 대상이다. 같은 패키지에 짝 테스트(`{클래스명}*Test.java` 또는 `{클래스명}*IT.java`)를 먼저 둔다 — 아래 「5. 테스트」 의 Auditing 검사를 이 짝 테스트로 쓴다.

### 2. Auditing 설정

- `config` 에 `@Configuration` 클래스를 하나 두고 `@EnableJpaAuditing` 을 켠다. 시각 공급자(`DateTimeProvider`)는 **`Clock` 빈**(지금 `config.ClockConfiguration` 의 `Clock.systemUTC()`)의 `instant()` 를 돌려주는 빈으로 등록하고 `@EnableJpaAuditing` 이 그 빈을 쓰게 한다. 공급자가 `Clock` 을 주입받아야 테스트의 `@Primary` 고정 시계가 Auditing 에도 적용된다.
- `@EnableJpaAuditing` 을 `MogumApplication` 에 붙이지 마라. 이유: ADR-078 이 Auditing 설정을 `config` 에 두기로 했다.
- 짝 테스트 `config/{클래스명}Test.java` — 고정 `Clock` 을 넣으면 공급자가 그 시각을 돌려주는지 확인한다(`ClockConfigurationTest` 처럼 컨텍스트를 직접 띄워도 된다).

### 3. `users` 맞추기

- 마이그레이션 `V7__…sql`(이름은 내용을 나타내게 정한다): `users.updated_at timestamptz` 를 더하고, 기존 행은 `created_at` 값으로 채운 뒤 NOT NULL 로 만든다. 한 마이그레이션 트랜잭션이다. **V1~V6 은 고치지 마라.** 이유: 운영 DB 에 이미 적용됐다.
- `User` 가 공통 상위 클래스를 상속한다. `User` 의 `createdAt` 필드를 지우고(상위 클래스가 갖는다) `User.join(String provider, String subject, String email, UserRole role)` 로 바꾼다 — 가입 시각 인자를 없앤다. 처음 상태 `ACTIVE` 는 그대로 팩토리 안에 고정한다(ADR-071).
- `LoginService` 는 가입 시각을 넘기지 않으므로 `Clock` 의존을 없앤다(생성자에서도 뺀다).
- `User.join` · `LoginService` 생성자를 쓰는 테스트(위 「읽어야 할 파일」 의 목록)를 모두 새 시그니처로 고친다. 고정 시각을 `join` 에 넘겨 `getCreatedAt()` 을 단언하던 곳은 아래 5 의 방식(컨텍스트의 고정 `Clock`)으로 옮기거나, 시각이 그 테스트의 관심이 아니면 단언을 뺀다. 단위 테스트(`UserTest` · `LoginServiceTest`)는 저장 전이라 시각이 없다 — 시각 단언은 DB 테스트로 옮긴다.
- `ddl-auto: validate` 다 — `User` 와 상위 클래스의 매핑이 V7 이후 스키마와 맞아야 모든 컨텍스트가 뜬다.

### 4. ArchUnit 규칙

- `ArchitectureTest` 에 **"`@Entity` 는 공통 상위 클래스를 상속한다"** 규칙을 더한다. 운영 클래스(`CLASSES`)로 검사하고, `User` 가 `CLASSES` 에 있음을 확인한다.
- 위반 픽스처(상속하지 않은 `@Entity`)는 **`com.mogum` 밖의 테스트 패키지**에 둔다(예: `backend/src/test/java/entityfixture/…`). 이유: 엔티티 스캔은 `com.mogum` 아래의 테스트 클래스까지 읽고, `ddl-auto: validate` 라 테이블 없는 엔티티가 잡히면 모든 `@SpringBootTest` 가 뜨지 않는다. 기존 `com.mogum.archfixture` 에도 두지 마라. 기존 위반 검사처럼 `assertViolation` 으로 그 픽스처가 거절되는지 확인한다.
- 기존 규칙을 무르거나 고치지 마라.

### 5. 테스트 — 07 §1.2 「생성 · 수정 시각」

- 공통 상위 클래스의 짝 테스트(DB 테스트 — `PostgresTestConfiguration` 상속)에서 실제 엔티티 `User` 로 확인한다. 테스트 컨텍스트에는 기존 `Clock` 빈과 다른 이름의 `@Primary` `Clock`(시각을 테스트가 정하고 바꿀 수 있는 것)을 넣는다 — 넣는 방법은 정해서 summary 에 적는다(step 1 · 2 의 DB 테스트가 같은 방법을 쓴다).
  - 저장하면 `created_at` 과 `updated_at` 이 둘 다 그 시계의 시각이다(DB 에서 다시 읽어 확인).
  - 시계를 앞으로 옮기고 엔티티 필드를 바꿔 커밋하면 `updated_at` 만 새 시각이고 `created_at` 은 그대로다. 필드는 테스트에서 바꾼다(`ReflectionTestUtils` — `UserRepositoryIT` 가 이미 쓴다). **이 검사만을 위해 엔티티에 변경 메서드를 더하지 마라.**
  - 테스트 시각은 마이크로초 아래 자리가 없는 값을 쓴다 — `timestamptz` 는 마이크로초까지 저장한다.
- `LoginServiceIT`(또는 회원 저장 IT)에서 첫 로그인의 가입 시각이 컨텍스트 고정 시계의 시각인지 확인한다 — 지금 `LoginServiceIT` 가 `Clock.fixed` 로 확인하는 것을 Auditing 경로로 옮긴다.
- V7 의 기존 행 채우기 — 옛 행의 `updated_at` 이 `created_at` 과 같은지 확인한다. `LoginIdentityMigrationIT` 가 옛 버전 DB 에 행을 넣고 최신까지 올리므로 거기에 단언을 더해도 된다.
- DB 는 테스트 JVM 하나에서 공유된다(`PostgresTestConfiguration` 주석) — 쓴 행은 테스트가 지운다.

### 6. 문서 (구현으로 정해진 사실만)

- `docs/04-jobs-and-cost.md` §2 — `common` 하위 패키지 나열(`common.api` · `common.time` · `common.logging`)에 새 하위 패키지를 더하고, 패키지 표의 `common` 행에 상위 클래스를, `config` 행에 Auditing 설정을 적는다.
- `docs/ARCHITECTURE.md` — 디렉터리 트리의 `common/` 에 새 하위 패키지 줄, `config/` 줄에 Auditing 설정.
- `AGENTS.md` — **패키지** 줄의 `common`(…) 괄호 안 나열에 새 하위 패키지를 더한다. 금지 목록 본문은 고치지 않는다.
- `docs/02-domain-data.md` §6.1 — `updated_at` 행의 "`3-curriculum-model` 에서 더한다" 를 실제 마이그레이션 파일 이름으로 바꾼다. §6.1.2 의 "새 DB도 V1~V6를 순서대로 적용한다" 에 V7 을 반영한다.
- `docs/ADR.md` ADR-078 — 트레이드오프 끝의 "구현 · 검증은 아직 수행하지 않았다" 를 이 step 이 한 것(공통 상위 클래스 · Auditing 설정 · ArchUnit 규칙 · `users` V7)과 남은 것(커리큘럼 엔티티 — 이 phase 의 step 1)으로 고친다. ADR 의 결정 · 이유 · 대안 본문은 고치지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `backend/src/main/java/com/mogum/common/` 바로 아래에 `.java` 파일이 없는가(04 §2)?
   - `@EnableJpaAuditing` 이 `config` 에만 있는가? `User.join` 에 시각 인자가 없고 `LoginService` 가 `Clock` 을 쓰지 않는가?
   - 위반 픽스처 `@Entity` 가 `com.mogum` 밖에 있는가?
   - V1~V6 파일이 바뀌지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3-curriculum-model/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — **공통 상위 클래스의 전체 이름(패키지 포함)** · Auditing 설정 클래스 이름 · 테스트용 고정 시계를 넣은 방법 · V7 파일 이름 · 서버 테스트 수(전후)를 넣는다(다음 step 이 상속 · 테스트에 쓴다)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 커리큘럼 테이블 · 엔티티 · 리포지토리를 만들지 마라. 이유: step 1 · 2 의 범위다.
- DB `DEFAULT now()` · `BEFORE UPDATE` 트리거로 시각을 채우지 마라. 이유: ADR-078 이 검토하고 버린 방식이다 — DB 시계라 고정 시계로 단언할 수 없다. V7 의 기존 행 채우기는 `UPDATE … SET updated_at = created_at` 로 하고 기본값을 남기지 않는다.
- 팩토리 · 엔티티 메서드가 생성 · 수정 시각을 인자로 받게 하지 마라. 이유: AGENTS 금지 28 · 32.
- 테스트만을 위해 엔티티에 변경 메서드 · setter 를 더하지 마라. 이유: 쓰는 흐름이 없는 메서드는 다음 TASK 가 잘못된 변경 경로로 쓴다.
- 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 이 step 은 04 §9.2 표에 줄을 더하지 않는다.
- 계약 문서(ADR 결정 본문 · AGENTS 금지 목록 · 02 · 05 · 07 의 규칙)를 고치지 마라 — 「6. 문서」 의 사실 갱신만 한다. 이유: 계약은 설계 커밋에서 정했다. 어긋나면 `blocked`.
- `frontend/` 를 고치지 마라. 이유: 이 step 과 무관하다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
