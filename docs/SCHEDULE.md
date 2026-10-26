# 개발 일정 — 2026-10-12 ~ 11-30

> 2026-10-11 인터뷰로 정했다([ADR-058](./ADR.md)). 2026-10-13 에 다시 잡았다 — TASK 를 의존 순서로 다시 배치하고, 배포를 2번으로 당기고, 마일스톤을 1주 당겼다([ADR-063](./ADR.md)). 2026-10-26 주간 점검에서 마일스톤을 다시 1주 미뤘다 — `2-deploy` 재설계와 인프라 첫 구축으로 W1~W2 의 TASK 가 밀렸다(§6). 같은 날 사용자 요청으로 공통 규약 TASK 둘(`2a-api-response` · `2b-ops-logging`)을 `3` 앞에 넣었다(§4). 순서의 근거는 [01 §11](./01-overview.md#11-진행-순서)이고, 이 문서는 그 순서에 날짜와 TASK 를 붙인다.
> TASK 안의 step 은 각 TASK 를 시작할 때 `/harness` 로 설계한다([ADR-052](./ADR.md)). 이 문서는 step 을 미리 쓰지 않는다.
> 매주 일요일에 실제 진행을 §6 에 적고 다음 주를 조정한다.

## 1. 목표와 전제

| | |
|---|---|
| **목표** | 11/30 까지 [공개 필수 목록](./01-overview.md#10-공개-필수-목록) 전부. 12월 첫 주에 바로 정원제로 공개할 수 있는 상태 |
| **완료에 포함** | 운영 서버 배포 · 본인 실사용 · 콘텐츠 품질 게이트 통과([01 §9](./01-overview.md#9-콘텐츠-품질--채점표와-공개-게이트)) |
| **밀리면** | **범위를 지키고 날짜를 미룬다.** 11/30 은 목표일이다 — §5 |
| **투입** | 1인 · 주 20시간 안팎(평일 저녁 + 주말) · 7주 ≈ 140시간(10/26 에 1주 늘렸다). 빠지는 날 없음. TASK 추정 시간은 그대로 쓴다 — W1 의 코드 TASK 는 추정보다 빨랐고 W2 는 재설계 · 인프라로 TASK 가 끝나지 않았다(§6). 더 빨리 끝나면 일요일 점검 때 다시 당긴다 |
| **실사용** | 핵심 경로가 돌면 **운영 서버(EC2)** 에서 휴대폰으로 시작한다(M2) |
| **범위 밖** | 숙련도 선별 실험([07 §1.3](./07-testing.md)) — 공개 후([ADR-057](./ADR.md)) |
| **외부 준비물** | OpenAI(결제 · 크레딧) · 구글 OAuth 클라이언트 준비됨. AWS · 도메인 · Vercel 은 10/25~26 준비했다(`2-deploy`). 슬랙은 `10-cost-control` 전에 만든다 |

## 2. 마일스톤

| 날짜 | 마일스톤 | 완료 기준 |
|---|---|---|
| **10/18(일)** → 10/26 달성 | M1 뼈대 · 운영 배포 | 백엔드 빌드(`./backend/gradlew -p backend build`) 통과 — ArchUnit · Flyway · Testcontainers. 화면은 `npm --prefix frontend run build` · `test` · `lint` 통과. `execute.py` 가 TASK 하나를 Codex 구현 → Claude 리뷰 → 커밋까지 끝까지 돈다. **운영 도메인에서 로그인 · CSRF · 화면 주소 새로고침이 된다 · DB 백업이 돈다** — 10/26 첫 배포로 확인. 운영 CSRF 는 PC 브라우저 확인이 남았다 |
| **11/8(일)** | M2 본인 실사용 시작 | 운영 서버에서 커리큘럼 → 강의 → 카드 → 복습을 한 바퀴 돈다. 나 말고는 생성할 수 없다 |
| **11/15(일)** | M3 품질 게이트 1차 채점 | 표본 10개의 채점 기록(CSV)이 있다 — 합격이든 불합격이든 |
| **11/29(일)** | M4 공개 준비 | 공개 필수 목록 전부 체크 · 용량 측정으로 정원 확정 · 품질 게이트 통과 |
| **11/30(월)** | 마감 | 여유 하루 |

## 3. 주차별 계획

시간은 사용자의 추정 시간이다(Codex 실행 시간이 아니다). TASK 하나 = 설계 · step 승인 · 실행 감시 · 막힘 해결 · `/review` 를 합쳐 약 3~7시간으로 잡았다.

| 주 | TASK | 사람이 할 일 | 추정 |
|---|---|---|---|
| **W1** 10/12–18 | `0-skeleton` · `1-auth`(끝) · `2-deploy` · `3-curriculum-model` | 구글 OAuth 클라이언트(끝) · **AWS 계정(생성일 확인 — [04 §3](./04-jobs-and-cost.md#3-배포)) · 도메인 구매 · Vercel 계정 · 요금제 약관 확인 · 구글 OAuth 에 운영 콜백 추가** | 약 20h(끝난 두 TASK 의 추정 11h 포함) — 실제는 §6 |
| **W2** 10/19–25 | `4-job-queue` · `5-llm-ledger` · `6-curriculum-gen` · `7-material-gen`(시작) | OpenAI 프로젝트 사용 한도 설정 — 실제 생성 호출 전에 | 약 20h — 실제는 §6(TASK 를 끝내지 못했다) |
| **W3** 10/26–11/1 | `2-deploy`(10/26 끝) · `2a-api-response` · `2b-ops-logging` · `3-curriculum-model` · `4-job-queue` · `5-llm-ledger`(시작) | **OpenAI 프로젝트 사용 한도 설정 — `5` 의 실제 호출 전에**(비상 상한 · 월 4만원 상당 — [04 §7](./04-jobs-and-cost.md)) · 운영 CSRF 등 PC 브라우저 확인([절차서](../deploy/README.md#운영-확인-목록)) | 약 20h |
| **W4** 11/2–8 | `5-llm-ledger` · `6-curriculum-gen` · `7-material-gen` · `8-review` → **M2** | **M2 실사용 시작** · 이후 매일 복습 | 약 20h + W3 에서 밀린 약 6~10h — 아래 |
| **W5** 11/9–15 | `9-mastery-loop` · `10-cost-control` · **품질 게이트 1차 채점**(`9` 가 끝난 뒤 · 약 12h) → **M3** | 슬랙 웹훅 · 외부 헬스체크 · 채점 | 약 22h |
| **W6** 11/16–22 | `11-delete-restart` · `12-screens-complete` · `13-account-public` | 약관 · 개인정보처리방침 문안 검토 · 구글 동의 화면 프로덕션 전환 신청. 1차 불합격이면 프롬프트 v7 + 새 표본 | 약 18h |
| **W7** 11/23–29 | `14-admin-status` · `15-fault-injection` · `16-capacity` → **M4** | 공개 필수 목록 점검 · 재채점(필요하면 약 12h) · 피드백 루프 확인 | 약 13~25h |

**10/26 조정 — 남은 추정 약 93~105시간 / W3~W7 100시간.** 여유가 거의 없고 W7 은 재채점이 필요하면 넘친다. 앞 주에 남은 시간은 다음 TASK 를 당겨 쓰고, 넘치면 일요일 점검 때 날짜를 다시 본다(§5).

**10/26 추가 — `2a` · `2b`(각 약 3~5h)로 남은 추정이 약 99~115시간 / 100시간이 됐다.** 뒤 TASK 가 약 6~10시간 밀려 W4 가 넘치고 M2(11/8)가 빠듯하다. 마일스톤은 그대로 두고 11/1 점검 때 M2 를 다시 본다(§5).

## 4. TASK 목록

`phases/{TASK}/` 디렉터리 이름이다. **TASK 완료 = 모든 step 승인 · `/review` 지적 반영 · PR 로 `main` 병합.** `main` 은 PR 로만 바뀐다(GitHub 룰셋 · `.githooks/`). `execute.py {TASK} --push` 로 `feat-{TASK}` 를 올리고 PR 을 열어 병합한 뒤, `main` 에서 `git pull --ff-only` 하고 다음 TASK 를 시작한다 — `execute.py` 는 지금 HEAD 에서 브랜치를 만든다.

**순서의 원칙 — 대상 → 장치 → 흐름.** 잡 · 원장 같은 장치는 그것이 가리킬 대상(테이블)이 있은 뒤에 만든다. 앞 TASK 가 아직 없는 대상을 가짜로 두고 만들면 뒤 TASK 가 열 · CHECK · 테스트를 다시 고친다([ADR-063](./ADR.md)).
**공통 규약은 도메인 TASK 앞에 둔다** — `2a`(응답 · 오류 · 예외) · `2b`(운영 로그)를 `3` 보다 먼저 해서, `3` 부터 더하는 예외 · 오류 코드 · 로그가 같은 규약 위에 쌓이게 한다. 그러면 뒤 TASK 가 이것들을 다시 고치지 않는다. 2026-10-26 에 사용자 요청으로 넣었고, 이미 문서에 쓰인 TASK 이름 참조를 바꾸지 않도록 번호를 다시 매기지 않았다.

| TASK | 주 | 목표 | 먼저 읽을 문서 | 선행 |
|---|---|---|---|---|
| `0-skeleton` | W1 | Gradle 단일 모듈 · 패키지(`core` ← `api` · `worker` · `infra`) · ArchUnit 규칙 · Flyway · Testcontainers(pgvector) · `TransactionRunner` · `Asia/Seoul` 시계 · 프론트 Vite 뼈대(jar 에 넣지 않는다 — ADR-060) · 로컬 Docker Compose. **FSRS 라이브러리 채택 확인** — 학습 단계를 끌 수 있는지(PRD 🅿️ "구현 첫 작업") | [ARCHITECTURE](./ARCHITECTURE.md) · [04 §2 · §5](./04-jobs-and-cost.md) | — |
| `1-auth` | W1 | 구글 OAuth · Spring Session JDBC · CSRF · 화면과 API 의 출처 분리(CORS · `XSRF-TOKEN` 쿠키 도메인 · 로그인 뒤 화면 주소로) · 로그아웃 · `users` · 소유권 `404` 공통 처리 · U1 최소판 · JSON API `/api` 접두사([05 §1](./05-api.md)) · JPA · 네이티브 `flush` 규칙 테스트(첫 엔티티 `users` — [07 §1.2](./07-testing.md)). **공개 전 운영 서버에서 나 말고는 생성할 수 없게 한다** — 관리자 이메일만 로그인한다([ADR-062](./ADR.md)) | [05 §1](./05-api.md) · [02 §6.1 · §7](./02-domain-data.md) | 0 |
| `2-deploy` | W1–W3 | 지금의 앱(로그인 · U1)을 EC2 에 먼저 올린다 — **Postgres 위치 결정**(1순위: 같은 EC2 2GB) · **앱 실행 방식 결정**(로컬과 같은 서버 이미지 `backend/Dockerfile` · jar + systemd — ADR-069) · 도메인(화면 → Vercel · `api.` → EC2) · HTTPS · 화면 Vercel 배포(API 주소 `VITE_API_BASE_URL` · 화면 주소 새로고침 → `index.html`) · 운영 환경 변수(`GOOGLE_CLIENT_ID` · `GOOGLE_CLIENT_SECRET` · `GOOGLE_REDIRECT_URI` · `APP_WEB_ORIGIN` · `APP_WEB_COOKIE_DOMAIN` · `APP_AUTH_ADMIN_EMAILS` — [05 §1.1](./05-api.md)) · `pg_dump` → S3 백업 · 배포 절차(SIGTERM 유예 설정 — [04 §4.5](./04-jobs-and-cost.md). 실행기 반납 동작은 `4` 에서 확인) · 운영 도메인에서 로그인 · CSRF · 새로고침 확인 | [04 §3 · §4.5](./04-jobs-and-cost.md#3-배포) · [05 §1.1](./05-api.md) · [01 §10](./01-overview.md) | 1 |
| `2a-api-response` | W3 | API 응답 · 오류 · 예외 처리 공통을 다시 설계한다. 범위: 성공 응답 형식(한 겹 감쌀지 포함) · 오류 응답 형식과 `code` 목록 · 도메인 예외를 `code` · HTTP 상태로 바꾸는 한 경로 · 입력 검증 실패. Spring Security(401 · 403 · `CSRF_INVALID`) · MVC 표준 예외 · 컨테이너 ERROR 디스패치도 같은 형식으로 응답하고, 화면 API 클라이언트(`frontend/src/api/`)는 `code` 로 분기한다. 지금의 `common`(`ApiError` · `ApiExceptionHandler` · `ApiErrorController` · `NotFoundException`)에서 출발하고, 형식을 바꾸면 05 §1.2 를 먼저 고친다. 예외를 로그에 어느 수준으로 남길지와 상관 ID 는 `2b` 에서 정한다 | [05 §1.2 · §1.3](./05-api.md) · [06 §2.6 · §15](./06-screens.md) | 2 |
| `2b-ops-logging` | W3 | 운영 로그 전략과 공통 설정. 정할 것: 무엇을 어느 수준으로 남길지(요청 · 인증 · 4xx/5xx · 기동/종료, 그리고 뒤 TASK 가 잡 · 외부 호출에 쓸 규칙) · 형식 · 요청 상관 ID(MDC — 잡의 `jobId` · `step` 은 `4` 가 이 규약에 더한다 — 04 §9) · 남기지 않을 것의 범위(비밀 · 세션/쿠키 · 개인정보 · 사용자 입력/생성 원문) · 운영 EC2 에서의 보관 · 회전 · 보는 법(지금은 SSM 에서 `docker logs` — 절차서) · 메트릭(04 §9) · 슬랙 알림(`10`)과 나누는 기준. 분산 트레이싱은 넣지 않는다(01 §6). 새 외부 서비스나 상주 프로세스를 들이려면 2GB EC2 메모리와 월 고정비 근거를 함께 적는다. 로그 장치를 `common` 에 더하면 `common` 하위 패키지 분리 여부와 모양(예: `api` · `time` · `logging`)도 이 TASK 설계에서 정하고 04 §2 의 "평평하게 둔다" 규칙을 함께 고친다(2a 브랜치 리뷰 때 사용자 결정으로 미룸) | [04 §9](./04-jobs-and-cost.md) · [01 §6](./01-overview.md) · [02 §7](./02-domain-data.md) · [절차서](../deploy/README.md#서버-구성) | 2a |
| `3-curriculum-model` | W3 | 잡이 가리킬 대상을 먼저 만든다 — `curriculum_groups` · `curriculum_steps` · `curriculums` · `lessons` 테이블 · JPA 엔티티 · Spring Data 리포지토리(`curriculum.domain` · `curriculum.repository` — ADR-067) · 주인 확인 조회. 생성 · API · 화면은 없다(`6` 에서) | [02 §1 · §2 · §2.1 · §6.2 · §6.2.1 · §7](./02-domain-data.md) | 2b |
| `4-job-queue` | W3 | `jobs`(대상 FK · 종류/상태별 CHECK 포함) · `job_events` · 적재(진행 중 2개 · 사용자 advisory lock · 대상당 활성 잡 하나) · `SKIP LOCKED` 수령 · 실행기 `N` · 리스 · 수령 회차 · 반납 · 회수 · 시한 · `GET /jobs/{id}` · `aheadCount` · `POST /jobs/{id}/retry`(공통 규칙 — DRAFT 검사는 `6`, 킬스위치 · 쿼터는 `10` 에서 앞에 끼운다) · `app.role` 역할별 빈 등록과 역할별 기동 테스트([04 §3.1](./04-jobs-and-cost.md)). 장애 주입 #1~#4(#4 는 리스 강제 만료로 — 실제 프로세스 종료는 `15`). 호출 상한 열 · `generation_limits` · 조회의 `retried` 는 `5`, 성공 전환 · 조회의 `result` · 단계 이름은 `6` · `7` 에서 | [04 §3.1 · §4 · §5 · §6 · §7.1](./04-jobs-and-cost.md) · [02 §6.9](./02-domain-data.md) · [05 §4 · §5](./05-api.md) · [07 §1.1](./07-testing.md) | 3 |
| `5-llm-ledger` | W3 | `LlmClient` · `EmbeddingClient` 인터페이스 · `GuardedLlmClient`(재시도 · 응답 분류 · `insufficient_quota`) · `llm_calls` 원장(`usage` 패키지) · 누적 호출 상한 · 입력 예산 검사 · OpenAI 어댑터(`generation.llm` · `maxRetries(0)`) · LLM 스텁 · `jobs` 의 호출 상한 열 · `generation_limits` · 잡 조회의 `retried`. 장애 주입 #8~#15 | [03 §8](./03-generation.md) · [02 §6.8](./02-domain-data.md) · [04 §4.8](./04-jobs-and-cost.md) · [07 §3](./07-testing.md) · `reference-code/` | 4 |
| `6-curriculum-gen` | W3–W4 | 잡 I(단일/그룹 판단) · 잡 A(다시 짜기 · 단계 생성) · 프롬프트 v6(I · A) · 성공 전환과 결과 연결(잡 조회의 `result` · 단계 이름 포함) · 요청 식별자 중복 처리 · 실패 요청 지우기 · 커리큘럼 · 묶음 조회와 승인 API · U2 · U10 · U3 · U11 최소판. 숙련도 선별은 학습 이력이 아직 없으므로 `EMPTY` 경로만 — 선별 연결은 `9` 에서 | [03 §2](./03-generation.md) · [02 §2.1 · §6.2 · §6.2.1](./02-domain-data.md) · [05 §2](./05-api.md) · [06 §6~§9](./06-screens.md) | 5 |
| `7-material-gen` | W4 | 잡 B ①②③ · 프롬프트 v6(B) · 이어 쓰기 · 개념 동일성(`resolveConcepts` · 정의 임베딩) · 강의 자료 요청 API · 잡 B 의 성공 전환 · 조회의 `result` · 단계 이름 · U4 최소판(KaTeX · mermaid). 숙련도 연결은 `9` 에서 — 그전에는 학습 이력이 없다 | [03 §3~§7](./03-generation.md) · [02 §5 · §6.3 · §6.4](./02-domain-data.md) · [05 §3](./05-api.md) · [06 §10](./06-screens.md) | 6 |
| `8-review` | W4 | 학습 완료(강의 ID 기준 한 번) · `card_states` · `reviews` · FSRS · 오늘의 복습(한국 날짜) · U5 최소판 | [02 §3 · §6.5 · §8.1](./02-domain-data.md) · [05 §6](./05-api.md) · [06 §11](./06-screens.md) | 7 |
| `9-mastery-loop` | W5 | 숙련도 `R` · `S` · `minReviewCount` 조회 SQL(라이브러리 일치 테스트) · `MasteryPolicy` · 잡 B 의 `lookupMastery` 연결 · 잡 I/A 숙련도 선별(잠정 설정 — [ADR-057](./ADR.md)). **피드백 루프가 여기서 닫힌다** | [02 §4 · §5.4 · §8.2.1 · §8.3](./02-domain-data.md) · [03 §2 · §3](./03-generation.md) | 8 |
| `10-cost-control` | W5 | 사용자 월 · 일일 비용 쿼터 · 전역 월 킬스위치(ADR-073) · 우리 API 레이트리밋 · 슬랙 알림 · 외부 헬스체크 · 막힘 문구 | [04 §7 · §9](./04-jobs-and-cost.md) · [05 §1.2 · §1.4 · §1.5](./05-api.md) · [06 §15](./06-screens.md) | 8 |
| `11-delete-restart` | W6 | 커리큘럼 완전 삭제 · 묶음 삭제 · 처음부터 다시 만들기 · 다시 짜기 하루 3회 · 늦은 응답이 지운 것을 되살리지 않는지 | [02 §6.2 · §6.2.1](./02-domain-data.md) · [04 §4.9](./04-jobs-and-cost.md) · [05 §2](./05-api.md) | 9 |
| `12-screens-complete` | W6 | 화면 "공개 전 완성" 열 — 대기 블록 전부 · 목차 · 키보드 · 모바일 하단 고정 · 기다리는 동안 모드 · 접근성 | [06 §2 · §16](./06-screens.md) · [UI_GUIDE](./UI_GUIDE.md) | 11 |
| `13-account-public` | W6 | U7 가입(약관 동의 · 정원 검사) · U8 대기 명단 · U9 설정 · 회원 탈퇴(삭제 범위 전부) · P1 · P2 · U1 가입 안내 · 정원 안내 · 바닥글 · 관리자 전용 로그인 걷어내기([ADR-062](./ADR.md)) | [02 §7](./02-domain-data.md) · [06 §3~§5 · §12 · §13](./06-screens.md) | 11 |
| `14-admin-status` | W7 | U6 시스템 상태(관리자 전용 · DB 만 읽는다) · 가입 섹션 | [05 §7](./05-api.md) · [06 §14](./06-screens.md) · [02 §6.9](./02-domain-data.md) | 13 |
| `15-fault-injection` | W7 | 앞 TASK 에서 못 한 프로세스 단위 장애 주입(`kill -9` · SIGTERM 등 — #4 의 실제 프로세스판 · #5)과 [07 §1.1](./07-testing.md) 16개 전체 확인 | [07 §1](./07-testing.md) | 14 |
| `16-capacity` | W7 | k6 · `loadtest` 프로필 · 운영과 같은 메모리 조건 → `N` · 워커 폴링 주기 · 정원 `U_max` 확정. 통과 기준은 측정 전에 정한다 | [07 §2 · §3](./07-testing.md) | 15 |

- 장애 주입 테스트는 해당 TASK 안에서 TDD 로 같이 쓴다. `15` 는 남은 것과 전체 확인만 한다.
- 품질 채점 · 약관 문안 · 외부 계정은 TASK 가 아니다 — Codex 가 할 수 없다. §3 의 "사람이 할 일" 에 둔다.

## 5. 운영 규칙

- **생성 구성은 `9-mastery-loop` 뒤로 고정한다.** 1차 채점(M3)은 그 구성으로 하고, 프롬프트 · 모델 · 후처리를 바꾸면 새 표본 10개로 다시 채점해야 한다([01 §9](./01-overview.md)). `10` 이후 TASK 의 step 에는 이 금지를 넣는다.
- **M2 가 11/15 를 넘기면 공개 준비 완료일을 다시 잡는다.** M2 뒤에 필요한 일(W5~W7)만 약 3주다.
- **매주 일요일 점검** — 끝난 TASK · 밀린 TASK · 쓴 시간을 §6 에 적고, 남은 TASK 를 다시 배치한다. 날짜가 바뀌면 §2 를 고친다.
- **Postgres 는 앱과 같은 EC2(2GB)로 정했다**(2026-10-21 · ADR-072). 용량 측정(`16`)은 이 메모리 조건으로 잰다([07 §2](./07-testing.md)). 측정 뒤 바꾸면 데이터 이전이 필요하다.
- **TASK 마다 배포한다** — `2-deploy` 이후의 TASK 는 PR 병합 뒤 운영 서버에 올리고, 운영에서만 드러나는 문제는 다음 TASK 를 시작하기 전에 고친다. 10/26 부터 `main` 병합(백엔드 · `deploy/` · 배포 워크플로 변경)이 곧 자동 배포다([절차서](../deploy/README.md#github-actions-배포)).
- **구글 동의 화면의 프로덕션 전환** — 요건(개인정보처리방침 URL · 앱 정보 등)과 걸리는 기간은 신청 전에 확인한다. P1 · P2 가 필요할 수 있어 `13` 과 함께 W6 초에 한다.

## 6. 진행 기록

| 주 | 끝난 TASK | 밀린 것 · 이유 | 쓴 시간 | 조정 |
|---|---|---|---|---|
| W1 | `0-skeleton` · `1-auth`(10/12) | `2-deploy` — 1차를 10/13 실행하고 10/15 폐기(로컬 태그 `archive/2-deploy-v1`), W2 에 다시 설계 · `3-curriculum-model` | 약 7h(10/12 커밋 시각 기준 추정 · `0-skeleton` 계획 시간 제외) — 추정 11h. 10/13~18 의 `2-deploy` 1차 · 설계 문서 리뷰 시간은 기록하지 않았다 | 10/13 재조정 — TASK 를 의존 순서로 다시 배치(`3-curriculum-model` 분리 · 배포를 `2` 로) · 마일스톤 1주 당김(ADR-063) |
| W2 | — | `2-deploy`(W3 첫날 10/26 끝) · `3`~`7` 시작 못 함 — 패키지 구조 재구성(ADR-067~071 · PR #9) · 배포 재설계(ADR-072 · 10/21) · 새 AWS 계정과 인프라 첫 구축(조직 SCP 리전 제한 · OIDC 불변 subject — [절차서](../deploy/README.md#처음-구축-순서)) | 약 15h(커밋 시각 기준 추정) | 10/20 패키지 구조를 도메인별 패키지 + 표준 Spring 계층으로 재구성(ADR-067) — `3` 부터 새 구조로 만든다. **10/26 점검 — 마일스톤 1주 미룸**(M2 11/8 · M3 11/15 · M4 11/29 · 마감 11/30), W3~W7 재배치. M1 은 10/26 달성(운영 CSRF 확인만 남음). AWS 실제 단가 약 $22/월(부가세 별도 — 절차서 「비용」)로 월 예산 5만원 중 LLM 몫은 약 1.7만원 — 예산 · 정원 조정은 사용자가 정한다 → **10/26 정했다** — LLM 만 월 3만원 목표(고정비 별도) · 사용자 월 2,000원 + 일 500원 · 정원 10명 · 전역 월 킬스위치(ADR-073) |
| W3 | `2a-api-response`(10/26 — step 0~4 첫 시도 승인 · 브랜치 리뷰 끝. 병합 전 사용자 결정으로 입력 검증 오류를 칸별 `{field, reason}` · `FIELD_INVALID` 로 다시 정한 수정 step 5 · 6 도 첫 시도 승인 · 브랜치 리뷰 끝 — PR #17 병합 전) · `2b-ops-logging`(10/27 — PR #17 위에 쌓아 진행. step 0~3 모두 승인(step 1 반려 1 · Claude 사용 한도로 리뷰 재개 1) · 브랜치 리뷰 중 — PR 은 #17 병합 뒤) | | `2a` 실행 약 19분 + 수정 step 4 · 5 · 6 약 10분(Codex · 리뷰) + 설계 · 리뷰 대화 · `2b` 실행 약 20분(사용 한도 대기 약 1시간 45분 제외) + 설계 · 리뷰 대화 | 10/26 사용자 요청으로 `2a-api-response` · `2b-ops-logging` 를 `3` 앞에 넣었다(§4) — 마일스톤은 그대로 두고 11/1 점검 때 M2 를 다시 본다. 10/27 `2b` 를 PR #17 병합 전에 로컬 `feat-2a-api-response`(`cff44fe`) 위에 쌓아 병렬로 시작했다 — §4 의 "병합 뒤 `main` 에서 다음 TASK" 의 예외(사용자 결정). 조건: PR #17 은 merge commit 으로 병합(squash · rebase 면 `2b` 에 2a 커밋이 두 번 들어간다) · 2a 를 rebase · force push 하지 않는다 · 2a 에 커밋이 더 붙으면 `2b` 에 merge. `2b` 의 PR 은 PR #17 병합 뒤에 연다 |
| W4 | | | | |
| W5 | | | | |
| W6 | | | | |
| W7 | | | | |
