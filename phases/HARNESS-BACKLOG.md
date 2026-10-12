# 하네스 백로그

> 개발하면서 찾은 **하네스(`scripts/` · `.claude/` · `.codex/`) 문제**와 **step 작성 교훈**을 모은다. 규칙은 `CLAUDE.md` 의 "하네스 개선".
> 실행을 감시할 때와 `/review` 의 하네스 회고에서 바로 추가한다. 고치는 일은 사용자 확인 뒤 `main` 에서 딴 별도 브랜치에서 테스트부터 쓴다.
> 상태 — **할 일** · **관찰**(트리거가 오면 할 일로) · **해결**(고친 브랜치 · 커밋) · **반영**(설계 원칙으로 올림).

## phase 별 기록

같은 기준으로 비교하려고 남긴다. 시간은 사람 개입 포함, 비용은 step 리뷰(`claude -p`) 합계.

| phase | step | 시간 | 리뷰 비용 | 반려 | blocked | 사람 개입 | 비고 |
|---|---|---|---|---|---|---|---|
| `0-skeleton` (2026-10-12) | 8 | 약 30분 + step 7 약 3분 | 약 $5.1 | 0 | 1 | 2 | step 당 Codex 1~4분 · 리뷰 37~125초. Codex 입력 40만~100만 토큰/step(약 90% 캐시) |

## 하네스

| # | 상태 | 문제 | 근거 | 조치 |
|---|---|---|---|---|
| H1 | 해결 | 로그의 step 소요 시간이 늘 `[0s]` | `0-skeleton` 로그 전부 — `progress_indicator` 블록 안에서 `elapsed` 를 읽었다 | `fix/execute-harness` |
| H2 | 해결 | step 사이에 멈출 방법이 없다. 리뷰 중에 죽이면 그 step 이 리뷰 없이 `completed` 로 남아 재실행 때 건너뛴다 | `0-skeleton` step 0 — step 파일을 고치려고 프로세스를 강제로 죽였다 | `.git/harness-stop` 멈춤 요청 · `completed_at` 없는 `completed` 는 리뷰부터 — `fix/execute-harness` |
| H3 | 해결 | `blocked` · `error` 를 `pending` 으로 되돌리면 사유가 Codex 에 전달되지 않는다 | `0-skeleton` step 4 — step 파일에 "재개 안내" 를 손으로 적어 메웠다 | 남은 사유를 재개 첫 프롬프트에 넣고 지운다 — `fix/execute-harness` |
| H4 | 해결 | Codex · 리뷰가 1800초를 넘으면 예외로 하네스가 죽고 상태 기록이 남지 않는다 | 코드 검토(실제로 일어나지는 않았다) | 실패한 시도 / 판정 없음으로 처리 — `fix/execute-harness` |
| H5 | 해결 | 리뷰 출력 파싱에 한 번 실패하면 실행 전체가 멈춘다 | 코드 검토 | Codex 없이 리뷰만 한 번 더 — `fix/execute-harness` |
| H6 | 할 일 | step 별 수치(시도 횟수 · Codex 시간 · 리뷰 시간 · 리뷰 비용 · 반려 요약)가 남지 않아 회고 때 로그를 파싱해야 한다 | `0-skeleton` 회고 — `step{N}-output.json` · `step{N}-review.json` 을 스크립트로 모았다 | `execute.py` 가 `index.json` 의 step 항목에 기록 |
| H7 | 할 일 | 리뷰어가 승인하면서 남긴 비차단 권고가 사람에게 보이지 않는다 | `0-skeleton` step 0 — "JUnit 5 강제는 step 1 계획에서 제거 권장" 이 `step0-review.json` 의 summary 끝에만 있었다 | 리뷰 스키마에 `notes`(비차단 권고) 를 두고 승인 로그 · `index.json` 에 남긴다 |
| H8 | 관찰 | Codex 출력이 끝날 때 한꺼번에 저장돼 실행 중에는 무엇을 하는지 보이지 않는다 | `0-skeleton` — step 이 1~4분이라 지금은 비용이 작다 | 트리거: step 이 10분을 넘기는 일이 생기면 출력을 파일로 흘려 쓴다 |
| H9 | 관찰 | 리뷰어가 AC 를 처음부터 다시 돌린다(`--rerun-tasks`) — 의도한 검증 비용 | `0-skeleton` — 리뷰 37~125초 · $0.46~1.13 | 트리거: 리뷰 시간이 Codex 시간보다 길어지는 step 이 반복되면 |

## step 작성 교훈

| # | 상태 | 교훈 | 근거 | 조치 |
|---|---|---|---|---|
| S1 | 반영 | 버전 · 이름을 느슨하게 쓰면 Codex 가 문자 그대로 강제한다 | `0-skeleton` step 0 — "JUnit 5" → Boot 관리 JUnit 6 을 5 로 낮췄고, Spring Framework 7 의 `SpringExtension`(JUnit 6 필요)과 충돌했다 | `/harness` C 원칙 9 |
| S2 | 반영 | step 이 바꾼 사실의 문서 갱신을 작업에 적지 않으면 문서가 낡는다 | `0-skeleton` 리뷰 — `AGENTS.md` 의 `./gradlew test` 설명 · `docs/ARCHITECTURE.md` 의 `static/` 위치 · 02 §4 문구가 코드와 어긋났다 | `/harness` C 원칙 8 · `AGENTS.md` 작업 방식 |
| S3 | 관찰 | 테스트 전용 클래스도 `@SpringBootTest` 의 스캔 범위(`com.mogum`)에 든다 — `@Entity` · Spring Data `Repository` 픽스처는 모든 컨텍스트 테스트를 깨뜨린다 | `0-skeleton` step 7 — 설계 때 미리 막았다 | 다시 나오면 원칙으로 올린다 |
| S4 | 유지 | 판단이 필요한 확인 작업은 "안 되면 `blocked` + 적을 내용" 을 step 에 적어 두면 Codex 가 임의로 결정하지 않는다 | `0-skeleton` step 4 — FSRS `R` 경과일 불일치를 `blocked` 로 넘겼고 사용자가 결정했다 | 같은 패턴을 계속 쓴다 |
