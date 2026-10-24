# Step 1: session-api

> 2026-10-19 후속 변경: 이 step의 구글 전용 계정 모델/클래스는 ADR-064로 대체했다. 현재 구현은 `Login` · `ExternalAccount` · `OAuthLoginHandler`와 `user_identities`이며, 기존 V2는 유지하고 V4로 이전한다. 최신 구조와 검증은 [02 §6.1](../../docs/02-domain-data.md) · [04 §2](../../docs/04-jobs-and-cost.md) · [07 §1.2](../../docs/07-testing.md)를 따른다.

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 21 · 23 · 25 · 26 · 27, 작업 방식(TDD 짝 테스트 규칙)
- `docs/05-api.md` §1(경로 — `/api` 접두사) · §1.1(인증 — 세션 · CSRF · 화면과 API 의 출처) · §1.2(에러 형식 · `code` 표 — `UNAUTHORIZED` · `NOT_FOUND` · `CSRF_INVALID`) · §1.3(소유권)
- `docs/02-domain-data.md` §6.1(`users` 와 그 아래 "세션 테이블" 문단)
- `docs/04-jobs-and-cost.md` §3.2(만료 세션 정리)
- `docs/07-testing.md` §1.2 의 "세션" · "소유권" 행
- `docs/ADR.md` ADR-010 · ADR-060 · ADR-062
- 이전 step 산출물: `src/main/java/com/mogum/core/account/` · `src/main/java/com/mogum/infra/account/` 의 사용자 어댑터 · `src/main/resources/db/migration/` · `src/main/resources/application.yml` · `build.gradle` · `src/test/java/com/mogum/support/PostgresTestConfiguration.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

Spring Security + Spring Session JDBC 로 **쿠키 세션 위의 JSON API 바닥**을 만든다 — 세션 저장 · 요청마다 사용자 조회 · 공통 에러 형식 · CSRF · `GET /api/me` · `POST /api/auth/logout`. 구글 로그인(세션을 만드는 쪽)은 step 3, CORS · 쿠키 도메인은 step 2 다. 이 step 의 테스트는 세션을 테스트 지원 코드로 직접 만든다.

### 1. 의존성

- `spring-boot-starter-security` · `spring-boot-starter-session-jdbc`, 테스트 `spring-boot-starter-security-test`. **버전을 적지 않는다** — Spring Boot 4.1.1 이 관리한다(Spring Security 7.1.x · Spring Session 4.1.x). Boot 4 의 API 를 쓴다 — 옛 3.x 예제(`WebSecurityConfigurerAdapter` · `antMatchers` 등)를 옮기지 마라.

### 2. 세션 테이블 — Flyway

- 다음 번호의 마이그레이션(예: `V3__create_spring_session.sql`)에 Spring Session JDBC 의 PostgreSQL 스키마를 넣는다. **관리 버전 jar 안의 `org/springframework/session/jdbc/schema-postgresql.sql` 내용을 그대로 옮긴다** — 컬럼 · 인덱스를 손으로 바꾸지 않는다.
- 라이브러리의 스키마 자동 생성은 끈다(`spring.session.jdbc.initialize-schema: never`). 이유: 02 §6.1 — 스키마는 Flyway 만 바꾼다.
- 만료 세션 정리 주기는 기본값 그대로 둔다(04 §3.2).

### 3. 세션 — 사용자 id 만

- **비활동 7일이면 만료**(ADR-010).
- 세션 쿠키 — `HttpOnly` · `Secure` · `SameSite=Lax` · `Domain` 없음(05 §1.1). **`Secure` 는 요청이 https 인지에 맡기지 말고 항상 켠다.** 이유: 운영은 리버스 프록시 뒤라 앱이 http 로 받는다. 로컬 `http://localhost` 는 브라우저가 `Secure` 쿠키를 받아 준다.
- **세션에는 사용자 id 만 둔다**(금지 목록 27). 이메일 · 상태 · 역할 · 권한 · 구글 정보를 세션에 넣지 않는다.
  - 로그인 상태를 세션에 어떻게 담을지는 재량이다 — 사용자 id 하나만 담은 `Authentication` 을 `SecurityContext` 로 저장하든, 사용자 id 속성만 두고 요청마다 `Authentication` 을 만들든 된다. 어느 쪽이든 **`spring_session.principal_name` 이 `users.id` 문자열이 되게 한다**(02 §6.1 — 탈퇴 때 그 사용자의 세션을 한 번에 지우려고).
  - 세션 속성은 Java 직렬화로 DB 에 저장된다. 직접 만든 타입을 넣으면 `serialVersionUID` 를 고정하고 필드는 사용자 id 하나로 둔다. 이유: 타입이 바뀌면 배포 뒤 기존 세션을 읽지 못한다.
  - 세션에 로그인 사용자를 기록하는 형식(속성 이름 · 타입)을 summary 에 적는다. step 3 의 실제 로그인이 같은 형식으로 쓴다.
- **요청마다 `users` 에서 그 사용자를 읽는다**(step 0 의 포트). 상태 · 역할은 여기서 읽은 값만 쓴다. 행이 없으면(탈퇴 등) 그 세션을 무효화하고 `401 UNAUTHORIZED` 다. 사용자를 메모리에 캐시하지 않는다(금지 목록 25).
- 컨트롤러가 현재 사용자(id · 상태 · 역할)를 받는 공통 방법(인자 리졸버 등)을 둔다. 이후 TASK 의 모든 `{id}` 조회는 이 사용자 id 로 주인을 확인한다(05 §1.3).

### 4. 공통 에러 · 경로

- 에러 응답 본문은 05 §1.2 형식 `{ "code": …, "message": … }` 하나로 통일한다(`retryAfterSeconds` 는 값이 있을 때만 — 이 step 에는 없다). Spring Security 가 내는 401 · 403 도 같은 형식이다.
- **로그인 없이 `/api/**` → `401 UNAUTHORIZED` JSON.** 구글 로그인 · 로그인 페이지로 리다이렉트하지 않는다(`Location` 없음). 화면은 401 을 받아 U1 으로 간다(06 §2.6). 폼 로그인 · HTTP Basic · 기본 로그인 페이지를 켜지 않는다.
- **"없거나 남의 것" 공통 예외** — `core` 최상위 패키지(`com.mogum.core`)에 하나 둔다(이름 재량, 예: `NotFoundException`). 유스케이스가 이걸 던지면 `404 NOT_FOUND` 다(05 §1.3 — `403` 이 아니다). 로그인한 사용자가 없는 `/api/**` 경로를 부르면 역시 `404 NOT_FOUND` JSON 이다.
- 05 §1.2 표에 없는 `code` 를 새로 만들면 표에 행을 추가한다.

### 5. CSRF

- Spring Security 의 CSRF 보호를 켠 채로 둔다(05 §1.1).
- 토큰은 **`XSRF-TOKEN` 쿠키**에 둔다(세션에 두지 않는다 — 세션에는 사용자 id 만). 쿠키는 스크립트가 읽을 수 있어야 한다(`HttpOnly` 아님) · `Secure` · `SameSite=Lax` · `Path=/`. 이 step 에서는 `Domain` 을 두지 않는다 — 도메인 설정은 step 2 다.
- 화면은 `XSRF-TOKEN` 쿠키 값을 **그대로** `X-XSRF-TOKEN` 헤더에 실어 보낸다. 이 방식으로 통과해야 한다 — Spring Security 6+ 의 기본 토큰 처리(BREACH 마스킹)는 원래 쿠키 값을 거절할 수 있으니 설정으로 맞춘다.
- 화면이 첫 상태 변경 요청 전에 쿠키를 갖도록, 쿠키가 없으면 `GET /api/me` 응답에서 발급한다.
- 실패는 `403 CSRF_INVALID`(05 §1.2).

### 6. 엔드포인트

- `GET /api/me` → `200 { "email", "status", "role", "termsAgreed", "waitlistPosition" }`. `termsAgreed` = `terms_agreed_at` 이 있다. `waitlistPosition` 은 지금은 늘 `null`(대기 명단은 `12-account-public`). `quota` 는 `9-cost-control` 에서 더하므로 이 step 에서는 넣지 않는다.
- `POST /api/auth/logout` → `204`. **이 요청의 세션만** 무효화하고(세션 행 삭제) 세션 쿠키를 만료시킨다. 같은 사용자의 다른 세션은 그대로다. 세션이 없어도 `204` 다(멱등). CSRF 검사 대상이다.

### 7. 패키지

- 보안 설정 · 컨트롤러 · DTO 는 `com.mogum.api` 아래(예: `com.mogum.api.auth` · `com.mogum.api.account`). `api` 는 `infra` 를 참조하지 않는다 — 사용자 조회는 `core.account` 포트로 한다(ArchUnit 규칙 3).
- 루트 `com.mogum` 에는 꼭 필요한 배선만 둔다.

### 8. 테스트

TDD 가드: 구현 파일마다 같은 패키지에 `{클래스명}*Test.java` 또는 `{클래스명}*IT.java` 가 먼저 있어야 쓰기가 허용된다.

**세션 · CSRF 동작은 실제 세션 저장소(`spring_session` 행)와 실제 쿠키로 검증한다.** `@WithMockUser` · `SecurityMockMvcRequestPostProcessors.authentication()` · `csrf()` 후처리기로 세션 · CSRF 동작을 대신하지 마라 — 그건 이 step 이 만드는 장치를 건너뛴다. 세션을 만드는 테스트 지원 코드(예: `src/test/java/com/mogum/support/` 의 평범한 클래스)는 앱의 세션 저장소 빈으로 세션을 저장하고 그 세션 쿠키 값을 돌려준다.

- Flyway 가 세션 테이블을 만든다. 테스트로 만든 세션 행의 `principal_name` 이 사용자 id 이고 비활동 만료가 7일(604800초)이다.
- **07 §1.2 "세션" 행 중 이 step 몫** — 같은 사용자의 세션 둘(기기 둘). 한쪽을 로그아웃하면 그쪽만 `401`, 다른 쪽은 `GET /api/me` `200`. SQL 로 `users.role` 을 바꾸면 세션을 바꾸지 않아도 다음 요청의 `/api/me` 에 바뀐 값이 나온다. SQL 로 `users` 행을 지우면 두 세션 모두 `401`.
- 로그인 없이 · 모르는 세션 쿠키로 `GET /api/me` → `401 {"code":"UNAUTHORIZED"}` · `Location` 헤더 없음.
- 로그인 상태로 없는 `/api/…` 경로 → `404 {"code":"NOT_FOUND"}`. 공통 예외 → `404 NOT_FOUND` 변환은 예외 처리기를 직접 부르는 단위 테스트로 확인해도 된다.
- CSRF — `GET /api/me` 응답의 `XSRF-TOKEN` 쿠키 값을 그대로 `X-XSRF-TOKEN` 헤더에 넣은 로그아웃은 `204`. 헤더가 없거나 다르면 `403 {"code":"CSRF_INVALID"}` 이고 세션은 살아 있다. `XSRF-TOKEN` 쿠키는 `HttpOnly` 가 아니고 `Secure` · `SameSite=Lax` 다.
- 세션 쿠키 속성(`HttpOnly` · `Secure` · `SameSite=Lax` · `Domain` 없음)을 확인한다 — 이 step 에는 세션을 만드는 HTTP 경로가 없으니 로그아웃이 내려보내는 만료 쿠키 등으로 확인한다. 로그인 때 발급되는 쿠키는 step 3 에서 확인한다.
- `GET /api/me` 의 필드 · 값.
- 테스트 DB 는 JVM 에서 공유된다 — 사용자 · 세션 값이 테스트끼리 겹치지 않게 한다.

### 9. 문서

- `docs/05-api.md` §1.1 — `GET /me` 예시 아래에 이 phase 의 응답 범위를 한 줄로 적는다(`quota` 는 `9-cost-control` 부터 · `waitlistPosition` 은 `12-account-public` 전까지 `null`). 세션에 로그인 사용자를 담는 형식이 정해졌으면 한 줄로 적는다.
- `docs/02-domain-data.md` §6.1 "세션 테이블" 문단 — 마이그레이션 파일 이름을 적는다.

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
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 세션에 로그인 사용자를 담는 형식 · 현재 사용자를 받는 방법 · 테스트 세션 지원 코드 경로 · 공통 예외 이름을 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- OAuth2 client 의존성 · `/auth/google` 경로를 넣지 마라. 이유: step 3 의 범위다.
- CORS 설정 · `XSRF-TOKEN` 쿠키 `Domain` 설정을 넣지 마라. 이유: step 2 의 범위다.
- 세션에 사용자 id 외의 값(이메일 · 상태 · 역할 · 권한 · CSRF 토큰)을 넣지 마라. 이유: 금지 목록 27 — 승인 · 역할 변경이 로그인 중인 사람에게 바로 반영돼야 한다.
- JWT · 토큰 인증을 만들지 마라. 이유: ADR-010.
- 테스트 전용 `@RestController` · `@Component` · `@Entity` 를 `src/test/java/com/mogum` 아래에 만들지 마라. 이유: `@SpringBootTest` 의 스캔 범위라 모든 컨텍스트 테스트에 섞인다(HARNESS-BACKLOG S3).
- `src/test/resources/application.yml` 을 만들지 마라. 이유: `src/main/resources/application.yml` 을 통째로 가린다.
- `/api/**` 의 401 을 로그인 페이지 리다이렉트로 바꾸지 마라. 이유: 화면(SPA)이 401 을 받아야 U1 로 보낼 수 있다(06 §2.6).
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
