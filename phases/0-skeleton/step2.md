# Step 2: transaction-runner

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 2 · 3 · 4
- `docs/04-jobs-and-cost.md` §5(트랜잭션 경계 — LLM 호출 중에는 커넥션을 쥐지 않는다)
- `docs/ARCHITECTURE.md` — 패턴 표의 "드러낸 트랜잭션"
- 이전 step 산출물: `src/test/java/com/mogum/support/` 의 Postgres 테스트 설정 · `src/main/resources/application.yml` · `src/test/java/com/mogum/ArchitectureTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

트랜잭션 경계를 코드에 드러내는 `TransactionRunner` 포트와 구현을 만든다. 이후 모든 TASK 가 `@Transactional` 대신 이것을 쓴다 — 어노테이션은 경계를 메서드 뒤에 숨겨 커넥션을 LLM 호출 너머로 끌고 간다(04 §5).

### 1. 포트 — `src/main/java/com/mogum/core/TransactionRunner.java`

```java
public interface TransactionRunner {
    <T> T inTransaction(Supplier<T> work);
    void inTransaction(Runnable work);
}
```

### 2. 구현 — `src/main/java/com/mogum/infra/SpringTransactionRunner.java`

- `TransactionTemplate`(기본 전파 `REQUIRED`)으로 구현하고 빈으로 등록한다.
- 블록이 던진 런타임 예외는 **감싸지 않고 그대로** 호출자에게 전파한다.

### 3. 테스트 — `src/test/java/com/mogum/infra/SpringTransactionRunnerIT.java` (실제 Postgres)

- 정상 반환 → 커밋되어 다른 트랜잭션에서 보인다
- 런타임 예외 → 롤백되고, 같은 예외 객체가 호출자에게 전파된다
- 블록 안에서는 트랜잭션이 활성이고, 반환한 뒤에는 활성 트랜잭션이 없다(블록 밖에서 커넥션을 쥐지 않는다)
- 테스트용 테이블은 테스트 안에서 만들고 지운다

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

- `core` 에 `@Transactional` · `org.springframework.transaction` 을 쓰지 마라. 이유: 금지 목록 2 · 3 — ArchUnit 이 빌드를 깬다. 규칙을 무르지 말고 코드를 고친다.
- readOnly · 전파 옵션 · 격리 수준 · 재시도 같은 변형을 추가하지 마라. 이유: 쓰는 곳이 생길 때 그 TASK 에서 넣는다.
- 테스트용 테이블을 Flyway 마이그레이션에 넣지 마라. 이유: 운영 스키마에 테스트 흔적이 남는다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
