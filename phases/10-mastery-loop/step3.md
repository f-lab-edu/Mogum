# Step 3: course-plan-mastery

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/03-generation.md` §1.1 · §1.2 · §2(강의 목록 — 「넣는 재료」 · 「숙련도가 하는 일」 · 「관련 개념 검색」 표 · 「피드백 — 다시 짜기」) · §4 · §6
- `docs/02-domain-data.md` §5 · §6
- `docs/04-jobs-and-cost.md` §4 · §10(시한 식 — step 2 가 고쳤다)
- `docs/ADR.md` — ADR-031 · ADR-032 · ADR-035 · ADR-037 · ADR-046 · ADR-051 · ADR-052 · ADR-118 · ADR-120 · ADR-121
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 1 · 3 · 4 · 14 · 15 · 26
- step 0 ~ 2 가 만든 것(summary 참고) — 판정 enum · `card.service` 의 `hasConcepts` · `findRelated` · 판정 지시 문구 파일(`prompts/planning/mastery-*.txt`) · `MaterialGenerator` 가 임베딩 · 조회를 부르는 방식
- `backend/src/main/java/com/mogum/generation/NewCourseGenerator.java` · `PlanRoadmapCourseGenerator.java` · `ReplanCourseGenerator.java` · `RoadmapCoursePlanReader.java` · `plan/CoursePlanInput.java` · `plan/CoursePlanFormat.java` · `plan/PlanPromptSupport.java` · `MaterialGenerator.java`
- `backend/src/main/resources/prompts/planning/` 전체
- `backend/src/main/java/com/mogum/config/GenerationConfiguration.java`
- `backend/src/test/java/com/mogum/generation/NewCourseGeneratorIntegrationTest.java` · `PlanRoadmapCourseGeneratorIntegrationTest.java` · `ReplanCourseGeneratorIntegrationTest.java` · `plan/CoursePlanFormatTest.java` · `support/IntegrationTestConfiguration.java`(`TestEmbeddingProvider` · `TestChatProvider`)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

강의 목록을 짜는 세 만들기(새 코스 `NEW_COURSE` · 로드맵의 코스 `PLAN_ROADMAP_COURSE` · 다시 짜기 `REPLAN_COURSE`)에 **관련 개념과 판정**을 넘긴다(ADR-032 · 03 §2). 로드맵 계획(`NEW_ROADMAP` · `REPLAN_ROADMAP`)은 step 4, 반영 내역 출력은 step 5 다.

**계약 문서는 이 phase 전에 정해 커밋했다(03 §2 「관련 개념 검색」 표). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 관련 개념 준비 — `generation` 의 한 곳

step 4 의 로드맵 계획도 같은 준비를 쓰므로 `generation` 에 한 곳(클래스 하나)으로 둔다.

```java
// 이름은 정해도 된다. 실패를 예외가 아니라 값으로 돌려준다.
RelatedResult prepare(JobSnapshot job, List<String> queryTexts);   // 성공: List<RelatedConcept> · 실패: GenerationOutcome.Failure
```

- 사전이 비었으면(`hasConcepts`) 임베딩을 부르지 않고 빈 목록이다.
- 질의 글들을 **임베딩 한 번**으로 보낸다(`EmbeddingCall` — 호출 기록이 남는다). 실패면 그 실패를 돌려주고 호출자는 채팅을 부르지 않는다.
- `findRelated(userId, 벡터들, 20, now)` — **20** 은 이 클래스의 이름 있는 상수 하나다(03 §2 「넘기는 수」). 「지금」은 주입한 `Clock` 에서 읽는다.
- 트랜잭션 밖에서 부른다(금지 3). 예상 밖 예외는 호출자의 `GenerationFailures.unavailable(exception)` 로 간다 — 다른 실패 갈래로 바꾸지 마라(S23).

### 2. 질의 — 03 §2 표 그대로

| 만들기 | 질의 글 |
|---|---|
| 새 코스 · 단일 코스 다시 짜기 | 주제와 목적 문구를 이은 글 하나 — 목적 문구는 프롬프트에 넘기는 것과 같은 정해진 문구(「기타」면 적은 글) |
| 로드맵의 코스 · 그 코스 다시 짜기 | 그 코스의 계획 개념(`courses.plan_concepts`) 정의마다 하나 |

- 메모 · 피드백 · 수준은 질의에 넣지 마라. 이유: 03 §2 가 정한 질의가 아니다.

### 3. 프롬프트

- `CoursePlanInput` 에 관련 개념 목록을 담고, `CoursePlanFormat` 이 user 데이터에 개념마다 **이름 · 한 줄 정의 · 판정 지시 문구**(step 2 의 `prompts/planning/mastery-*.txt`)로 싣는다. 목록이 비면 그 칸을 싣지 않는다. 판정 이름 · 숫자는 싣지 마라(ADR-031 · 03 §4).
- `prompts/planning/course.txt` 에 규칙을 더한다(03 §2 「숙련도가 하는 일」 · 「관련 개념 검색」):
    - 관련 개념의 지시가 「짧게」인 개념은 다른 개념과 한 강의에 묶어 적은 자리를 쓰고, 「자세히」인 개념에는 강의를 따로 줄 수 있다. 판정으로 개념을 빼지 않는다.
    - 주제와 관련된 흔들리는 개념을 다시 짚는 강의를 넣을 수 있다 — 로드맵 계획에 없는 개념이어도 된다. **10강 중 최대 2강**.
    - 주제와 관계없는 관련 개념은 쓰지 않는다.
    - 다시 짜기의 피드백은 어떤 것이든 숙련도로 정한 배분보다 앞선다(ADR-037).
- 출력 형식(`new-course-output.txt` · `lessons-output.txt`)은 바꾸지 마라. 이유: step 5 의 범위다.

### 4. 세 생성기 · 조립

- `NewCourseGenerator` · `PlanRoadmapCourseGenerator` · `ReplanCourseGenerator` 의 `generate` 가 재료 읽기 뒤 · 채팅 앞에 1 을 부른다. 재료 읽기는 지금처럼 읽기 트랜잭션에서 값으로 복사하고, 그 트랜잭션이 끝난 뒤 임베딩을 부른다(금지 3 · 4 — `ReplanCourseGenerator` 는 지금 `transactionTemplate.execute` 안에서 입력을 만든다. 임베딩을 그 안에서 부르지 마라).
- 채팅은 한 시도에 1번(+ 이어 쓰기)이다(금지 15). `save` 는 바꾸지 않는다.
- `GenerationConfiguration` 이 1 의 클래스를 조립해 세 생성기에 넘긴다.

### 5. 시험

- `CoursePlanFormatTest` — 관련 개념이 이름 · 정의 · 지시 문구로 실린다 · 비면 칸이 없다 · 판정 이름 · 숫자가 없다 · 규칙 문장(최대 2강 · 관계없으면 쓰지 않음 · 피드백이 앞선다)이 system 에 있다.
- 1 의 클래스 시험 — 사전이 비면 임베딩 요청 없음 · 임베딩 실패를 돌려준다 · 20개 상수를 넘겨 부른다.
- 세 생성기 통합 시험(`TestEmbeddingProvider.vector` 로 질의 글과 사전 개념의 벡터를 정한다):
    - 새 코스 — 질의 글이 주제 + 목적 문구이고, 가까운 사전 개념이 판정 지시와 함께 채팅 프롬프트에 실린다.
    - 로드맵의 코스 — 질의가 계획 개념 정의들이다.
    - 다시 짜기 — 단일 코스 · 로드맵의 코스 각각 위 질의를 쓴다.
    - 사전이 비면 임베딩 요청이 없고 지금과 같은 프롬프트다(관련 개념 칸 없음).
    - 임베딩 실패면 채팅 요청이 없고 작업이 그 실패로 끝난다 · 호출 기록이 남는다.
    - 임베딩 · 채팅이 트랜잭션 밖에서 불린다(`outsideTransaction`).

### 6. 문서 — 구현으로 정해진 사실만

- `docs/03-generation.md` §2 「관련 개념 검색」 표 아래에 구현 줄 하나 — 준비 클래스 · 상수 자리 · 세 생성기가 부르는 자리.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.generation.*' --tests 'com.mogum.config.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 질의가 03 §2 표 그대로인가? 최대 20개 · 하한 없음인가?
   - 사전이 비면 임베딩을 부르지 않는가? 임베딩 · 채팅이 트랜잭션 밖이고, 채팅은 1번(+ 이어 쓰기)인가?
   - 프롬프트에 판정 이름 · 숫자가 없는가? 피드백이 숙련도보다 앞선다는 규칙이 있는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/10-mastery-loop/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 준비 클래스 · 메서드 · 돌려주는 값 모양(step 4 가 쓴다) · `CoursePlanInput` 에 더한 칸 · user 데이터의 키 이름 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 로드맵 계획 생성기(`NewRoadmapGenerator` · `ReplanRoadmapGenerator`)와 `RoadmapPlanFormat` · `roadmap.txt` 를 고치지 마라. 이유: step 4 의 범위다.
- 강의 목록 출력 형식 · 해석 · 저장(반영 내역)을 고치지 마라. 이유: step 5 의 범위다.
- 관련 개념 검색에 유사도 하한을 두거나 서버가 배분 · 다시 짚는 강의 수를 검사하지 마라. 이유: 03 §2 · ADR-121 — 프롬프트 지시로만 한다.
- LLM 에 도구를 주거나 채팅을 두 번 부르지 마라. 이유: 금지 15 · ADR-118.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 호출 기록에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
