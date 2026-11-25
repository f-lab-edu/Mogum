# Step 5: mastery-report

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/03-generation.md` §2 · §5(숙련도 반영 내역 — 누가 적나 · 다시 짜면 바꾼다 · 로드맵 계획은 적지 않는다 · **모양이 틀려도 강의 목록은 버리지 않는다**)
- `docs/02-domain-data.md` §2.1 · §2.2 · §7(지우기 — 반영 내역은 코스와 함께) · §8.1 · §8.3(`courses.mastery_report` — `{"shortened": [이름], "revisited": [이름]}`)
- `docs/ADR.md` — ADR-090 · ADR-121 · ADR-130 · ADR-132 · ADR-183
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 4 · 6 · 9 · 24 · 27 · 28
- step 3 · 4 가 만든 것(summary 참고) — 관련 개념 · `course.txt` 의 규칙
- `backend/src/main/java/com/mogum/course/domain/Course.java`(`planConcepts` 의 jsonb 매핑 · 팩토리 `single` · `inRoadmap` · `planLessons`) · `course/domain/PlannedConcept.java`
- `backend/src/main/resources/db/migration/` 의 `courses` 를 만든 마이그레이션(`mastery_report` 칸 — 이미 있다)
- `backend/src/main/java/com/mogum/generation/NewCourseGenerator.java` · `PlanRoadmapCourseGenerator.java` · `ReplanCourseGenerator.java` · `plan/CoursePlan.java` · `plan/CoursePlanFormat.java` · `plan/PlanJson.java`
- `backend/src/main/resources/prompts/planning/new-course-output.txt` · `lessons-output.txt` · `course.txt`
- `backend/src/test/java/com/mogum/course/domain/` 의 `Course` 시험 · 픽스처 · `generation/plan/CoursePlanFormatTest.java` · 세 생성기 통합 시험

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

강의 목록을 짤 때 LLM 이 숙련도를 어떻게 반영했는지(짧게 다룬 개념 · 다시 짚은 개념의 이름)를 결과에 함께 적게 하고, 서버는 확인하지 않고 `courses.mastery_report` 에 남긴다(ADR-121 · 03 §5). 칸은 이미 있고 매핑만 없다.

**계약 문서는 이 phase 전에 정해 커밋했다(03 §5 · 02 §8.3). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 출력 형식 · 해석

- `new-course-output.txt` · `lessons-output.txt` 의 출력 형식에 반영 내역을 더한다 — `"masteryReport": {"shortened": ["개념 이름"], "revisited": ["개념 이름"]}`. `course.txt` 에 뜻을 적는다 — `shortened` 는 관련 개념 가운데 짧게 다룬(다른 개념과 묶은) 개념, `revisited` 는 다시 짚는 강의에서 다룬 흔들리는 관련 개념. 해당이 없으면 빈 목록. 관련 개념이 없으면 둘 다 빈 목록.
- 해석(`CoursePlanFormat`)은 반영 내역을 `CoursePlan` 에 담는다. **반영 내역이 없거나 모양이 틀리면(객체가 아님 · 목록이 아님 · 글이 아닌 항목 등) 강의 목록은 그대로 받고 반영 내역만 빈 목록 둘로 둔다**(03 §5). 반영 내역 때문에 `Optional.empty()`(형식 불합격 → 다시 시도)를 돌려주지 마라. 강의 목록 자체의 검사는 그대로다.
- 서버는 이름이 목록 · 관련 개념에 실제로 있는지 맞춰 보지 않는다(ADR-121).

### 2. 저장 — `Course`

- `course.domain` 에 반영 내역 값(record — 이름 두 목록)을 두고 `Course` 에 `mastery_report` 를 jsonb 로 매핑한다(`planConcepts` 와 같은 방식). 이 칸을 채우기 전에 만든 코스는 비어 있다(02 §8.3) — 읽을 때 비어 있음을 그대로 둔다.
- 값을 넣는 길은 생성 흐름마다 정한다(금지 24 — 팩토리 · 이름 있는 메서드, 바깥 값은 인자로):
    - 새 코스 — 단일 코스를 만드는 팩토리가 받는다.
    - 로드맵의 코스 — 짜기 전 → 검토 중으로 바꾸는 메서드(`planLessons`)가 받는다.
    - 다시 짜기 — 강의 목록을 바꾸는 저장에서 새 내역으로 바꾼다(03 §5).
- 저장은 지금처럼 워커가 소유권을 확인한 결과 저장 트랜잭션 안이다(금지 9). 순서 · 첫 쓰기를 바꾸지 마라.
- 로드맵 계획이 만드는 짜기 전 코스(`inRoadmap`)는 반영 내역이 없다 — 강의 목록이 생길 때 채운다.

### 3. 시험

- `CoursePlanFormatTest` — 반영 내역을 읽는다 · 없거나 모양이 틀린 여러 경우에 강의 목록은 받고 내역은 빈 목록 둘 · 강의 목록이 틀리면 지금처럼 불합격.
- `Course` 단위 시험 — 팩토리 · 메서드가 넣은 내역을 getter 로 확인(금지 24).
- 레포지토리 또는 통합 — jsonb 로 저장 · 다시 읽기가 같은 값이다.
- 세 생성기 통합 시험 — 새 코스 · 로드맵의 코스 · 다시 짜기가 응답의 내역을 저장한다 · 다시 짜면 이전 내역이 새 것으로 바뀐다 · 내역 없는 응답(공용 `TestChatProvider` 의 기본 응답)이면 빈 목록 둘이 저장되고 작업은 성공이다 · 로드맵 계획은 `mastery_report` 를 건드리지 않는다(짜기 전 코스는 비어 있다).

### 4. 문서 — 구현으로 정해진 사실만

- `docs/03-generation.md` §5 끝에 구현 줄 하나 — 출력 키 · 해석 규칙 · 넣는 팩토리 · 메서드 이름.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.generation.*' --tests 'com.mogum.course.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 반영 내역의 모양이 틀려도 강의 목록이 저장되고 다시 시도하지 않는가?
   - 서버가 내역을 맞춰 보지 않는가? 다시 짜면 바뀌고, 로드맵 계획은 건드리지 않는가?
   - 엔티티 규칙(팩토리 · 메서드 · public 생성자 없음 · Auditing)과 결과 저장 순서(금지 9)를 지켰는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/10-mastery-loop/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 반영 내역 record 이름 · 칸 · `Course` 의 getter 이름(step 6 이 쓴다) · 바꾼 팩토리 · 메서드 시그니처 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 마이그레이션을 더하지 마라. 이유: `courses.mastery_report` 칸이 이미 있다(02 §8.3). 칸 모양이 맞지 않으면 `blocked`.
- 코스 응답 · 화면에 반영 내역을 싣지 마라. 이유: step 6 · 7 의 범위다.
- 반영 내역을 서버가 목록 · 관련 개념과 맞춰 보거나 고치지 마라. 이유: ADR-121.
- 로드맵 계획 출력에 반영 내역을 더하지 마라. 이유: 03 §5.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
