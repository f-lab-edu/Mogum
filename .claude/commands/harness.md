이 프로젝트는 Harness 프레임워크를 사용한다. 아래 워크플로우에 따라 작업을 진행하라.

**작업 분담** — 기획(A~D)과 리뷰는 Claude Code(너), 구현(E)은 Codex 가 한다. 너는 `backend/` · `frontend/` 의 제품 코드를 직접 고치지 않는다(사용자가 직접 요청한 경우만 예외). 고칠 것이 있으면 step 파일로 만들어 Codex 에 넘긴다.

---

## 워크플로우

### A. 탐색

`docs/` 하위 문서(PRD, ARCHITECTURE, ADR 등)를 읽고 프로젝트의 기획·아키텍처·설계 의도를 파악한다. 필요시 Explore 에이전트를 병렬로 사용한다. 이전 phase 에서 나온 step 작성 교훈과 하네스 문제는 `phases/HARNESS-BACKLOG.md` 에 있다 — step 을 쓰기 전에 본다.

### B. 논의

구현을 위해 구체화하거나 기술적으로 결정해야 할 사항이 있으면 사용자에게 제시하고 논의한다.

### C. Step 설계

사용자가 구현 계획 작성을 지시하면 여러 step으로 나뉜 초안을 작성해 피드백을 요청한다.

설계 원칙:

1. **Scope 최소화** — 하나의 step에서 하나의 레이어 또는 모듈만 다룬다. 여러 모듈을 동시에 수정해야 하면 step을 쪼갠다. 범위는 파일이 아니라 검사 · 사실 단위로 나눈다 — 금지사항에 "이 파일은 다음 step 범위" 를 적기 전에, 그 파일 안에 이번 step 이 바꾸는 사실을 검사하는 불변식이 있는지 보고 있으면 그 검사만 고치게 이름으로 열어 준다(2-deploy 1차 step 9 — 묶어 막았더니 Codex 가 구현 전에 blocked).
2. **자기완결성** — 각 step 파일은 독립된 Codex 세션(`codex exec`)에서 실행된다. Codex 는 이 대화를 보지 못한다. "이전 대화에서 논의한 바와 같이" 같은 외부 참조는 금지한다. 필요한 정보는 전부 파일 안에 적는다.
3. **사전 준비 강제** — 하네스는 설계 문서를 프롬프트에 넣지 않는다. Codex 가 스스로 읽는 것은 `AGENTS.md` 뿐이다. 그래서 이 step 에 필요한 `docs/` 문서(가능하면 절 번호까지)와 이전 step에서 생성/수정된 파일 경로를 반드시 적는다. 경로는 저장소 루트 기준 상대경로로 쓴다(`docs/ADR.md`). `/docs/...` 처럼 `/` 로 시작하면 파일시스템 절대경로로 읽힌다.
4. **시그니처 수준 지시** — 함수/클래스의 인터페이스만 제시하고 내부 구현은 에이전트 재량에 맡긴다. 단, 설계 의도에서 벗어나면 안 되는 핵심 규칙(멱등성, 보안, 데이터 무결성 등)은 반드시 명시한다. `AGENTS.md` 금지 목록 중 이 step 에서 특히 어기기 쉬운 항목은 번호로 짚는다. 테스트 파일 경로는 TDD 가드의 짝 규칙(`AGENTS.md` 작업 방식)에 맞춰 적는다. 맞지 않으면 Codex 의 구현 파일 쓰기가 막힌다.
5. **AC는 실행 가능한 커맨드** — "~가 동작해야 한다" 같은 추상적 서술이 아닌 실제 실행 가능한 검증 커맨드를 넣는다. 리뷰어(Claude)가 같은 커맨드를 다시 돌려 확인하므로 **아래 형태로만** 쓴다. 리뷰어는 이 형태만 실행할 수 있다(`scripts/execute.py` 의 `REVIEW_TOOLS`).
   - 백엔드: `./backend/gradlew -p backend test` · `./backend/gradlew -p backend test --tests '*FooTest'` · `./backend/gradlew -p backend build` (`cd backend && ...` 형태는 리뷰어가 실행하지 못한다)
   - 화면: `npm --prefix frontend run test` · `npm --prefix frontend run lint` (`cd frontend && ...` 형태는 리뷰어가 실행하지 못한다)
6. **주의사항은 구체적으로** — "조심해라" 대신 "X를 하지 마라. 이유: Y" 형식으로 적는다.
7. **네이밍** — step name은 kebab-case slug로, 해당 step의 핵심 모듈/작업을 한두 단어로 표현한다 (예: `project-setup`, `api-layer`, `auth-flow`).
8. **문서 갱신을 작업에 넣는다** — 이 step 이 정하거나 바꾸는 사실(버전 · 경로 · 명령어 · 디렉터리 구조 · 🅿️ 값 · "구현 · 검증은 아직" 표기)이 문서에 있으면, 갱신할 문서와 절을 작업 목록에 적는다. 적지 않으면 Codex 는 범위 밖이라 보고 건드리지 않는다.
9. **버전 · 이름을 느슨하게 쓰지 않는다** — Codex 는 적힌 말을 문자 그대로 강제한다(0-skeleton: "JUnit 5" → Boot 관리 JUnit 6 을 5 로 낮춤). 프레임워크가 관리하는 버전을 따르게 하려면 그렇게 쓰고, 고정이 필요하면 정확한 값과 이유를 적는다.
10. **화면 step 은 시안을 짚는다** — `frontend/` 화면을 만들거나 바꾸는 step 은 `docs/design/README.md` 의 표(「화면별 시안」 · 「공통 조각」 · 「흐름」)에서 파일과 **상태 값**을 골라 '읽어야 할 파일'에 적는다. 예: `docs/design/U2 Screen.dc.html` — `state`: `normal` · `requests`. 화면 사이 이동을 만드는 step 만 `docs/design/화면 흐름 보드.dc.html` 의 흐름(`S1`~`S7`)을 더한다. 프레임 모음(`머금 화면.dc.html`)은 적지 않는다 — 화면 파일과 내용이 같고 크다.
    - **범위는 06 §16 의 그 단계 열이다.** 시안에는 완성형이 다 그려져 있다. 이 step 에서 만들 상태 · 영역을 작업에 적고, 시안에 있어도 범위 밖인 것은 금지사항에 "X 를 만들지 마라. 이유: 06 §16 상 {단계} 에서 만든다" 로 적는다. 적지 않으면 Codex 가 시안 전체를 만든다.
    - 시안에 없는 상태(README 「아직 그리지 않은 상태」)는 06 의 상태 표 · 문구를 가리킨다. 시안과 문서가 다른 곳(README 「문서와 다른 곳」)이 이 step 에 걸리면 작업에 "문서를 따른다" 로 짚는다.
11. **확인한 사실만 단정한다** — Codex 는 step 문장을 사실로 받아 견본 · 테스트 · 문서에 고정한다. 도구 동작과 외부 서비스가 만드는 식별 문자열(OIDC `sub` · 콜백 주소 · 토큰 클레임)은 실제 설정을 조회한 값과 그 명령을 함께 적는다. 확인하지 못했으면 "확인해서 적는다" 로 쓴다(1-auth step 3 — Vite proxy 의 Host 동작을 단정해 문서에 틀린 내용이 들어감 · 2-deploy step 4 — 문서의 기본 `sub` 형식을 적었는데 저장소는 불변 subject 라 첫 배포 OIDC 가 거절됨).
12. **외부 서비스의 가짜는 실제 서비스의 검증을 그대로 한다** — 외부 연동 step 은 "가짜가 검사할 것" 을 작업에 적는다. 프레임워크가 기본으로 켜는 프로토콜 옵션(PKCE 등)도 포함한다. 안 하면 테스트는 통과하고 실제 연동만 깨진다(1-auth step 3 — 가짜 토큰 엔드포인트가 PKCE 를 검사하지 않아 `code_verifier` 를 버린 구현이 통과).
13. **보안 필터 · 에러 처리는 실제 HTTP 로 검증한다** — MockMvc 는 서블릿 ERROR 디스패치와 실제 컨테이너 동작을 거치지 않는다. 이런 step 은 AC 에 `RANDOM_PORT` 테스트를 넣는다(1-auth step 1 — `/error` 가 막혀 400 · 405 · 500 이 모두 401 이 됐는데 MockMvc 테스트는 통과).
14. **Java 테스트가 비소스 파일을 읽으면 Gradle `test` 입력으로 선언하게 적는다** — `deploy/` · 워크플로 · `vercel.json` 처럼 소스 밖 파일만 바꾸면 `test` 가 UP-TO-DATE 로 건너뛰어 불변식이 다시 돌지 않는다(2-deploy 1차 step 2~5).
15. **스크립트 · 워크플로는 대상 환경(GitHub 러너 · 서버 OS)에 기본으로 있는 명령만 쓰고, 쓰는 명령 목록을 절차서에 둔다** — Codex 환경에만 있는 도구로도 테스트가 통과한다(2-deploy 1차 step 4 — `rg` 에 기대 리뷰어 환경에서 `command not found`).
16. **사용자가 정한 값(보관 기간 · 한도 · 정원)은 "총 X 를 넘기지 않는다" 처럼 상한으로 적는다** — 구성 요소별로 "X 와 맞춘다" 고 쓰면 Codex 가 요소마다 X 를 줘 합이 커진다(2-deploy 1차 step 7 — 보관 30일이 현재 30일 + 이전 버전 30일이 됨).
17. **운영 장치의 요구는 원하는 단순함의 수준까지 적는다** — "크기 상한을 둔다" 만 적으면 Codex 가 가장 엄격하게 해석해 과설계한다. "로그 파일에 덧붙이고 시작할 때 크기를 보고 교체" 처럼 방식까지 적는다(2-deploy 1차 step 6 — FIFO · 수집기로 194 → 327줄).
18. **사람이 붙여 넣어 실행할 명령 블록은 권한 전환과 나머지를 나눈다** — `sudo -i` 는 따로 두고, 나머지는 `bash -euo pipefail <<'EOF'` 스크립트 한 덩어리로 쓴다. 한 블록에 섞으면 `sudo -i` 뒤 줄이 새 셸로 가지 않고, 대화형 셸의 `set -e` 는 실패 한 번에 세션을 닫는다. 이런 블록은 테스트도 리뷰도 실행하지 못한다(2-deploy 절차서 4번).
19. **워크플로의 서드파티 액션은 전체 커밋 SHA 로 고정하고 줄 끝에 릴리스를 주석으로 단다** — 태그는 옮겨질 수 있고 SonarCloud 게이트(`githubactions:S7637`)가 PR 에서 막는다. 자격을 받은 뒤 도는 공식 액션도 같이 고정한다. SHA 는 조회한 값을 step 에 적는다(원칙 11 · PR #11).
20. **계약(API 응답 · 오류 형식 · ADR · `AGENTS.md` 금지 목록)을 바꾸는 phase 는 Claude 가 계약 문서를 step 전에 먼저 고쳐 커밋하고, step 은 그 절을 가리킨다** — step 에는 "어긋나면 코드를 계약에 맞추고, 못 맞추면 blocked 로 멈춰라. 문서를 고치지 마라" 를 적는다. Codex 가 계약을 스스로 해석해 문서와 코드를 함께 바꾸지 않고, step 은 "05 §1 을 따른다" 처럼 짧아진다. step 이 고치는 문서는 구현으로 확정된 사실(경로 · 클래스 이름 · 검증 줄)만 남는다(`2a-api-response` · 그 수정 step 5 · 6 · `2b-ops-logging` · 그 수정 step 5 — 모두 계약 해석으로 인한 반려 · blocked 0).
21. **운영 로그 줄은 `docs/04-jobs-and-cost.md` §9.2 의 표에서 골라 적는다** — 잡 · 외부 호출 · 알림 · 반복 루프를 만드는 step 은 '읽어야 할 파일'에 §9.2 를 넣고, 표에서 해당 줄을 작업에 적고, 그 줄이 찍히는지 출력 캡처(`OutputCaptureExtension`) 테스트로 확인하게 한다. 줄 이름을 정하면 그 step 이 표에 적는다. 금지사항에는 "표에 없는 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, DB 에 남는 사실은 DB 에서 본다" 를 적는다.
22. **테스트 전용 픽스처를 `@SpringBootTest` 의 스캔에 넣지 않는다** — 스캔은 `src/test` 의 `com.mogum` 아래 클래스까지 읽어, 픽스처 하나가 모든 컨텍스트 테스트에 섞인다. 컴포넌트(`@RestController` · `@Configuration` · `@Component`)는 그것을 쓰는 테스트 클래스의 중첩 static 클래스로 두고 `@Import` 로 그 컨텍스트에만 붙인다 — Spring Boot 4.1.1 의 `TestTypeExcludeFilter` 가 `@Test` 메서드가 있는 클래스의 중첩 클래스를 스캔에서 뺀다. `@Entity` · Spring Data `Repository` 픽스처는 `com.mogum` 밖 패키지에 둔다 — `ddl-auto: validate` 라 테이블 없는 엔티티 하나로 모든 컨텍스트가 뜨지 않고, 중첩으로 두면 빠지는지는 확인하지 않았다. 이런 픽스처를 만드는 step 은 둘 곳을 작업에 적는다(`2a-api-response` step 1 · 2 · `3-curriculum-model` step 0 — S3).

### D. 파일 생성

사용자가 승인하면 아래 파일들을 생성한다.

#### D-1. `phases/index.json` (전체 현황)

여러 task를 관리하는 top-level 인덱스. 이미 존재하면 `phases` 배열에 새 항목을 추가한다.

```json
{
  "phases": [
    {
      "dir": "0-mvp",
      "status": "pending"
    }
  ]
}
```

- `dir`: task 디렉토리명.
- `status`: `"pending"` | `"completed"` | `"error"` | `"blocked"`. execute.py가 실행 중 자동으로 업데이트한다.
- 타임스탬프(`completed_at`, `failed_at`, `blocked_at`)는 execute.py가 상태 변경 시 자동 기록한다. 생성 시 넣지 않는다.

#### D-2. `phases/{task-name}/index.json` (task 상세)

```json
{
  "project": "Mogum",
  "phase": "<task-name>",
  "steps": [
    { "step": 0, "name": "project-setup", "status": "pending" },
    { "step": 1, "name": "core-types", "status": "pending" },
    { "step": 2, "name": "api-layer", "status": "pending" }
  ]
}
```

필드 규칙:

- `project`: 프로젝트명.
- `phase`: task 이름. 디렉토리명과 일치시킨다.
- `steps[].step`: 0부터 시작하는 순번.
- `steps[].name`: kebab-case slug.
- `steps[].status`: 초기값은 모두 `"pending"`.

상태 전이와 자동 기록 필드:

| 전이 | 기록되는 필드 | 기록 주체 |
|------|-------------|----------|
| → `completed` | `completed_at`, `summary` | Codex (summary), execute.py (Claude 리뷰 승인 후 timestamp) |
| → `error` | `failed_at`, `error_message` | Codex 또는 execute.py (리뷰 반려 3회 · 리뷰 실행 실패), execute.py (timestamp) |
| → `blocked` | `blocked_at`, `blocked_reason` | Codex (reason) 또는 execute.py (Codex 사용 한도), execute.py (timestamp) |

`summary`는 step 완료 시 산출물을 한 줄로 요약한 것으로, execute.py가 다음 step 프롬프트에 컨텍스트로 누적 전달한다. 따라서 다음 step에 유용한 정보(생성된 파일, 핵심 결정 등)를 담아야 한다.

`created_at`은 execute.py가 최초 실행 시 task 레벨에 한 번만 기록한다. step 레벨의 `started_at`도 execute.py가 각 step 시작 시 자동 기록한다. 생성 시 넣지 않는다.

#### D-3. `phases/{task-name}/step{N}.md` (각 step마다 1개)

```markdown
# Step {N}: {이름}

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/ARCHITECTURE.md`
- `docs/{이 step 에 해당하는 문서}.md §{절}`
- (화면 step) `docs/UI_GUIDE.md` · `docs/design/README.md` · `docs/design/{화면 파일}` — `state`: {이 step 에서 만들 상태 값}
- {이전 step에서 생성/수정된 파일 경로}

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

{구체적인 구현 지시. 파일 경로, 클래스/함수 시그니처, 로직 설명을 포함.
코드 스니펫은 인터페이스/시그니처 수준만 제시하고, 구현체는 에이전트에게 맡겨라.
단, 설계 의도에서 벗어나면 안 되는 핵심 규칙은 명확히 박아넣어라.}

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # 백엔드 테스트 통과 (Testcontainers — Docker 필요)
npm --prefix frontend run test      # 화면 테스트 통과 (화면 step 일 때)
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 패키지 구조(도메인별 패키지 · 그 안은 `controller` · `service` · `repository` · `domain` · `dto` · `generation` 은 `worker` · `config` 만 참조)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
   - (화면 step) 적힌 시안 상태와 영역 순서 · 문구 · 가장 강조가 맞는가? 적히지 않은 상태를 만들지 않았는가? 기준은 `docs/design/README.md` 「어디까지 맞추나」
3. 결과에 따라 `phases/{task-name}/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- {이 step에서 하지 말아야 할 것. "X를 하지 마라. 이유: Y" 형식}
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
```

### E. 실행 — Codex 구현 · Claude 리뷰

사용자가 터미널에서 실행한다. 필요한 것: `codex` · `claude` CLI 로그인, Docker 실행 중(Testcontainers).

```bash
python3 scripts/execute.py {task-name}        # 순차 실행
python3 scripts/execute.py {task-name} --push  # 실행 후 push
```

step 하나의 흐름:

1. **Codex 구현** — `codex exec --dangerously-bypass-approvals-and-sandbox --dangerously-bypass-hook-trust`. 프롬프트는 작업 규칙 · 이전 step summary · step 파일이다. `AGENTS.md` 는 Codex 가 스스로 읽는다. 샌드박스를 끄는 이유는 Gradle(`~/.gradle` · 네트워크)과 Testcontainers(Docker 소켓)가 workspace-write 에서 막히기 때문이다. 되돌리기 어려운 명령은 `.codex/rules/harness.rules` 가 막는다. TDD 는 `.codex/hooks.json` 의 TDD 가드가 강제한다. 훅 신뢰 승인을 건너뛰는 이유는, 승인 전이거나 고친 뒤 재승인 전인 훅이 `exec` 에서 조용히 빠지기 때문이다.
2. **Claude 리뷰** — Codex 가 `completed` 로 끝내면 `claude -p --permission-mode dontAsk` 가 읽기 전용으로 리뷰한다. 볼 수 있는 것은 이 step 시작 커밋 이후의 diff 와 untracked 파일이다. AC 를 다시 돌리고 `approve` / `request_changes` 를 JSON 으로 판정한다. 결과는 `phases/{task}/step{N}-review.json` 에 남는다.
3. **승인** → 코드(`feat`)와 메타데이터(`chore`)를 나눠 커밋한다.
   **반려** → 지적 사항(findings)을 다음 Codex 시도의 프롬프트에 넣는다. 이전 시도의 변경은 작업 트리에 남긴 채로 고치게 한다. Codex 실패와 리뷰 반려를 합쳐 최대 3회 시도한다.
   **판정 없음**(리뷰 출력 파싱 실패 · 시간 초과) → Codex 를 다시 돌리지 않고 리뷰만 한 번 더 한다. 그래도 없으면 `error`. 리뷰가 사용 한도에 걸린 것은 판정 없음으로 세지 않는다(아래 「사용 한도」).

execute.py가 자동으로 처리하는 것:

- `feat-{task-name}` 브랜치 — 없으면 현재 커밋에서 만든다. 있으면 현재 커밋과의 관계를 본다: 현재 커밋을 포함하면 그대로 checkout, 현재 커밋보다 뒤면(병합 뒤 다른 브랜치에 수정 step 을 얹은 경우) 현재 커밋까지 앞으로 옮긴다, 서로 없는 커밋이 있으면 옮기지 않고 멈춘다. step 수 · 막힘은 브랜치를 바꾼 뒤의 index 로 판단한다
- 컨텍스트 누적 — 완료된 step의 summary를 다음 step 프롬프트에 전달
- 자가 교정 — Codex 에러 메시지나 Claude 리뷰 지적을 다음 시도에 피드백. 마지막 리뷰 반려 지적은 뒤이은 다른 실패(status 미갱신 · 시간 초과)에 덮이지 않고 다음 시도와 최종 `error_message` 에 함께 남는다
- 시간 초과 — Codex · 리뷰 각각 1800초. 넘으면 하네스가 죽지 않고 실패한 시도로 처리한다(Codex 는 다음 시도에 "시간 초과" 를 알린다)
- 사용 한도 — Codex 출력에서 사용 한도 오류(`usage limit`)를 알아보면 시도로 세지 않고 `blocked` 로 멈춘다. 사유에 오류 문구(다시 쓸 수 있는 시각)와 직전 리뷰 지적을 남긴다 — 한도가 풀린 뒤 `status` 만 `pending` 으로 바꿔 재실행한다. 리뷰(`claude -p`) 출력에서 사용 한도(`usage_limit_reached` · 429)를 알아보면 리뷰를 다시 하지 않고 다시 쓸 수 있는 시각을 찍은 뒤 멈춘다(종료 코드 2 · 상위 `phases/index.json` 은 `blocked`). step 은 `completed` · `completed_at` 없음 그대로라 **index 를 고치지 않고** 한도가 풀린 뒤 재실행하면 리뷰부터 한다 — `pending` 으로 바꾸면 끝난 Codex 를 다시 돌린다
- 리뷰 전 중단 복구 — `completed` 인데 `completed_at`(리뷰 승인 기록)이 없는 step 은 건너뛰지 않고 다음 실행에서 리뷰부터 다시 한다. Codex 가 `completed_at` 을 쓰면 Codex 가 끝나자마자 지운다
- 타임스탬프 — started_at, completed_at, failed_at, blocked_at 자동 기록

실행을 감시할 때 — 재시도 · 반려 · blocked 와 그 원인, 사람이 끼어든 지점, 유난히 오래 걸린 step 을 메모해 둔다. F 의 하네스 회고에 쓴다. 로그 파일로 돌리면(`> log 2>&1`) 진행 표시는 프레임 대신 `◐ {작업} — 시작` · `◐ {작업} — 끝 [N초]` 두 줄만 남고 결과 줄과 순서가 맞는다.

중간에 멈추기 — 프로세스를 죽이지 않는다. 아래 파일을 만들면 **지금 step 이 리뷰 · 커밋까지 끝난 뒤** 다음 step 앞에서 멈추고 파일을 지운다(`.git` 안이라 커밋되지 않는다). 다시 실행하면 이어 한다. step 파일을 실행 중에 고쳐야 할 때 쓴다.

```bash
touch "$(git rev-parse --git-path harness-stop)"
```

에러 복구:

- **error 발생 시** — 실패한 step 의 변경은 **커밋하지 않고** 작업 트리에 남는다. `step{N}-review.json` · `step{N}-output.json` 으로 원인을 본다. 그다음 `phases/{task-name}/index.json` 에서 해당 step의 `status` 만 `"pending"` 으로 바꾸고 재실행한다. **`error_message` 는 지우지 않는다** — execute.py 가 재개한 첫 시도의 프롬프트에 넣고 지운다. 남은 변경을 그대로 두면 다음 시도의 리뷰 범위에 포함된다. 버리려면 사용자가 직접 정리한다.
- **리뷰만 실패한 error**(`error_message` 가 "Claude 리뷰 판정을 얻지 못했다") — Codex 는 끝났다. `pending` 이 아니라 `status` 를 `"completed"` 로 바꾸고 `error_message` · `failed_at` 을 지운 뒤 재실행하면 리뷰부터 한다(`completed_at` 이 없어야 한다). 사유를 지우는 이유: 남기면 리뷰가 반려했을 때 다음 Codex 프롬프트에 리뷰 실패 사유가 "이전 실행에서 멈췄다" 로 들어간다.
- **blocked 발생 시** — `blocked_reason` 에 적힌 사유를 해결한 뒤(결정은 step 파일 · 문서에 반영한다), `status` 만 `"pending"` 으로 바꾸고 재실행한다. **`blocked_reason` 은 지우지 않는다** — 위와 같이 전달된다.
- 리뷰 지적이 step 설계 자체의 문제라면(지시가 모호함 · 문서와 충돌) Codex 를 다시 돌리지 말고 step 파일부터 고친다.

### F. 최종 리뷰

phase 가 끝나면 `/review {task-name}` 으로 브랜치 전체(`main...HEAD`)를 리뷰한다. step 단위 리뷰가 놓치는 것을 본다. 예를 들어 step 사이의 일관성, 문서와 코드의 어긋남, 탈퇴/삭제 범위 누락이 있다. 고칠 것이 있으면 사용자 승인 후 같은 phase 에 수정 step(`step{N}.md` + index 항목 `pending`)을 추가하고 E 를 다시 실행한다.

`/review` 는 두 가지를 더 한다(`.claude/commands/review.md`):

- **문서 최신화** — 이 phase 로 바뀐 사실이 문서에 반영됐는지 본다. 문서만의 어긋남은 Claude 가 직접 고친다.
- **하네스 회고** — step 별 시도 · 반려 · blocked 원인 · 사람 개입 · 시간 · 비용을 정리하고, 하네스나 step 설계로 막을 수 있었던 것을 `phases/HARNESS-BACKLOG.md` 에 적는다. 하네스 수정은 사용자 확인 뒤 `main` 에서 딴 별도 브랜치에서 한다.
