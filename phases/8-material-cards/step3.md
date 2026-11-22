# Step 3: material-generator

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/03-generation.md` §1 · **§3(강의 자료와 카드 — 넣는 것 · 뼈대와 선택 섹션 · 표현 수단 · 카드)** · §4(프롬프트 공통 규칙) · §7
- `docs/ADR.md` — ADR-011 · ADR-013 · ADR-019 · ADR-021 ~ ADR-025 · ADR-033 · ADR-039 ~ ADR-042 · ADR-048 · ADR-117 · ADR-119 · ADR-123 · ADR-124 · ADR-127 · ADR-130 · ADR-157 · **ADR-185 · ADR-186 · ADR-187**
- `docs/02-domain-data.md` §2.1 · §2.4 · §3 · §8.3(`materials`) · §8.5(`MATERIAL` · 고유 색인)
- `docs/04-jobs-and-cost.md` §2.3 · §4.1 · §4.6 · §9.1(`job generate failed`) · §10
- `docs/05-api.md` §1.2 · §2(지금 「`MATERIAL` 은 운영 처리기가 없어 `INVALID_REQUEST`」)
- `docs/07-testing.md` §1 · §2
- `AGENTS.md` — 금지 1 · 3 · 4 · 6 · 9 · 11 · 13 · 14 · 15 · 16 · 18 · 24 · 25 · 26
- step 0 · 1 · 2 가 만든 것(summary 참고) — `LlmProperties.material()` · 임베딩 설정 · `EmbeddingCall` · `TestEmbeddingProvider` · 카드 넣기 서비스
- 본뜰 처리기: `backend/src/main/java/com/mogum/job/PlanRoadmapCourseIntakeHandler.java` · `generation/PlanRoadmapCourseGenerator.java` · `generation/RoadmapCoursePlanReader.java` · `generation/GenerationFailures.java` · `generation/plan/`(`CoursePlanFormat` · `PlanPromptSupport` · `PlanJson`) · `backend/src/main/resources/prompts/planning/` · `config/GenerationConfiguration.java`
- 틀: `job/service/JobIntakeService.java`(주인 확인 → 처리기 `check` → 같은 대상의 작업 반환 순서) · `job/service/JobReadService.java`(`MATERIAL` 결과 `LESSON`) · `worker/JobRunner.java`
- 결과 엔티티: `course/domain/Lesson.java` · `Material.java`(`write`) · `Course.java` · `Roadmap.java` · `LearningInput.java`
- 시험: `backend/src/test/java/com/mogum/support/IntegrationTestConfiguration.java`(`TestChatProvider.defaultText` · `TestEmbeddingProvider`) · `generation/PlanRoadmapCourseGeneratorIntegrationTest.java` · `job/controller/JobControllerIntegrationTest.java` · **`job/controller/JobControllerNoHandlersIntegrationTest.java`**

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

강의를 처음 열 때 화면이 `MATERIAL` 작업을 걸고(05 §2), 워커가 **채팅 호출 한 번(+ 이어 쓰기)으로 강의 자료와 카드를 함께** 받은 뒤(ADR-119), 카드 개념의 정의를 **임베딩 한 번**으로 받아, 결과 저장 트랜잭션에서 강의 자료 · 카드 · 개념을 함께 저장한다. 이 step 은 받기 처리기와 생성 처리기를 짝으로 더한다(04 §2.3).

**숙련도 판정 지시는 넣지 않는다** — 숙련도 고리 TASK 의 일이다(03 §3 · README 2026-11-23). 모든 개념을 처음 배우는 것처럼 다룬다.

**계약 문서는 이 phase 전에 정해 커밋했다. 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 받기 처리기 — `com.mogum.job` 아래 `MaterialIntakeHandler`

주인 확인은 틀이 먼저 한다(`JobIntakeService.checkOwner` — 남의 것은 `NOT_FOUND`). `check` 는 **강의를 열 수 있는지**만 본다:

- 그 강의의 코스가 학습 중(`LEARNING`) 또는 다 마침(`COMPLETED`)이다 — 검토 중 · 짜기 전에는 강의를 열지 않는다(06 §7).
- 그 강의보다 앞 순서의 강의가 모두 학습 완료다(ADR-019).
- 아니면 거절한다 — 새 오류 code 를 `ErrorCode` 에 더하고(예: `LESSON_NOT_OPEN`), 05 §1.2 의 표에 묶음 · 상태 코드 · 문구와 함께 한 줄 적는다(금지 25). step 4 의 강의 읽기 · 학습 완료가 같은 code 를 쓴다.
- **강의 자료가 이미 있는지는 보지 마라** — 틀이 `check` 뒤에 같은 강의의 작업(성공 포함 — 고유 색인)을 찾아 돌려준다. 여기서 거절하면 이미 만든 강의의 작업을 돌려받지 못한다.
- `job` 은 `generation` · `worker` 를 참조하지 않는다(금지 1).

### 2. 재료 읽기 — 트랜잭션 하나에서 값으로(금지 4)

`RoadmapCoursePlanReader` 처럼 읽기 전용 `TransactionTemplate` 으로 읽어 record 로 넘긴다. 담을 것(ADR-185 · 03 §3 — **이것뿐이다**):

- 강의 한 줄 — 순서 · 제목 · 한 줄 설명 · 다룰 개념(`[{name, definition}]`).
- 코스 제목 · 주제 — 단일 코스는 코스의 `topic`, 로드맵의 코스는 로드맵의 `topic`.
- 그 코스의 강의 목록 — 강의마다 순서 · 제목 · 한 줄 설명. 이번 강의를 표시한다. **다른 강의의 다룰 개념은 넣지 않는다.**
- 목적(+ 기타 글) · 메모 — 단일 코스는 코스의 것, 로드맵의 코스는 로드맵의 것(ADR-046).
- 넣지 않는 것 — 지금 수준(목록을 짤 때만 쓴다 — 03 §1.2) · 다시 짜기 피드백(ADR-040) · 숙련도.

### 3. 프롬프트 — `backend/src/main/resources/prompts/material/`

03 §3 · §4 를 지시로 옮긴다. 담을 것:

- 고정 뼈대(이번 강의에서 배울 것 — 목표와 앞 강의와 이어짐 · 개념 설명 — 다룰 개념마다 · 정리)와 선택 섹션 다섯의 붙는 조건(ADR-021 · ADR-124). 넣지 않는 것 — 확인 질문 · 다음 강의 예고.
- 무엇을 다룰지는 강의 한 줄, 어떻게 다룰지는 메모 — 부딪히면 강의 한 줄이 앞선다(ADR-042). 설명하는 것은 이번 강의의 다룰 개념뿐이고, 목록의 뒤 강의에서 다룰 것을 미리 끌어오지 않는다(ADR-185). 메모는 규칙 안에서만(ADR-039).
- 표현 수단(ADR-022) — 마크다운(제목 · 목록 · 강조 · 표) · 코드 블록(언어 표시) · Mermaid(흐름도 · 시퀀스 · 클래스 · ER · 상태도 · 막대/선 차트 — `strict` 보안 설정에서 그려지는 문법) · KaTeX 수식 · **이미지 금지**.
- 목적에 따라 — 업무: 예제 · 실습 · 현업에서 주의할 점 / 면접: 왜 · 비교 · 면접 질문형 카드 / 교양: 비유 · 흐름(03 §1.2). 고른 값은 정해진 문구로, 「기타」 · 메모는 적은 글 그대로(03 §4).
- 카드 — 질문형 하나(ADR-005) · **강의당 3~6장 · 카드당 개념 1~3개**(03 §3) · 개념마다 이름과 한 줄 정의를 LLM 이 적는다(정의는 같은 개념 판정에 쓴다 — 한 문장으로, 그 개념만의 뜻을).
- 학습과 상관없는 요청을 막는 문구를 넣지 않는다(ADR-114).
- 지금 `prompts/planning/` 의 공통 규칙 · 사용자 자료 틀 · 목적 문구를 나눠 쓸 수 있으면 쓰고, 강의 목록 프롬프트의 동작을 바꾸지 마라.

### 4. 출력 형식과 형식 검사 — `com.mogum.generation.material`(이름은 맞게)

- 형식은 정해서 쓴다. 고를 때 볼 것: 본문 마크다운에는 코드 블록 · 백틱 · 백슬래시(KaTeX) · 따옴표가 많다 — JSON 문자열에 본문을 넣으면 이스케이프 실수로 형식 불합격이 잦을 수 있다(예: 본문은 그대로 쓰고 정해진 구분 줄 뒤에 카드 JSON). **이어 쓰기로 이어 붙인 출력도 그대로 해석돼야 한다**(`ChatCall` 이 앞부분 뒤에 붙인다).
- 형식 검사(ADR-163 — 「본문 · 카드를 읽을 수 있나」): 본문이 비어 있지 않다 · 카드 1장 이상 · 카드마다 질문 · 답이 비어 있지 않고 개념 1개 이상 · 개념마다 이름 · 정의가 비어 있지 않다. **카드 수 · 개념 수의 범위는 검사하지 않는다**(3~6 · 1~3 을 넘어도 받는다 — 03 §3). Mermaid · KaTeX 문법은 검사하지 않는다(ADR-117).
- 해석 시험에 코드 블록 · KaTeX(`\frac`, `\\`) · Mermaid · 표가 든 본문, 잘려서 이어 붙은 출력, 불합격 경우들을 넣는다.

### 5. 생성 처리기 — `com.mogum.generation.MaterialGenerator`

- `generate(JobSnapshot)` — 트랜잭션 밖(금지 3): 재료 읽기 → `material` 채팅 설정으로 `ChatCall` → 해석 → 모든 카드 개념의 정의를 **한 번의** `EmbeddingCall` 로 → 성공이면 본문 · 카드 · 개념(벡터 포함)을 담은 `GeneratedResult` record. 채팅 · 임베딩 어느 쪽이든 실패면 그 `Failure`(임베딩 실패로 채팅 결과를 버리는 것은 감수한다 — ADR-187). 같은 정의 글은 한 번만 보내도 된다.
- 예상 밖 예외는 지금 처리기들처럼 `GenerationFailures`(`job generate failed`)로 — 조용히 삼키지 마라.
- `save(JobSnapshot, GeneratedResult)` — 워커가 소유권 확인을 첫 쓰기로 마친 트랜잭션 안(금지 9): `Material.write(lesson, body)` + step 2 의 카드 넣기 서비스 → `ResultRef(lessonId)`. 강의 · 코스 상태는 바꾸지 않는다(학습 완료는 step 4).
- 빈 조립은 `config.GenerationConfiguration` 에 둔다.

### 6. 테스트 스텁과 기존 시험

- `TestChatProvider.defaultText(MATERIAL)` 에 4번 형식의 정상 기본 응답을 더한다(카드 둘 이상 · 개념이 겹치는 카드 포함).
- **`JobControllerNoHandlersIntegrationTest`** — 이 step 뒤에는 운영 처리기가 없는 종류가 없다. 남은 사실(처리기 없는 종류는 받지 않고 DB 를 그대로 둔다)이 더 시험할 대상이 없으면 이 클래스를 지우고, summary 에 「지운 시험 → 이유」를 적는다. 그 클래스가 함께 확인하던 다른 사실이 있으면 다른 시험으로 옮긴다.

### 7. 시험 — 운영 처리기 + LLM · 임베딩 스텁으로(`@IntegrationTest`, `JobRunner.runOnce()`)

- 성공 — 강의 자료 본문 · 카드(순서 · `ACTIVE` · `due_at` 비어 있음) · 개념과 연결 · 작업 성공과 결과 `LESSON`. 호출 기록 `CHAT` 한 줄 + `EMBEDDING` 한 줄(작업 · 사용자 · 종류 `MATERIAL`).
- 잘린 출력 → 이어 쓰기 → 성공.
- 형식 불합격이 이어져 실패 · 임베딩 실패 — **강의 자료 · 카드 · 개념이 남지 않는다**(07 §1 결과는 성공할 때) · 크레딧 복구 · 실패 갈래.
- 기존 개념에 붙는 경우(앞 강의에서 넣은 개념과 같은 벡터) — 새 개념이 생기지 않는다.
- 재료 — 채팅 요청에 코스 제목 · 주제 · 강의 목록 · 이번 강의 표시 · 목적 문구 · 메모가 있고, 지금 수준 · 다시 짜기 피드백 · 다른 강의의 다룰 개념이 없다. 로드맵의 코스는 로드맵의 주제 · 목적 · 메모.
- HTTP 받기(`POST /api/jobs`, 실제 HTTP) — 학습 중 코스의 1강 · 앞 강의를 마친 강의는 받고 크레딧을 쓴다. 검토 중 코스 · 앞 강의를 마치지 않은 강의는 새 code 로 거절하고 아무것도 바꾸지 않는다. 남의 강의는 `NOT_FOUND`. **같은 강의에 받기가 동시에 두 번 와도 작업 · 쓴 크레딧이 하나다**(07 §1). 이미 성공한 강의를 다시 받으면 그 작업을 돌려주고 크레딧을 쓰지 않는다.

### 8. 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §1.2(새 code) · §2(`MATERIAL` 을 받는다 · 받는 조건).
- `docs/04-jobs-and-cost.md` §2.3(처리기 클래스) · §10(`material` 묶음을 쓰는 곳).
- `docs/03-generation.md` §7 「섹션 이름 · 프롬프트 문구」 칸에 강의 자료 프롬프트 파일 경로를 더한다(이 칸만).
- `docs/07-testing.md` §2(`defaultText(MATERIAL)`).

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.generation.*' --tests 'com.mogum.job.*' --tests 'com.mogum.worker.*' --tests 'com.mogum.card.*' --tests 'com.mogum.support.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 한 시도의 채팅 호출이 1번(+ 이어 쓰기) · 임베딩 1번인가(금지 15)? 둘 다 트랜잭션 밖인가?
   - 재료가 03 §3 의 목록뿐인가(수준 · 피드백 · 다른 강의의 개념 · 숙련도 없음)?
   - 실패 · 임베딩 실패 뒤 강의 자료 · 카드 · 개념이 남지 않는가? 저장이 소유권 확인 뒤 한 트랜잭션인가?
   - 개념을 넣는 경로가 step 2 의 서비스뿐인가?
   - 받기 처리기가 강의 자료의 유무로 거절하지 않는가? 새 code 가 05 §1.2 에 있는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/8-material-cards/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 두 처리기 · 재료 읽기 · 형식 클래스 이름 · 출력 형식 요약 · 새 오류 code 와 문구 · 프롬프트 파일 · 지우거나 옮긴 시험 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 숙련도 판정 · 관련 개념 검색을 넣지 마라. 이유: 숙련도 고리 TASK(SCHEDULE §3 의 10)의 일이다.
- 카드 수 · 개념 수의 범위를 형식 검사로 막지 마라. 이유: 03 §3 — 한 장 넘친 것으로 다시 부르면 돈을 두 번 쓴다.
- 강의 읽기 · 학습 완료 API 와 화면을 만들지 마라. 이유: step 4 · 6 의 범위다.
- 강의 자료 다시 만들기를 만들지 마라. 이유: ADR-025.
- 실제 OpenAI 를 부르지 마라. 이유: 비용.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
