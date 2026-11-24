# Step 6: course-report-api

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/05-api.md` §1 · §1.3 · §1.4 · §3(「코스 하나」와 그 구현 줄 — **숙련도 고리 TASK 의 `masteryReport` 줄**)
- `docs/03-generation.md` §5 · `docs/06-screens.md` §7(숙련도 반영 한 줄 · 판정 이름 · 수치는 보이지 않는다)
- `docs/ADR.md` — ADR-089 · ADR-090 · ADR-121
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 18 · 20 · 25
- step 5 가 만든 것(summary 참고) — 반영 내역 record · `Course` 의 getter
- `backend/src/main/java/com/mogum/course/dto/CourseResponse.java` · `course/service/CourseReadService.java` · `course/service/CourseCommandService.java`(시작 · 제목 고치기 응답) · `course/controller/CourseController.java`
- `backend/src/test/java/com/mogum/course/controller/CourseControllerTest.java` · `CourseControllerIntegrationTest.java` · `course/service/CourseReadServiceTest.java` · `CourseCommandServiceTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

코스 화면(검토 중)이 숙련도 반영 한 줄을 그리도록 「코스 하나」 응답에 두 수를 싣는다(ADR-090 · 05 §3).

**계약 문서는 이 phase 전에 정해 커밋했다(05 §3 의 `masteryReport` 줄). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

- `CourseResponse` 에 `masteryReport: {shortened, revisited}`(두 정수)를 더한다. 수는 저장된 반영 내역의 두 이름 목록의 길이를 서버가 확인하지 않고 그대로 센 값이다(ADR-090 · ADR-121).
- **상태가 `REVIEWING` 이고 내역이 저장돼 있을 때만** 싣는다 — 두 수가 0이어도 싣는다. 그 밖(학습 중 · 다 마침 · 짜기 전 · 내역 없음)은 칸을 생략한다(비어 있는 선택 칸 생략 — 05 §3).
- 개념 이름 · 판정 이름 · 숙련도 수치는 싣지 마라(05 §3 · 06 §7 · 금지 20).
- `CourseResponse` 를 만드는 곳(코스 하나 읽기 · 「이대로 시작」 · 제목 고치기)이 모두 같은 규칙이 되게 한 곳에서 만든다 — 지금 `todayReviewCount` 를 채우는 방식을 따른다.
- 코스 목록 · 로드맵 응답에는 싣지 마라(05 §3).

### 시험

- 컨트롤러(`@WebMvcTest`) — 응답 JSON 에 `masteryReport.shortened` · `revisited` 가 정수로 있다 · 생략될 때 키가 없다.
- 서비스 또는 통합 — 검토 중 + 내역 있음 → 두 수 · 검토 중 + 빈 목록 둘 → `{0, 0}` · 검토 중 + 내역 없음(이 기능 전의 코스) → 생략 · 학습 중(내역 있어도) → 생략 · 제목 고치기 응답(검토 중) 도 같은 칸 · 「이대로 시작」 응답(학습 중)은 생략 · 남의 코스는 지금처럼 `NOT_FOUND`.
- 응답 어디에도 개념 이름 목록이 없다.

### 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §3 「코스 하나」 구현 줄에 `masteryReport` 를 채우는 클래스 · 메서드를 더한다(규칙 문장은 고치지 마라).

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.course.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 검토 중 + 내역 있음일 때만 싣고, 0 / 0 도 싣는가? 세 응답이 같은 규칙인가?
   - 개념 이름 · 판정 · 수치가 응답에 없는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/10-mastery-loop/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 응답 칸 이름 · 모양 · 싣는 조건(step 7 화면이 쓴다) · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 화면(`frontend/`)을 고치지 마라. 이유: step 7 의 범위다.
- 0 / 0 일 때 칸을 생략하지 마라. 이유: 05 §3 — 0 / 0 을 보일지는 화면이 정한다.
- 숙련도를 응답에서 다시 계산하지 마라. 이유: 화면에 보이는 것은 LLM 이 적은 내역의 수다(ADR-090 · ADR-121).
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 접근 줄이 요청을 남긴다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
