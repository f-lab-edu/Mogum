# Step 9: sonar-await-result

PR #29 의 SonarCloud 품질 게이트가 새 코드 신뢰성 B 로 실패했다. 원인은 한 건이다 — `java:S899`(「Do something with the "boolean" value returned by "await"」) · `backend/src/main/java/com/mogum/worker/WorkerLifecycle.java:94`. 이 한 건만 고친다.

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/04-jobs-and-cost.md` §2.3 의 `6-job-worker` step 5 구현 문단(워커 수명 · 정상 종료)
- `backend/src/main/java/com/mogum/worker/WorkerLifecycle.java`
- `backend/src/test/java/com/mogum/worker/WorkerLifecycleTest.java` · `backend/src/test/java/com/mogum/worker/WorkerLifecycleIntegrationTest.java`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

`WorkerLifecycle.Slot.poll()` 은 빈손일 때 `wakeup.await(pollInterval)` 로 쉰다. `wakeup` 은 `stop(...)` 이 `slot.stopping = true` 를 쓴 **뒤에만** `countDown()` 하는 래치라, `await` 가 `true` 를 돌려주면 멈추라는 신호다. 지금은 그 반환값을 버리고 다음 바퀴의 `while (!stopping)` 검사에 기댄다.

- `await` 의 반환값을 받아, `true` 면 그 자리에서 루프를 끝낸다. `false`(쉬는 간격이 지남)면 지금처럼 다음 바퀴로 간다.
- 동작은 바뀌지 않아야 한다 — 멈추라는 신호 뒤에는 새 작업을 꺼내지 않고, 간격이 지나면 다시 꺼낸다. 인터럽트 처리는 그대로다.
- 테스트: 기존 `WorkerLifecycleTest` · `WorkerLifecycleIntegrationTest` 가 그대로 통과해야 한다. 이미 「멈추라는 신호 뒤에는 꺼내지 않는다」를 보는 테스트가 있으면 그것으로 충분하다. 없으면 빈손으로 쉬는 중에 멈추면 더 꺼내지 않고 곧바로 끝나는지 보는 테스트를 `WorkerLifecycleTest` 에 먼저 더한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*WorkerLifecycle*'
./backend/gradlew -p backend build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가? 특히 금지 3(`worker` 에 `@Transactional` 금지) · 26(작업에 적지 않은 운영 로그 줄을 더하지 않는다).
3. 결과에 따라 `phases/6-job-worker/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `WorkerLifecycle.java` 의 `poll()` 밖을 고치지 마라. 이유: 게이트를 막은 것은 이 한 건이다.
- 같은 PR 분석의 다른 Sonar 지적(인지 복잡도 `S3776` · `S1192` · props 읽기 전용 · 테스트의 `Thread.sleep` 등)을 고치지 마라. 이유: 유지보수성이라 게이트와 무관하고, 코드 읽기 정리는 사용자가 별도 브랜치에서 하기로 했다.
- 새 로그 줄을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓴다(04 §9).
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
