# Step 0: users-account

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 2 · 3 · 4 · 25 · 27, 작업 방식(TDD 짝 테스트 규칙)
- `docs/ARCHITECTURE.md` — 디렉토리 구조 · 패턴(드러낸 트랜잭션)
- `docs/04-jobs-and-cost.md` §2(패키지 경계 · ArchUnit) · §5(트랜잭션 경계)
- `docs/02-domain-data.md` §6.1(`users`) · §7(계정 — 이 step 은 정원 · 탈퇴를 만들지 않는다. 테이블 모양만 참고)
- `docs/05-api.md` §1.1(인증 — 특히 "공개 전 임시 — 관리자 이메일만 로그인")
- `docs/ADR.md` ADR-010 · ADR-062
- `docs/07-testing.md` §1.2 의 "JPA · 네이티브 혼합" 행
- 이전 phase 산출물: `src/main/java/com/mogum/core/TransactionRunner.java` · `src/main/java/com/mogum/infra/SpringTransactionRunner.java` · `src/main/java/com/mogum/ClockConfiguration.java` · `src/main/resources/application.yml` · `src/main/resources/db/migration/` · `src/test/java/com/mogum/support/PostgresTestConfiguration.java` · `src/test/java/com/mogum/ArchitectureTest.java` · `src/test/java/com/mogum/infra/SpringTransactionRunnerIT.java`(테스트 작성 방식 참고)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

`1-auth` 의 첫 step 이다. 이 phase 는 구글 로그인 · 쿠키 세션 · CSRF · CORS · 최소 로그인 화면을 만든다. 이 step 은 그 바닥인 **`users` 테이블 · 계정 도메인 · 로그인 판정 유스케이스**만 만든다. 웹 · 보안 · 세션 · OAuth 는 다음 step 들의 범위다.

### 1. Flyway — `users`

`src/main/resources/db/migration/V2__create_users.sql`. 02 §6.1 의 컬럼 그대로다.

- `id bigserial` PK · `google_sub text` **unique · not null** · `email text not null` · `status text not null` · `role text not null` · `terms_agreed_at timestamptz` · `terms_version text` · `created_at timestamptz not null`
- `status` 는 `PENDING` / `ACTIVE` / `WAITLISTED`, `role` 은 `USER` / `ADMIN` 만 허용하는 CHECK 를 둔다.
- 세션 테이블(`spring_session` …)은 이 step 에서 만들지 않는다 — step 1 의 범위다.

### 2. `core.account` — 도메인 · 포트 · 유스케이스

패키지 `com.mogum.core.account`. 이름은 아래를 기준으로 하되 더 맞는 이름이 있으면 바꿔도 된다(바꾸면 summary 에 적는다).

```java
public record User(long id, String googleSub, String email, UserStatus status, UserRole role,
                   Instant termsAgreedAt, String termsVersion, Instant createdAt) {}
public enum UserStatus { PENDING, ACTIVE, WAITLISTED }
public enum UserRole { USER, ADMIN }

public interface UserRepository {          // 포트 — 구현은 infra
    Optional<User> findById(long id);
    Optional<User> findByGoogleSub(String googleSub);
    // 로그인에 필요한 생성 · 갱신 메서드는 재량
}

public record GoogleAccount(String sub, String email, boolean emailVerified) {}

public final class GoogleLogin {           // 로그인 판정 유스케이스
    public LoginResult login(GoogleAccount account);   // 성공이면 사용자 id, 거절이면 사유
}
```

`GoogleLogin.login` 의 규칙 — **이 규칙이 이 step 의 핵심이다.**

1. `emailVerified` 가 거짓이면 거절(사유: 이메일 미인증). DB 에 아무것도 쓰지 않는다.
2. **관리자 이메일 목록에 없는 이메일이면 거절**(사유: 허용되지 않은 계정 — ADR-062). DB 에 아무것도 쓰지 않는다 — 이미 행이 있는 사용자라도 갱신하지 않는다. 이메일 비교는 앞뒤 공백을 무시하고 대소문자를 구분하지 않는다. 목록이 비어 있으면 아무도 로그인하지 못한다.
3. 통과하면 `google_sub` 로 찾는다. 이메일은 바뀔 수 있으므로 **식별자는 `google_sub` 다**(02 §6.1).
   - 있으면 `email` 과 `role` 을 이번 로그인 값으로 갱신한다. `status` · 약관 컬럼 · `created_at` 은 건드리지 않는다.
   - 없으면 새로 만든다 — `status=ACTIVE`, `role` 은 아래 역할 계산, 약관 컬럼 NULL, `created_at` 은 주입받은 `Clock` 의 현재 시각.
4. **역할 계산과 거절을 따로 둔다.** 역할은 "관리자 이메일 목록에 있으면 `ADMIN`, 아니면 `USER`" 로 계산하는 독립된 코드로 두고, 2번의 거절은 그와 별개의 분기로 둔다. 이유: `12-account-public` 에서 거절 분기만 지우고 역할 계산은 남긴다(ADR-062). 지금은 거절 때문에 결과가 늘 `ADMIN` 이지만, 역할 계산 테스트는 `USER` 경우도 확인한다.
5. 찾기와 생성 · 갱신은 `TransactionRunner` 로 한 트랜잭션에 둔다(금지 목록 3 — `@Transactional` 을 `core` 에 쓰지 않는다).
6. 같은 `google_sub` 의 동시 첫 로그인은 unique 제약으로 한 행만 남는 것으로 충분하다 — 진 쪽은 예외로 끝나도 된다(사용자가 다시 로그인하면 기존 행으로 들어온다). 재시도 · `ON CONFLICT` 장치를 따로 만들지 마라.

관리자 이메일 목록은 설정 `app.auth.admin-emails`(쉼표 구분 목록 — 환경 변수로 덮어쓸 수 있게)에서 받는다. **바인딩과 빈 등록은 루트 패키지 `com.mogum` 에 바로 둔다** — `com.mogum.config` 같은 하위 패키지에 두지 않는다(ArchUnit 규칙 1 의 허용 목록이 루트 `..mogum` 만 허용한다). `core` 는 Spring 설정 바인딩을 모른다 — 목록은 생성자 인자 등 평범한 값으로 받는다. `application.yml` 의 기본값은 빈 목록이다(실제 이메일을 저장소에 적지 않는다).

### 3. `infra` — JPA 엔티티 · 어댑터

- `users` 를 매핑하는 JPA 엔티티는 `infra` 에만 둔다(금지 목록 4). `ddl-auto: validate` 를 통과해야 한다.
- `UserRepository` 구현은 Spring Data 리포지토리를 써도 된다. **트랜잭션 안에서 완전히 채운 `User` record 를 돌려준다** — 엔티티를 `infra` 밖으로 내보내지 않는다.

### 4. 테스트

TDD 가드: 구현 파일마다 같은 패키지에 `{클래스명}*Test.java` 또는 `{클래스명}*IT.java` 가 먼저 있어야 쓰기가 허용된다. record · enum · interface · `@Entity` 는 검사하지 않는다.

- **`GoogleLogin`** — 위 규칙 1~4 의 분기 전부: 미인증 거절 · 목록 밖 거절(기존 행이 있어도 갱신 없음) · 대소문자/공백 무시 · 빈 목록 · 첫 로그인 생성(`ACTIVE` · `ADMIN` · 약관 NULL · 주입한 시계의 시각) · 재로그인 시 같은 id 로 이메일 갱신 · 역할 계산(`USER` 경우 포함).
  실제 Postgres 로 돌리든 가짜 포트로 돌리든 재량이다. 거절 때 "DB 에 쓰지 않았다" 는 실제 DB 로 확인한다.
- **어댑터** — 실제 Postgres(`PostgresTestConfiguration` 상속)로 저장 · 조회 · `google_sub` unique 위반 · CHECK 위반.
- **JPA · 네이티브 혼합 규칙 테스트**(07 §1.2) — 한 트랜잭션에서 `users` 엔티티를 JPA 로 바꾸고, `flush()` 한 뒤 같은 트랜잭션의 `EntityManager` 네이티브 쿼리로 읽으면 바뀐 값이 보인다. 그리고 포트가 돌려준 record 를 트랜잭션이 끝난 뒤 읽어도 예외가 없다.
  - Hibernate 는 네이티브 쿼리 전에 스스로 flush 할 수도 있다. **flush 없이 옛 값이 읽힌다는 것을 단언하는 테스트는 쓰지 마라** — 동작이 설정에 따라 다르다. 대신 flush 없이 읽었을 때 실제로 어떤 값이 보였는지를 관찰해 summary 에 한 줄로 적는다(`AGENTS.md` 금지 목록 4 는 고치지 않는다 — 문서 판단은 리뷰 때 한다).
- 테스트 DB 는 JVM 에서 공유된다(`PostgresTestConfiguration` 주석). `google_sub` · 이메일은 테스트마다 겹치지 않는 값을 쓰거나 테스트가 만든 행을 지운다.

### 5. 문서

- `docs/02-domain-data.md` §6.1 — 마이그레이션에서 정한 NOT NULL · CHECK 가 표와 다르면 표를 맞춘다. 다르지 않으면 고치지 않는다.

## Acceptance Criteria

```bash
./gradlew test    # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 패키지 구조(`core` ← `api` · `worker` · `infra`)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/1-auth/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 다음 step 이 쓸 이름(유스케이스 · 결과 타입 · 포트 메서드 · 설정 키 · 마이그레이션 번호)을 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- Spring Security · Spring Session · OAuth 의존성과 컨트롤러를 넣지 마라. 이유: step 1 · 3 의 범위다.
- `PENDING` 생성 · 약관 동의 · 정원 검사 · 탈퇴를 만들지 마라. 이유: `12-account-public` 의 범위다(ADR-062).
- 관리자 목록 밖의 계정을 `users` 에 저장하지 마라(거절 사유 기록용 행 포함). 이유: 약관 동의 전 이메일 저장 금지 — ADR-062 의 이유다.
- 실제 이메일 주소를 `application.yml` · 테스트 · 문서에 적지 마라. 이유: 저장소가 공개될 수 있다. 테스트는 `example.com` 같은 예시 도메인을 쓴다.
- 테스트용 `@Entity` · Spring Data `Repository` 를 `src/test/java/com/mogum` 아래에 만들지 마라. 이유: `@SpringBootTest` 의 스캔 범위에 들어가 모든 컨텍스트 테스트가 깨진다(HARNESS-BACKLOG S3).
- `src/test/resources/application.yml` 을 만들지 마라. 이유: 같은 이름이라 `src/main/resources/application.yml` 을 통째로 가린다. 테스트 전용 값은 `@SpringBootTest(properties = …)` · `@DynamicPropertySource` 등으로 준다.
- 사용자를 프로세스 메모리에 캐시하지 마라. 이유: 금지 목록 25 · 27 — 상태 · 역할은 요청마다 `users` 에서 읽는다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
