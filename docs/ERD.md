# ERD — 테이블과 칸 한눈에

> 테이블 · 칸 · 연결을 한 장에 모은 **보기용 그림**이다. 칸의 뜻 · 빈 값 규칙 · `CHECK` · 색인 · 지우는 순서는 [02 §8](./02-domain-data.md#8-테이블) 이 단일 출처다 — **02 §8 을 고치면 이 그림도 같은 변경에서 고친다.**
> 이름은 [GLOSSARY](./GLOSSARY.md), 단위는 [02 §1](./02-domain-data.md#1-단위와-관계), 상태 같은 값 목록과 뜻은 [02 §8.7](./02-domain-data.md#87-값-목록).

## 읽는 법

- **모든 테이블에 `id`(bigserial PK) · `created_at` · `updated_at` 이 있다** — 그림에는 `id` 만 적고 시각 둘은 뺐다. `created_at` 이 뜻을 갖는 곳: `users`(신청한 때) · `reviews`(평가한 때) · `generation_jobs`(받은 순서) · `credit_entries`(마지막 채움 뒤의 씀인지 — 복구 조건) · `llm_calls`(한 달 합계).
- 칸 설명 끝의 **(빈 값)** 은 비어 있을 수 있는 칸이다. 언제 비는지는 02 §8.
- **입력 칸 묶음**(`topic` · `level` · `level_other` · `purpose` · `purpose_other` · `memo`)은 `roadmaps` · `courses` · `generation_jobs` 가 같이 쓴다(ADR-137).
- **SET NULL** 이라 적은 연결만 지울 때 저절로 끊긴다 — `cards.lesson_id` · `credit_entries.job_id` · `llm_calls.job_id` · `llm_calls.user_id`(ADR-156). 나머지는 자식부터 지운다(02 §8.6).
- 로그인 세션 테이블(`SPRING_SESSION` · `SPRING_SESSION_ATTRIBUTES`)은 기존 그대로라 그리지 않았다.

## 전체

```mermaid
erDiagram
    users ||--o{ credit_entries : "크레딧 움직임"
    users ||--o{ roadmaps : ""
    users ||--o{ courses : ""
    users ||--o{ cards : "주인"
    users ||--o{ concepts : "개념 사전"
    users ||--o{ generation_jobs : ""
    users |o--o{ llm_calls : "SET NULL"
    roadmaps |o--o{ courses : "로드맵의 코스"
    courses ||--o{ lessons : "1~10"
    lessons ||--o| materials : "처음 열 때"
    lessons |o--o{ cards : "SET NULL"
    cards ||--o{ card_concepts : ""
    concepts ||--o{ card_concepts : ""
    cards ||--o{ reviews : "평가 한 번씩"
    generation_jobs }o--o| courses : "대상 · 결과"
    generation_jobs }o--o| roadmaps : "대상 · 결과"
    generation_jobs }o--o| lessons : "강의 자료"
    generation_jobs |o--o{ credit_entries : "SET NULL"
    generation_jobs |o--o{ llm_calls : "SET NULL"

    users {
        bigint id PK
        text provider UK "provider + subject 고유"
        text subject UK
        text email
        text name
        text status "APPLIED(신청) · APPROVED(승인) · REJECTED(거절됨)"
        timestamptz approved_at "(빈 값) 승인한 때"
        integer credit_balance "남은 크레딧 — 신청할 때 채운다"
        timestamptz credit_refill_at "(빈 값) 다음에 채우는 때 — APPROVED(승인)일 때만"
    }

    credit_entries {
        bigint id PK
        bigint user_id FK
        text type "REFILL(채움) · CONSUME(씀, −) · RESTORE(복구, +)"
        integer amount "credit_balance 가 바뀐 만큼"
        bigint job_id FK "(빈 값) SET NULL"
    }

    roadmaps {
        bigint id PK
        bigint user_id FK
        text title
        text description "로드맵 설명 — 계획과 함께 만든다"
        text status "REVIEWING(검토 중) · IN_PROGRESS(진행 중) · COMPLETED(다 마침)"
        text topic "입력 칸 묶음"
        text level
        text level_other "(빈 값)"
        text purpose
        text purpose_other "(빈 값)"
        text memo "(빈 값)"
    }

    courses {
        bigint id PK
        bigint user_id FK
        bigint roadmap_id FK "(빈 값) 단일 코스면 빈다"
        integer position "(빈 값) 로드맵 안의 순서 — roadmap_id + position 고유"
        text title
        text status "NOT_PLANNED(짜기 전 — 로드맵의 코스만) · REVIEWING(검토 중) · LEARNING(학습 중) · COMPLETED(다 마침)"
        text topic "(빈 값) 입력 칸 묶음 — 단일 코스만"
        text level "(빈 값)"
        text level_other "(빈 값)"
        text purpose "(빈 값)"
        text purpose_other "(빈 값)"
        text memo "(빈 값)"
        text plan_description "(빈 값) 로드맵의 코스만"
        jsonb plan_concepts "(빈 값) 로드맵의 코스만 — name, definition 목록"
        jsonb mastery_report "(빈 값) 숙련도 반영 내역"
        timestamptz started_at "(빈 값) 이대로 시작한 때"
        timestamptz last_lesson_opened_at "(빈 값)"
        timestamptz completed_at "(빈 값) 다 마친 때 — COMPLETED(다 마침)일 때만"
    }

    lessons {
        bigint id PK
        bigint course_id FK
        integer position "1~10 — course_id + position 고유"
        text title
        text description
        jsonb concepts "다룰 개념 — name, definition 목록"
        timestamptz completed_at "(빈 값) 학습 완료한 때"
    }

    materials {
        bigint id PK
        bigint lesson_id FK, UK
        text body "마크다운 본문"
    }

    cards {
        bigint id PK
        bigint user_id FK
        bigint lesson_id FK "(빈 값) SET NULL — 코스를 지우면 빈다"
        integer position "lesson_id + position 고유"
        text question
        text answer
        text status "ACTIVE(쓰는 중) · COURSE_DELETED(코스 지워짐)"
        timestamptz due_at "(빈 값) 학습 완료 전이면 빈다"
        text fsrs_state "LEARNING(처음 배우는 중) · REVIEW(복습 중) · RELEARNING(다시 배우는 중)"
        integer fsrs_step "(빈 값) REVIEW(복습 중)면 빈다"
        double stability "(빈 값) 복습 전이면 빈다"
        double difficulty "(빈 값)"
        timestamptz last_reviewed_at "(빈 값)"
    }

    card_concepts {
        bigint card_id PK, FK
        bigint concept_id PK, FK
    }

    reviews {
        bigint id PK
        bigint card_id FK
        text rating "AGAIN(다시) · HARD(어려움) · GOOD(좋음) · EASY(쉬움)"
    }

    concepts {
        bigint id PK
        bigint user_id FK
        text name "겹칠 수 있다"
        text definition
        vector embedding "차원을 고정하지 않는다"
    }

    generation_jobs {
        bigint id PK
        bigint user_id FK
        text type "NEW_COURSE(새로 배우기 — 코스) · NEW_ROADMAP(새로 배우기 — 로드맵) · PLAN_ROADMAP_COURSE(로드맵 코스 하나의 강의 목록 — 첫 코스 · 다음 코스) · REPLAN_COURSE(다시 짜기 — 코스) · REPLAN_ROADMAP(다시 짜기 — 로드맵 계획) · MATERIAL(강의 자료 + 카드)"
        text status "QUEUED(기다림) · RUNNING(만드는 중) · SUCCEEDED(성공) · FAILED(실패)"
        text topic "(빈 값) 입력 칸 묶음 — NEW_*(새로 배우기)만"
        text level "(빈 값)"
        text level_other "(빈 값)"
        text purpose "(빈 값)"
        text purpose_other "(빈 값)"
        text memo "(빈 값)"
        text feedback "(빈 값) REPLAN_*(다시 짜기)만"
        bigint course_id FK "(빈 값) 대상 · 결과 — 셋 가운데 많아야 하나"
        bigint roadmap_id FK "(빈 값)"
        bigint lesson_id FK "(빈 값)"
        integer claim_count "꺼낼 때마다 1 늘린다"
        timestamptz lease_until "(빈 값) RUNNING(만드는 중)일 때만"
        text error_type "(빈 값) FAILED(실패)일 때만 — PROVIDER_UNAVAILABLE(연결 · 시간 초과 · 몰림) · INVALID_OUTPUT(형식 검사 불합격) · PROVIDER_REJECTED(키 · 쿼터 같은 설정 문제) · INPUT_TOO_LARGE(입력이 너무 큼)"
        timestamptz started_at "(빈 값)"
        timestamptz finished_at "(빈 값)"
    }

    llm_calls {
        bigint id PK
        bigint job_id FK "(빈 값) SET NULL"
        bigint user_id FK "(빈 값) SET NULL"
        text job_type
        text call_type "CHAT(대화 생성) · CONTINUATION(이어 쓰기) · EMBEDDING(임베딩)"
        text model
        integer input_tokens "(빈 값)"
        integer output_tokens "(빈 값)"
        numeric cost "(빈 값)"
        integer duration_ms "(빈 값)"
        text outcome "(빈 값) 끝나기 전이면 빈다 — SUCCEEDED(성공) · TRUNCATED(잘림) · INVALID_OUTPUT(형식 검사 불합격) · TIMEOUT(시간 초과) · RATE_LIMITED(몰림 429) · PROVIDER_ERROR(프로바이더 · 연결 오류) · REJECTED(키 · 쿼터 같은 거절)"
    }
```
