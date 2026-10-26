# 아키텍처

> **요약 입구다.** 상세와 근거는 [04 서버 구조 · 잡 · 비용](./04-jobs-and-cost.md) · [02 데이터](./02-domain-data.md) · [03 생성](./03-generation.md) · [05 API](./05-api.md) — 둘이 다르면 그쪽이 맞다.
> 전제는 하나다 — **LLM 호출은 느리고(수 초~1분), 매번 돈이 나가고, 실패한다.**

## 디렉토리 구조

```
/
├── backend/                         # Spring Boot — EC2 에 배포. 단일 Gradle 모듈 · 자체 wrapper(ADR-068)
│   ├── build.gradle · settings.gradle · gradlew · gradle/wrapper/
│   ├── Dockerfile · .dockerignore   # 서버 이미지(빌드 → JRE 실행, exec 형식 ENTRYPOINT) — 로컬 compose 가 쓰고 운영도 쓸 수 있다(ADR-069)
│   ├── src/main/java/…/mogum/
│   │   ├── MogumApplication.java    # 진입점 · @ConfigurationPropertiesScan
│   │   ├── config/                  # 설정 값(WebProperties · AuthProperties) · 시계 빈 · app.role 별 빈 등록 — worker · generation 을 조립하는 유일한 곳
│   │   ├── common/                  # 공통 오류 응답(ApiError · ApiExceptionHandler · NotFoundException → 404) · SeoulClock
│   │   ├── auth/                    # Spring Security · 구글 OAuth 로그인 처리 · 세션 → 회원 필터 · API 레이트리밋 필터
│   │   ├── account/                 # 회원 · 로그인 — 도메인 패키지는 항상 아래 다섯 하위 패키지로 나눈다
│   │   │   ├── controller/          # MeController
│   │   │   ├── service/             # LoginService · ExternalAccount(입력) · LoginResult(결과)
│   │   │   ├── repository/          # UserRepository(Spring Data) · 네이티브 쿼리 클래스
│   │   │   ├── domain/              # User(@Entity · Lombok @Getter · @NoArgsConstructor(PROTECTED) · 생성은 정적 팩토리 User.join) · UserRole · UserStatus · 도메인 규칙
│   │   │   └── dto/                 # MeResponse — HTTP 요청 · 응답만
│   │   ├── <도메인>/                 # curriculum · material · concept · review … — account 와 같은 다섯 하위 패키지. MasteryPolicy 등은 domain/
│   │   ├── job/                     # 적재 · 재시도 · 잡 조회 API · jobs/job_events 네이티브 SQL(repository/) — 같은 다섯 하위 패키지 · generation 참조 금지
│   │   ├── usage/                   # llm_calls 원장 · 일일 · 월 합계(쿼터 · 킬스위치 근거) — 생성과 적재가 함께 쓴다 · 같은 다섯 하위 패키지
│   │   ├── generation/              # 생성 유스케이스(잡 I · A · B ①②③ · resolveConcepts) + 유료 호출(LlmClient · EmbeddingClient · GuardedLlmClient · InputBudget) — worker · config 만 참조. 계층이 아니라 용도로 나눈다
│   │   │   └── llm/                 # OpenAI 어댑터 — OpenAI SDK 는 여기에만
│   │   └── worker/                  # 수령 루프 · 실행기 N 개 · 단계 실행 — config 만 참조
│   ├── src/main/resources/
│   │   ├── db/migration/            # Flyway 버전드 SQL(세션 테이블 포함)
│   │   ├── prompts/v6/              # 운영 프롬프트 — 개발하며 새로 만든다. 명세는 03 §2~§6. 바꾸면 v7 로(금지 목록 18)
│   │   └── (static/ 없음)           # 화면은 Vercel 에 따로 배포한다
│   └── src/test/java/…              # 구현과 같은 패키지에 짝 테스트 · ArchUnit(+ archfixture 위반 픽스처) · Testcontainers · 장애 주입 · LLM 스텁
├── compose.yaml                     # 로컬 Postgres(pgvector) + 서버 컨테이너 — 저장소 루트에서. 포트는 127.0.0.1 에만 연다
├── .env.example                     # 로컬 백엔드 환경 변수 견본 — .env 는 커밋하지 않는다
├── deploy/                          # 운영 EC2 배포
│   ├── compose.yaml · Caddyfile     # Caddy · 앱 · Postgres, HTTPS · 메모리 · 종료 대기
│   ├── deploy.sh · backup.sh · restore.sh · mogum.env.example
│   ├── aws/                        # OIDC/EC2 신뢰 · 역할 권한 · ECR/S3 수명주기 JSON 견본
│   ├── systemd/                    # 일일 백업 service · timer
│   └── README.md                   # 처음 구축 · 운영 확인 · 복원/되돌리기 절차서
├── .github/workflows/deploy.yml     # main → OIDC · ECR · S3 · SSM 배포 · 공개 HTTPS 헬스
├── frontend/                    # React + TypeScript · Vite — Vercel 에 정적 파일로 배포
│   ├── vercel.json             # 정적 파일 우선 · SPA index.html rewrite
│   └── src/
│       ├── pages/               # U1~U9 · P1 · P2 — 화면 하나에 파일 하나
│       ├── components/          # 상단 바 · 카드 · 막힘 문구 · 마크다운 렌더러(KaTeX · mermaid)
│       ├── api/                 # fetch 래퍼(API 주소 · credentials · XSRF-TOKEN 헤더) · 05 응답 타입 · TanStack Query 훅
│       └── lib/                 # 문구 표(06 §15) · 유틸
├── phases/                      # 하네스 step 파일(Claude 가 쓰고 Codex 가 구현) · index.json. 실행 산출물(*-output.json · *-review.json)은 커밋하지 않는다
├── scripts/                     # 하네스 — execute.py(step 실행 · 리뷰 · 커밋) · tdd_guard.py(TDD 가드 훅) · 그 테스트
├── .claude/                     # Claude Code 설정 — 훅 배선(settings.json) · /harness · /review 명령. 개인 설정(settings.local.json)만 커밋하지 않는다
├── .codex/                      # Codex 설정 — 훅 배선(hooks.json) · 위험 명령 금지 규칙(rules/)
├── prompts/v5/                  # 1단계 실험 프롬프트 — 로컬에만 · 저장소 밖(.gitignore). 실측 근거로만 참고
└── reference-code/              # 1단계 실측 OpenAI 어댑터 — 로컬에만 · 저장소 밖 · 참고용
```

## 패턴

| 패턴 | 내용 | 상세 |
|---|---|---|
| **도메인별 패키지 · 표준 계층** | 도메인마다 Controller → Service → Repository 와 엔티티를 한 패키지에 두고, 그 안은 `controller` · `service` · `repository` · `domain` · `dto` 로 항상 나눈다. 생성과 유료 호출은 `generation` 에 모으고 `worker` · `config` 만 참조한다. `worker` 는 `config` 만 참조한다. **ArchUnit 이 첫 커밋부터 강제**(ADR-067) | [04 §2](./04-jobs-and-cost.md#2-패키지-경계) |
| **잡 테이블이 큐** | `jobs` + `FOR UPDATE SKIP LOCKED`. 수령 한 문장이 FIFO · 동시 수령 방지 · 죽은 잡 회수 · 펜싱 회차 증가를 한다 | [04 §4](./04-jobs-and-cost.md#4-생성-작업--jobs-테이블이-큐다) |
| **수령 회차 펜싱** | `jobs` 의 모든 쓰기는 명시적 SQL + `lease_epoch` 조건. `0 rows` 면 즉시 중단. JPA 엔티티가 아니다 | [04 §4.4](./04-jobs-and-cost.md) |
| **LLM 경로의 드러낸 트랜잭션** | 일반 서비스는 `@Transactional`. LLM 을 부르는 `generation` · `worker` 는 `TransactionTemplate` 으로 짧게 끊고(ArchUnit 이 `@Transactional` 을 막는다) LLM 호출 중에는 커넥션을 쥐지 않는다. 호출을 사이에 둘 때는 엔티티 대신 ID · 값을 넘긴다 | [04 §5](./04-jobs-and-cost.md) |
| **감싼 LLM 호출** | `GuardedLlmClient` 가 재시도 · `usage` 기록 · 리스 갱신을 모든 호출에 똑같이. `LlmClient` 는 1턴 단위 | [03 §8](./03-generation.md#8-llm-호출-규약) |
| **독립적인 사용량 기록** | 채팅 · 임베딩 시도별 `llm_calls`. 사용량은 원장 행에 한 번만 확정하고(조건부 UPDATE) 일일 합계는 저장하지 않고 원장에서 더한다. 잡 결과 저장과는 분리. 리스를 잃어도 사용량을 기록한다 | [02 §6.8](./02-domain-data.md) |
| **작업 전체 호출 상한** | 원장의 종류별 누적 등록 건수를 잡 행 잠금 안에서 검사. 재시작 · 사용자 재시도에도 유지하며, 미확인 호출도 몫을 쓴다 | [04 §4.8](./04-jobs-and-cost.md) |
| **입력 예산 검사** | 재료 상한의 합으로 단계별 정적 최대 입력을 설계 때 계산하고, 요청마다 전체 요청의 UTF-8 바이트 수(토큰 수의 증명된 상계)를 등록 전에 확인. 초과는 전송하지 않으며, 검사한 요청을 그대로 보낸다 | [03 §8.7](./03-generation.md) |
| **관련 숙련도 선별** | 주제/목적 임베딩 검색 + 키워드 검색. 사용자 학습 개념만 후보로 삼고, 선별한 ID의 숙련도를 예산 안에서 주입. 검색 근거는 생성 시점 스냅샷으로 보존 | [02 §5.4](./02-domain-data.md) |
| **단계 산출물 저장** | 잡 B 는 ①②③ 단계마다 `payload` 저장 → 실패한 단계부터 재개 | [04 §4.6](./04-jobs-and-cost.md) |
| **파생값은 저장하지 않는다** | 숙련도는 조회 때 계산, "생성 중 · 실패"는 잡으로 판단 | [02 §1 · §4](./02-domain-data.md) |
| **역할 전환(비상용)** | 같은 jar 를 `app.role` = `all` · `api` · `worker` 로. 판단은 `config` 의 빈 등록에서만 — 실행기는 컴포넌트 스캔으로 올리지 않는다 | [04 §3.1](./04-jobs-and-cost.md) |

## 데이터 흐름

**로그인 — 회원당 외부 로그인 식별자 하나(ADR-066)**

```text
구글 인증 완료 → auth: 검증된 등록과 응답을 ExternalAccount로 변환
              → account.service.LoginService: 이메일 검증 · 허용 목록 검사(DB 접근 전)
              → UserRepository.findByProviderAndSubject 로 조회
                  기존 회원: 기존 ID 반환, 계정 정보 보존
                  신규 회원: 외부 식별자를 포함해 users 한 행 저장
              → auth: 사용자 ID만 세션에 저장 · 화면으로 이동
```

서비스 회원 ID는 세션과 학습 데이터의 기준이다. 로그인 식별자는 회원 테이블의 `(provider, subject)` 두 컬럼이며 복합 unique 제약으로 보호한다. 로그인 입력은 `ExternalAccount(provider, subject, email, emailVerified)`다. `GoogleAccountMapper`가 구글 응답을 해석하며 현재 활성 제공자는 구글 하나다. V6는 기존 `google_sub`를 `subject`로 바꾸고 provider를 `google`로 채우면서 회원 정보와 ID 세션을 보존한다([02 §6.1](./02-domain-data.md)). 별도 연결 테이블은 두지 않는다.

**생성 — 전부 비동기**

```
화면 → POST(커리큘럼 · 자료 · 재시도)
     → job(컨트롤러 → 서비스, @Transactional): 월 킬스위치 → 월 · 일일 비용 쿼터 → 진행 중 2개(사용자 advisory lock) → jobs INSERT → 202 { jobId }
     → 화면: GET /jobs/{id} 2초 폴링(TanStack Query)
worker 실행기(빈손일 때만 1개 수령)
     → 단계마다: 짧은 트랜잭션으로 읽기 → LLM 호출(커넥션 없음 · 시도마다 리스 갱신) → 짧은 트랜잭션으로 산출물 저장
       각 외부 시도: 실행 권한 확인 + 원장 등록 → 전송 → 원장 행 확정 → 리스 재확인
     → 완료 트랜잭션: jobs UPDATE 를 맨 앞에 → Material · Flashcard · 개념(사용자 락 안 · 사용자별 사전) · Lesson.READY · 성공 이벤트
     → 실패 중 사람이 움직여야 하는 것(크레딧 소진 · 요청 거부) → 슬랙(하루 한 번)
```

초기 요청은 워커의 첫 생성 호출에서 단일/그룹 판단과 해당 결과를 함께 받는다. 단일 강의 목록은 추가 목록 생성 없이 검토로, 그룹 분할안은 사용자 요청 시 1단계 상세 생성으로 이어진다. 접수 시 `CURRICULUM_INITIAL` 잡에 입력을 저장하고 성공 시 해당 리소스를 새로 만들어 FK/성공/이벤트를 함께 커밋한다. 초기 실패에는 도메인 리소스가 없으며 잡 ID/미완료 요청 목록으로 복귀한다([03 §2.1](./03-generation.md)).

**학습 · 복습 — 동기**

넓은 주제는 전체 목표와 단계별 제목/범위/목적/권장 순서의 분할안을 먼저 보존한다. 상세 커리큘럼은 1단계부터 사용자 클릭으로 생성하고, 모든 강의 완료 후 다음 단계 생성 안내를 제공한다. 다음 단계 요청은 최신 숙련도를 선별하는 기존 비동기 생성 경로로 이어지며 복습 완료를 요구하거나 자동 적재하지 않는다. 저장 구조는 `curriculum_groups` → `curriculum_steps` ← `curriculums.step_id`이며 초기 통합 잡은 `CURRICULUM_INITIAL`이다. 묶음/단계 상태는 저장하지 않고 연결 커리큘럼/잡에서 조회한다. 그룹 조회/단계 생성 API와 잡 I의 출력/호출 규약은 구현 전 설계 항목이다([02 §2.1](./02-domain-data.md) · [05 §2.1](./05-api.md)).

```
최초 학습 완료 → 강의 완료 · card_states 최초 생성(NEW · 오늘) · 전 강의 완료 시 커리큘럼 완료를 같은 트랜잭션으로 처리 → 응답에 그 강의의 현재 미평가 카드 → 즉시 첫 복습. 완료 재요청도 성공하며 기존 복습 상태/이력은 보존한다([02 §3](./02-domain-data.md)).
오늘의 복습 → card_states 중 한국 날짜로 오늘 안에 예정된 것 → 평가 → FSRS 갱신 + reviews 삽입(한 트랜잭션)
다음 생성 → 잡 A는 주제/목적 의미 · 키워드 검색으로 관련 개념 선별 / 잡 B는 이름으로 조회
         → 숙련도(R · S · minReviewCount) 조회 → 백엔드 MasteryPolicy 판정 → LLM이 판정에 맞춰 구성
         → 잡 A: 반영 내역 검증 / 잡 B: 백엔드가 개요의 SKIP · BRIEF · DETAIL 및 목표 분량 확정 후 본문 생성
```

## 상태 관리

| 상태 | 어디에 |
|---|---|
| 도메인 · 잡 · 큐 · 쿼터 · 킬스위치 · 정원 · 알림 중복 방지 | **PostgreSQL 하나** — 프로세스 메모리에 공유 상태를 두지 않는다(예외: API 레이트리밋 버킷) |
| 로그인 | 쿠키 세션 → Spring Session JDBC(Postgres). 세션에는 사용자 id 만, 상태 · 역할은 요청마다 `users` 에서 |
| 화면의 서버 상태 | TanStack Query — 폴링 · 429 때 간격 늘리기 · 무효화. 전역 상태 라이브러리는 쓰지 않는다 |
| 화면의 로컬 상태 | `useState` — 답 보기 · 인라인 확인 펼침 · 입력 |

## 배포

성공/실패 전환과 이벤트 저장은 같은 트랜잭션이다. 리스 만료 RUNNING 수령도 회수 이벤트와 함께 커밋한다. U6의 최근 24시간 실패율/회수는 `job_events`, 호출 재시도 등록/429/비용은 `llm_calls`, 현재 대기/실행 상태는 `jobs`에서 읽는다. 작업 삭제/탈퇴 시 이벤트의 잡 연결만 해제하며 사용자/콘텐츠 원문 없는 운영 이력은 보존한다([02 §6.9](./02-domain-data.md)).

화면은 Vercel(`{도메인}` — 정적 파일) · API 는 EC2 한 대(`api.{도메인}`) · JVM 하나(`app.role=all` — API + 워커 실행기 `N` 개) · PostgreSQL + pgvector(앱과 같은 EC2 · 2GB — EC2 안은 Caddy · 앱 · Postgres 의 Docker Compose, ADR-072) · OpenAI. 운영 관측은 관리자 화면 U6(DB 만 읽는다) + 슬랙 알림 + 외부 헬스체크. ([04 §3](./04-jobs-and-cost.md#3-배포))

처음 구축 · 운영 확인 · 백업/복원 · 되돌리기는 [운영 절차서](../deploy/README.md)를 따른다. 저장소의 배포 파일과 워크플로는 만들었고, 절차서의 처음 구축 순서 1~8은 2026-10-25~26 수행했다 — 첫 배포가 성공했고 운영 도메인 · 백업 · 복원 연습을 확인했다. 남은 운영 확인(목록 밖 계정 · `SESSION` 쿠키 · CSRF · 되돌리기)은 절차서에 표시했다.
