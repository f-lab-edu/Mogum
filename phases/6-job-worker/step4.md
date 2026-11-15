# Step 4: worker-run

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/04-jobs-and-cost.md` §2.2(패키지 경계) · §4(만드는 작업 — **이 step 의 계약이다**: 흐름 · 상태 · 꺼내기 · 순서 · 시한 · 되찾기 · 실패 · 다시 시도 · 지울 때) · §5 · §7(실패하면 복구) · §9.1(로그) · §10
- `docs/02-domain-data.md` §8.2(복구 조건) · §8.5(대상 · 결과 칸 · `NEW_*` 는 성공할 때만 대상) · §8.7(`generation_jobs.status` · `error_type`)
- `docs/07-testing.md` §1(동시에 꺼내기 · 시한 · 되찾기 · 늦게 끝난 옛 워커 · 지운 뒤의 늦은 결과 · 결과는 성공할 때 · 크레딧) · §2(LLM 스텁 — 이 TASK 가 흉내 낼 것)
- `docs/ADR.md` — ADR-103 · ADR-118 · ADR-125 · ADR-126 · ADR-127 · ADR-128 · ADR-129 · ADR-130 · ADR-133 · ADR-163 · ADR-166
- `docs/README.md` 「정한 것」 2026-11-15 세 줄
- `docs/TEST_GUIDE.md` 전체 — 특히 §1 · §5
- `AGENTS.md` — 금지 1 · 3 · 4 · 6 · 8 · 9 · 10 · 12 · 22 · 26 · 28
- step 0 ~ 3 이 만든 것: `backend/src/main/java/com/mogum/job/` 전체 · `account/service/`(크레딧 `restoreIfOwed`) · `common/logging/`(MDC `jobId` 상수) · 테스트 전용 처리기를 둔 공용 테스트 설정(step 2 summary) · `GenerationJobFixtures`
- `backend/src/main/java/com/mogum/worker/package-info.java` · `generation/package-info.java` · `config/` · `backend/src/test/java/com/mogum/ArchitectureTest.java`(패키지 · `@Transactional` 규칙) · `backend/src/test/java/com/mogum/archfixture/`
- 대상 엔티티: `backend/src/main/java/com/mogum/course/domain/` · `course/repository/`
- 로그 본보기: `backend/src/main/java/com/mogum/common/logging/RequestLogFilter.java` · `backend/src/test/java/com/mogum/auth/OAuthLoginHandlerIntegrationTest.java`(`OutputCaptureExtension`)
- `backend/src/test/java/com/mogum/support/TestClock.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 step 은 **워커가 작업 하나를 처리하는 한 바퀴**를 만든다 — 꺼내기 · 시한 늘리기 · 만들기 · 자동 다시 시도 · 결과 저장 · 실패와 복구. 이 한 바퀴를 스레드로 돌리고 서버 시작 · 종료에 맞추는 것은 step 5 다. 이 step 의 한 바퀴는 테스트가 직접 부를 수 있어야 한다(예: `boolean runOnce()` — 집은 작업이 있었는지).

만들기 · 결과 저장은 종류마다 다르다 — 이 phase 에서는 **인터페이스만 두고 운영 구현을 만들지 않는다**(README 2026-11-15). 실제 LLM 호출 · 호출 기록 · 이어 쓰기는 강의 목록 TASK 가 이 인터페이스 뒤에 붙인다. 시험은 테스트 전용 스텁으로 한다.

**계약 문서는 이 phase 전에 정해 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 만들기 인터페이스 — `com.mogum.generation`

유료 호출을 하는 쪽이라 `generation` 에 둔다 — `worker` · `config` 만 참조한다(금지 1). 이름 · 모양은 예시다.

```java
public interface JobGenerator {
    JobType type();
    /** 트랜잭션 밖에서 부른다 — 유료 호출이 여기 들어온다. 엔티티가 아니라 값(record)만 받는다(금지 4). */
    GenerationOutcome generate(JobSnapshot job);
    /** 결과 저장 트랜잭션 안에서, 아직 내 작업인지 확인한 뒤에 부른다 — 결과 생성 · 입력 옮겨 담기 · 상태 바꾸기. NEW_* 는 만든 결과의 ID 를 돌려준다. */
    ResultRef save(JobSnapshot job, GeneratedResult result);
}
```

- `GenerationOutcome` 은 성공(결과 값) · 일시적 실패 · 형식 검사 불합격 · 거절 · 입력이 너무 큼을 가른다(04 §4.6 · ADR-163). `error_type` 은 이 갈래로 정한다 — 일시적 `PROVIDER_UNAVAILABLE` · 형식 `INVALID_OUTPUT` · 거절 `PROVIDER_REJECTED` · 너무 큼 `INPUT_TOO_LARGE`(02 §8.7).
- 잘린 출력 · 이어 쓰기는 `generate` 안의 일이다 — 이 step 은 갈래로 두지 않는다(07 §2 · README 2026-11-15).
- `JobSnapshot` 은 작업 ID · 사용자 ID · 종류 · 대상 ID · 입력 칸 묶음 · 피드백 · 이번 `claim_count` 를 담는 record 다.

### 2. 한 바퀴 — `com.mogum.worker`

`worker` 는 `@Transactional` 을 쓰지 않고 `TransactionTemplate` 으로 경계를 드러낸다(금지 3 — ArchUnit 이 막는다). 만드는 동안 DB 커넥션을 쥐지 않는다.

1. **꺼내기**(금지 8 · ADR-129) — 한 트랜잭션 · 한 번에: 차례를 기다리는 작업, 또는 **시한이 지난 `RUNNING`** 가운데 받은 순서(`created_at` — 같으면 정한 순서)가 가장 앞선 하나를 잠그고(`FOR UPDATE SKIP LOCKED`) `RUNNING` · `claim_count + 1` · `lease_until` · `started_at` 으로 바꾼다. 없으면 빈손으로 끝난다. 네이티브 SQL 이면 `updated_at` 을 직접 쓴다(금지 28).
2. **시한 늘리기**(ADR-126) — 만드는 동안 정한 간격으로 `lease_until` 을 늘린다. 쓰기는 **같은 `claim_count` · `RUNNING`** 을 조건에 넣는다(금지 9). `0 rows` 면 시한을 잃은 것이다 — 그 뒤 이 시도는 아무것도 쓰지 않는다.
3. **만들기** — 그 종류의 `JobGenerator.generate` 를 트랜잭션 밖에서 부른다.
4. **자동 다시 시도**(금지 12 · ADR-127) — 일시적 · 형식 불합격만 정한 횟수까지 정한 간격을 두고 처음부터 다시 `generate` 한다. 같은 꺼내기 안에서 하고 `claim_count` 는 늘리지 않는다. 거절 · 너무 큼은 다시 하지 않는다.
5. **결과 저장**(금지 9 · 04 §4.1) — 한 트랜잭션에서 **첫 쓰기로 아직 내 작업인지(같은 `claim_count` · `RUNNING`) 확인한다.** `0 rows` 면 아무것도 쓰지 않고 멈춘다(결과 · 상태 · 크레딧 모두). 맞으면 `JobGenerator.save` → 작업 `SUCCEEDED` · `finished_at` · `lease_until` 비움 · `NEW_*` 는 결과 칸(02 §8.5 의 `CHECK`)을 함께 한다. 크레딧은 그대로 둔다(성공은 기록하지 않는다 — 02 §8.2).
6. **실패로 끝내기** — 횟수를 넘겼거나 다시 하지 않는 실패면 한 트랜잭션에서 첫 쓰기로 같은 확인을 하며 `FAILED` · `error_type` · `finished_at` · `lease_until` 비움으로 바꾸고, 같은 트랜잭션에서 step 1 의 `restoreIfOwed` 로 크레딧을 돌려준다(ADR-166). `0 rows` 면 쓰지도 돌려주지도 않는다 — **작업이 지워졌으면 복구는 지우는 쪽이 한다**(04 §4.7 · README 2026-11-15).
7. **결과 저장이 예외로 실패하면** 그 작업은 그대로 둔다 — 시한이 지나면 되찾아져 처음부터 다시 만든다(04 §4.5). 실패로 바꾸지 않는다.

- **시각** — 시한의 계산과 「지났는가」 비교는 같은 시계로 한다. 테스트가 `sleep` 없이 공용 `TestClock` 을 움직여 시한 지남을 만들 수 있게 한다.
- **값** — 시한 · 늘리는 간격 · 자동 다시 시도 횟수 · 간격은 설정 한 곳의 **임시 값**이다. 테스트는 간격을 0 에 가깝게 둔다. 시한이 「한 시도가 걸릴 수 있는 가장 긴 시간」보다 길어야 한다는 식(금지 10)은 step 5 가 04 §10 에 적는다 — 이 step 은 값을 한 곳에 모으기만 한다.
- **메모리 공유 상태 없음**(금지 22) — 시도 하나 안의 지역 상태(시한을 잃었는가)는 괜찮다. 워커끼리 · 요청과 나누는 카운터 · 캐시 · 잠금은 두지 않는다.

### 3. 로그 줄

처리하는 동안 MDC 에 `jobId` · `userId` 를 넣고, 한 바퀴가 끝나면 지운다(금지 26 — 스레드를 다시 쓴다).

| 수준 | 언제 | 줄 |
|---|---|---|
| `INFO` | 작업을 끝냈을 때 | `job finished status={SUCCEEDED\|FAILED}` |
| `WARN` | 시한을 잃었을 때 — 늘리기 · 저장 · 실패 기록의 확인이 `0 rows` (한 시도에 한 번) | `job lease lost` |
| `ERROR` | 결과 저장 트랜잭션이 예외로 실패했을 때 — 스택과 함께 | `job save failed` |

- 줄에 입력 · 결과 원문 · 예외 메시지 원문을 싣지 않는다(금지 26). 스택의 예외 메시지에 입력이 섞일 수 있으면 확인한다.
- 외부 호출 실패의 `WARN` 은 실제 호출을 만드는 강의 목록 TASK 가 더한다 — 이 step 은 남기지 않는다.
- 정한 줄을 04 §9.1 표 아래에 적는다(테스트가 실제로 찍은 줄을 옮긴다).

### 4. 테스트 전용 스텁

- 테스트 전용 `JobGenerator` 를 step 2 의 테스트 처리기와 같은 공용 테스트 설정의 중첩 static 클래스로 둔다(`/harness` 원칙 22). 테스트가 작업마다 갈래를 정할 수 있게 한다 — 성공 · 일시적 실패 N 번 뒤 성공 · 형식 불합격 · 거절 · 너무 큼 · **오래 걸림**(테스트가 풀어 줄 때까지 기다림 — 시한 · 되찾기 · step 5 의 종료 시험이 쓴다). 몇 번 불렸는지 셀 수 있게 한다.
- 스텁의 `save` 는 실제 결과 행을 만든다 — 예: `NEW_COURSE` 면 `Course.single`, `MATERIAL` 이면 `Material.write`. 「결과는 성공할 때」를 행으로 확인하기 위해서다.
- 테스트 처리기 · 스텁은 운영 코드에 두지 마라.

### 5. 테스트(07 §1 — 실제 PostgreSQL, `@IntegrationTest`)

- **동시에 꺼내기** — 기다리는 작업 여럿을 워커 여럿(스레드)이 동시에 `runOnce` 해도 작업마다 한 번만 집는다(`claim_count` 1 · 스텁 호출 수).
- **순서** — 먼저 받은 작업부터 집는다.
- **시한 · 되찾기** — 시한이 지난 `RUNNING` 을 다른 워커가 집어(`claim_count` 2) 처음부터 만든다.
- **시한 늘리기** — 오래 걸리는 시도도 늘리는 동안은 다른 워커가 집지 못한다.
- **늦게 끝난 옛 워커** — 옛 워커가 오래 걸리는 동안 시한을 지나게 하고 새 워커가 되찾아 끝낸 뒤, 옛 워커를 풀어 주면 결과가 하나뿐이고 옛 워커는 아무것도 쓰지 않으며 `job lease lost` 가 찍힌다.
- **지운 뒤의 늦은 결과** — 만드는 동안 테스트가 작업 행을 지우면(지우기 · 탈퇴 TASK 대신 — README 2026-11-15) 워커가 결과를 만들지 않고 크레딧을 돌려주지 않으며 예외로 죽지 않는다.
- **결과는 성공할 때** — 실패하면 결과 행(코스 · 강의 자료)이 없다. 성공하면 하나이고 `NEW_*` 작업이 그 결과를 가리킨다.
- **자동 다시 시도** — 일시적 실패가 횟수 안이면 성공(꺼내기 한 번) · 넘으면 `FAILED` + `PROVIDER_UNAVAILABLE` · 형식 불합격도 같다(`INVALID_OUTPUT`) · 거절과 너무 큼은 스텁이 한 번만 불리고 바로 `FAILED`.
- **크레딧** — 자동 실패는 한 번 돌려준다 · 성공은 그대로 · 되찾아도 다시 쓰지 않는다 · 잔액 = 기록의 합.
- **로그** — 세 줄이 정한 때 찍히고 `jobId` 가 붙는다 · 한 바퀴 뒤 MDC 가 비어 있다 · 입력에 넣은 표식 문자열이 출력에 없다.
- `ArchitectureTest` 가 통과한다 — `job` 이 `generation` 을, `worker` 밖이 `worker` 를 참조하지 않는다.

### 6. 문서 — 구현으로 정해진 사실만

- `docs/04-jobs-and-cost.md` §2.3 — `generation` 의 만들기 인터페이스 · `worker` 의 한 바퀴. §9.1 — 로그 줄. §10 — 꺼낼 때 쓰는 DB 기능 · 자동 다시 시도 횟수 · 간격의 설정 이름과 임시 값(시한 식은 step 5).
- `docs/07-testing.md` §2 · §5 — 스텁의 주입 방법(테스트 설정 · 갈래를 정하는 법).
- `backend/src/main/java/com/mogum/worker/package-info.java` · `generation/package-info.java` 의 옛 설명(「수령 루프 · 단계 실행」 · `GuardedLlmClient` 등)을 지금 하는 일로 고친다(ADR-146).

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.worker.*' --tests 'com.mogum.generation.*' --tests 'com.mogum.job.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 꺼내기가 한 문장 · 한 트랜잭션에서 잠그고 바꾸며 `SKIP LOCKED` 인가? 시한이 지난 `RUNNING` 도 집는가?
   - 늘리기 · 저장 · 실패의 쓰기가 모두 같은 `claim_count` · `RUNNING` 조건이고, 저장은 그 확인이 첫 쓰기인가? `0 rows` 면 아무것도 쓰지 않는가?
   - `generate` 동안 트랜잭션 · 커넥션을 쥐지 않는가? `worker` · `generation` 에 `@Transactional` 이 없는가?
   - 자동 다시 시도가 일시적 · 형식 불합격만인가?
   - 운영 코드에 `JobGenerator` 구현이 없는가?
   - 로그 줄이 표의 세 줄뿐이고 원문이 없는가? MDC 를 지우는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/6-job-worker/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 만들기 인터페이스 · `GenerationOutcome` 갈래 · 한 바퀴의 진입점 시그니처 · 꺼내기 SQL 의 요지 · 설정 이름과 임시 값 · 스텁을 둔 곳과 갈래 정하는 법(step 5 가 쓴다) · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 실제 LLM · 임베딩 호출 · 프로바이더 SDK · 호출 기록(`llm_calls`) · 이어 쓰기를 만들지 마라. 이유: SCHEDULE §3 상 강의 목록 TASK 에서 만든다.
- 종류별 `JobGenerator` 의 운영 구현을 만들지 마라. 이유: 강의 목록 · 강의 자료 TASK 가 채운다(README 2026-11-15).
- 워커 스레드를 띄우거나 서버 시작 · 종료에 묶지 마라. 이유: step 5 의 범위다 — 이 step 의 한 바퀴는 테스트가 직접 부른다.
- 꺼낸 횟수(`claim_count`)에 상한을 두지 마라. 이유: 처리 중에 서버를 죽이는 작업은 막지 않기로 했다(04 §4.5 · README 2026-11-15).
- 지운 작업의 크레딧을 워커가 돌려주지 마라. 이유: 지울 때의 복구는 지우는 쪽(지우기 · 탈퇴 TASK)이 한다(02 §8.6).
- SDK · 라이브러리의 자동 재시도에 기대지 마라. 이유: 다시 시도는 우리 코드가 정한 횟수로만 한다(금지 12).
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다.
- 계약 문서(04 · 02 의 규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
