# Step 7: archunit-fixtures

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 1 · 2 · 3 · 4
- `docs/04-jobs-and-cost.md` §2(ArchUnit 규칙 원문 — 규칙 5 `core는_인프라_기술을_모른다`)
- `docs/07-testing.md` §1.2 의 "ArchUnit" 행
- 이전 step 산출물: `src/test/java/com/mogum/ArchitectureTest.java` · `src/test/java/com/mogum/archfixture/` · `build.gradle`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

phase 리뷰에서 나온 보강이다. 지금 `ArchitectureTest` 는 규칙 5(`core` 는 인프라 기술을 모른다)가 위반을 잡는다는 것을 `org.springframework.web` 예시 하나로만 보인다. step 0 때는 JPA 가 없어서 그랬다. step 1 에서 JPA · Spring Data 가 들어왔으므로, 07 §1.2 가 ArchUnit 을 두는 이유로 직접 꼽은 **`core` 에 `jakarta.persistence` 가 들어오는 경우**를 포함해 위반 예시를 보강한다.

### 1. 위반 예시 — `src/test/java/com/mogum/archfixture/core/`

규칙 5 의 금지 패키지 중 지금 클래스패스에 있는 셋에 하나씩:

- `jakarta.persistence` — 예: `EntityManager` 를 필드나 매개변수로 참조
- `org.springframework.transaction` — 예: 메서드에 `@Transactional`
- `org.springframework.data` — 예: `Pageable` 을 매개변수로 참조

**JPA · Spring Data 스캔에 걸리지 않는 형태로 만든다.** `@Entity` 클래스나 Spring Data `Repository` 를 상속한 인터페이스를 만들지 마라. 이유: 테스트 클래스도 `@SpringBootTest` 의 엔티티 · 리포지토리 스캔 범위(`com.mogum`)에 들어가, `ddl-auto: validate` 가 없는 테이블로 실패하거나 리포지토리 빈 생성이 깨진다. 해당 패키지의 타입을 참조하는 것만으로 의존이 생긴다.

### 2. 테스트 — `src/test/java/com/mogum/ArchitectureTest.java`

- 기존 `core에서_Spring_Web_참조를_거부한다` 를 위 세 예시까지 포함하는 검사로 넓힌다(매개변수화 등 형태는 재량). 각 예시에서 규칙 5 가 실패하고, 위반 메시지에 그 예시 클래스 이름이 들어 있어야 한다(기존 `assertViolation` 을 쓴다).
- `com.openai` 는 아직 의존성이 없으므로 예시를 만들지 않는다.
- 운영 코드 검사(`CLASSES`)에 위반 예시가 섞이지 않는다는 기존 테스트가 그대로 통과해야 한다.

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

- 04 §2 의 규칙 자체(패턴 · 금지 패키지 목록 · 이름)를 바꾸지 마라. 이유: 금지 목록 1 — 이 step 은 증거만 보강한다.
- `src/main` 을 고치지 마라. 이유: 테스트 보강 step 이다.
- 위반 예시를 `@Entity` · `@Component` · Spring Data `Repository` 로 만들지 마라. 이유: 위 1번 — 스프링 컨텍스트 테스트가 깨진다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
