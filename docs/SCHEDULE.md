# 개발 일정 — 2026-10-12 ~ 11-30

> 2026-10-11 인터뷰로 정했다([ADR-058](./ADR.md)). 순서의 근거는 [01 §11](./01-overview.md#11-진행-순서)이고, 이 문서는 그 순서에 날짜와 TASK 를 붙인다.
> TASK 안의 step 은 각 TASK 를 시작할 때 `/harness` 로 설계한다([ADR-052](./ADR.md)). 이 문서는 step 을 미리 쓰지 않는다.
> 매주 일요일에 실제 진행을 §6 에 적고 다음 주를 조정한다.

## 1. 목표와 전제

| | |
|---|---|
| **목표** | 11/30 까지 [공개 필수 목록](./01-overview.md#10-공개-필수-목록) 전부. 12월 초에 바로 정원제로 공개할 수 있는 상태 |
| **완료에 포함** | 운영 서버 배포 · 본인 실사용 · 콘텐츠 품질 게이트 통과([01 §9](./01-overview.md#9-콘텐츠-품질--채점표와-공개-게이트)) |
| **밀리면** | **범위를 지키고 날짜를 미룬다.** 11/30 은 목표일이다 — §5 |
| **투입** | 1인 · 주 20시간 안팎(평일 저녁 + 주말) · 7주 ≈ 140시간. 빠지는 날 없음 |
| **실사용** | 핵심 경로가 돌면 **운영 서버(EC2)** 에서 휴대폰으로 시작한다(M2) |
| **범위 밖** | 숙련도 선별 실험([07 §1.3](./07-testing.md)) — 공개 후([ADR-057](./ADR.md)) |
| **외부 준비물** | OpenAI(결제 · 크레딧) 준비됨. AWS · 도메인 · 구글 OAuth · 슬랙은 새로 만든다 |

## 2. 마일스톤

| 날짜 | 마일스톤 | 완료 기준 |
|---|---|---|
| **10/18(일)** | M1 뼈대 | `./gradlew build` 통과 — ArchUnit · Flyway · Testcontainers. 화면은 `npm --prefix frontend run build` · `test` · `lint` 통과. `execute.py` 가 TASK 하나를 Codex 구현 → Claude 리뷰 → 커밋까지 끝까지 돈다 |
| **11/8(일)** | M2 본인 실사용 시작 | 운영 서버에서 커리큘럼 → 강의 → 카드 → 복습을 한 바퀴 돈다. 나 말고는 생성할 수 없다. DB 백업이 돈다 |
| **11/15(일)** | M3 품질 게이트 1차 채점 | 표본 10개의 채점 기록(CSV)이 있다 — 합격이든 불합격이든 |
| **11/29(일)** | M4 공개 준비 | 공개 필수 목록 전부 체크 · 용량 측정으로 정원 확정 · 품질 게이트 통과 |
| **11/30(월)** | 마감 | 여유 하루 |

## 3. 주차별 계획

시간은 사용자의 추정 시간이다(Codex 실행 시간이 아니다). TASK 하나 = 설계 · step 승인 · 실행 감시 · 막힘 해결 · `/review` 를 합쳐 약 4~7시간으로 잡았다.

| 주 | TASK | 사람이 할 일 | 추정 |
|---|---|---|---|
| **W1** 10/12–18 | `0-skeleton` · `1-auth` | `docs/design-bundle` 을 PR 로 `main` 에 병합하고 시작 · 구글 OAuth 클라이언트(로컬 콜백 `http://localhost:5173/auth/google/callback` — Vite proxy 경유) · AWS 계정(생성일 확인 — [04 §3](./04-jobs-and-cost.md#3-배포)) | 약 11h |
| **W2** 10/19–25 | `2-job-queue` · `3-llm-ledger` | — | 약 12h |
| **W3** 10/26–11/1 | `4-curriculum-gen` · `5-deploy` | 도메인 구매 · 구글 OAuth 에 운영 콜백 추가 · Vercel 계정 · 요금제 약관 확인 | 약 12h |
| **W4** 11/2–8 | `6-material-gen` · `7-review` → 다시 배포 | OpenAI 프로젝트 사용 한도 설정 · **M2 실사용 시작** · 이후 매일 복습 | 약 13h |
| **W5** 11/9–15 | `8-mastery-loop` · `9-cost-control` | 슬랙 웹훅 · 외부 헬스체크 · **품질 게이트 1차 채점**(`8` 이 끝난 뒤 · 약 12h) | 약 21h |
| **W6** 11/16–22 | `10-delete-restart` · `11-screens-complete` · `12-account-public` · `13-admin-status` | 약관 · 개인정보처리방침 문안 검토 · 구글 동의 화면 프로덕션 전환 신청. 1차 불합격이면 프롬프트 v7 + 새 표본 | 약 20h |
| **W7** 11/23–29 | `14-fault-injection` · `15-capacity` | 공개 필수 목록 점검 · 재채점(필요하면 약 12h) · 피드백 루프 확인 | 약 13~25h |

**여유는 앞쪽에 있다.** W1~W4 는 주마다 약 8시간이 남고 W5~W7 은 꽉 찬다. 앞 주에 남은 시간은 다음 TASK 를 당겨 쓴다. 합계는 약 110~125시간 / 140시간이다.

## 4. TASK 목록

`phases/{TASK}/` 디렉터리 이름이다. **TASK 완료 = 모든 step 승인 · `/review` 지적 반영 · PR 로 `main` 병합.** `main` 은 PR 로만 바뀐다(GitHub 룰셋 · `.githooks/`). `execute.py {TASK} --push` 로 `feat-{TASK}` 를 올리고 PR 을 열어 병합한 뒤, `main` 에서 `git pull --ff-only` 하고 다음 TASK 를 시작한다 — `execute.py` 는 지금 HEAD 에서 브랜치를 만든다.

| TASK | 주 | 목표 | 먼저 읽을 문서 | 선행 |
|---|---|---|---|---|
| `0-skeleton` | W1 | Gradle 단일 모듈 · 패키지(`core` ← `api` · `worker` · `infra`) · ArchUnit 규칙 · Flyway · Testcontainers(pgvector) · `TransactionRunner` · `Asia/Seoul` 시계 · 프론트 Vite 뼈대(jar 에 넣지 않는다 — ADR-060) · 로컬 Docker Compose. **FSRS 라이브러리 채택 확인** — 학습 단계를 끌 수 있는지(PRD 🅿️ "구현 첫 작업") | [ARCHITECTURE](./ARCHITECTURE.md) · [04 §2 · §5](./04-jobs-and-cost.md) | — |
| `1-auth` | W1 | 구글 OAuth · Spring Session JDBC · CSRF · 화면과 API 의 출처 분리(CORS · `XSRF-TOKEN` 쿠키 도메인 · 로그인 뒤 화면 주소로) · 로그아웃 · `users` · 소유권 `404` 공통 처리 · U1 최소판 · JSON API `/api` 접두사([05 §1](./05-api.md)) · JPA · 네이티브 `flush` 규칙 테스트(첫 엔티티 `users` — [07 §1.2](./07-testing.md)). **공개 전 운영 서버에서 나 말고는 생성할 수 없게 한다** — 관리자 이메일만 로그인한다([ADR-062](./ADR.md)) | [05 §1](./05-api.md) · [02 §6.1 · §7](./02-domain-data.md) | 0 |
| `2-job-queue` | W2 | `jobs` · `job_events` · 적재(진행 중 2개 · 사용자 advisory lock) · `SKIP LOCKED` 수령 · 실행기 `N` · 리스 · 수령 회차 · 반납 · 회수 · `GET /jobs/{id}` · `aheadCount` · `app.role` 역할별 빈 등록과 역할별 기동 테스트([04 §3.1](./04-jobs-and-cost.md)). 장애 주입 #1~#4 | [04 §4 · §5 · §6](./04-jobs-and-cost.md) · [02 §6.9](./02-domain-data.md) · [05 §4](./05-api.md) | 1 |
| `3-llm-ledger` | W2 | `LlmClient` · `EmbeddingClient` 포트 · `GuardedLlmClient`(재시도 · 응답 분류 · `insufficient_quota`) · `llm_calls` 원장 · 누적 호출 상한 · 입력 예산 검사 · OpenAI 어댑터(`maxRetries(0)`) · LLM 스텁. 장애 주입 #8~#15 | [03 §8](./03-generation.md) · [02 §6.8](./02-domain-data.md) · [04 §4.8](./04-jobs-and-cost.md) · [07 §3](./07-testing.md) · `reference-code/` | 2 |
| `4-curriculum-gen` | W3 | 잡 I(단일/그룹 판단) · 잡 A(다시 짜기 · 단계 생성) · 프롬프트 v6(I · A) · 묶음/단계 · 요청 식별자 중복 처리 · 실패 요청 지우기 · U2 · U10 · U3 · U11 최소판. 숙련도 선별은 학습 이력이 아직 없으므로 `EMPTY` 경로만 — 선별 연결은 `8` 에서 | [03 §2](./03-generation.md) · [02 §2.1 · §6.2 · §6.2.1](./02-domain-data.md) · [05 §2](./05-api.md) · [06 §6~§9](./06-screens.md) | 3 |
| `5-deploy` | W3 | 지금까지의 앱을 EC2 에 먼저 올린다 — **Postgres 위치 결정**(1순위: 같은 EC2 2GB) · 도메인(화면 → Vercel · `api.` → EC2) · HTTPS · 화면 Vercel 배포(API 주소 `VITE_API_BASE_URL` · 화면 주소 새로고침 → `index.html`) · 운영 환경 변수(`GOOGLE_CLIENT_ID` · `GOOGLE_CLIENT_SECRET` · `GOOGLE_REDIRECT_URI` · `APP_WEB_ORIGIN` · `APP_WEB_COOKIE_DOMAIN` · `APP_AUTH_ADMIN_EMAILS` — [05 §1.1](./05-api.md)) · `pg_dump` → S3 백업 · 배포 절차(SIGTERM 유예) | [04 §3](./04-jobs-and-cost.md#3-배포) · [01 §10](./01-overview.md) | 4 |
| `6-material-gen` | W4 | 잡 B ①②③ · 프롬프트 v6(B) · 이어 쓰기 · 개념 동일성(`resolveConcepts` · 정의 임베딩) · U4 최소판(KaTeX · mermaid). 숙련도 연결은 `8` 에서 — 그전에는 학습 이력이 없다 | [03 §3~§7](./03-generation.md) · [02 §5 · §6.3 · §6.4](./02-domain-data.md) · [05 §3](./05-api.md) · [06 §10](./06-screens.md) | 4 |
| `7-review` | W4 | 학습 완료(강의 ID 기준 한 번) · `card_states` · `reviews` · FSRS · 오늘의 복습(한국 날짜) · U5 최소판 | [02 §3 · §6.5 · §8.1](./02-domain-data.md) · [05 §6](./05-api.md) · [06 §11](./06-screens.md) | 6 |
| `8-mastery-loop` | W5 | 숙련도 `R` · `S` · `minReviewCount` 조회 SQL(라이브러리 일치 테스트) · `MasteryPolicy` · 잡 B 의 `lookupMastery` 연결 · 잡 I/A 숙련도 선별(잠정 설정 — [ADR-057](./ADR.md)). **피드백 루프가 여기서 닫힌다** | [02 §4 · §5.4 · §8.2.1 · §8.3](./02-domain-data.md) · [03 §2 · §3](./03-generation.md) | 7 |
| `9-cost-control` | W5 | 사용자 일일 비용 쿼터 · 전역 킬스위치 · 우리 API 레이트리밋 · 슬랙 알림 · 외부 헬스체크 · 막힘 문구 | [04 §7 · §9](./04-jobs-and-cost.md) · [05 §1.2 · §1.4 · §1.5](./05-api.md) · [06 §15](./06-screens.md) | 7 |
| `10-delete-restart` | W6 | 커리큘럼 완전 삭제 · 묶음 삭제 · 처음부터 다시 만들기 · 다시 짜기 하루 3회 · 늦은 응답이 지운 것을 되살리지 않는지 | [02 §6.2 · §6.2.1](./02-domain-data.md) · [04 §4.9](./04-jobs-and-cost.md) · [05 §2](./05-api.md) | 8 |
| `11-screens-complete` | W6 | 화면 "공개 전 완성" 열 — 대기 블록 전부 · 목차 · 키보드 · 모바일 하단 고정 · 기다리는 동안 모드 · 접근성 | [06 §2 · §16](./06-screens.md) · [UI_GUIDE](./UI_GUIDE.md) | 10 |
| `12-account-public` | W6 | U7 가입(약관 동의 · 정원 검사) · U8 대기 명단 · U9 설정 · 회원 탈퇴(삭제 범위 전부) · P1 · P2 · U1 가입 안내 · 정원 안내 · 바닥글 · 관리자 전용 로그인 걷어내기([ADR-062](./ADR.md)) | [02 §7](./02-domain-data.md) · [06 §3~§5 · §12 · §13](./06-screens.md) | 10 |
| `13-admin-status` | W6 | U6 시스템 상태(관리자 전용 · DB 만 읽는다) · 가입 섹션 | [05 §7](./05-api.md) · [06 §14](./06-screens.md) · [02 §6.9](./02-domain-data.md) | 12 |
| `14-fault-injection` | W7 | 앞 TASK 에서 못 한 프로세스 단위 장애 주입(`kill -9` · SIGTERM 등)과 [07 §1.1](./07-testing.md) 16개 전체 확인 | [07 §1](./07-testing.md) | 13 |
| `15-capacity` | W7 | k6 · `loadtest` 프로필 · 운영과 같은 메모리 조건 → `N` · 워커 폴링 주기 · 정원 `U_max` 확정. 통과 기준은 측정 전에 정한다 | [07 §2 · §3](./07-testing.md) | 14 |

- 장애 주입 테스트는 해당 TASK 안에서 TDD 로 같이 쓴다. `14` 는 남은 것과 전체 확인만 한다.
- 품질 채점 · 약관 문안 · 외부 계정은 TASK 가 아니다 — Codex 가 할 수 없다. §3 의 "사람이 할 일" 에 둔다.

## 5. 운영 규칙

- **1차 채점(M3) 뒤에는 생성 구성을 건드리지 않는다.** 프롬프트 · 모델 · 후처리를 바꾸면 새 표본 10개로 다시 채점해야 한다([01 §9](./01-overview.md)). `9` 이후 TASK 의 step 에는 이 금지를 넣는다.
- **M2 가 11/15 를 넘기면 공개 준비 완료일을 다시 잡는다.** M2 뒤에 필요한 일(W5~W7)만 약 3주다.
- **매주 일요일 점검** — 끝난 TASK · 밀린 TASK · 쓴 시간을 §6 에 적고, 남은 TASK 를 다시 배치한다. 날짜가 바뀌면 §2 를 고친다.
- **`5-deploy` 의 Postgres 위치는 용량 측정(`15`)보다 먼저 정한다.** 측정 뒤 바꾸면 데이터 이전이 필요하다. 측정은 정한 위치의 메모리 조건으로 한다([07 §2](./07-testing.md)).
- **구글 동의 화면의 프로덕션 전환** — 요건(개인정보처리방침 URL · 앱 정보 등)과 걸리는 기간은 신청 전에 확인한다. P1 · P2 가 필요할 수 있어 `12` 와 함께 W6 초에 한다.

## 6. 진행 기록

| 주 | 끝난 TASK | 밀린 것 · 이유 | 쓴 시간 | 조정 |
|---|---|---|---|---|
| W1 | | | | |
| W2 | | | | |
| W3 | | | | |
| W4 | | | | |
| W5 | | | | |
| W6 | | | | |
| W7 | | | | |
