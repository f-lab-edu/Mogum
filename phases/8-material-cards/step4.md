# Step 4: lesson-api

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §2.3(상태 — 코스 다 마침 · 로드맵 다 마침) · §3(학습과 복습 — 학습 완료 · 카드 숨김) · §8.3(`lessons.completed_at` · `courses.last_lesson_opened_at` · `completed_at`) · §8.4(`cards.due_at`)
- `docs/05-api.md` §1(가져오기는 상태를 바꾸지 않는다 — 강의를 가져올 때 마지막으로 연 때만 예외) · §1.2 · §1.3 · §3(「강의 하나」 · 코스 하나의 구현 줄) · §4(「학습 완료」 · 코스 동작의 구현 줄)
- `docs/06-screens.md` §7 · §9 · §10
- `docs/07-testing.md` §1(상태 전환 · 남의 것 · 가져오기)
- `docs/ADR.md` — ADR-012 · ADR-014 · ADR-019 · ADR-080 · ADR-081 · ADR-099 · ADR-101 · ADR-136 · ADR-142 · ADR-155 · ADR-169 · ADR-176
- `docs/TEST_GUIDE.md` 전체
- `AGENTS.md` — 금지 7 · 18 · 21 · 25 · 27 · 28
- step 3 이 만든 것(summary 참고) — 강의를 열 수 없을 때의 오류 code
- 본뜰 코드: `backend/src/main/java/com/mogum/course/controller/CourseController.java` · `course/service/CourseReadService.java` · `CourseCommandService.java` · `course/repository/CourseJobRepository.java` · `JpaCourseJobRepository.java`(`course` 가 `job` 을 참조하지 않고 작업을 읽는 방법) · `course/dto/`
- 엔티티: `course/domain/Course.java` · `Lesson.java` · `Roadmap.java` · `Material.java` · `card/domain/Card.java` · `card/repository/CardRepository.java`
- `backend/src/test/java/com/mogum/ArchitectureTest.java`(`COURSE_DOES_NOT_DEPEND_ON_JOB` 등)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

강의 화면(06 §9)이 쓸 **강의 하나 읽기**와 **학습 완료**를 만든다. 학습 완료는 되돌리지 않고 강의마다 한 번만 반영한다(ADR-012 · 금지 21). 학습 완료하면 그 강의의 카드에 다음 볼 때를 채워 오늘 복습에 들이고(ADR-014 · ADR-155), 마지막 강의면 코스가 다 마침, 그 코스가 로드맵의 마지막 코스면 로드맵도 다 마침이 된다 — 같은 트랜잭션에서(02 §2.3 · ADR-176).

**계약 문서는 이 phase 전에 정해 커밋했다. 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

경로 · 칸 이름은 지금 코스 API 의 짜임에 맞춰 정하고 05 에 적는다(05 §5 — TASK 에서 정한다). 성공은 `ApiResponse`, 오류는 `ApiException(ErrorCode)`(금지 25).

### 1. 강의 하나 읽기 — `course` 패키지

- 담는 것(05 §3) — 강의(ID · 코스 ID · 순서 · 제목) · 학습 완료 여부 · 강의 자료 본문(있으면) · 강의 자료가 없고 그 강의의 `MATERIAL` 작업이 있으면 그 작업(ID · 상태). 화면이 「본문을 그린다 / 기다리는 화면으로 간다 / 작업을 건다」를 이 응답 하나로 가른다. 코스 제목처럼 화면 위에 필요한 값이 있으면 더해도 된다. 그 밖의 칸을 미리 넣지 마라.
- 주인이 아니거나 없으면 `NOT_FOUND`(금지 18). 강의를 열 수 없으면(코스가 학습 중 · 다 마침이 아님 · 앞 강의를 학습 완료하지 않음) step 3 의 code — 받기와 같은 조건이다. 조건 확인을 한 곳에 두고 받기 처리기와 나눠 쓸 수 있으면 나눠 쓴다(`course` 가 `job` 을 참조하지 않게 — ArchUnit).
- 작업은 `CourseJobRepository` 처럼 `course` 안에서 네이티브로 읽는다. 사용자 응답에 시스템 정보(오류 code 원문 · 횟수 · 시한)를 싣지 마라(금지 20).
- **읽으면 그 코스의 `last_lesson_opened_at` 을 적는다** — 05 §1 의 유일한 예외다(ADR-099). 열 수 있는 강의를 읽을 때만 적는다.
- **읽기는 작업을 걸지 않는다** — 강의 자료가 없어도 만들지 않는다(07 §1 「가져오기」 — 화면이 받기를 부른다).

### 2. 학습 완료 — `course` 패키지

- 받는 조건 — 주인 · 열 수 있는 강의 · **강의 자료가 있다**(카드가 없는 채 완료되는 길을 막는다). 강의 자료가 없으면 거절하는 code 를 정한다(새로 만들면 05 §1.2 에 한 줄).
- 한 트랜잭션에서:
    - `lessons.completed_at` 을 적는다.
    - 그 강의의 `ACTIVE` 카드 가운데 `due_at` 이 빈 것에 `due_at` = 학습 완료한 때를 채운다 — FSRS 의 새 카드는 바로 볼 수 있다. FSRS 상태 · 단계는 건드리지 않는다(금지 21). 카드 쓰기는 `card` 패키지의 메서드로 한다. 벌크 갱신이면 `updated_at` 을 SQL 에 직접 쓴다(금지 28 — 바뀌는지 시험).
    - 그 코스의 모든 강의가 학습 완료면 코스를 다 마침(`COMPLETED`) · `completed_at` 으로(ADR-101 · ADR-169).
    - 그 코스가 로드맵의 마지막 순서 코스(더 뒤 순서의 코스가 없다)면 로드맵을 다 마침으로(ADR-176). 앞 코스를 다 마쳐서는 로드맵이 바뀌지 않는다.
    - 상태를 바꾸는 메서드는 엔티티에 둔다(지금 `start` · `planLessons` 처럼). 상태는 앞으로만 간다.
- **이미 학습 완료한 강의** — 아무것도 바꾸지 않고 같은 응답을 성공으로 돌려준다. 카드의 다음 볼 때 · 복습 기록 · 코스 완료 시각을 다시 쓰지 않는다(ADR-012 · 금지 21).
- **동시에 두 번** — 두 요청이 겹쳐도 한 번만 반영된다(완료 시각 · 카드의 다음 볼 때가 한 번 적힌다). 막는 방법은 정한다(예: 강의 · 코스 행 잠금 · 조건부 갱신). 서버 메모리의 잠금을 쓰지 마라(금지 22).
- 응답 — 화면이 「코스 화면으로 / 축하 화면으로」를 가를 수 있게 코스 ID 와 이 완료로 코스가 다 마침이 됐는지(05 §4 · ADR-081)를 싣는다. 이미 완료한 강의를 다시 보내면 「이번에 다 마침이 됐는지」는 거짓이다.

### 3. 시험

- 컨트롤러(`@WebMvcTest`) — 경로 · 응답 모양 · 오류.
- 통합(`@IntegrationTest` — 실제 DB):
    - 읽기 — 본문 있음 / 본문 없음 + 작업 있음(상태별) / 둘 다 없음 · 마지막으로 연 때가 적힌다 · 작업 · 크레딧 · 강의 자료가 생기지 않는다 · 열 수 없는 강의 · 남의 강의 `NOT_FOUND`.
    - 학습 완료 — 카드 `due_at` 이 채워지고 다른 강의의 카드는 그대로 · `updated_at` 이 바뀐다 · 중간 강의는 코스가 학습 중 그대로 · 마지막 강의에서 코스 다 마침 + `completed_at` · 로드맵의 마지막 코스에서 로드맵 다 마침 · 앞 코스에서는 로드맵 그대로(07 §1 상태 전환) · 두 번째 요청은 아무것도 바꾸지 않음 · **동시에 두 번** · 강의 자료 없음 · 열 수 없는 강의 · 남의 강의.

### 4. 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §3(강의 하나 — 경로 · 칸) · §4(학습 완료 — 경로 · 응답 · 거절 · 다시 보냄) · §1.2(새 code 가 있으면) · §5 의 「나머지는 각 TASK」 문장에 이 TASK 를 더한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.course.*' --tests 'com.mogum.card.*' --tests 'com.mogum.job.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 읽기가 마지막으로 연 때 말고는 아무것도 바꾸지 않는가? 작업을 걸지 않는가?
   - 학습 완료가 한 트랜잭션에서 강의 · 카드 · 코스 · 로드맵을 함께 바꾸는가? 다시 보내거나 동시에 보내도 한 번만인가?
   - 카드의 FSRS 상태를 건드리지 않는가? 벌크 갱신이 `updated_at` 을 쓰는가?
   - `course` 가 `job` · `generation` 을 참조하지 않는가? 남의 것은 `NOT_FOUND` 인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/8-material-cards/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 두 경로와 응답 칸(step 6 화면이 쓴다) · 오류 code · 동시 완료를 막은 방법 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 학습 완료 되돌리기 · 강의 자료 다시 만들기 · 이 강의의 카드 보기 API 를 만들지 마라. 이유: 06 §9 의 「두지 않는 것」(ADR-012 · ADR-025 · ADR-014).
- 복습(오늘 복습할 카드 · 평가 · FSRS 계산) · 카드 지우기 · 코스의 오늘 복습할 카드 수를 만들지 마라. 이유: SCHEDULE §3 상 복습 TASK(9)에서 만든다.
- 스트릭 · 홈 읽기를 만들지 마라. 이유: SCHEDULE §3 상 홈 · 크레딧 TASK(11)에서 만든다.
- 강의 화면 · 축하 화면을 만들지 마라. 이유: step 6 의 범위다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
