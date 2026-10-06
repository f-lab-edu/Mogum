# 05. API

> 엔드포인트 · 요청 · 응답 · 상태 코드 · 에러 `code`. 화면 매핑은 §8, 화면 문구는 [06 §12](./06-screens.md).
> **생성은 전부 비동기다** — 새 잡을 만드는 요청은 `202` + `jobId` 를 돌려주고, 화면은 `GET /jobs/{id}` 를 2초마다 부른다.

---

## 1. 공통

### 1.1 인증 — 구글 OAuth + 쿠키 세션(DB 저장)

| 엔드포인트 | 동작 |
|---|---|
| `GET /auth/google` | `state` 를 짧은 수명 쿠키에 담고 구글 동의 화면으로 |
| `GET /auth/google/callback` | `state` 를 쿠키와 대조(CSRF) → 처음 온 사람이면 `PENDING` 사용자 생성 → **세션 생성 · 세션 쿠키** → 약관 전이면 U7 · 대기 명단이면 U8 · 아니면 U2 |
| `GET /me` | 현재 사용자 — 화면이 어디로 갈지 이걸로 정한다 |
| `POST /me/terms` | `{ "termsVersion": "…" }` → 동의 기록 → **처음이면 여기서 정원 검사** → `{ "status": "ACTIVE" \| "WAITLISTED" }` |
| `POST /auth/logout` | **이 기기의 세션만** 무효화 · 쿠키 삭제(`HttpOnly` 라 스크립트는 못 지운다). 다른 기기는 로그인이 유지된다 |
| `DELETE /me` | 회원 탈퇴 — 한 트랜잭션 삭제(**그 사용자의 세션 전부 포함**) → `204`. 약관 거부 · 대기 취소도 이걸 쓴다 |

```jsonc
// GET /me
{ "email": "someone@gmail.com", "status": "ACTIVE", "waitlistPosition": null, "termsAgreed": true,
  "role": "USER", "quota": { "usedRatio": 0.6, "resetsAt": "2026-12-15T00:00:00+09:00" } }   // 쿼터는 비율로만
```

- **세션은 Postgres 에 둔다**(Spring Security `oauth2Login` + **Spring Session JDBC**). 쿠키에는 세션 번호만 있다 — `HttpOnly` · `Secure` · `SameSite=Lax`. 로그인 때 세션 번호를 새로 발급한다(세션 고정 방어 — 프레임워크 기본).
- **비활동 7일이면 만료.** 세션에는 **사용자 id 만** 둔다 — 상태(`ACTIVE` · `WAITLISTED` · `PENDING`) · 역할은 **요청마다 `users` 에서 읽는다.** 승인 · 약관 변경이 바로 반영되게.
- **CSRF** — `SameSite=Lax` 에 더해 Spring Security 기본 CSRF 보호를 켠다(화면은 `XSRF-TOKEN` 쿠키를 읽어 헤더로 보낸다). API 와 화면은 같은 사이트에 둔다.
- 요청마다 **세션 조회 + 사용자 조회**, 그리고 마지막 접근 시각 갱신(세션 행 UPDATE)이 붙는다 — 용량 측정에 포함한다.
- **부하 테스트 세션** — `POST /dev/session { "userKey": "vu-17" }` 가 사용자(`ACTIVE` · 약관 동의)와 세션을 만들고 세션 쿠키를 준다. **`loadtest` 프로필에만 빈이 있다.** `loadtest` 와 `aws` 프로필이 함께 켜지면 **기동을 거부**한다. 제품 API 수에 세지 않는다.

> **왜 JWT 가 아닌가** — 로그아웃 · 탈퇴를 즉시 반영하고 대기 · 약관 같은 바뀌는 상태를 확인하려면 **어차피 요청마다 DB 를 본다.** 그러면 JWT 의 장점(DB 없이 검증)이 사라지고 발급 · 검증 필터 · 서명 키 관리만 남는다.
> 세션은 프레임워크 기본 동작이고, 기기별 로그아웃이 되고, DB 에 있어 배포 · 프로세스 분리에도 유지된다. 다시 볼 조건 — 쿠키를 못 쓰는 클라이언트(모바일 앱)나 다른 도메인의 서비스가 같은 인증을 믿어야 할 때.

### 1.2 에러

```json
{ "code": "USER_QUOTA_EXCEEDED", "message": "일일 한도를 모두 사용했습니다", "retryAfterSeconds": 32400 }
```

포맷보다 **`code` 가 고정돼 있는 것**이 중요하다. RFC 9457 은 쓰지 않는다(`type` URI 를 운영할 클라이언트가 하나뿐이다).

| `code` | HTTP | 언제 |
|---|---|---|
| `UNAUTHORIZED` | 401 | 세션 없음 · 만료 · 로그아웃 · 탈퇴로 삭제됨 |
| `TERMS_REQUIRED` | 403 | 약관 전 — `GET /me` · `POST /me/terms` · 로그아웃 · `DELETE /me` 만 된다 |
| `ACCOUNT_WAITLISTED` | 403 | 대기 명단 — 위 네 개만 된다 |
| `RATE_LIMITED` | 429 | 우리 API 레이트리밋 · `Retry-After` |
| `NOT_FOUND` | 404 | 없거나 **남의 것**(§1.3) |
| `STRUCTURAL_LIMIT` | 400 | 기간 1~8주 · 주당 3~5강 · 총 40강 · 입력 길이(주제 100 · 목적 200 · 피드백 500자) 위반 |
| `USER_QUOTA_EXCEEDED` | 429 | 일일 생성 비용 한도 · `Retry-After` = 한국 시간 자정까지 |
| `ACTIVE_JOB_LIMIT` | 429 | 진행 중 생성이 이미 2개. 하나 끝나면 풀린다 |
| `GLOBAL_BUDGET_EXHAUSTED` | 503 | 전역 킬스위치 · `Retry-After` |
| `REGENERATION_LIMIT` | 409 | 다시 짜기 3회 초과 |
| `CURRICULUM_NOT_DRAFT` · `CURRICULUM_GENERATING` · `CURRICULUM_NOT_ACTIVE` | 409 | 상태 충돌 — 버리기도 `DRAFT` 가 아니면 `NOT_DRAFT`, 짜는 중이면 `GENERATING` |
| `LESSON_NOT_READY` | 409 | 자료 없는 강의에 학습 완료 |
| `JOB_NOT_FAILED` | 409 | 실패하지 않은 잡에 재시도 |
| `JOB_NOT_RETRYABLE` | 409 | 거절된 주제(`TOPIC_REJECTED`) 잡에 재시도 |
| `NO_CARDS_DUE` | 409 | 복습할 카드가 없는데 세션 요청 |

- `429` = *"이 사용자가 많이 썼다 · 시간이 지나면 풀린다"*. `503` = *"서비스 전체가 지금 신규를 안 받는다"*. `403` = *"누구인지는 알지만 아직 못 쓴다"* — `401` 과 구분해야 화면이 로그인으로 튕기지 않는다.
- **우리 `429` 와 프로바이더 `429` 는 다른 층이다** — 메트릭에서 `api.reject{code}` 와 `llm.call.retry{reason}` 로 나눈다.

### 1.3 소유권 — 남의 것은 없는 것으로

`{id}` 가 붙은 모든 엔드포인트(`/curriculums/{id}` · `/lessons/{id}` · `/jobs/{id}` · `POST /reviews` 의 `flashcardId`)는 주인을 확인하고, 아니면 **`404`** 다. *"그 id 가 있다"* 는 사실도 남의 정보다. 관리자 전용 `GET /system/status` 도 관리자가 아니면 `404`.

### 1.4 우리 API 레이트리밋

| 대상 | 한도 |
|---|---|
| 로그인한 사용자 | 분당 120회(폴링 30회 + 화면 이동 여유) |
| 로그인 전(`/auth/*`) | IP 당 분당 20회 |

프로세스 안 **메모리 버킷**이다(서버가 한 대). 비용 방어가 아니라 폴링 폭주 · 스크립트 남용 방어다. `loadtest` 프로필에서는 끈다.

### 1.5 새 잡을 만드는 요청의 공통 검사

`POST /curriculums` · `POST /curriculums/{id}/regenerate` · `POST /lessons/{id}/materials`(신규) · `POST /jobs/{id}/retry` 는 적재 전에 **킬스위치 → 쿼터 → 진행 중 2개** 순서로 검사한다([04 §7.1](./04-jobs-and-cost.md)).

---

## 2. 커리큘럼 — U2 · U3

| | 엔드포인트 | 응답 |
|---|---|---|
| 목록 | `GET /curriculums` | 진행 중 · 완료 + 진도 + `nextLesson: { id, orderNo, title }`(처음으로 안 끝난 강의, `ACTIVE` 가 아니면 `null`) |
| 생성 | `POST /curriculums` `{ topic, purpose, weeks }` | `202 { curriculumId, jobId }` — **빈 `DRAFT` 를 먼저 만든다** |
| 상세 | `GET /curriculums/{id}` | 강의 목록 · 키워드 · `masteryReflection` · `generation` |
| 다시 짜기 | `POST /curriculums/{id}/regenerate` `{ feedback? }`(500자 이내) | `202 { jobId }` · 한도 초과 `409` |
| 승인 | `POST /curriculums/{id}/approve` | `DRAFT → ACTIVE`. 생성 잡이 진행 중이면 `409 CURRICULUM_GENERATING` |
| 그만두기 | `POST /curriculums/{id}/abandon` | `ACTIVE → ABANDONED`. 이 커리큘럼의 **대기 중 잡은 `CANCELLED`**, 실행 중인 잡은 끝낸다. 공부한 카드는 복습에 남는다 |
| 버리기 | `DELETE /curriculums/{id}` | `204`. **`DRAFT` 이고 활성 잡이 없을 때만** — 한 트랜잭션에서 `lesson` · 이 커리큘럼의 `jobs` · `curriculum` 삭제. `DRAFT` 가 아니면 `409 CURRICULUM_NOT_DRAFT`, 짜는 중이면 `409 CURRICULUM_GENERATING` |

```jsonc
// GET /curriculums/{id}
{
  "id": 12, "title": "경제 기초 4주", "topic": "경제 기초", "status": "DRAFT",
  "regenerationCount": 1, "regenerationLimit": 3,
  "masteryReflection": [ { "concept": "기회비용", "action": "EXCLUDED" } ],
  "lessons": [ { "id": 101, "orderNo": 1, "week": 1, "title": "…", "objective": "…",
                 "keywords": ["기회비용", "희소성"], "status": "NOT_STARTED",
                 "hasMaterial": false, "generating": false } ],      // generating 은 파생값 — 진행 중 잡이 있나
  "generation": { "jobId": 90, "status": "FAILED" }                   // 이 커리큘럼의 최근 잡 · 없으면 null
}
```

생성 요청을 보낸 화면은 **곧바로 U3 로 이동해 거기서 기다린다**(주소에 커리큘럼 id 가 있어 새로고침에도 안전).

---

## 3. 강의 — U4

### 3.1 자료 생성 요청 — `POST /lessons/{id}/materials`

| 상황 | 응답 |
|---|---|
| 자료가 이미 있다 | `200 { materialId }` |
| 신규 | `202 { jobId, position }` |
| 이 강의에 진행 중인 잡이 있다 | `202` + **기존 `jobId`** — 에러가 아니다 |
| 이 강의의 최근 잡이 `FAILED` | `202` + 기존 `jobId` — **그 잡을 재시도한다**(산출물이 이어진다) |
| 검사에 걸림 | §1.5 의 `429` · `503` |

동시 요청 둘은 부분 unique 인덱스가 하나를 실패시키고, API 가 그 예외를 잡아 기존 잡을 돌려준다([04 §4.7](./04-jobs-and-cost.md)).

### 3.2 열람 · 학습 완료

| | 엔드포인트 | 비고 |
|---|---|---|
| 열람 | `GET /lessons/{id}/material` | 마크다운 6섹션 + `truncated` |
| 학습 완료 | `POST /lessons/{id}/complete` | **같은 트랜잭션에서 그 강의 카드들의 `card_state` 를 만든다**(`NEW` · `next_review_at=now()`) |

```jsonc
// POST /lessons/{id}/complete → 200
{ "lessonStatus": "COMPLETED",
  "reviewCards": [ { "flashcardId": 1, "question": "…", "modelAnswer": "…", "keyPoints": ["…"], "concept": "기준금리",
                     "source": { "curriculumTitle": "경제 기초 4주", "lessonOrder": 6 } } ] }   // 방금 그 강의 카드만
```

응답의 카드는 **편의**다. 화면이 무시하고 떠나도 카드는 이미 오늘의 복습에 들어 있다.

---

## 4. 잡 — U3 · U4

```
GET /jobs/{jobId}          Cache-Control: no-store       — 화면은 2초마다
```

```jsonc
{ "jobId": 77, "status": "RUNNING",          // QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELLED
  "step": 2, "totalSteps": 3, "stepName": "본문 작성",
  "queuePosition": null,                     // QUEUED 일 때만 — 나보다 먼저 적재된 QUEUED 수 + 1
  "retried": true,                           // 재시도가 한 번이라도 있었나 — 화면은 "평소보다 조금 더 걸려요" 로만
  "materialId": null,                        // SUCCEEDED 면 채워진다 (커리큘럼 잡은 curriculumId)
  "failure": null }                          // FAILED 면 { code, message }
```

토큰 · 비용은 사용자 응답에 싣지 않는다 — 사용자가 할 수 있는 게 없는 정보다. 관리자 U6 에 있다.

**재시도** — `POST /jobs/{jobId}/retry` → `202 { jobId, position }`. 같은 행을 되살려 **실패한 단계부터** 재개한다. §1.5 검사를 똑같이 받는다.

## 5. 실패 코드 — `failure.code`

| `code` | 언제 | 다시 시도 |
|---|---|---|
| `DEADLINE_EXCEEDED` | 잡 시한 10분 초과 | ✅ |
| `LLM_OUTPUT_INVALID` | 재요청까지 파싱 실패 · 개요 없음 · 섹션 누락 | ✅ |
| `LLM_UNAVAILABLE` | 재시도 소진(429 · 5xx · 타임아웃) · `retry-after` > 60초 | ✅ |
| `LLM_REQUEST_REJECTED` | 프로바이더가 요청 거부(400 · 401 · 403 · 413) — **우리 버그** | ✅ |
| `PROVIDER_SPEND_CAP` | 프로바이더 크레딧 소진(`insufficient_quota`) | ❌ |
| `TOPIC_REJECTED` | 잡 A 가 주제를 거절했다 — 위험 · 불법 · 연습형([03 §2](./03-generation.md)) | ❌ — 같은 주제는 같은 결과다. 새 주제로 만든다 |

`CANCELLED`(커리큘럼 그만두기)는 실패가 아니라 재시도 대상이 아니다. `TOPIC_REJECTED` 잡에 `POST /jobs/{id}/retry` 를 부르면 `409 JOB_NOT_RETRYABLE` — 같은 주제는 다시 돌려도 같은 거절에 돈만 쓴다. `PROVIDER_SPEND_CAP` 은 화면에 버튼이 없을 뿐 API 는 막지 않는다 — 크레딧을 채운 뒤 강의를 다시 열면 그 잡이 재시도된다(§3.1).

---

## 6. 복습 — U5

| | 엔드포인트 | 비고 |
|---|---|---|
| 오늘의 복습 | `GET /reviews/due` | **한국 날짜로 오늘 안에 예정된** 카드 — 첫 복습 카드 전부 + 하루 50장 중 남은 만큼 오래 밀린 순([02 §8.1](./02-domain-data.md)) |
| 평가 | `POST /reviews` `{ flashcardId, rating }` | `card_state` 갱신(FSRS) + `review` 삽입 — 한 트랜잭션 |

```jsonc
// GET /reviews/due
{ "cards": [ { "flashcardId": 1, "question": "…", "modelAnswer": "…", "keyPoints": ["…"], "concept": "기준금리",
               "source": { "curriculumTitle": "경제 기초 4주", "lessonOrder": 6 } } ],   // 출처 — 섞여 나와도 맥락을 알게
  "overdueCount": 4, "dailyLeft": 43 }

// POST /reviews → 200
{ "nextReviewAt": "2026-10-02T09:00:00Z", "remaining": 7, "dailyLeft": 43 }
```

복습이 밀려도 다음 강의는 막지 않는다 — `overdueCount` 로 배지만 띄운다. 평가 되돌리기는 없다(알려진 한계).

---

## 7. 시스템 상태 — U6 (관리자)

```
GET /system/status          관리자 아니면 404 · 화면은 진입 시 1회 + 새로고침 버튼만
```

```jsonc
{ "jobs":        { "queued": 3, "running": 2, "executors": 4, "oldestQueuedSeconds": 40 },
  "cost":        { "todayTokens": 84000, "todayCost": 620, "killSwitchRemaining": 9380 },
  "reliability": { "rate429": 0.02, "retries": 5, "failureRate": 0.01, "reclaimed": 0 },   // 최근 24시간
  "conceptMatch": { "linked": 41, "split": 3, "created": 128,
                    "borderline":      [ { "a": "인플레이션", "b": "inflation", "sim": 0.76 } ],
                    "mergeCandidates": [ { "keepId": 12, "dropId": 57, "sim": 0.77 } ] },
  "users":       { "active": 14, "cap": 25, "waitlisted": 3, "activeToday": 6 } }
```

**전부 DB 에서 읽는다** — 운영 서버에 Prometheus 가 없다. `jobs` 카운터 · `daily_usage` · `concept_match_log` · `users`.
`borderline` 과 `mergeCandidates` 는 같은 데이터원(이름 다름 분기 · 임계값 −0.05)이다 — **임계값을 내릴지, 이 쌍만 병합할지**를 같은 자리에서 판단한다.

---

## 8. 화면 ↔ 엔드포인트

| 화면 | 엔드포인트 |
|---|---|
| U1 로그인 | `GET /auth/google` · `/callback` · `GET /me` |
| U2 홈 | `GET /curriculums` · `POST /curriculums` · `GET /reviews/due` · `GET /me` |
| U3 커리큘럼 | `GET /curriculums/{id}` · `/approve` · `/regenerate` · `/abandon` · `DELETE /curriculums/{id}` · `GET /jobs/{id}` · `POST /jobs/{id}/retry` |
| U4 강의 | `POST /lessons/{id}/materials` · `GET /jobs/{id}` · `POST /jobs/{id}/retry` · `GET /lessons/{id}/material` · `POST /lessons/{id}/complete` |
| U5 복습 | `GET /reviews/due` · `POST /reviews` |
| U6 시스템 상태 | `GET /system/status` |
| U7 가입 · U8 대기 · U9 설정 | `POST /me/terms` · `GET /me` · `POST /auth/logout` · `DELETE /me` |

**21개**(계정 6 · 커리큘럼 7 · 강의 3 · 잡 2 · 복습 2 · 상태 1, `/dev/session` 제외).

**만들지 않는 것** — 커리큘럼 목록 페이징(사용자당 몇 개뿐) · `/v1/` 경로 버저닝 · 개념 병합 · 정원 · 대기 승인 관리 API(SQL 로) · SSE.
