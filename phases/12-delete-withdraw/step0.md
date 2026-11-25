# Step 0: course-delete

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §2 · §4 · §7(지우기 범위 표 — 「코스를 지울 때」 칸) · §8.1(`ON DELETE SET NULL` 연결 넷) · §8.2(크레딧 복구 조건) · §8.3 · §8.4 · §8.5 · §8.6(「코스」 순서 · 「지우기 TASK 에서 정한 것」 ① ② ③) · §8.7
- `docs/04-jobs-and-cost.md` §2.2(패키지 경계 · `course` 가 `job` 을 참조하지 않는 방법) · §4.1 · §4.2 · §7
- `docs/05-api.md` §1 · §1.2 · §1.3 · §4(보내는 동작 표 · 「카드 지우기」 구현 줄 — 같은 짜임의 `204`)
- `docs/06-screens.md` §7(「지울 때」)
- `docs/07-testing.md` §1(「지운 뒤의 늦은 결과」 · 「지우기 범위」 · 「크레딧」 · 「남의 것」)
- `docs/ADR.md` — ADR-026 · ADR-027 · ADR-100 · ADR-112 · ADR-126 · ADR-134 · ADR-139 · ADR-140 · ADR-156 · ADR-165 · ADR-166
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 7 · 9 · 18 · 19 · 22 · 25 · 26 · 27 · 28
- `backend/src/main/java/com/mogum/course/` 전체 — 특히 `controller/CourseController.java` · `service/CourseCommandService.java`(`CreditLedger.lock` 뒤 상태 확인) · `repository/JpaCourseJobRepository.java`(`course` 가 네이티브 SQL 로 `generation_jobs` 를 읽는 방식)
- `backend/src/main/java/com/mogum/account/service/CreditLedger.java`(`restoreIfOwed` — 복구 조건이 이미 구현돼 있다)
- `backend/src/main/java/com/mogum/job/service/JobIntakeService.java`(`discard` — 복구 → 작업 지우기 순서)
- `backend/src/main/java/com/mogum/worker/JobRunner.java`(결과 저장 · 실패 기록의 소유권 확인 UPDATE → `restoreIfOwed`) · `backend/src/main/java/com/mogum/card/service/LessonCardWriter.java`(사용자 행 `FOR UPDATE`)
- `backend/src/main/java/com/mogum/card/` 의 `Card` · `CardStatus` · `CardRepository` · `CardCommandService`(카드 지우기)
- `backend/src/main/java/com/mogum/generation/call/LlmCallWriter.java`(지운 작업 · 사람을 비우고 쓰는 방법)
- `backend/src/test/java/com/mogum/worker/JobRunnerIntegrationTest.java` · `backend/src/test/java/com/mogum/card/service/LessonCardWriterConcurrencyIntegrationTest.java`(지운 작업 · 동시성 시험을 쓴 방식)
- `backend/src/test/java/com/mogum/ArchitectureTest.java`(`CARD_DOES_NOT_DEPEND_ON_COURSE` · `COURSE_DOES_NOT_DEPEND_ON_JOB`)
- `backend/src/main/java/com/mogum/common/api/ErrorCode.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

사용자가 단일 코스를 지운다(ADR-026 · ADR-100). 지우면 코스 · 강의 · 강의 자료 · 숙련도 반영 내역 · 입력 · 그 코스의 작업이 사라지고, 카드는 상태만 「코스 지워짐」(`COURSE_DELETED`)으로 바꿔 남긴다. 개념 사전 · 크레딧 · 크레딧 기록 · 호출 기록은 남는다(02 §7). 만드는 중인 작업도 함께 지우고, 성공하지 않은 작업에 쓴 크레딧은 돌려준다(ADR-140).

**계약 문서는 이 phase 전에 정해 커밋했다(02 §7 · §8.6 · 05 §4 표 · 06 §7 · ADR-140 · ADR-190). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. API — `course` 패키지

- `DELETE /api/courses/{id}` — 본문 없음. 성공하면 **`204` · 본문 없음**(지금의 카드 지우기와 같은 방식).
- 단일 코스(`roadmap_id` 가 비었다)는 **상태와 상관없이**(검토 중 · 학습 중 · 다 마침) 지운다.
- **로드맵의 코스**(`roadmap_id` 가 있다)는 지우지 않는다 — 새 오류 code `COURSE_DELETE_NOT_ALLOWED`(409), 문구 「로드맵의 코스는 로드맵을 지워야 해요」. 아무것도 바꾸지 않는다. 이유: ADR-027 — 로드맵은 로드맵 단위로만 지운다. 화면에는 이 버튼이 없어 서버만의 막기다.
- 없는 코스 · 남의 코스는 `NOT_FOUND`(404)(금지 18). 같은 코스를 두 번 지우면 두 번째는 `NOT_FOUND`.
- 성공은 `ApiResponse` 경로 · 오류는 `ApiException(ErrorCode)`(금지 25). 새 enum 상수에는 한국어 Javadoc 한 줄.

### 2. 지우기 — 한 트랜잭션, 02 §8.6 「코스」 순서

**잠그는 순서가 핵심이다(02 §8.6 ③).** 워커는 결과 저장 · 실패 기록에서 **작업 행(소유권 확인 UPDATE) → 사용자 행(`FOR UPDATE`)** 순서로 잡는다. 지우기도 같은 순서로 잡는다:

1. 그 코스와 그 코스의 강의를 대상으로 한 `generation_jobs` 행을 모두(상태와 상관없이 — 성공한 `NEW_COURSE` · `MATERIAL` 포함) `FOR UPDATE` 로 잡는다. 이 단계에서 주인 · 대상 확인에 필요한 코스 행도 읽는다.
2. 사용자 행을 잡고(`CreditLedger`), 성공하지 않은 작업(`QUEUED` · `RUNNING` · `FAILED`)마다 `CreditLedger.restoreIfOwed` 로 복구한다 — 복구 조건(그 작업의 마지막 기록이 이번 주기의 씀일 때만)은 이미 그 메서드에 있다. 복구는 작업을 지우기 **전에** 한다(02 §8.6).
3. 작업들을 지운다 → 그 코스 강의의 `ACTIVE` 카드를 `COURSE_DELETED` 로 바꾼다 → `materials` → `lessons` → `courses`. 카드의 `lesson_id` 는 강의를 지울 때 `ON DELETE SET NULL` 로 저절로 빈다. 크레딧 기록 · 호출 기록의 `job_id` 도 저절로 빈다(02 §8.1).

- **1 → 2 순서를 바꾸지 마라.** 이유: 사용자 행을 먼저 잡으면, 작업 행을 쥔 채 사용자 행을 기다리는 워커와 서로를 기다린다.
- 1 이 기다린 뒤(워커가 먼저 결과를 저장하고 커밋) 3 의 카드 상태 바꾸기는 **그 뒤의 문장**에서 하므로, 방금 저장된 카드도 `COURSE_DELETED` 가 된다 — 카드를 1 보다 먼저 읽어 두지 마라.
- `course` 는 `job` 패키지를 참조하지 못한다(ArchUnit). 작업 행을 잡고 지우는 것은 `course.repository` 의 네이티브 SQL 로 한다(`JpaCourseJobRepository` 와 같은 방식 — 그 클래스에 더하거나 같은 짜임의 조각을 만든다). `card` 는 `course` 를 참조하지 못한다 — 카드 상태 바꾸기는 `card` 쪽 메서드(강의 ID 목록을 받는다 등)로 두고 `course` 가 부른다.
- 카드 상태를 벌크 UPDATE 나 네이티브 SQL 로 바꾸면 `updated_at` 을 SQL 에 직접 쓰고(금지 28) 테스트로 확인한다. `CHECK`(쓰는 중이면 강의가 있다) 때문에 카드 상태를 바꾸기 전에 강의를 지우면 실패한다.
- 「코스 하나의 내용(작업 · 카드 · 자료 · 강의 · 코스)을 지우는 일」은 다음 step(로드맵 지우기)이 코스마다 다시 쓴다. 로드맵 지우기 자체는 만들지 마라 — 다음 step 이 쓰기 좋게 `course.service` 안에 이 일을 한 곳에 둔다.
- 작업 받기와 지우기가 같은 순간에 오는 경합(지우기가 잡은 뒤 새로 들어온 작업)은 막지 않는다(02 §8.6 · ADR-102). 서버 메모리 잠금을 쓰지 마라(금지 22).
- 개념 사전(`concepts`) · `card_concepts` · `reviews` · `credit_entries` · `llm_calls` 행은 지우지 마라.

### 3. 시험

- 컨트롤러(`@WebMvcTest`) — 경로 · `204` · `NOT_FOUND` · `COURSE_DELETE_NOT_ALLOWED` 의 code 와 문구.
- 통합(`@IntegrationTest` — 실제 DB · `TestClock`):
    - **지우기 범위**(07 §1) — 학습 중인 단일 코스(강의 · 강의 자료 · 학습 완료 뒤 카드 · 복습 기록 · 개념 · 숙련도 반영 내역 · 성공한 작업 · 호출 기록 · 크레딧 기록이 있다)를 지우면: 코스 · 강의 · 강의 자료 · 작업이 없다 · 카드는 남고 `COURSE_DELETED` · `lesson_id` 빈 값 · `updated_at` 이 지운 시각 · 질문 · 답 · `card_concepts` · 복습 기록 · 개념이 그대로 · 호출 기록 · 크레딧 기록은 남고 `job_id` 만 빈 값 · 다른 코스와 그 카드는 그대로. 지운 코스의 카드는 오늘 복습할 카드 · 홈의 수에 나오지 않는다.
    - 검토 중 · 다 마침인 단일 코스도 지운다. 로드맵의 코스는 `COURSE_DELETE_NOT_ALLOWED` 이고 아무것도 바뀌지 않는다. 남의 코스 · 없는 코스 · 두 번째 지우기는 `NOT_FOUND`.
    - **크레딧**(07 §1) — 기다림 · 만드는 중 · 실패한 작업(다시 짜기 · 강의 자료)의 크레딧은 돌려받고 복구 기록이 한 줄씩 · 성공한 작업은 돌려주지 않는다 · 실패를 버리고 이미 복구한 작업은 다시 복구하지 않는다(그 작업은 이미 없다) · 마지막 채움 전에 쓴 몫은 돌려주지 않는다 · 남은 양과 기록의 합이 맞는다.
    - **지운 뒤의 늦은 결과**(07 §1) — 강의 자료 작업이 만드는 중일 때 코스를 지운 뒤, 워커가 그 시도의 결과를 저장하려 하면 아무것도 쓰지 않고(강의 자료 · 카드 · 개념이 생기지 않는다) 그 시도를 멈춘다. 그 시도의 호출 기록은 `job_id` 빈 값으로 남는다.
    - 지운 뒤 그 작업의 작업 읽기는 `NOT_FOUND` 다(02 §8.6 ②).
    - **동시성** — 워커의 실제 결과 저장(강의 자료 — 작업 행 UPDATE → 사용자 행 잠금)이 작업 행을 쥔 동안 지우기가 들어와도, 둘 다 교착 오류 없이 끝나고 남은 상태가 일관된다: 워커가 먼저면 저장된 카드까지 `COURSE_DELETED` 이고 강의 자료 · 강의는 없다 · 지우기가 먼저면 워커는 아무것도 쓰지 않는다. 두 순서를 시험한다(래치 등으로 순서를 고정한다 — 기존 `LessonCardWriterConcurrencyIntegrationTest` 의 방식을 본다).

### 4. 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §4 — 「코스 지우기」 구현 줄(경로 · `204` · 거절 · 잠그는 순서를 짚는 한 줄). §1.2 — `COURSE_DELETE_NOT_ALLOWED`(409)와 문구. §5 첫 줄의 「…에서 정했다」 목록에 이 step 을 더한다.
- `docs/02-domain-data.md` §8.6 — 「지우기 TASK 에서 정한 것」 아래에 구현 사실(어느 클래스가 작업 행을 잡는지) 한 줄. 규칙 문장은 고치지 않는다.
- `docs/07-testing.md` §1 — 「지운 뒤의 늦은 결과」 · 「지우기 범위」 줄의 근거 칸에 시험 클래스 이름을 더한다(「크레딧 채우기」 줄처럼).

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.course.*' --tests 'com.mogum.card.*' --tests 'com.mogum.worker.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 작업 행 → 사용자 행 순서로 잡는가? 복구가 작업을 지우기 전인가?
   - 카드 상태 바꾸기가 작업 행을 잡은 뒤의 문장인가? 벌크 갱신이 `updated_at` 을 쓰는가?
   - 개념 사전 · 복습 기록 · 크레딧 기록 · 호출 기록을 지우지 않는가?
   - `course` 가 `job` 을, `card` 가 `course` 를 참조하지 않는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/12-delete-withdraw/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 경로 · 응답 · 새 오류 code 와 문구 · 다음 step 이 코스마다 부를 메서드(클래스 · 시그니처) · 작업 행을 잡는 리포지토리 메서드 · 카드 상태를 바꾸는 `card` 메서드 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 로드맵 지우기 · 탈퇴 · 화면을 만들지 마라. 이유: 다음 step(1 · 2 · 3 · 4)에서 만든다.
- 만드는 중인 작업이 있다고 지우기를 막지 마라. 이유: ADR-140 — 함께 지운다.
- FK 를 `ON DELETE CASCADE` 로 바꾸거나 엔티티에 cascade · `@OneToMany` 를 더하지 마라. 이유: 금지 27 · ADR-156 — 지우는 순서가 코드에 드러나야 한다.
- 작업을 찾는 색인(`course_id` · `roadmap_id` · `lesson_id`)을 더하지 마라. 이유: 02 §8.6 ① — 정원 10명이라 행이 적다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 접근 줄에 경로 · 결과 · `userId` 가 이미 남는다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
