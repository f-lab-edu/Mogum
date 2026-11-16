# Step 2: job-intake

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/04-jobs-and-cost.md` §4.1 · §4.2 · §4.6(실패 · 다시 시도 · 버리기) · §5 · §6(한 사람이 건 수 — **이 step 의 계약이다**) · §7 · §9.1(로그) · §10
- `docs/02-domain-data.md` §8.2(복구 조건) · §8.5(대상 칸 · 같은 대상 하나) · §8.7(`error_type` 과 화면의 묶음)
- `docs/05-api.md` §1.2(오류 묶음 — 「만드는 중인 것이 많음」) · §1.3(남의 것은 없는 것으로) · §2(받기 · 다시 시도 · 버리기)
- `docs/06-screens.md` §6(버리면 만들기 전으로)
- `docs/07-testing.md` §1 — 「한 사람이 건 수」 · 「크레딧」 · 「같은 받기 두 번」 · 「남의 것」
- `docs/ADR.md` — ADR-086 · ADR-104 · ADR-112 · ADR-133 · ADR-143 · ADR-157 · ADR-166 · ADR-174
- `docs/README.md` 「정한 것」 2026-11-15 세 줄 — 처리기 자리만 둔다
- `docs/TEST_GUIDE.md` 전체 — 특히 §1 · §4 · §5
- `AGENTS.md` — 금지 1 · 3 · 11 · 18 · 22 · 26 · 27 · 28
- step 0 · 1 이 만든 것: `backend/src/main/java/com/mogum/job/` · `account/service/`(step 1 의 크레딧 서비스) · `config/CreditProperties.java` · `backend/src/test/java/com/mogum/job/domain/GenerationJobFixtures.java`
- 대상 엔티티 · 레포지토리: `backend/src/main/java/com/mogum/course/domain/Course.java` · `Roadmap.java` · `Lesson.java` · `course/repository/`
- 로그 본보기: `backend/src/main/java/com/mogum/common/logging/RequestLogFilter.java`(MDC 키 상수) · `auth/OAuthLoginHandler.java` · 그 테스트 `backend/src/test/java/com/mogum/auth/OAuthLoginHandlerIntegrationTest.java`(`OutputCaptureExtension`)
- `backend/src/test/java/com/mogum/ArchitectureTest.java` — 패키지 규칙

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 step 은 `job` 패키지의 **받기 · 다시 시도 · 버리기** 서비스를 만든다 — 크레딧이 움직이는 세 동작이다. HTTP 는 step 3, 워커는 step 4 다.

종류마다 다른 일 가운데 **받을 때의 확인**(대상이 지금 받을 수 있는 상태인지)은 요청 경로라 `job` 쪽 처리기 인터페이스로 둔다. 이 phase 에서는 인터페이스만 두고 운영 구현은 만들지 않는다 — 강의 목록 · 강의 자료 TASK 가 채운다(README 2026-11-15). 그래서 **운영에서는 어떤 종류도 받지 않는다.** 시험은 테스트 전용 처리기로 한다.

**계약 문서는 이 phase 전에 정해 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 처리기 인터페이스 — `com.mogum.job`

이름 · 모양은 예시다. 지킬 것은 아래 규칙이다.

```java
public interface JobIntakeHandler {
    JobType type();
    /** 대상이 지금 이 만들기를 받을 수 있는지 확인한다. 받을 수 없으면 ApiException. 대상의 주인 확인은 틀이 먼저 한다. */
    void check(long userId, JobRequest request);
}
```

- `JobRequest` 는 받기 요청의 값(종류 · 대상 ID · 입력 칸 묶음 · 피드백)을 담는 record 다. step 3 의 요청 DTO 가 이것으로 바뀐다.
- 처리기는 빈으로 모아 종류로 찾는다. **처리기가 없는 종류는 받지 않는다** — 기존 code 가운데 맞는 것(예: `INVALID_REQUEST`)으로 거절한다. 이 거절은 뒤 TASK 가 처리기를 더하면 사라지므로 새 오류 code 를 만들지 마라.
- 이 인터페이스는 `generation` · `worker` 를 참조하지 않는다(금지 1). 만들기 · 결과 저장은 step 4 가 워커 쪽에 둔다.

### 2. 받기 — `job.service`

한 트랜잭션(`@Transactional` — 일반 서비스)에서 이 순서로 한다.

1. step 1 의 계정 서비스로 **사용자 행을 잠근다** — 같은 사람의 요청이 한 줄로 선다. 서버가 여러 대여도 DB 잠금이라 같다(금지 11 · 22).
2. **대상의 주인을 확인한다** — 02 §8.5 의 대상 칸(코스 · 로드맵 · 강의 — 강의는 그 코스의 주인)이 이 사람의 것이 아니거나 없으면 `NOT_FOUND`(금지 18). 처리기를 부르기 전에 틀이 한다.
3. 그 종류의 처리기로 `check` 한다.
4. **같은 대상의 작업이 이미 있으면 그 작업을 그대로 돌려준다** — 크레딧을 쓰지 않고 건 수도 세지 않는다(ADR-157). 「같은 대상」은 02 §8.5 의 규칙이다 — `MATERIAL` · `PLAN_ROADMAP_COURSE` 는 상태와 상관없이, `REPLAN_*` 는 성공하지 않은 것. step 0 의 DB 막기는 같은 사람의 요청이 잠금으로 한 줄로 서므로 마지막 방어선이다.
5. **한 사람이 건 수를 센다** — `QUEUED` · `RUNNING` 을 모든 종류 함께(04 §6). 상한 이상이면 새 오류 code(예: `TOO_MANY_IN_PROGRESS`)로 거절한다 — 묶음은 05 §1.2 의 「만드는 중인 것이 많음」. 상한은 설정 한 곳의 **임시 값**이다(04 §10 의 예: 2~3).
6. 작업을 적고(step 0 의 팩토리) → 종류의 크레딧 값(step 1 의 설정)만큼 쓴다. 모자라면 step 1 의 「크레딧 부족」이 나가고 트랜잭션 전체가 롤백된다 — 작업 행도 남지 않는다.
7. 돌려주는 값은 작업 ID 와 상태 정도의 record 다(step 3 이 응답을 만든다). 엔티티를 컨트롤러로 넘기지 마라.

- 입력 칸 묶음의 길이 · 빈 글 검사는 step 3 의 `@Valid` 와 DB 가 한다. 서비스에서 다시 검사하지 마라.

### 3. 다시 시도 · 버리기 — `job.service`

둘 다 사용자 행을 먼저 잠근다 — 같은 작업에 다시 시도와 버리기가 함께 와도 한 줄로 선다.

- **다시 시도**(ADR-086 · ADR-174)
    - 작업이 없거나 남의 것이면 `NOT_FOUND`.
    - `FAILED` 가 아니면 **아무것도 바꾸지 않고 지금 작업을 돌려준다** — 연타한 두 번째 다시 시도가 크레딧을 또 쓰지 않는다.
    - `error_type = INPUT_TOO_LARGE` 면 거절한다 — 다시 해도 같다. code 는 정해서 05 에 적는다.
    - 한 사람이 건 수를 받기와 같이 센다.
    - `FAILED → QUEUED` 로 바꾸고(`error_type` · `finished_at` 을 비운다 — step 0 의 `CHECK` 를 따른다) 크레딧을 다시 쓴다(02 §8.7 `CONSUME` — 다시 시도할 때). 받은 순서(`created_at`)는 바꾸지 않는다.
- **버리기**(ADR-086)
    - 작업이 없거나 남의 것이면 `NOT_FOUND`.
    - `FAILED` 가 아니면 지우지 않고 거절한다 — 만드는 중 취소는 두지 않는다(06 §6). code 는 정해서 05 에 적는다.
    - step 1 의 `restoreIfOwed` 를 부른 뒤 작업 행을 지운다. 실패할 때 워커가 이미 돌려줬으면 조건이 막아 두 번 돌려주지 않는다(02 §8.2 — 버리기도 복구의 때다). 크레딧 기록의 `job_id` 는 `ON DELETE SET NULL` 로 비워진다.
- JPA 를 거치지 않는 갱신을 쓰면 `updated_at` 을 SQL 에 직접 쓴다(금지 28).

### 4. 로그 줄

- MDC 키 `jobId` 를 `common.logging` 의 상수로 둔다(04 §9.1). 넣은 곳에서 지운다(금지 26).
- `INFO` — 새 작업을 적었을 때 `job received type={type}`, 다시 시도로 기다림에 돌렸을 때 `job retried`. MDC 에 `jobId`(요청의 `requestId` · `userId` 는 필터가 넣는다). 이미 있는 작업을 돌려줄 때 · 거절할 때는 남기지 않는다.
- 줄에 입력(주제 · 메모 · 피드백)을 싣지 않는다(금지 26).
- 정한 줄을 04 §9.1 표 아래에 적는다(테스트가 실제로 찍은 줄을 옮긴다 — 키 순서는 정해지지 않았다).

### 5. 테스트 전용 처리기

- 시험용 `JobIntakeHandler` 는 운영 코드에 두지 않는다. 공용 테스트 설정(`@TestConfiguration`)의 중첩 static 클래스로 둔다(TEST_GUIDE §5 · `/harness` 원칙 22 — 클래스마다 `@Import` 하면 컨텍스트가 클래스마다 새로 뜬다). 뒤 step(워커)의 테스트 처리기도 같은 곳에 둘 수 있게 둔다.
- 받을 수 없는 상태를 흉내 내는 갈래(예: 정해 둔 값이면 거절)를 하나 둔다 — 거절할 때 작업 · 크레딧이 그대로인지 본다.

### 6. 테스트

- **서비스 단위**(Fake) — 처리기가 없는 종류는 거절 · 주인 아님 · 같은 대상이면 이미 있는 것 · 상한 · 크레딧 부족 · 다시 시도의 갈래(실패 아님 · 입력이 너무 큼 · 상한) · 버리기의 갈래.
- **통합**(`@IntegrationTest` — 잠금 · 고유 · 롤백이 DB 동작이라 TEST_GUIDE §1)
    - **한 사람이 건 수** — 상한 바로 아래에서 같은 사람의 두 받기가 동시에 와도 하나만 받는다(07 §1).
    - **같은 받기 두 번** — 같은 강의 · 같은 코스에 받기가 동시에 두 번 와도 작업 하나 · 쓴 크레딧 하나이고, 둘 다 같은 작업 ID 를 받는다(07 §1).
    - **크레딧** — 받으면 그 종류의 값만큼 줄고 기록이 하나다 · 모자라면 작업이 남지 않는다 · 다시 시도는 다시 쓴다 · 버리기는 이미 돌려준 작업을 다시 돌려주지 않는다 · 잔액 = 기록의 합.
    - **남의 것** — 남의 강의 · 코스에 받기, 남의 작업에 다시 시도 · 버리기는 `NOT_FOUND` 이고 아무것도 바뀌지 않는다.
    - 실패한 작업은 step 0 픽스처의 테스트 SQL 로 만든다(워커는 step 4).
- **로그** — `OutputCaptureExtension` 으로 두 줄이 찍히고 `jobId` 가 붙는지, 주제 · 피드백에 넣은 표식 문자열이 출력에 없는지 본다. 요청이 끝난 뒤 MDC 에 `jobId` 가 남지 않는지 본다.

### 7. 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §1.2 — 새 code(만드는 중인 것이 많음 · 다시 시도 거절 · 버리기 거절)의 상태 코드 · 문구.
- `docs/04-jobs-and-cost.md` §6 · §10 — 한 사람이 건 수의 설정 이름 · 임시 값 · 같은 순간의 두 요청을 막는 방법(사용자 행 잠금). §9.1 — 로그 줄. §2.3 — `job` 패키지의 처리기 인터페이스 · 서비스.
- `docs/02-domain-data.md` §9 — 같은 대상 막는 방법에 「받기는 사용자 행 잠금 뒤 이미 있는 작업을 돌려준다」를 step 0 이 적은 줄 옆에 더한다.
- `backend/src/main/java/com/mogum/job/package-info.java` 의 옛 설명(「잡 적재 유스케이스 …」)을 이 패키지가 하는 일로 고친다(ADR-146 — 옛 이름은 그 코드를 고치는 TASK 에서).

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.job.*' --tests 'com.mogum.account.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 받기 · 다시 시도 · 버리기가 사용자 행을 먼저 잠그는가? 서버 메모리 잠금 · 카운터가 없는가(금지 22)?
   - 같은 대상이면 이미 있는 작업을 돌려주고 크레딧을 쓰지 않는가? 순서가 잠금 → 주인 → 처리기 → 같은 대상 → 건 수 → 적기 · 쓰기인가?
   - 운영 코드에 `JobIntakeHandler` 구현이 없는가? `job` 이 `generation` · `worker` 를 참조하지 않는가?
   - 로그 줄이 작업에 적은 두 줄뿐이고 입력이 없는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/6-job-worker/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 처리기 인터페이스와 `JobRequest` 시그니처 · 서비스 메서드 시그니처와 돌려주는 record · 새 오류 code · 설정 이름과 임시 값 · MDC 상수 이름 · 테스트 전용 처리기를 둔 곳과 쓰는 법(step 4 · 5 가 쓴다) · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 종류별 처리기의 운영 구현(새로 배우기 · 로드맵의 코스 · 다시 짜기 · 강의 자료의 받을 때 확인)을 만들지 마라. 이유: 강의 목록 · 강의 자료 TASK 가 채운다(README 2026-11-15).
- 컨트롤러 · 요청/응답 DTO · 작업 읽기를 만들지 마라. 이유: step 3 의 범위다.
- 워커 · 꺼내기 · 실패 처리를 만들지 마라. 이유: step 4 · 5 의 범위다.
- 대상이 없는 새로 배우기(`NEW_*`)를 두 번 눌렀을 때 막는 장치를 만들지 마라. 이유: 강의 목록 TASK 에서 정한다(README 2026-11-15).
- 만드는 중인 작업을 지우거나 멈추는 길(취소)을 만들지 마라. 이유: 06 §6 이 두지 않는다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(02 · 04 · 05 의 규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
