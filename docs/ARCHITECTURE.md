# 아키텍처

> **요약 입구다.** 상세와 근거는 [04 서버 구조 · 잡 · 비용](./04-jobs-and-cost.md) · [02 데이터](./02-domain-data.md) · [03 생성](./03-generation.md) · [05 API](./05-api.md) — 둘이 다르면 그쪽이 맞다.
> 전제는 하나다 — **LLM 호출은 느리고(수 초~1분), 매번 돈이 나가고, 실패한다.**

## 디렉토리 구조

```
/
├── build.gradle                 # 단일 Gradle 모듈 — frontend 빌드 결과를 static/ 으로 복사
├── src/main/java/…/mogum/
│   ├── MogumApplication.java    # 루트 — 설정 배선 · app.role 별 빈 등록만
│   ├── core/                    # 도메인 · 유스케이스 · 포트(LlmClient · EmbeddingClient · JobStore · UsageLedger · TransactionRunner …)
│   │   ├── generation/          # 생성 유스케이스(잡 A · 잡 B ①②③ · resolveConcepts) — api 가 참조 금지
│   │   └── job/                 # 적재 유스케이스(검사 · 적재)
│   ├── api/                     # 컨트롤러 · 인증 · DTO · 레이트리밋
│   ├── worker/                  # 수령 루프 · 실행기 N 개 · 단계 실행
│   └── infra/                   # JPA 리포지토리 · jobs 명시적 SQL(JdbcClient) · OpenAI 어댑터 · pgvector · 슬랙 웹훅
├── src/main/resources/
│   ├── db/migration/            # Flyway 버전드 SQL(세션 테이블 포함)
│   └── static/                  # frontend 빌드 결과(커밋하지 않는다)
├── src/test/java/…              # ArchUnit · Testcontainers · 장애 주입 · LLM 스텁
├── frontend/                    # React + TypeScript · Vite
│   └── src/
│       ├── pages/               # U1~U9 · P1 · P2 — 화면 하나에 파일 하나
│       ├── components/          # 상단 바 · 카드 · 막힘 문구 · 마크다운 렌더러(KaTeX · mermaid)
│       ├── api/                 # fetch 래퍼(XSRF-TOKEN 헤더) · 05 응답 타입 · TanStack Query 훅
│       └── lib/                 # 문구 표(06 §12) · 유틸
├── prompts/v5/                  # 확정 프롬프트 — 바꾸면 v6 로(금지 목록 18)
└── reference-code/              # 1단계 실측 OpenAI 어댑터 — 참고용 · 구현 뒤 삭제
```

## 패턴

| 패턴 | 내용 | 상세 |
|---|---|---|
| **포트와 어댑터** | `core` 는 포트만 안다. `api` · `worker` · `infra` 가 `core` 를 참조하고 그 반대는 없다. **ArchUnit 이 첫 커밋부터 강제** | [04 §2](./04-jobs-and-cost.md#2-패키지-경계) |
| **잡 테이블이 큐** | `jobs` + `FOR UPDATE SKIP LOCKED`. 수령 한 문장이 FIFO · 동시 수령 방지 · 죽은 잡 회수 · 펜싱 회차 증가를 한다 | [04 §4](./04-jobs-and-cost.md#4-생성-작업--jobs-테이블이-큐다) |
| **수령 회차 펜싱** | `jobs` 의 모든 쓰기는 명시적 SQL + `lease_epoch` 조건. `0 rows` 면 즉시 중단. JPA 엔티티가 아니다 | [04 §4.4](./04-jobs-and-cost.md) |
| **드러낸 트랜잭션** | `@Transactional` 대신 `TransactionRunner` — LLM 호출 중에는 커넥션을 쥐지 않는다 | [04 §5](./04-jobs-and-cost.md) |
| **감싼 LLM 호출** | `GuardedLlmClient` 가 재시도 · `usage` 기록 · 리스 갱신을 모든 호출에 똑같이. 포트는 1턴 단위 | [03 §8](./03-generation.md#8-llm-호출-규약) |
| **단계 산출물 저장** | 잡 B 는 ①②③ 단계마다 `payload` 저장 → 실패한 단계부터 재개 | [04 §4.6](./04-jobs-and-cost.md) |
| **파생값은 저장하지 않는다** | 숙련도는 조회 때 계산, "생성 중 · 실패"는 잡으로 판단 | [02 §1 · §4](./02-domain-data.md) |
| **역할 전환(비상용)** | 같은 jar 를 `app.role` = `all` · `api` · `worker` 로. 판단은 루트 설정의 빈 등록에서만 | [04 §3.1](./04-jobs-and-cost.md) |

## 데이터 흐름

**생성 — 전부 비동기**

```
화면 → POST(커리큘럼 · 자료 · 재시도)
     → api: 킬스위치 → 비용 쿼터 → 진행 중 2개(사용자 advisory lock) → jobs INSERT → 202 { jobId }
     → 화면: GET /jobs/{id} 2초 폴링(TanStack Query)
worker 실행기(빈손일 때만 1개 수령)
     → 단계마다: 짧은 트랜잭션으로 읽기 → LLM 호출(커넥션 없음 · 시도마다 리스 갱신) → 짧은 트랜잭션으로 산출물 저장
     → 완료 트랜잭션: jobs UPDATE 를 맨 앞에 → Material · Flashcard · 개념(전역 락 안) · Lesson.READY
     → 실패 중 사람이 움직여야 하는 것(크레딧 소진 · 요청 거부) → 슬랙(하루 한 번)
```

**학습 · 복습 — 동기**

```
학습 완료 → card_state 생성(NEW · 오늘) 같은 트랜잭션 → 응답에 그 강의 카드 → 즉시 첫 복습
오늘의 복습 → card_state 중 한국 날짜로 오늘 안에 예정된 것 → 평가 → FSRS 갱신 + review 삽입(한 트랜잭션)
다음 생성 → 숙련도(R · S · minReviewCount)를 조회 때 계산해 프롬프트에 → SKIP · BRIEF · DETAIL
```

## 상태 관리

| 상태 | 어디에 |
|---|---|
| 도메인 · 잡 · 큐 · 쿼터 · 킬스위치 · 정원 · 알림 중복 방지 | **PostgreSQL 하나** — 프로세스 메모리에 공유 상태를 두지 않는다(예외: API 레이트리밋 버킷) |
| 로그인 | 쿠키 세션 → Spring Session JDBC(Postgres). 세션에는 사용자 id 만, 상태 · 역할은 요청마다 `users` 에서 |
| 화면의 서버 상태 | TanStack Query — 폴링 · 429 때 간격 늘리기 · 무효화. 전역 상태 라이브러리는 쓰지 않는다 |
| 화면의 로컬 상태 | `useState` — 답 보기 · 인라인 확인 펼침 · 입력 |

## 배포

EC2 한 대 · JVM 하나(`app.role=all` — API + 화면 정적 파일 + 워커 실행기 `N` 개) · PostgreSQL + pgvector(🅿️ 위치는 용량 측정 전에) · OpenAI. 운영 관측은 관리자 화면 U6(DB 만 읽는다) + 슬랙 알림 + 외부 헬스체크. ([04 §3](./04-jobs-and-cost.md#3-배포))
