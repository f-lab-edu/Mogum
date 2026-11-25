# Step 4: home-course-todo

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/06-screens.md` §4(홈 — **이어서 배우기 · 할 일** · 비었을 때) · §15.1(「배우는 중인 코스」 가정)
- `docs/05-api.md` §1 · §1.3 · §1.4 · **§3(「홈의 칸마다」 · 「코스 하나」의 `nextLessonId` · 「로드맵 하나」의 `nextCourse` 줄)**
- `docs/02-domain-data.md` §2.3(코스 · 로드맵 상태) · §8.3(`courses.last_lesson_opened_at` · `started_at`)
- `docs/ADR.md` — ADR-075 · ADR-099 · ADR-144
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 7 · 18 · 20 · 25
- step 3 이 만든 것(summary 참고) — 홈 칸 읽기의 경로 · 이름 짓는 방식을 따른다
- `backend/src/main/java/com/mogum/course/` — `service/CourseReadService.java`(`nextLessonId` 계산) · `service/RoadmapReadService.java`(`nextCourse`) · `repository/CourseJobRepository.java` · `JpaCourseJobRepository.java` · `repository/CourseRepository.java` · `domain/Course.java` · `controller/`
- `backend/src/test/java/com/mogum/ArchitectureTest.java` — `course` 는 `job` 에 의존하지 않는다

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

홈의 둘째 · 셋째 칸 — **이어서 배우기**와 **할 일** — 의 읽기를 만든다. 고르는 규칙은 화면이 아니라 서버의 그 칸 읽기 안에 둔다(ADR-144).

**계약 문서는 이 phase 전에 정해 커밋했다(06 §4 · 05 §3 · ADR-099). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

경로 · 칸 이름은 step 3 의 홈 읽기와 같은 방식으로 정한다(재량). 두 칸은 따로 읽는다(ADR-144).

### 이어서 배우기 — 코스 하나와 그 다음 강의

- 대상 코스(ADR-099) — 그 사람의 **학습 중(`LEARNING`)** 코스 가운데 `last_lesson_opened_at` 이 가장 최근인 것. 강의를 연 코스가 하나도 없으면 `started_at` 이 가장 최근인 것. 학습 중 코스가 없으면 비어 있다.
- 응답 — 코스 ID · 코스 제목 · 다음 강의(ID · 제목). 진도는 싣지 마라(06 §4).
- **다음 강의는 「코스 하나」의 `nextLessonId` 와 같은 규칙**(학습 중에서 완료하지 않은 가장 앞 강의)이다 — 계산을 한 곳에 두고 둘이 함께 쓴다. 따로 다시 쓰지 마라.
- 비었을 때는 칸을 생략하거나 빈 값으로 둔다(재량 — 화면이 칸을 숨긴다 · 06 §4).
- 동점(같은 시각)은 `id` 큰 쪽으로 정해 결과가 흔들리지 않게 한다.

### 할 일 — 누르면 그 화면으로 가는 항목 목록

항목은 셋이다(06 §4 · ADR-099). 항목마다 종류 · 대상 ID · 제목을 싣는다.

1. **검토를 기다리는 강의 목록** — 상태가 `REVIEWING` 인 코스(단일 코스 · 로드맵의 코스 모두). → 코스 화면.
2. **검토를 기다리는 로드맵 계획** — 상태가 `REVIEWING` 인 로드맵. → 로드맵 화면.
3. **「다음 코스 만들기」를 누를 수 있게 된 로드맵** — `IN_PROGRESS` 로드맵 가운데 「로드맵 하나」의 `nextCourse` 가 있고 `enabled: true` 인 것. **`RoadmapReadService` 의 `nextCourse` 판정을 그대로 함께 쓴다** — 홈과 로드맵 화면이 같은 규칙으로 판단해야 한다. 판정을 옮겨야 하면 한 곳으로 옮기고 둘이 부르게 한다. → 로드맵 화면.

- 순서 — 최근 것이 먼저(`updated_at` 또는 `created_at` 내림차순, `id` 로 동점 정리 — 재량, summary 에 적는다). 목록은 나눠 받지 않는다(05 §5 — 정원 10명).
- 만드는 중 · 실패한 작업은 이 칸이 아니다(step 5 의 칸). 코스의 다시 짜기 작업이 걸려 있는 검토 중 코스도 1번에 그대로 넣는다 — 두 칸에 함께 보이는 것은 감수한다(ADR-102).
- `course` 에서 작업 상태를 알아야 하면 기존 `CourseJobRepository`(네이티브 SQL) 방식을 쓴다 — `job` 클래스를 참조하지 마라(`ArchitectureTest`).

### 공통

- 남의 것은 보이지 않는다(그 사람의 것만 읽는다 — 금지 18).
- 입력(주제 · 수준 · 목적 · 메모) · 사용자 ID · 시스템 정보를 싣지 마라(05 §3 · 금지 20).
- 읽기는 아무것도 바꾸지 않는다 — 특히 `last_lesson_opened_at` 을 적지 마라(그것은 강의 읽기만 적는다 — 05 §1).

### 시험

- 이어서 배우기: 학습 중 코스 둘 중 강의를 더 최근에 연 쪽 · 강의를 연 코스가 없으면 최근에 시작한 쪽 · 강의를 연 코스가 있으면 방금 시작한 코스가 아니라 강의를 연 코스(ADR-099 감수) · 검토 중 · 다 마침 · 짜기 전 코스는 대상이 아니다 · 학습 중 코스가 없으면 비었다 · 다음 강의가 「코스 하나」의 `nextLessonId` 와 같다.
- 할 일: 검토 중 단일 코스 · 검토 중 로드맵 코스 · 검토 중 로드맵 · `nextCourse.enabled` 인 진행 중 로드맵이 들어간다 · `enabled: false`(앞 코스가 아직)인 로드맵 · 다음 코스의 강의 목록 작업이 이미 걸린 로드맵 · 다 마친 로드맵 · 학습 중 코스는 빠진다 · 남의 것은 빠진다 · 순서.
- 컨트롤러(`@WebMvcTest`) — 응답 모양.
- 읽기가 상태 · 시각을 바꾸지 않는다.

### 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §3 「홈의 칸마다」 구현 줄에 두 읽기(경로 · 칸 이름 · 모양 · 순서)를 더한다.
- `docs/04-jobs-and-cost.md` §2.2 에 `nextLessonId` · `nextCourse` 판정을 함께 쓰는 곳을 한 줄 적는다(옮겼다면 어디로).

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.course.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 이어서 배우기의 고르는 규칙이 ADR-099 그대로인가? 다음 강의 · 다음 코스 판정이 기존 읽기와 한 곳을 함께 쓰는가?
   - `course` 가 `job` 을 참조하지 않는가? 진도 · 입력 · 시스템 정보가 응답에 없는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/11-home-credits/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 두 읽기의 경로 · 칸 이름 · 모양 · 항목 종류 값 · 순서(step 6 화면이 쓴다) · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 할 일에 「다음 코스 만들기」 버튼 동작(작업 받기)을 넣지 마라. 이유: ADR-099 — 그 버튼은 로드맵 화면에만 둔다. 홈은 로드맵 화면으로 보낸다.
- 만드는 중 · 실패 읽기를 만들지 마라. 이유: step 5 의 범위다.
- 화면(`frontend/`)을 고치지 마라. 이유: step 6 의 범위다.
- 이어서 배우기 · 할 일을 코스 탭 · 로드맵 탭 응답에 더하지 마라. 이유: 06 §15.1 — 정하지 않았다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 접근 줄이 요청을 남긴다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
