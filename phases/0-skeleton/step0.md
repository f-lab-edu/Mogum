# Step 0: gradle-archunit

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 1 · 2 · 26, 작업 방식(TDD 가드의 짝 테스트 규칙)
- `docs/ARCHITECTURE.md` — 디렉토리 구조 · 패턴
- `docs/04-jobs-and-cost.md` §1(기술 선택) · §2(패키지 경계 — ArchUnit 규칙 원문)
- `docs/ADR.md` ADR-005 · ADR-059

저장소에 아직 Java 코드가 없다. 이 step 이 첫 코드다.

## 작업

단일 Gradle 모듈 · 패키지 경계 · ArchUnit 규칙을 만든다.

### 환경 (이 머신)

- 기본 `java` 는 25 다. JDK 21 은 SDKMAN(`~/.sdkman/candidates/java/21.0.6-librca`)에 있다. **빌드는 Java 21 toolchain 으로 한다.** `./gradlew` 는 Java 25 위에서 데몬이 돌아도 동작해야 한다 — Java 25 를 지원하는 Gradle 9.x 를 쓴다.
- `gradle` 명령이 설치되어 있지 않다. wrapper(`gradlew` · `gradlew.bat` · `gradle/wrapper/`)를 만드는 방법은 재량이다(배포판을 내려받아 `gradle wrapper` 실행 등). wrapper jar 까지 저장소에 남는 파일로 둔다.

### 1. Gradle

- `settings.gradle` — `rootProject.name = 'mogum'`
- `build.gradle`(Groovy DSL) — `group = 'com.mogum'` · Java 21 toolchain · **Spring Boot 4.x 최신 안정판** · JUnit Jupiter(Boot 가 관리하는 버전 — JUnit 4 가 아니라는 뜻이다. 버전을 따로 고정하지 않는다)
- 의존성은 이 step 에 필요한 것만 넣는다: Spring MVC starter · test starter · ArchUnit(JUnit Platform 용). Boot 4 는 starter 가 기능별로 쪼개졌다(예: MVC 는 `spring-boot-starter-webmvc`) — 실제 아티팩트 이름은 확인해서 쓴다.

### 2. 애플리케이션

- `src/main/java/com/mogum/MogumApplication.java` — `@SpringBootApplication` 과 `main` 만.

### 3. 패키지

뼈대에서는 각 패키지에 역할을 한 줄로 적은 `package-info.java`를 둔다(역할은 04 §2 표). 구현이 추가된 패키지에서는 이 설명용 파일을 제거하고, 구현이 없는 패키지에서는 유지한다.

- `com.mogum.core` · `com.mogum.core.job` · `com.mogum.core.generation`
- `com.mogum.api` · `com.mogum.worker` · `com.mogum.infra`

### 4. ArchUnit — `src/test/java/com/mogum/ArchitectureTest.java`

- 04 §2 의 규칙 5개를 **그대로** 옮긴다 — 규칙 이름 · 패키지 패턴 · 금지 패키지 목록까지.
- 규칙 1 의 루트 설정 패턴 `"..mogum"` 은 `com.mogum` 패키지 **자체**에만 맞는다. 그래서 **설정 배선 클래스는 하위 패키지(`com.mogum.config` 등)가 아니라 `com.mogum` 에 둔다** — 하위 패키지에 두면 규칙 1 에 걸린다. 이 사실을 테스트 클래스 주석에 한 줄 남긴다.
- 운영 코드 검사는 `src/main` 클래스만 가져온다(테스트 클래스 제외).
- **규칙이 실제로 위반을 잡는다는 것을 테스트로 보여라.** 지금은 패키지가 비어 있어 규칙이 아무것도 검사하지 않고 통과할 수 있다. 테스트 전용 위반 예시 클래스(`src/test/java/com/mogum/archfixture/...`)를 규칙마다 하나 이상 두고, 각 규칙이 그 예시에서 실패하는 것을 확인한다.
  - 규칙 1 은 `api → core.generation` 과 함께 **`core.job → core.generation` 우회**도 잡는지 확인한다(04 §2 — 허용 목록으로 적은 이유).
  - 규칙 5 는 지금 클래스패스에 있는 금지 패키지(예: `org.springframework.web`) 하나로 보여도 된다. `jakarta.persistence` · `com.openai` 는 아직 의존성이 없다.
- 빈 패키지 때문에 운영 코드 검사가 "검사 대상 없음" 으로 실패하면 **그 운영 코드 검사에만** `allowEmptyShould(true)` 를 쓴다. 위반 예시 테스트가 규칙의 효력을 보장한다.

### 5. 설정

- `src/main/resources/application.yml` — `spring.application.name: mogum` 만.

## Acceptance Criteria

```bash
./gradlew test
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

- DB · JPA · Flyway · Spring Security 의존성을 넣지 마라. 이유: step 1 과 `1-auth` 범위다. 지금 넣으면 컨텍스트 기동에 DB 가 필요해진다.
- 04 §2 의 규칙을 무르지 마라(패키지 빼기 · `ignoreDependency` · 패턴 완화 · 이름 바꾸기). 이유: 금지 목록 1 — 규칙은 첫 커밋부터 강제한다.
- `archunit.properties` 로 빈 검사 허용을 전역으로 켜지 마라. 이유: 이후 추가될 모든 규칙이 아무것도 검사하지 않고 통과할 수 있게 된다.
- Kotlin DSL(`build.gradle.kts`)을 쓰지 마라. 이유: `docs/ARCHITECTURE.md` 가 `build.gradle` 로 적고 있다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라(`scripts/` 의 Python 테스트 포함).
