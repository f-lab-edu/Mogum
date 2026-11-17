# Step 3: new-learning-jobs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/04-jobs-and-cost.md` §2.3(「만드는 작업」 줄들 — **받기 처리기와 생성 처리기는 같은 step 에서 짝으로 더한다**) · §4.1(흐름 — 저장 트랜잭션) · §4.6 · §10
- `docs/02-domain-data.md` §2.1 · §2.2 · §2.3 · §2.4(입력을 결과에 옮겨 담는다) · §8.3
- `docs/03-generation.md` §2
- `docs/05-api.md` §2(받기 — 지금 「운영 받기 처리기는 아직 없어 여섯 종류 모두 `INVALID_REQUEST`」)
- `docs/07-testing.md` §1(결과는 성공할 때 · 크레딧) · §2(LLM 스텁 · 지금 테스트 전용 처리기 주입)
- `docs/TEST_GUIDE.md` 전체 — 특히 §5(공용 설정 · 컨텍스트를 늘리지 않는다)
- `docs/ADR.md` — ADR-127 · ADR-130 · ADR-132 · ADR-137 · ADR-157 · ADR-181
- `docs/README.md` 「정한 것」 2026-11-17 — 새로 배우기 두 번 누름은 서버 장치를 두지 않는다
- `AGENTS.md` — 금지 1 · 3 · 4 · 6 · 9 · 12 · 14 · 24 · 26 · 27 · 28
- step 0 · 1 · 2 가 만든 것(summary 참고) — 호출 기록 · 채팅 호출과 어댑터 인터페이스 · 강의 목록 · 로드맵 계획의 재료와 해석
- 틀: `backend/src/main/java/com/mogum/job/JobIntakeHandler.java` · `job/service/JobIntakeService.java` · `generation/JobGenerator.java` · `JobSnapshot.java` · `worker/JobRunner.java` · `config/WorkerConfiguration.java`
- 결과 엔티티: `backend/src/main/java/com/mogum/course/domain/Course.java`(`single` · `inRoadmap`) · `Roadmap.java`(`plan`) · `Lesson.java`(`listed`) · `course/repository/`
- **테스트 전용 처리기와 그것을 쓰는 시험**: `backend/src/test/java/com/mogum/support/IntegrationTestConfiguration.java`(`JobIntakeHandlers` · `JobGenerators` · `TestJobIntakeHandler` · `TestJobGenerator`) · `support/NoJobHandlersIntegrationTest.java` · `job/controller/JobControllerIntegrationTest.java` · `JobControllerNoHandlersIntegrationTest.java` · `job/service/JobIntakeServiceIntegrationTest.java` · `worker/JobRunnerIntegrationTest.java` · `worker/WorkerLifecycleIntegrationTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 step 이 **운영에서 처음으로 만들기를 받는다** — 새 코스(`NEW_COURSE`) · 새 로드맵(`NEW_ROADMAP`) 의 받기 처리기와 생성 처리기를 짝으로 더한다.

**먼저 풀어야 할 것 — 테스트 전용 처리기와 키가 겹친다.** 지금 공용 통합 테스트 설정은 여섯 종류 모두에 테스트 전용 받기 처리기(`TestJobIntakeHandler`)와 생성 처리기(`TestJobGenerator`)를 **빈으로** 올린다. `JobIntakeService` 와 `JobRunner` 는 처리기를 종류별 `Collectors.toUnmodifiableMap` 으로 모으므로, 같은 종류의 운영 처리기 빈이 생기면 키가 겹쳐 **모든 통합 테스트의 컨텍스트가 뜨지 않는다.** 이 phase 의 step 4 · 5 와 강의 자료 TASK 도 같은 일을 겪는다 — 한 번에 풀어 둔다.

**계약 문서는 이 phase 전에 정해 커밋했다(`fa5af24`). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 테스트 전용 처리기를 공용 컨텍스트에서 뺀다

지킬 끝 모양:

- **공용 통합 컨텍스트에 테스트 전용 받기 · 생성 처리기 빈이 없다** — 여섯 종류 모두. 공용 컨텍스트는 운영과 같은 처리기 구성이다. 운영 처리기가 없는 종류(지금 이 step 뒤에는 `PLAN_ROADMAP_COURSE` · `REPLAN_*` · `MATERIAL`)는 운영처럼 받지 않는다.
- **틀을 시험하는 테스트(받기 · 워커 · 수명 · 기다리는 화면 읽기)는 테스트 전용 처리기를 계속 쓰되, 빈이 아니라 시험이 직접 구성한 객체에 넘긴다** — `JobRunnerIntegrationTest` 가 이미 `JobRunner` 를 직접 구성하는 방식이다. `JobIntakeService` · `WorkerLifecycle` 도 같이. 이렇게 하면 뒤 step 이 운영 처리기를 더해도 틀 시험이 흔들리지 않는다.
- **운영 처리기를 시험할 때 바꾸는 자리는 LLM 어댑터 하나다**(07 §2) — step 1 의 어댑터 인터페이스를 공용 테스트 설정에서 **LLM 스텁**으로 바꾼다. 그 위의 채팅 호출(이어 쓰기 · 호출 기록 · 형식 검사)과 생성 처리기 · 저장은 실제 코드가 돈다. 스텁은 작업마다(호출에 실린 작업 ID 로) 응답을 정할 수 있다: 정상 · 일시적 실패 · 형식 불합격 · 잘린 출력(뒤에 이어 쓰기) · 다시 해도 같은 실패(거절 · 입력이 너무 큼). 기본은 각 종류의 맞는 답이다.
- 둘 곳 — 공용 테스트 설정(`@TestConfiguration`)의 중첩 static 클래스다. 클래스마다 `@Import` 하지 마라 — 컨텍스트가 클래스마다 새로 뜬다(TEST_GUIDE §5 · `/harness` 원칙 22).
- 「처리기가 없는 운영 구성」 전용 묶음(`NoJobHandlersIntegrationTest` · `test.job-intake-handlers` 속성)은 공용 컨텍스트가 운영 구성이 되면 필요가 없다. 없애도 되고, 그러면 공용 컨텍스트 수가 줄어든다. 「처리기 없는 종류는 받지 않는다」 시험은 운영 처리기가 아직 없는 종류로 옮긴다.
- 운영 코드에 테스트용 분기 · 속성을 더하지 마라.
- **옮긴 시험은 summary 에 「옛 시험이 확인하던 사실 → 새 시험」 대응을 적는다** — 빠뜨린 사실이 드러나게. 지운 시험은 없어야 한다(사실이 다른 시험으로 옮겨 갔으면 그렇게 적는다).

### 2. 받기 처리기 — `com.mogum.job` 아래(운영 구현)

- `NEW_COURSE` · `NEW_ROADMAP` 의 `check` 는 확인할 대상이 없다 — 입력 칸 검사는 요청 DTO(`@ValidJobRequest`)와 DB 가 이미 한다. 서비스에서 다시 검사하지 마라.
- 새로 배우기를 두 번 받아도 막지 않는다 — 서버 장치를 두지 않는다(README 2026-11-17 · ADR-157).
- `job` 은 `generation` · `worker` 를 참조하지 않는다(금지 1).

### 3. 생성 처리기 — `com.mogum.generation` 아래(운영 구현)

`JobGenerator` 의 `generate` · `save` 를 두 종류에 구현한다.

- `generate(JobSnapshot)` — 트랜잭션 밖이다. 스냅샷의 입력으로 step 2 의 재료를 만들고(새 코스 → 강의 목록 · 새 로드맵 → 로드맵 계획), step 1 의 채팅 호출에 step 2 의 해석 함수를 넘긴다. 성공이면 해석한 값을 `GeneratedResult` 값으로, 실패면 그 `Failure` 를 돌려준다. **예외를 던지지 마라** — 던지면 작업이 「만드는 중」에 남아 시한마다 다시 돈다. 호출 기록에는 작업 ID · 사용자 ID · 종류가 실린다.
- `save(JobSnapshot, GeneratedResult)` — 워커가 소유권 확인을 첫 쓰기로 마친 트랜잭션 안이다(금지 9). 여기서 결과를 만들고 **입력을 결과에 옮겨 담는다**(02 §2.4 · ADR-137).
    - 새 코스 → `Course.single(userId, input, title)` + 강의마다 `Lesson.listed(...)`(순서 1부터). 코스는 검토 중이다.
    - 새 로드맵 → `Roadmap.plan(userId, input, title, description)` + 계획의 코스마다 `Course.inRoadmap(roadmap, position, title, planDescription, planConcepts)`(순서 1부터 · 짜기 전). 강의는 만들지 않는다(02 §2.2 · ADR-132).
    - 새 결과 ID 를 `ResultRef` 로 돌려준다. 엔티티는 팩토리로만 만든다(금지 24). 처음 상태는 팩토리가 정한다.
- 빈 조립은 지금 방식(`config` 가 `generation` 을 조립 · 또는 지금 코드가 쓰는 방식)을 따른다.

### 4. 테스트

- **운영 처리기 + LLM 스텁으로 워커 한 바퀴**(`@IntegrationTest` — 실제 `JobRunner` 의 `runOnce()`):
    - 새 코스 성공 — 코스(검토 중) · 강의(순서 · 제목 · 설명 · 개념) · 코스의 입력 칸 · 작업 성공과 결과 연결. 호출 기록 한 줄(`CHAT` · `SUCCEEDED` · 종류 · 작업 · 사용자).
    - 새 로드맵 성공 — 로드맵(검토 중 · 제목 · 설명 · 입력) · 계획의 코스들(짜기 전 · 순서 · 계획 설명 · 계획 개념) · 강의 없음.
    - 잘린 출력 → 이어 쓰기 → 성공 — 호출 기록 `CHAT`(`TRUNCATED`) + `CONTINUATION`.
    - 형식 불합격이 이어져 실패 — 정한 횟수만큼 호출 · 작업 `FAILED`(`INVALID_OUTPUT`) · **코스 · 로드맵 · 강의가 남지 않는다**(07 §1 결과는 성공할 때) · 크레딧 복구.
    - 일시적 실패 뒤 성공 · 거절은 한 번만 부르고 `PROVIDER_REJECTED` · 입력이 너무 큼은 `INPUT_TOO_LARGE`.
- **HTTP 받기** — 실제 HTTP(`@IntegrationTest`)로 `POST /api/jobs` 의 `NEW_COURSE` · `NEW_ROADMAP` 가 받아지고 크레딧을 쓴다. 운영 처리기가 없는 종류는 여전히 받지 않는다.
- 틀 시험(받기 · 워커 · 수명 · 읽기 · HTTP)이 모두 그대로 통과한다 — 1번의 대응대로.
- 공용 통합 컨텍스트에 테스트 전용 처리기 빈이 없음을 확인하는 시험을 하나 둔다(뒤 step 이 다시 겹치지 않게).

### 5. 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §2 — 「운영 받기 처리기는 아직 없어 여섯 종류의 받기 모두 `INVALID_REQUEST`」를 지금 받는 종류(`NEW_COURSE` · `NEW_ROADMAP`)와 아직 받지 않는 종류로 고친다.
- `docs/07-testing.md` §2 — 테스트 전용 처리기 주입 방식(빈 → 직접 구성) · LLM 스텁의 자리와 쓰는 법.
- `docs/04-jobs-and-cost.md` §2.3 — 두 종류의 처리기 클래스 한 줄 · 「운영 생성 처리기는 여전히 없고」 문장들을 지금 사실로.
- `docs/TEST_GUIDE.md` — 공용 컨텍스트 · 대역을 설명하는 절에 바뀐 사실이 있으면 고친다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.generation.*' --tests 'com.mogum.job.*' --tests 'com.mogum.worker.*' --tests 'com.mogum.support.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 공용 통합 컨텍스트에 테스트 전용 처리기 빈이 없는가? 클래스마다 `@Import` 하거나 컨텍스트를 늘리지 않았는가? 운영 코드에 테스트용 분기가 없는가?
   - 운영 처리기 시험에서 바꾼 자리가 LLM 어댑터뿐인가?
   - `generate` 가 예외를 던지지 않는가? 실패 · 버리기 뒤에 빈 결과가 남지 않는가?
   - 입력이 결과에 옮겨 담기는가? 로드맵의 코스가 짜기 전이고 강의가 없는가?
   - summary 에 옮긴 시험의 사실 대응이 있는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/7-course-planning/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 두 처리기 클래스 · LLM 스텁의 자리와 쓰는 법(step 4 · 5 가 쓴다) · 틀 시험이 테스트 처리기를 받는 방법 · 옮긴 시험의 사실 대응 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `PLAN_ROADMAP_COURSE` · `REPLAN_*` · `MATERIAL` 의 운영 처리기를 만들지 마라. 이유: step 4 · 5 와 강의 자료 TASK 의 범위다. 받기 처리기만 있고 생성 처리기가 없으면 작업이 「만드는 중」에 남아 끝나지 않는다(04 §2.3).
- 새로 배우기 두 번 누름을 서버에서 막는 장치를 만들지 마라. 이유: README 2026-11-17 — 화면이 요청 중 버튼을 막는 것만 한다.
- 관련 개념 검색 · 숙련도 · 숙련도 반영 내역을 만들지 마라. 이유: 숙련도 고리 TASK 의 일이다.
- 틀 시험이 확인하던 사실을 빼지 마라. 이유: 받기 · 워커 · 수명의 규칙(07 §1)을 지키는 시험이다. 옮길 수 없으면 `blocked`.
- 실제 OpenAI 를 부르지 마라. 이유: 비용.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
