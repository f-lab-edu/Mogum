# Step 4: roadmap-plan-mastery

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/03-generation.md` §1.1 · §2(로드맵 계획 — 「숙련도가 하는 일」의 로드맵 계획 줄 · 「관련 개념 검색」 표 · 「피드백 — 다시 짜기」) · §4 · §5(반영 내역 — 로드맵 계획은 적지 않는다)
- `docs/ADR.md` — ADR-008 · ADR-009 · ADR-010 · ADR-031 · ADR-032 · ADR-037 · ADR-118 · ADR-167
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 1 · 3 · 4 · 14 · 15 · 26
- step 0 ~ 3 이 만든 것(summary 참고) — 특히 step 3 의 관련 개념 준비 클래스와 `CoursePlanFormat` 이 관련 개념을 싣는 방식 · `course.txt` 에 더한 규칙
- `backend/src/main/java/com/mogum/generation/NewRoadmapGenerator.java` · `ReplanRoadmapGenerator.java` · `plan/RoadmapPlanInput.java` · `plan/RoadmapPlanFormat.java` · `plan/PlanPromptSupport.java`
- `backend/src/main/resources/prompts/planning/roadmap.txt` · `common.txt`
- `backend/src/main/java/com/mogum/config/GenerationConfiguration.java`
- `backend/src/test/java/com/mogum/generation/NewRoadmapGeneratorIntegrationTest.java` · `ReplanRoadmapGeneratorIntegrationTest.java` · `plan/RoadmapPlanFormatTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

로드맵 계획(새로 `NEW_ROADMAP` · 다시 짜기 `REPLAN_ROADMAP`)에도 관련 개념과 판정을 넘긴다(03 §1.1 · §2). 반영 내역은 적지 않는다(03 §5).

**계약 문서는 이 phase 전에 정해 커밋했다. 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

- 두 생성기가 재료 읽기 뒤 · 채팅 앞에 step 3 의 준비 클래스를 부른다. 질의는 **주제와 목적 문구를 이은 글 하나**다 — 새로 · 다시 짜기 모두(03 §2 표). step 3 이 새 코스에 쓴 질의 글 만들기와 같은 코드를 쓴다 — 두 벌을 만들지 마라.
- `ReplanRoadmapGenerator` 가 읽기 트랜잭션 안에서 입력을 만들면 그 트랜잭션이 끝난 뒤 임베딩을 부른다(금지 3 · 4).
- `RoadmapPlanInput` 에 관련 개념 목록을 담고, `RoadmapPlanFormat` 이 step 3 과 **같은 키 · 같은 모양**으로 user 데이터에 싣는다(이름 · 한 줄 정의 · 판정 지시 문구). 비면 칸을 싣지 않는다. 판정 이름 · 숫자는 싣지 마라.
- `prompts/planning/roadmap.txt` 에 규칙을 더한다 — 「짧게」 개념은 다른 개념과 묶어 적은 자리를 쓰고 「자세히」 개념에는 자리를 더 준다 · 판정으로 개념을 빼지 않는다 · 주제와 관계없는 관련 개념은 쓰지 않는다 · 다시 짜기의 피드백이 숙련도보다 앞선다(ADR-037). 코스 수 규칙(2개 이상 — ADR-008)은 그대로다.
- 출력 형식 · 해석 · 저장은 바꾸지 마라 — 반영 내역을 받지 않는다(03 §5).
- 사전이 비면 임베딩 없이 지금과 같은 프롬프트다. 임베딩 실패면 채팅을 부르지 않고 그 실패로 끝난다.
- 예상 밖 예외는 지금처럼 `GenerationFailures.unavailable(exception)` 이다.

### 시험

- `RoadmapPlanFormatTest` — 관련 개념이 step 3 과 같은 키 · 모양으로 실린다 · 비면 칸이 없다 · 판정 이름 · 숫자가 없다 · 규칙 문장이 system 에 있다.
- 두 생성기 통합 시험 — 질의 글이 주제 + 목적 문구 · 관련 개념이 채팅 프롬프트에 실린다 · 사전이 비면 임베딩 요청 없음 · 임베딩 실패면 채팅 요청 없음과 그 실패 · 트랜잭션 밖 호출.

### 문서 — 구현으로 정해진 사실만

- step 3 이 03 §2 에 적은 구현 줄에 로드맵 계획 두 생성기를 더한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.generation.*' --tests 'com.mogum.config.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 질의 글 만들기 · 관련 개념 싣기가 step 3 과 한 벌인가?
   - 사전이 비면 임베딩을 부르지 않는가? 임베딩 · 채팅이 트랜잭션 밖이고, 채팅은 1번(+ 이어 쓰기)인가?
   - 로드맵 계획의 출력 · 저장이 그대로이고 반영 내역이 없는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/10-mastery-loop/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 바꾼 생성기 · `RoadmapPlanInput` 에 더한 칸 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 로드맵 계획에 반영 내역 출력 · `roadmaps` 칸 · 마이그레이션을 더하지 마라. 이유: 03 §5 — 반영 내역은 강의 목록에만 남긴다.
- 강의 목록 생성기 · `CoursePlanFormat` · `course.txt` 를 고치지 마라(공용 코드를 한 벌로 모으는 데 필요한 이동은 예외 — 동작은 그대로). 이유: step 3 이 끝냈고, 출력 · 저장은 step 5 의 범위다.
- 관련 개념 검색에 유사도 하한을 두거나 서버가 배분을 검사하지 마라. 이유: 03 §2 · ADR-121.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 호출 기록에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
