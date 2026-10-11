# Step 1: database

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 2 · 3 · 4 · 25, 명령어
- `docs/04-jobs-and-cost.md` §1(기술 선택) · §3(배포 — 로컬 · 운영 구성) · §5(트랜잭션 경계)
- `docs/ADR.md` ADR-002 · ADR-042 · ADR-059
- `docs/07-testing.md` §1.1 첫머리(실제 PostgreSQL 위에서 테스트한다)
- 이전 step 산출물: `build.gradle` · `src/test/java/com/mogum/ArchitectureTest.java` · `src/main/resources/application.yml`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

PostgreSQL + pgvector · Flyway · JPA 설정 · Testcontainers 테스트 기반 · 로컬 Docker Compose 를 만든다. 도메인 테이블은 만들지 않는다.

Docker 가 필요하다(Testcontainers). Docker 데몬에 연결할 수 없으면 `blocked`(사유: Docker 미실행)로 멈춘다.

### 1. 의존성

- Spring Data JPA · Flyway(Boot 4 는 Flyway 전용 starter 가 있다 — 그것과 `flyway-database-postgresql`) · PostgreSQL JDBC 드라이버
- 테스트: `spring-boot-testcontainers` · Testcontainers 2.x 의 PostgreSQL · JUnit Jupiter 모듈. **2.x 는 아티팩트 이름이 바뀌었다**(`testcontainers-` 접두사) — 확인해서 쓴다.

### 2. 테스트 DB

- 이미지는 `pgvector/pgvector:pg17`.
- 테스트 지원 설정(예: `src/test/java/com/mogum/support/PostgresTestConfiguration.java` — `@ServiceConnection`)을 두고, **모든 DB 테스트가 컨테이너 하나를 재사용**하게 한다. 이후 TASK 에서 DB 테스트가 수백 개가 된다 — 테스트 클래스마다 컨테이너를 새로 띄우지 않는다.
- 다음 step 들이 이 설정을 가져다 쓴다. 쓰는 방법(어노테이션 · `@Import` 등)이 클래스 주석에 드러나게 한다.

### 3. Flyway

- `src/main/resources/db/migration/V1__enable_pgvector.sql` — `CREATE EXTENSION IF NOT EXISTS vector;` 한 문장만.

### 4. `application.yml`

- `spring.jpa.open-in-view: false` — 금지 목록 3
- `spring.jpa.hibernate.ddl-auto: validate` — 스키마는 Flyway 만 바꾼다
- 로컬 데이터소스 — `compose.yaml` 의 Postgres 를 가리키는 기본값. 환경 변수로 덮어쓸 수 있게 한다(예: `${DB_URL:jdbc:postgresql://localhost:5432/mogum}`). 기본값에는 로컬 전용 계정 · 비밀번호만 둔다.

### 5. `compose.yaml` (저장소 루트)

- Postgres 서비스 하나. 테스트와 **같은 이미지 태그**, 이름 있는 볼륨, `5432` 포트.

### 6. `AGENTS.md` 명령어

- 명령어 블록 맨 위에 `docker compose up -d        # 로컬 Postgres(pgvector) — bootRun 전에` 한 줄을 넣는다. 다른 줄은 건드리지 않는다.

### 7. 테스트 — `src/test/java/com/mogum/DatabaseSetupIT.java`

- 애플리케이션 컨텍스트가 Testcontainers Postgres 로 뜬다
- Flyway `V1` 이 적용됐고 `vector` 확장이 설치되어 있다(`pg_extension`)
- `spring.jpa.open-in-view` 가 꺼져 있다

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
3. 결과에 따라 `phases/0-skeleton/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `spring-boot-docker-compose` 를 넣지 마라. 이유: 로컬 DB 는 `docker compose up -d` 로 명시적으로 띄운다 — `bootRun` · 테스트가 몰래 컨테이너를 띄우지 않게 한다.
- 도메인 테이블 · JPA 엔티티 · Spring Session 테이블을 만들지 마라. 이유: 각 TASK(`1-auth` 부터)의 범위다.
- `compose.yaml` 에 LLM 스텁 · Prometheus · Grafana 를 넣지 마라. 이유: 04 §3 의 로컬 구성은 최종 모습이고, 각 TASK 에서 넣는다.
- H2 · 임베디드 DB 로 테스트하지 마라. 이유: advisory lock · `SKIP LOCKED` · pgvector 는 실제 PostgreSQL 에서만 검증된다(07 §1.1).
- `.env` 파일을 만들거나 읽지 마라. 이유: `AGENTS.md` — 비밀은 저장소와 에이전트 밖에 둔다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
