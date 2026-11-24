# Step 1: review-read

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §3(오늘 복습할 카드 — 날 단위 · 학습 단계 · 앞당겨 보기 · 코스로 거르기 · 하루의 경계) · §4 · §8.4(`cards` · 색인 · 오늘 복습할 카드 규칙) · §8.7
- `docs/05-api.md` §1 · §1.2 · §1.3 · §1.4 · §3(「오늘 복습할 카드」 · 「코스 하나」와 그 구현 줄 — 「오늘 복습할 카드 수는 해당 TASK 에서 더한다」) · §5
- `docs/06-screens.md` §7(「이 코스 복습하기」) · §11(복습 탭) · §15.1(탭 복귀 — 다시 읽는다)
- `docs/07-testing.md` §1(하루의 경계 · 같은 날 다시 보기 · 남의 것)
- `docs/ADR.md` — ADR-014 · ADR-017 · ADR-155 · ADR-164 · ADR-165 · ADR-170 · ADR-172 · ADR-188
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 17 · 18 · 20 · 21 · 25 · 27
- step 0 이 만든 것(summary 참고) — FSRS 계산 클래스의 `preview` · 새벽 4시 경계의 `SeoulClock`
- 본뜰 코드: `backend/src/main/java/com/mogum/course/controller/LessonController.java` · `course/service/LessonReadService.java` · `course/service/CourseReadService.java` · `course/dto/CourseResponse.java` · `course/repository/JpaCourseJobRepository.java`(다른 도메인의 테이블을 네이티브로 읽는 방법)
- `backend/src/main/java/com/mogum/card/` 전체 · `backend/src/main/java/com/mogum/course/service/LessonCommandService.java`(이미 `course` → `card` 로 참조한다)
- `backend/src/test/java/com/mogum/ArchitectureTest.java` · `backend/src/test/java/com/mogum/support/`(`IntegrationTest` · `TestClock`) · `backend/src/test/java/com/mogum/card/domain/CardFixtures.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

복습 탭(06 §11)이 쓸 **오늘 복습할 카드 읽기**와, 코스 화면(06 §7)의 「이 코스 복습하기」가 쓸 **코스의 오늘 복습할 카드 수**를 만든다. 평가 · 카드 지우기는 step 2 다.

**계약 문서는 이 phase 전에 정해 커밋했다. 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

경로 · 칸 이름은 지금 API 의 짜임에 맞춰 정하고 05 에 적는다(05 §5 — TASK 에서 정한다). 성공은 `ApiResponse`, 오류는 `ApiException(ErrorCode)`(금지 25).

### 1. 고르는 규칙 — `card` 패키지에 한 곳

오늘 복습할 카드를 고르는 규칙을 `card.service` 의 한 곳에 둔다. 읽기(아래 2)와 수(아래 3), 그리고 뒤 TASK 의 홈 「오늘 복습」 수가 모두 이 한 곳을 쓴다 — 버튼의 「3장」과 눌러서 보이는 카드 수가 다르면 안 된다.

- 대상 — 그 사용자의 `ACTIVE` 카드 가운데 `due_at` 이 비어 있지 않은 것(학습 완료 전 · 코스 지워짐은 빠진다 — ADR-014 · ADR-165).
- `REVIEW` 카드 — `due_at` 이 **다음 하루의 시작(새벽 4시 — step 0 의 `SeoulClock`) 전**이면 들어간다. 지난 날의 밀린 카드도 들어간다.
- 학습 단계 카드(`LEARNING` · `RELEARNING`) — `due_at` 이 지금까지면 들어간다.
- **앞당겨 보기**(ADR-164 · Anki 기본값) — 위 둘로 고른 카드가 하나도 없으면, `due_at` 이 지금부터 20분 안인 학습 단계 카드를 넣는다. 20분은 이름 있는 상수 하나로 둔다(설정으로 빼지 않는다).
- 코스로 거르기(ADR-172) — 코스 ID 를 주면 그 코스의 강의에 달린 카드만 고른다. 앞당겨 보기의 「하나도 없으면」도 거른 범위 안에서 본다. 일정은 바꾸지 않는다.
- 순서 — `due_at` 오름차순, 같으면 카드 ID(정하고 05 에 적는다).
- 「지금」 · 「다음 하루의 시작」은 `Clock` 에서 받는다. 서버 · DB 기본 시간대를 쓰지 마라(금지 17).
- **`card` 는 `course` 패키지를 참조하지 않는다** — `course` → `card` 방향의 참조가 이미 있어(`LessonCommandService` → `LessonCardActivator`) 반대 방향은 순환이 된다. 카드 → 강의 → 코스를 거르는 일은 `card` 안에서 테이블을 네이티브 SQL 로 읽는다(`course` 가 `generation_jobs` 를 읽는 `JpaCourseJobRepository` 처럼). 이 방향을 ArchUnit 규칙으로 막는다(`card` 가 `course` 를 참조하지 않는다 — 위반 픽스처로 확인).

### 2. 오늘 복습할 카드 읽기 — API

- 담는 것(05 §3 · 06 §11) — 카드마다 ID · 질문 · 답 · 평가 넷의 다음 간격(ADR-170 — step 0 의 `preview` 를 **이 요청의 「지금」** 으로 부른 값)과 몇 장 남았는지. 간격은 화면이 「1분」 · 「3일」로 바꿔 보일 수 있게 숫자로 싣는다(단위 — 예: 초 — 를 정해 05 에 적는다). 문구로 바꾸는 것은 화면의 일이다(step 3).
- 코스로 거를 때(`courseId` 쿼리) — 06 §11 이 같은 화면에 코스 제목을 보이므로 코스 제목도 싣는다. 남의 코스 · 없는 코스는 `NOT_FOUND`(금지 18). 코스의 카드가 없거나 오늘 볼 것이 없으면 빈 목록이다(오류가 아니다).
- 받은 카드를 다 보면 화면이 다시 읽는다(ADR-164) — 목록을 나눠 받지 않는다(05 §5 — 정원 10명).
- 응답에 카드의 FSRS 칸(안정도 · 어려움 · 단계 · 상태 · 다음 볼 때)과 강의 · 코스 출처, 시스템 정보를 싣지 마라(금지 20 · 06 §11 · design README 「06 과 다른 곳」의 카드 출처). 화면이 쓰지 않는 칸이다.
- **읽기는 아무것도 바꾸지 않는다** — 카드 · 복습 기록 · 시각(05 §1).

### 3. 코스의 오늘 복습할 카드 수 — 코스 하나 읽기

- 05 §3 「코스 하나」(`GET /api/courses/{id}`)의 응답 `CourseResponse` 에 이 코스의 오늘 복습할 카드 수(정수)를 더한다 — 1 의 규칙(거르기 포함) 그대로 센 값이다. 06 §7 의 버튼은 이 수가 0 보다 클 때만 보인다(step 4).
- `CourseResponse` 를 돌려주는 다른 동작(코스 시작 · 제목 고치기 — 05 §4)도 같은 칸을 채운다. 응답 모양이 둘로 갈리지 않게 한다.
- `course` 가 1 의 `card` 서비스를 부른다(이미 있는 방향이다).

### 4. 시험

- 컨트롤러(`@WebMvcTest`) — 경로 · 쿼리 · 응답 모양 · 남의 코스 오류.
- 통합(`@IntegrationTest` — 실제 DB · `TestClock`):
    - **하루의 경계**(07 §1) — `REVIEW` 카드의 `due_at` 이 다음 날 03:59 KST 면 오늘 들어가고 04:00 KST 면 빠진다 · 지금이 새벽 1시면 아직 전날이다 · 지난 날의 밀린 카드는 들어간다.
    - 학습 단계 — 다음 볼 때가 지난 카드는 들어가고 아직이면 빠진다 · **다른 볼 카드가 없으면 20분 안의 카드가 앞당겨 들어온다**, 있으면 들어오지 않는다 · 20분 넘어서는 들어오지 않는다.
    - 학습 완료 전(`due_at` 비어 있음) · `COURSE_DELETED` · 남의 카드는 빠진다.
    - 코스로 거르기 — 그 코스 카드만 · 다른 코스의 카드는 빠진다 · 거른 범위 안에서 앞당겨 보기 · 남의 코스 `NOT_FOUND`.
    - 평가 넷의 간격이 step 0 의 `preview` 와 같다.
    - 순서 · 읽기 뒤 카드 · 복습 기록 · `updated_at` 이 그대로다.
    - 코스 하나 읽기의 수 == 그 코스로 거른 읽기의 카드 수(같은 시각) · 코스 시작 · 제목 고치기 응답에도 칸이 있다.

### 5. 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §3 — 「오늘 복습할 카드」의 경로 · 쿼리 · 칸 · 간격 단위 · 순서를 구현 줄로 더하고, 「코스 하나」 칸 목록에 오늘 복습할 카드 수를 더한다. 「위 네 응답에는 … 오늘 복습할 카드 수를 싣지 않는다. 뒤 두 칸은 각각 해당 TASK에서 더한다」 문장을 지금 사실(숙련도 반영 내역만 남았다)에 맞춘다. §5 의 「나머지는 각 TASK」 문장에 이 TASK 를 더한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.card.*' --tests 'com.mogum.course.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 고르는 규칙이 한 곳이고, 읽기와 코스의 수가 그것을 함께 쓰는가?
   - 새벽 4시 경계 · 앞당겨 보기 · 코스 거르기가 02 §3 그대로인가?
   - `card` 가 `course` 를 참조하지 않는가? ArchUnit 이 막는가?
   - 읽기가 아무것도 바꾸지 않는가? 응답에 FSRS 칸 · 출처 · 시스템 정보가 없는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/9-card-review/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 경로 · 쿼리 · 응답 칸(이름 · 간격 단위 — step 3 화면이 쓴다) · `CourseResponse` 에 더한 칸 이름(step 4 화면이 쓴다) · 고르는 규칙의 클래스 · 메서드 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 평가 보내기 · 카드 지우기를 만들지 마라. 이유: step 2 의 범위다.
- 홈의 「오늘 복습」 읽기 · 스트릭을 만들지 마라. 이유: SCHEDULE §3 상 홈 · 크레딧 TASK(11)에서 만든다 — 이 step 은 그 TASK 가 쓸 규칙 한 곳만 둔다.
- 숙련도 · 숙련도 반영 내역을 만들지 마라. 이유: SCHEDULE §3 상 숙련도 고리 TASK(10)에서 만든다.
- 예정과 상관없이 코스의 카드를 다 보는 읽기를 만들지 마라. 이유: 06 §7 · ADR-172 — 평가가 FSRS 일정을 바꾼다.
- 일일 복습 개수 제한 · 목록 나눠 받기를 두지 마라. 이유: 금지 17 · 05 §5.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
