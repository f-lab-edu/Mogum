# 07. 테스트 · LLM 스텁 · 품질 점검

> 무엇으로 *"깨지지 않는다"* 를 보이나. 새 설계에서 정한 것은 **결정이 「시험으로 확인한다」고 남긴 것**과 공개 전 품질 점검까지다 — 시험 방법 · 도구 · 수치는 각 TASK 에서 정한다.
> 테스트를 먼저 쓰고 구현한다는 작업 방식은 `AGENTS.md` 를 따른다. 실제 LLM 을 부르는 시험은 기본 실행에서 빼고 스텁으로 돈다.
> PR 마다 무엇이 도는지는 [deploy/README.md 「PR 검사」](../deploy/README.md#pr-검사)에 있다.
> 테스트 코드를 **어떻게 쓰는지**(종류 · 이름 · 본문 · 데이터 · 격리 · 도구)는 [TEST_GUIDE](./TEST_GUIDE.md)에 있다(ADR-179). 이 문서는 **무엇을** 시험하는지를 다룬다.
> 시험이 지키는 규칙의 상세는 근거 ADR 과 [02](./02-domain-data.md) · [04](./04-jobs-and-cost.md)에 있다.

---

## 1. 반드시 있어야 하는 시험

결정에서 나온 것이다. 실제 PostgreSQL 위에서 돈다(기존 `0-skeleton` 의 Testcontainers — ADR-146).

| 시험 | 확인하는 것 | 근거 |
|---|---|---|
| 동시에 꺼내기 | 워커 여럿이 — 같은 서버든 다른 서버든 — 동시에 꺼내도 같은 작업을 집지 않는다 | ADR-129 |
| 시한 · 되찾기 | 시한이 지난 「만드는 중」 작업을 다른 워커가 가져가 처음부터 만든다 · 정상 종료 때 진행 중인 시도가 끝나 결과를 저장하고, 한도를 넘기면 기다림으로 돌려놓는다 | ADR-126 |
| 늦게 끝난 옛 워커 | 다른 워커가 되찾아 간 뒤 끝난 옛 워커는 결과를 쓰지 않는다 — 저장할 때 아직 자기 작업인지 확인한다 | ADR-126 |
| 지운 뒤의 늦은 결과 | 코스 · 로드맵을 지우거나 탈퇴한 뒤 끝난 워커가 결과를 되살리지 않는다 · 성공하지 않은 작업에 쓴 크레딧을 복구한다 | ADR-140 · `JobRunnerCourseDeletionIntegrationTest` · `CourseDeletionServiceIntegrationTest` · `JobRunnerRoadmapDeletionIntegrationTest` · `RoadmapDeletionServiceIntegrationTest` · `JobRunnerWithdrawalIntegrationTest` |
| 결과는 성공할 때 | 실패 · 버리기 뒤에 빈 코스 · 빈 강의가 남지 않는다 · 다시 짜기가 실패하면 이전 것이 그대로다 | ADR-130 · ADR-085 |
| 한 사람이 건 수 | 같은 순간의 두 요청이 함께 상한을 넘지 않는다 — 서버가 여럿이어도 | ADR-104 · ADR-133 |
| 크레딧 | 받을 때 쓰고 · 실패하면 복구하고 · 성공하면 그대로 둔다 · 성공한 작업의 코스를 지워도 · 실패한 뒤 버려도 복구가 한 번뿐이다 · 지난 주기에 쓴 몫은 복구하지 않는다 · 남은 양과 기록이 맞는다 | ADR-112 · ADR-131 · ADR-166 |
| 크레딧 채우기 | 신청할 때 한 주 몫 · 승인 전에는 쓰지 못한다 · 승인한 날부터 7일마다 새벽 4시(한국 시간)에 한 주 몫으로 · 서버가 여럿이어도 한 번만 · 서버가 꺼져 있던 때를 따라잡는다 | ADR-110 · ADR-133 · ADR-173 · ADR-189 · `CreditRefillServiceIntegrationTest`(DB 잠금 경합 · 따라잡기 · 차액 0/음수 · 복구 경계 · 실패 롤백/로그) · `CreditRefillServiceTest`(시각/트랜잭션 순서 · 목록 실패) · `CreditRefillConfigurationTest`(cron/시간대 · 기동 · 끄기) |
| 상태 전환 | 코스 · 로드맵의 상태가 바뀌는 동작에서만 앞으로 간다 · 마지막 코스를 다 마치면 로드맵도 다 마침이 되고, 그 전 코스를 다 마쳐서는 바뀌지 않는다 · 로드맵의 「이대로 시작」을 못 받으면 아무것도 바뀌지 않는다 | ADR-136 · ADR-143 · ADR-176 |
| 숙련도 판정 | 같은 복습 기록이면 언제나 같은 판정 — 「이 숙련도면 짧게」를 시험으로 확인한다 | ADR-031 |
| 지우기 범위 | [02 §7](./02-domain-data.md#7-지우기--탈퇴--함께-사라지는-범위) 의 표대로 지우고 남긴다 — 지운 코스의 카드 · 개념 사전 · 크레딧 · 호출 기록 | ADR-140 · `CourseControllerDeletionIntegrationTest` · `CourseDeletionServiceIntegrationTest` · `RoadmapDeletionServiceIntegrationTest` · `JpaAccountWithdrawalRepositoryIntegrationTest` · `WithdrawControllerIntegrationTest` · `OAuthLoginHandlerIntegrationTest`(탈퇴 뒤 재로그인 · 새 신청) |
| 하루의 경계 | 「오늘 복습할 카드」가 한국 시간 새벽 4시로 바뀐다 | ADR-017 |
| 스트릭 | 복습 1장 · 학습 완료 하나가 그날을 채운다 · 새벽 4시 경계로 센다(새벽 1시는 전날) · 오늘을 아직 채우지 않으면 어제까지의 수 · 하루를 건너뛰면 0일 | ADR-016 · ADR-017 · ADR-178 |
| 같은 날 다시 보기 | 복습 중에 「다시」를 누른 카드가 10분 뒤에(처음 배우는 카드의 「다시」는 첫 단계 1분 뒤) · 볼 카드가 그것만 남으면 바로 다시 나온다 · 학습 단계를 마치면 날 단위로 넘어간다 | ADR-164 |
| 남의 것 | 남의 코스 · 작업 · 카드는 없는 것처럼 답한다 | ADR-149 |
| 같은 받기 두 번 | 같은 강의 · 코스에 받기가 동시에 두 번 와도 작업 · 쓴 크레딧이 하나다 | ADR-157 |
| 가져오기 | 강의를 가져오는 것만으로 강의 자료를 만들지 않는다 | ADR-142 · ADR-143 |

- 코스 · 로드맵 화면은 상태마다 버튼이 달라 상태별로 시험한다(ADR-076 · ADR-101).

## 2. LLM 스텁

- `7-course-planning` step 1의 `generation.llm.FakeOpenAiServer`는 테스트 안의 JDK `HttpServer`다. 실제 SDK 4.75.1이 보내는 경로 · 인증 · 모델 · 출력 상한 · reasoning effort · 메시지 역할과 이어 쓰기를 검사한다. 500 · 429 · 시간 초과에서도 요청 수가 한 번인지 확인한다. `ChatCallIntegrationTest`는 호출 전 기록의 커밋 · 트랜잭션과 연결 반환 · 호출별 결과/토큰/비용 · 해석 실패의 마지막 기록 · 원문 로그 부재를 확인한다.
- `8-material-cards` step 1에서 같은 가짜 서버에 임베딩 응답을 더했다. `OpenAiEmbeddingProviderTest`는 `/embeddings` · 인증 · 모델 · 정의 문자열 배열과 입력 순서 · SDK 기본 `encoding_format=base64` · base64/float 응답 · 섞인 `index` 복원 · 잘못된 index/JSON/벡터 · 실패 분류를 확인한다. 500 · 429 · 헤더 전/본문 읽기 시간 초과와 연결 오류에서도 요청은 한 번이다. `EmbeddingCallIntegrationTest`는 호출 전 기록 커밋 · 트랜잭션과 연결 반환 · `EMBEDDING` 한 줄 · 입력 단가 비용 · 출력 토큰 없음 · 벡터 수/차원 검사 뒤에도 사용량 보존 · 예상 밖 예외 전달 · 원문 로그 부재를 확인한다.
- 공용 설정의 중첩 static `TestEmbeddingProvider`를 `@Primary`로 등록한다. 클래스별 `@Import` 없이 주입받고 `reset()`으로 설정과 관찰을 비운 뒤 쓴다. 기본은 정의 글의 해시로 만든 결정적 3,072차원 벡터이며, 실제 의미 유사도를 흉내 내지 않는다. `vector(definition, List<Float>)`로 정의별 벡터를 정하고, `reply(jobId, Reply...)`와 `failure(Failure)`로 작업별 실패 응답을 순서대로 정한다. 소진 뒤 기본 응답으로 돌아간다. 같은/다른 개념 시험은 명시적인 벡터를 설정한다. `requests(jobId)`와 `outsideTransaction(jobId)`로 받은 요청과 트랜잭션/EntityManager 부재를 본다. `IntegrationTestConfigurationEmbeddingIntegrationTest`가 결정성 · 설정 · reset · 관찰을 검증한다. 공용 컨텍스트 수는 늘리지 않는다.
- `ConceptSimilarityMeasurementTest`도 기본 `test`에서 제외하고 `llmTest`에만 포함한다(ADR-187). 환경 변수 키가 없거나 공백이면 건너뛴다. 한국어 개발 개념의 같은 쌍 15개 · 다른 쌍 15개(이름/뜻이 엇갈리는 쌍 · 가까운/먼 개념)를 이름과 정의로 코드에 두며 **정의만** 한 요청에 임베딩한다. DB 없이 실제 어댑터를 직접 쓰고 코사인 유사도를 계산한다. `backend/build/reports/concept-similarity.txt`에 쌍마다 같음/다름 · 이름 둘 · 유사도, 같은 쌍 최솟값 · 다른 쌍 최댓값 · 그 사이에 걸치는 쌍을 쓴다. 기준값은 단언하지 않고 사람이 보고 정한다. 이 step에서는 유료 호출 없이 컴파일과 키 없는/공백 키 건너뛰기만 확인했다.
- 실제 OpenAI 시험 `OpenAiLiveIntegrationTest`는 기본 `test`에서 제외한다. `./backend/gradlew -p backend llmTest`만 실행하며 `OPENAI_API_KEY` 환경 변수가 없거나 공백이면 건너뛴다. 짧은 요청 한 번의 끝남 · 사용량 · 호출 기록을 본다. 유료 호출이며 step 1에서는 실행하지 않았다.

- 생성은 서버가 정한 순서대로 부르므로(ADR-118) 스텁으로 흐름 전체를 시험한다 — 실제 LLM 은 부르지 않는다.
- 흉내 낼 것 — 정상 응답 · 일시적 실패 · 형식 불합격 · 잘린 출력(이어 쓰기) · 다시 해도 같은 실패(ADR-127).
    - 만드는 작업 TASK 는 테스트 전용 처리기로 정상 · 일시적 실패 · 형식 불합격 · 다시 해도 같은 실패(거절 · 입력이 너무 큼) · 오래 걸림(시한 · 정상 종료 시험)을 흉내 낸다. 잘린 출력(이어 쓰기)은 LLM 호출 안의 일이라, 그 호출을 만드는 강의 목록 TASK 가 LLM 스텁으로 흉내 낸다(README 2026-11-15).
- `7-course-planning` step 3부터 공용 `support.IntegrationTestConfiguration`은 테스트 받기 · 생성 처리기를 빈으로 올리지 않는다. 공용 컨텍스트는 운영 처리기를 그대로 쓴다. 별도 「처리기 없는 구성」 묶음과 `test.job-intake-handlers` 속성은 제거했다. `8-material-cards` step 3에서 여섯 종류 모두 운영 처리기가 생겨 `JobControllerNoHandlersIntegrationTest`를 제거했다. 처리기 없는 종류를 거절하는 틀의 규칙은 `JobIntakeServiceTest`에서 계속 확인한다.
- 운영 생성 흐름에서는 공용 설정의 중첩 static `TestChatProvider`를 `@Primary` 빈으로 등록해 `ChatProvider` 경계만 바꾼다. 실제 `ChatCall` · 이어 쓰기 · 형식 검사 · 호출 기록 · 생성 처리기 · 저장 · `JobRunner.runOnce()`가 돈다.
    - `TestChatProvider`를 주입받아 `reset()`으로 설정과 관찰을 비운 뒤 쓴다. `reply(jobId, Reply...)`는 `Request.jobId`별 응답을 순서대로 소모하며, 끝나면 종류(`Request.jobType`)에 맞는 정상 기본 응답을 준다. `defaultText(type)`는 새 코스의 두 강의 또는 새 로드맵의 두 코스 JSON이다. step 4의 `PLAN_ROADMAP_COURSE`와 step 5의 `REPLAN_COURSE`는 제목 없이 두 강의 JSON을 주며, `REPLAN_ROADMAP`은 새 로드맵과 같은 형식의 두 코스 JSON을 준다. `8-material-cards` step 3의 `defaultText(MATERIAL)`은 마크다운 본문 뒤 `<!-- MOGUM_CARDS -->` 한 줄과 카드 JSON 객체이며, 카드 세 장에 변수/함수 개념이 겹쳐 들어 있다.
    - `success(text)` · `failure(GenerationOutcome.Failure)`로 정상 · 형식 불합격 원문 · 일시적 실패 · 거절 · 입력 초과를 정한다. 잘림은 `Reply(앞부분, TRUNCATED, null, usage)` 다음에 `success(뒷부분)`을 넣는다. `requests(jobId)`로 시도 · 이어 쓰기의 작업 문맥과 메시지를, `outsideTransaction(jobId)`로 트랜잭션과 바인딩된 EntityManager 부재를 본다. 응답 시간 확률 분포는 두지 않는다.
- 틀 시험은 빈 대신 `new IntegrationTestConfiguration.FrameworkJobs(applicationContext)`를 직접 구성한다. `jobIntakeService`는 여섯 `TestJobIntakeHandler`를 받은 실제 서비스에 애노테이션 기반 트랜잭션 프록시를 직접 씌운 객체다. `generators`는 여섯 `TestJobGenerator` 객체 목록이며, 직접 만드는 `JobRunner` · `WorkerLifecycle`에 넘긴다. 클래스별 `@Import`는 더하지 않는다. 받기 · 워커 · 수명 시험은 운영 처리기가 늘어도 이 목록을 쓴다. 읽기 · HTTP 시험은 직접 저장한 작업 픽스처 또는 새로 배우기 운영 받기를 사용한다.
    - `TestJobGenerator.plan(jobId)`의 기본은 즉시 성공이다. `fail(GenerationOutcome.Failure, N)`은 앞 N번을 그 실패로 돌려준 뒤 성공한다. `reset()`으로 계획과 관찰을 비울 수 있고, 틀 시험은 테스트마다 새 객체를 만든다.
    - `holdFirst()`는 첫 호출을 `awaitEntered()`로 관찰하고 `release()`할 때까지 붙잡는다(미해제 시험은 15초에 끝낸다). 같은 작업의 두 번째 호출은 기다리지 않아 되찾은 새 워커와 늦은 옛 워커를 따로 끝낼 수 있다. 시한 지남은 `TestClock.advance`로 만들고, 갱신 예약의 실행만 짧은 간격과 Awaitility로 기다린다.
    - `calls()` · `saves()` · `snapshots()`로 호출을 확인한다. `outsideTransaction()`은 만들기 중 트랜잭션과 바인딩된 EntityManager 부재를, `mdc()`는 실행 중 키를 확인한다. `failSave(marker)`는 실제 결과 행을 만든 뒤 cause · suppressed에도 표식을 넣어 예외를 내며, `failSave(null)`로 해제한다. 결과 트랜잭션의 롤백과 안전한 스택 출력을 시험한다.
    - 스텁 `save`는 실제 코스 · 로드맵 · 강의 · 강의 자료를 만들거나 대상 상태를 바꾼다. 공용 수명 관리 빈은 워커 수 0으로 자동 실행을 끈다. `WorkerLifecycleIntegrationTest`는 직접 만든 수명 객체로 긴 시도 · 정상 종료 · 동시 실행을 제어한다.

## 3. 공개 전 품질 점검

**MVP 는 공개 전 품질 점검을 통과해야 연다**(ADR-066).

- 개발을 마친 뒤 마지막에, 실제로 만든 강의 · 카드 표본으로 한다.
- 스텁 · 형식 검사를 통과했다고 품질을 확인한 것이 아니다 — 형식은 서버가 검사하지만 내용 · 그림 · 수식은 검사하지 않는다(ADR-117 · ADR-120).
- 누가 어떻게 점검하나 · 기준 · 표본 수 · 통과선 · 채점 모델은 그 TASK 에서 정해 결정으로 남긴다(ADR-067 — 보류).

## 4. 용량

- 정원은 운영자가 승인할 때 지키는 10명이다(ADR-061) — 용량을 재서 정원을 정하지 않는다.
- 서비스 전체에서 동시에 만드는 수는 워커 수로 묶인다(ADR-105 · ADR-133). `6-job-worker` step 5에서 서버마다 워커 수를 임시 값으로 정했다([04 §10](./04-jobs-and-cost.md#10-아직-정하지-않은-값)). `WorkerLifecycleIntegrationTest`에서 설정한 수의 플랫폼 스레드가 서로 다른 작업을 동시에 처리하고, 기본 컨텍스트는 자동으로 집지 않는 것을 검증한다. 실제 운영 수는 용량을 재 보고 정한다.

## 5. 아직 정하지 않은 값

| 것 | 근거 |
|---|---|
| 만드는 작업의 스텁 직접 구성 · 수동 대기와 LLM 어댑터 스텁의 작업별 응답 · 이어 쓰기는 §2에 구현했다. 응답 시간 확률 분포는 두지 않는다 | ADR-118 |
| 품질 점검의 방식 · 기준 · 표본 수 · 통과선 | ADR-067 |
| 서버마다 워커 수는 `6-job-worker` step 5에서 임시 값으로 정했다(설정 · 값은 [04 §10](./04-jobs-and-cost.md#10-아직-정하지-않은-값)). 실제 운영 수는 용량을 재 보고 정한다 | ADR-105 |
