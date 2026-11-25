# Step 1: roadmap-delete

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §2.2 · §2.3 · §7(「로드맵을 지울 때」 칸) · §8.3(`roadmaps` · `courses`) · §8.5 · §8.6(「로드맵」 순서 · 「지우기 TASK 에서 정한 것」) · §8.7
- `docs/05-api.md` §1.2 · §1.3 · §4(보내는 동작 표의 「로드맵 지우기」 · step 0 이 적은 「코스 지우기」 구현 줄)
- `docs/06-screens.md` §8(「지울 때」 — 검토 중 · 진행 중 · 다 마침 모두)
- `docs/07-testing.md` §1(「지운 뒤의 늦은 결과」 · 「지우기 범위」 · 「크레딧」)
- `docs/ADR.md` — ADR-027 · ADR-132 · ADR-140 · ADR-165 · ADR-166 · ADR-176 · ADR-190
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 18 · 19 · 22 · 25 · 26 · 27 · 28
- step 0 이 만든 것(summary 참고) — 코스 지우기 API · 코스 하나의 내용을 지우는 `course.service` 메서드 · 작업 행을 잡는 리포지토리 메서드 · 카드 상태를 바꾸는 `card` 메서드와 그 시험
- `backend/src/main/java/com/mogum/course/controller/RoadmapController.java` · `backend/src/main/java/com/mogum/course/service/RoadmapReadService.java` · `backend/src/main/java/com/mogum/course/domain/Roadmap.java`
- `backend/src/main/java/com/mogum/worker/JobRunner.java`(로드맵 계획 · 로드맵의 코스 · 다시 짜기의 결과 저장)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

사용자가 로드맵을 지운다(ADR-027). **검토 중 · 진행 중 · 다 마침 모두** 지운다(ADR-190). 로드맵과 그 모든 코스 — 짜기 전(`NOT_PLANNED`) 코스까지 — 를 step 0 의 코스 규칙대로 지운다(02 §7).

**계약 문서는 이 phase 전에 정해 커밋했다. 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. API — `course` 패키지

- `DELETE /api/roadmaps/{id}` — 본문 없음. 성공하면 **`204` · 본문 없음**. 상태와 상관없이 지운다.
- 없는 로드맵 · 남의 로드맵 · 두 번째 지우기는 `NOT_FOUND`(404)(금지 18).

### 2. 지우기 — 한 트랜잭션, 02 §8.6 「로드맵」 순서

- step 0 과 같은 잠그는 순서다(02 §8.6 ③): **먼저 이 로드맵을 대상으로 한 작업(`NEW_ROADMAP` · `REPLAN_ROADMAP`)과 그 모든 코스 · 강의를 대상으로 한 작업을 함께 `FOR UPDATE` 로 잡고, 그다음 사용자 행을 잡아** 성공하지 않은 작업의 크레딧을 복구한 뒤 지운다. 사용자 행을 먼저 잡지 마라. 이유: step 0 과 같다 — 작업 행을 쥔 채 사용자 행을 기다리는 워커와 서로를 기다린다.
- 코스마다의 일은 step 0 의 「코스 하나의 내용을 지우는」 한 곳을 다시 쓴다 — 같은 규칙을 두 번 쓰지 마라. 잠금 · 복구를 로드맵 단위로 먼저 해야 하면 step 0 의 메서드를 그 순서에 맞게 나눠도 된다(나누면 step 0 의 시험이 그대로 통과해야 한다).
- 코스를 모두 지운 뒤 `roadmaps` 를 지운다.

### 3. 시험

- 컨트롤러(`@WebMvcTest`) — 경로 · `204` · `NOT_FOUND`.
- 통합(`@IntegrationTest` — 실제 DB · `TestClock`):
    - **지우기 범위**(07 §1) — 진행 중인 로드맵(학습 중 코스 하나 + 그 강의 · 강의 자료 · 카드 · 복습 기록 · 개념, 검토 중 코스 하나, 짜기 전 코스 하나, 성공한 `NEW_ROADMAP` · `PLAN_ROADMAP_COURSE` 작업, 호출 기록 · 크레딧 기록)을 지우면: 로드맵 · 모든 코스 · 강의 · 강의 자료 · 작업이 없다 · 카드는 `COURSE_DELETED` 로 남고 복습 기록 · 개념 연결 · 개념이 그대로 · 호출 기록 · 크레딧 기록은 `job_id` 만 빈 값 · 다른 로드맵 · 단일 코스는 그대로.
    - 검토 중 로드맵(코스가 모두 짜기 전)과 다 마친 로드맵도 지운다.
    - **크레딧** — 기다림 · 만드는 중 · 실패한 작업(로드맵 다시 짜기 · 로드맵의 코스 강의 목록 · 강의 자료)은 돌려받고, 성공한 작업은 돌려주지 않는다. 남은 양과 기록의 합이 맞는다.
    - **지운 뒤의 늦은 결과**(07 §1) — 로드맵의 코스 강의 목록 작업(`PLAN_ROADMAP_COURSE`)이 만드는 중일 때 로드맵을 지운 뒤 워커가 결과를 저장하려 하면, 강의가 생기거나 코스 · 로드맵이 되살아나지 않는다.
    - 지운 뒤 그 작업들의 작업 읽기 · 로드맵 읽기 · 그 코스 읽기는 `NOT_FOUND` 다.
    - 남의 로드맵 · 없는 로드맵 · 두 번째 지우기는 `NOT_FOUND`.

### 4. 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §4 — 「로드맵 지우기」 구현 줄(경로 · `204` · 상태와 상관없이). §5 첫 줄의 「…에서 정했다」 목록에 이 step 을 더한다.
- `docs/07-testing.md` §1 — step 0 이 더한 시험 클래스 이름 옆에 이 step 의 시험 클래스를 더한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.course.*' --tests 'com.mogum.card.*' --tests 'com.mogum.worker.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 로드맵과 그 코스들의 작업 행을 먼저 잡고 사용자 행을 나중에 잡는가?
   - 코스마다의 지우기가 step 0 과 같은 한 곳을 쓰는가? 짜기 전 코스까지 지우는가?
   - `course` 가 `job` 을, `card` 가 `course` 를 참조하지 않는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/12-delete-withdraw/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 경로 · 응답 · step 0 의 메서드를 바꿨다면 바뀐 시그니처 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 로드맵의 코스 하나만 지우는 길을 만들지 마라. 이유: ADR-027.
- 로드맵 상태로 지우기를 막지 마라. 이유: ADR-190 — 검토 중 · 진행 중 · 다 마침 모두 지운다.
- 탈퇴 · 화면을 만들지 마라. 이유: 다음 step(2 · 3 · 4)에서 만든다.
- FK 를 `ON DELETE CASCADE` 로 바꾸거나 엔티티에 cascade · `@OneToMany` 를 더하지 마라. 이유: 금지 27 · ADR-156.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 접근 줄에 경로 · 결과 · `userId` 가 이미 남는다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
