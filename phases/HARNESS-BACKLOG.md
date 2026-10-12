# 하네스 백로그

> 개발하면서 찾은 **하네스(`scripts/` · `.claude/` · `.codex/`) 문제**와 **step 작성 교훈**을 모은다. 규칙은 `CLAUDE.md` 의 "하네스 개선".
> 실행을 감시할 때와 `/review` 의 하네스 회고에서 바로 추가한다. 고치는 일은 사용자 확인 뒤 `main` 에서 딴 별도 브랜치에서 테스트부터 쓴다.
> 상태 — **할 일** · **관찰**(트리거가 오면 할 일로) · **해결**(고친 브랜치 · 커밋) · **반영**(설계 원칙으로 올림).

## phase 별 기록

같은 기준으로 비교하려고 남긴다. 시간은 사람 개입 포함, 비용은 step 리뷰(`claude -p`) 합계.

| phase | step | 시간 | 리뷰 비용 | 반려 | blocked | 사람 개입 | 비고 |
|---|---|---|---|---|---|---|---|
| `0-skeleton` (2026-10-12) | 8 | 약 30분 + step 7 약 3분 | 약 $5.1 | 0 | 1 | 2 | step 당 Codex 1~4분 · 리뷰 37~125초. Codex 입력 40만~100만 토큰/step(약 90% 캐시) |
| `1-auth` (2026-10-12) | 6(수정 step 1 포함) | 약 1시간 40분(12:07~13:47, step 5 추가 결정 10분 포함) | $7.2 이상 — 승인된 시도의 리뷰만(H6) | 4 + Codex 자체 error 1 | 0 | 1 | step 1 · 4 가 3회, step 5 가 2회. step 3 의 비차단 지적(PKCE)이 실제로는 로그인 전체를 깨는 결함이라 수정 step 5 를 더했다. Codex 입력 63만~220만 토큰/시도(약 90~95% 캐시) · 리뷰 98~299초 |

## 하네스

| # | 상태 | 문제 | 근거 | 조치 |
|---|---|---|---|---|
| H1 | 해결 | 로그의 step 소요 시간이 늘 `[0s]` | `0-skeleton` 로그 전부 — `progress_indicator` 블록 안에서 `elapsed` 를 읽었다 | `fix/execute-harness` |
| H2 | 해결 | step 사이에 멈출 방법이 없다. 리뷰 중에 죽이면 그 step 이 리뷰 없이 `completed` 로 남아 재실행 때 건너뛴다 | `0-skeleton` step 0 — step 파일을 고치려고 프로세스를 강제로 죽였다 | `.git/harness-stop` 멈춤 요청 · `completed_at` 없는 `completed` 는 리뷰부터 — `fix/execute-harness` |
| H3 | 해결 | `blocked` · `error` 를 `pending` 으로 되돌리면 사유가 Codex 에 전달되지 않는다 | `0-skeleton` step 4 — step 파일에 "재개 안내" 를 손으로 적어 메웠다 | 남은 사유를 재개 첫 프롬프트에 넣고 지운다 — `fix/execute-harness` |
| H4 | 해결 | Codex · 리뷰가 1800초를 넘으면 예외로 하네스가 죽고 상태 기록이 남지 않는다 | 코드 검토(실제로 일어나지는 않았다) | 실패한 시도 / 판정 없음으로 처리 — `fix/execute-harness` |
| H5 | 해결 | 리뷰 출력 파싱에 한 번 실패하면 실행 전체가 멈춘다 | 코드 검토 | Codex 없이 리뷰만 한 번 더 — `fix/execute-harness` |
| H6 | 할 일 | step 별 수치(시도 횟수 · Codex 시간 · 리뷰 시간 · 리뷰 비용 · 반려 요약)가 남지 않아 회고 때 로그를 파싱해야 한다. **재시도하면 `step{N}-review.json` · `step{N}-output.json` 을 덮어써 반려된 시도의 비용 · 지적은 아예 사라진다** | `0-skeleton` 회고 — 스크립트로 모았다. `1-auth` — step 1 · 4 · 5 의 반려 리뷰 4회 비용을 집계하지 못했고, 반려 사유는 실행 로그 한 줄 · 대화 기록으로만 남았다 | `execute.py` 가 시도별 기록을 덧붙여 남기고(덮어쓰지 않는다) `index.json` 의 step 항목에 합계를 기록 |
| H7 | 할 일 · **우선** | 리뷰어가 승인하면서 남긴 비차단 권고가 사람에게 보이지 않는다 | `0-skeleton` step 0 — "JUnit 5 강제는 step 1 계획에서 제거 권장" 이 `step0-review.json` 의 summary 끝에만 있었다. `1-auth` step 3 — "PKCE 를 확인하지 못한 위험" 을 비차단으로 남기고 승인했는데, 실제로는 구글 로그인이 언제나 실패하는 결함이었다. 사람이 리뷰 파일을 열어 보고서야 찾았다 | 리뷰 스키마에 `notes`(비차단 권고) 를 두고 승인 로그 · `index.json` 에 남긴다. 핵심 흐름을 깰 수 있는데 확인하지 못한 위험은 비차단이 아니라 `request_changes`(확인 테스트 요구)로 다루게 리뷰 프롬프트에 적는다 |
| H8 | 관찰 | Codex 출력이 끝날 때 한꺼번에 저장돼 실행 중에는 무엇을 하는지 보이지 않는다 | `0-skeleton` — step 이 1~4분이라 지금은 비용이 작다 | 트리거: step 이 10분을 넘기는 일이 생기면 출력을 파일로 흘려 쓴다 |
| H9 | 관찰 | 리뷰어가 AC 를 처음부터 다시 돌린다(`--rerun-tasks`) — 의도한 검증 비용 | `0-skeleton` — 리뷰 37~125초 · $0.46~1.13. `1-auth` — 모든 리뷰가 첫 `./gradlew test` 를 UP-TO-DATE 로 건너뛰고 `--rerun` · `cleanTest` 로 다시 돌렸다. 리뷰 98~299초 · $0.91~1.82 | 트리거: 리뷰 시간이 Codex 시간보다 길어지는 step 이 반복되면 |
| H10 | 관찰 | 리뷰어가 의존성(jar)의 실제 동작 · 기본값을 확인할 수단이 없다 — Bash 는 `gradlew` · `git` 만, 웹은 막혀 있다 | `1-auth` step 3 — Spring Security 7.1.1 이 PKCE 를 기본으로 켜는지 확인하지 못해 "확인 못 한 위험" 으로 남겼다. 사람이 `javap` 로 jar 를 열어 `requireProofKey` 기본값 `true` 를 확인했다 | 트리거: 같은 "확인 못 함" 이 다시 나오면 — 리뷰어에게 의존성 소스 jar 를 읽는 읽기 전용 수단을 준다 |

## step 작성 교훈

| # | 상태 | 교훈 | 근거 | 조치 |
|---|---|---|---|---|
| S1 | 반영 | 버전 · 이름을 느슨하게 쓰면 Codex 가 문자 그대로 강제한다 | `0-skeleton` step 0 — "JUnit 5" → Boot 관리 JUnit 6 을 5 로 낮췄고, Spring Framework 7 의 `SpringExtension`(JUnit 6 필요)과 충돌했다 | `/harness` C 원칙 9 |
| S2 | 반영 | step 이 바꾼 사실의 문서 갱신을 작업에 적지 않으면 문서가 낡는다 | `0-skeleton` 리뷰 — `AGENTS.md` 의 `./gradlew test` 설명 · `docs/ARCHITECTURE.md` 의 `static/` 위치 · 02 §4 문구가 코드와 어긋났다 | `/harness` C 원칙 8 · `AGENTS.md` 작업 방식 |
| S3 | 관찰 | 테스트 전용 클래스도 `@SpringBootTest` 의 스캔 범위(`com.mogum`)에 든다 — `@Entity` · Spring Data `Repository` 픽스처는 모든 컨텍스트 테스트를 깨뜨린다 | `0-skeleton` step 7 — 설계 때 미리 막았다 | 다시 나오면 원칙으로 올린다 |
| S5 | 할 일 | **외부 서비스의 가짜는 실제 서비스가 하는 검증을 그대로 한다** — 안 하면 테스트는 통과하고 실제 연동만 깨진다. 프레임워크가 기본으로 켜는 프로토콜 옵션(PKCE 등)도 가짜가 검사해야 드러난다 | `1-auth` step 3 — 가짜 구글 토큰 엔드포인트가 PKCE 를 검사하지 않아 `code_verifier` 를 버린 저장소가 통과했다 → 수정 step 5 | `/harness` C 원칙으로 올리자고 제안 — 외부 연동 step 에 "가짜가 검사할 것" 목록을 적는다 |
| S6 | 할 일 | **MockMvc 는 서블릿 ERROR 디스패치 · 실제 컨테이너 동작을 거치지 않는다** — 보안 필터 · 에러 처리 step 은 실제 HTTP(`RANDOM_PORT`) 테스트를 AC 에 넣는다 | `1-auth` step 1 — `/error` 가 `denyAll` 에 걸려 400 · 405 · 500 이 모두 401 로 바뀌었는데 MockMvc 테스트는 전부 통과했다(리뷰 반려 1회) | `/harness` C 원칙으로 올리자고 제안 |
| S7 | 할 일 | **확인하지 않은 도구 동작을 step 에 사실로 적지 않는다** — Codex 는 step 문장을 사실로 받아 문서에 옮긴다(S1 과 같은 뿌리) | `1-auth` step 3 — "Vite proxy 는 Host 를 바꾸지 않는다" 고 적었는데 문자열 축약형은 `changeOrigin: true` 다. step 4 의 Codex 가 이를 05 §1.1 에 썼고 리뷰가 반려했다 | `/harness` C 원칙 9 에 "확인한 사실만 단정한다 · 모르면 '확인해서 적는다' 로 쓴다" 를 더하자고 제안 |
| S4 | 유지 | 판단이 필요한 확인 작업은 "안 되면 `blocked` + 적을 내용" 을 step 에 적어 두면 Codex 가 임의로 결정하지 않는다 | `0-skeleton` step 4 — FSRS `R` 경과일 불일치를 `blocked` 로 넘겼고 사용자가 결정했다 | 같은 패턴을 계속 쓴다 |
