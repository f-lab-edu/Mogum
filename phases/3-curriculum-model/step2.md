# Step 2: owner-lookup

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 21(주인 확인 · 남의 것이면 `404`) · 31
- `docs/ADR.md` — ADR-077(부모를 따라가는 조회를 Spring Data 메서드 이름으로 쓴다)
- `docs/02-domain-data.md` — §6.2 · §6.2.1(단계 커리큘럼의 주인은 묶음의 주인 — `Curriculum.forStep`)
- `docs/05-api.md` §1.3 — 없는 것과 남의 것을 같은 `404` 로 돌려주는 이유
- step 0 · step 1 이 만든 파일(이전 step summary) — 특히 `backend/src/main/java/com/mogum/curriculum/domain/` · `…/curriculum/repository/` 와 그 테스트

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

`{id}` 로 찾는 리소스는 주인 확인 뒤에 돌려주고, 남의 것이면 없는 것과 같이 `404` 다(AGENTS 금지 21). `6` 의 서비스가 `Optional` 이 비면 `404` 로 바꾼다. 이 step 은 그 조회를 리포지토리 메서드 넷으로 만든다 — **주인 조건을 쿼리에 넣어** "읽고 나서 비교" 를 서비스마다 다시 쓰지 않게 한다. 계약 문서는 이미 있다. **코드가 문서와 어긋나면 코드를 문서에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문서를 고치지 마라** — 아래 「3. 문서」 의 사실 갱신만 한다.

## 작업

### 1. 리포지토리 메서드 — 메서드 이름 파생 쿼리

```java
// CurriculumRepository
Optional<Curriculum> findByIdAndUserId(Long id, Long userId);
// CurriculumGroupRepository
Optional<CurriculumGroup> findByIdAndUserId(Long id, Long userId);
// LessonRepository — 강의의 주인은 커리큘럼의 주인
Optional<Lesson> findByIdAndCurriculum_UserId(Long id, Long userId);
// CurriculumStepRepository — 단계의 주인은 묶음의 주인
Optional<CurriculumStep> findByIdAndGroup_UserId(Long id, Long userId);
```

- 이 넷만 만든다. `@Query` · 네이티브 SQL 로 쓰지 마라. 이유: ADR-077 이 연관을 둔 이유가 부모를 따라가는 조회를 메서드 이름으로 쓰는 것이다.
- 단계 커리큘럼도 `user_id` 를 묶음에서 복사해 가지므로(step 1 의 `Curriculum.forStep`) `CurriculumRepository.findByIdAndUserId` 하나로 하나짜리 · 단계 커리큘럼을 모두 찾는다 — 단계 커리큘럼용 조회를 따로 만들지 마라.

### 2. 테스트 — `backend/src/test/java/com/mogum/curriculum/repository/`

DB 테스트(`PostgresTestConfiguration` 상속 · step 1 의 정리 방식). 메서드 넷 각각:

- **내 것** — 주인 ID 로 찾으면 그 행이 나온다.
- **남의 것** — 다른 회원 ID 로 찾으면 비어 있다.
- **없는 것** — 없는 ID 면 비어 있다. 남의 것과 결과가 같은지(둘 다 `Optional.empty()`) 확인한다.
- 단계 커리큘럼 — `Curriculum.forStep` 으로 저장한 커리큘럼을 묶음 주인 ID 로 찾으면 나오고, 다른 회원 ID 로는 비어 있다.

### 3. 문서 (구현으로 정해진 사실만)

- `docs/02-domain-data.md` §6.2 · §6.2.1 — step 1 이 남긴 구현 표기 옆에 주인 확인 조회 메서드 이름(위 넷)과 "비면 서비스가 `404`(`6`)" 를 적는다. 표 · bullet 의 규칙은 고치지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 메서드가 위 넷뿐이고 모두 `Optional` 을 반환하는 파생 쿼리인가?
   - 메서드마다 내 것 · 남의 것 · 없는 것 세 경우가 테스트에 있는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/3-curriculum-model/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 메서드 넷 · 테스트 클래스 · 서버 테스트 수(전후)를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 서비스 · `404` 변환 · `ApiException` · 컨트롤러를 만들지 마라. 이유: `6` 의 서비스가 만든다 — 이 step 은 리포지토리까지다.
- 위 넷 밖의 조회 메서드(목록 · 상태 조회 등)를 더하지 마라. 이유: 쓰는 화면 · API 가 생기는 `6` 에서 정한다.
- 엔티티 · V8 마이그레이션을 고치지 마라. 이유: step 1 이 리뷰를 거쳐 확정했다. 고쳐야 하면 `blocked` 로 사유를 적는다.
- 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 이 step 은 04 §9.2 표에 줄을 더하지 않는다.
- 계약 문서(ADR 결정 본문 · AGENTS 금지 목록 · 02 의 표 · bullet 규칙 · 05 · 07)를 고치지 마라 — 「3. 문서」 의 사실 갱신만 한다. 이유: 계약은 설계 커밋에서 정했다.
- `frontend/` 를 고치지 마라. 이유: 이 step 과 무관하다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
