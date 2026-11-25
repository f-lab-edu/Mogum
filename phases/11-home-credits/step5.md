# Step 5: home-jobs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/06-screens.md` §4(홈 — **만드는 중 · 실패** 칸) · §6(기다리는 화면 — 홈에서 다시 연다)
- `docs/05-api.md` §1.3 · §1.4 · **§2(만드는 작업 — `JobResponse` 의 `subject` · `error`)** · §3(「홈의 칸마다」 — 「만드는 중(내 작업 가운데 성공하지 않은 것)」)
- `docs/04-jobs-and-cost.md` §4.2(작업 상태)
- `docs/ADR.md` — ADR-006 · ADR-086 · ADR-089 · ADR-144
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 7 · 18 · 20 · 25
- step 3 · 4 가 만든 것(summary 참고) — 홈 칸 읽기의 경로 · 이름 짓는 방식을 따른다
- `backend/src/main/java/com/mogum/job/` — `service/JobReadService.java` · `dto/JobResponse.java` · `controller/JobController.java` · `repository/GenerationJobRepository.java`
- `backend/src/test/java/com/mogum/job/` — `JobReadService` · `JobController` 의 기존 시험

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

홈의 넷째 칸 — **만드는 중 · 실패** — 은 내 작업 가운데 성공하지 않은 것(기다림 · 만드는 중 · 실패)이다(05 §3). 누르면 기다리는 화면(`/jobs/{id}`)이 열린다 — 떠나도 여기서 다시 연다(06 §4).

**계약 문서는 이 phase 전에 정해 커밋했다(05 §2 · §3 · 06 §4). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

- 로그인한 사람의 「성공하지 않은 작업」 목록 읽기를 만든다 — 상태 `QUEUED` · `RUNNING` · `FAILED`. 경로는 재량이다(예: `GET /api/jobs?unfinished` · 홈 칸 경로 — step 3 · 4 와 맞춘다). 이유를 summary 에 적는다.
- **항목의 모양은 기존 「작업 하나 읽기」의 `JobResponse` 를 그대로 쓴다** — `id` · `type` · `status` · `subject` · 실패면 `error`. 새 모양을 만들지 마라. 같은 변환 코드를 함께 써서 `subject`(새로 배우기의 입력 요약 또는 대상의 현재 제목)와 실패 `error`(두 묶음 code · 문구 — 05 §1.2)가 작업 하나 읽기와 같게 한다.
  - 목록을 만들 때 작업마다 따로 쿼리를 여러 번 돌리는 N+1 은 정원 10명 · 한 사람 동시 3개 상한이라 감수해도 된다 — 단순한 쪽을 고른다.
- 순서 — 최근에 받은 것이 먼저(`created_at DESC, id DESC`). 나눠 받지 않는다.
- 그 사람의 작업만 읽는다(금지 18). 다른 사람의 활동 · 대기 순서 · 횟수 · 시한 · 시작/끝 시각 · 실패 까닭 원문을 싣지 마라(05 §1.4 · §2 · 금지 20).
- 읽기는 아무것도 바꾸지 않는다(상태 · 시각 · 크레딧).

### 시험

- 기다림 · 만드는 중 · 실패는 들어가고 성공은 빠진다.
- 남의 작업은 빠진다.
- 순서가 최근 먼저다.
- 항목이 같은 작업의 「작업 하나 읽기」 응답과 같다(`subject` · 실패 `error` 포함) — 두 응답을 비교한다.
- 비면 빈 목록이다.
- 컨트롤러(`@WebMvcTest`) — 응답 모양 · 시스템 정보 칸이 없다.
- 읽기가 상태 · 시각 · 크레딧을 바꾸지 않는다.

### 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §3 「홈의 칸마다」 구현 줄에 이 읽기(경로 · 모양 · 순서)를 더한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.job.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 항목이 `JobResponse` 와 같은 변환으로 만들어지는가? 성공 작업이 빠지는가?
   - 남의 작업 · 시스템 정보가 없는가(금지 18 · 20)?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/11-home-credits/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 경로 · 응답 모양(목록 칸 이름) · 순서(step 6 화면이 쓴다) · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 홈에서 다시 시도 · 버리기를 할 수 있게 새 동작을 더하지 마라. 이유: 06 §4 — 누르면 기다리는 화면으로 가고, 거기에 다시 시도 · 버리기가 있다(06 §6).
- 작업 상태 · 횟수를 숫자로 요약한 칸(만드는 중 n개 등)을 더하지 마라. 이유: 화면이 목록 길이로 안다 — 쓰이지 않는 칸이다.
- 화면(`frontend/`)을 고치지 마라. 이유: step 6 의 범위다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 접근 줄이 요청을 남긴다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
