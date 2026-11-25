# 05. API

> 화면과 서버가 무엇을 주고받는가 — 공통 모양 · 인증 · 오류 · 만드는 작업 · 화면마다 읽기와 동작.
> **만들기는 전부 비동기다** — 「만드는 작업 받기」는 작업을 돌려주고, 화면은 그 작업을 정한 간격으로 다시 읽는다(ADR-143). 작업의 수명은 [04 §4](./04-jobs-and-cost.md#4-만드는-작업), 화면은 [06](./06-screens.md).
> **경로 · 칸 이름 · 상태 코드 · 오류 code 목록 · 간격은 TASK 에서 정한다**(README 2026-11-03). 상태 같은 값은 [02 §8.7](./02-domain-data.md#87-값-목록)의 코드 이름을 그대로 쓴다.

---

## 1. 공통

**리소스마다 주소를 두고 JSON 으로 주고받는다**(ADR-142).

- 읽기는 가져오기, 바꾸는 동작은 보내기다.
- **가져오기는 만들기를 걸거나 상태를 바꾸지 않는다** — 강의를 가져올 때 「마지막으로 연 때」(ADR-099)를 적는 것만 예외다.
- 성공 · 오류 응답의 모양은 기존 `2a-api-response` 를 그대로 쓴다(ADR-146).
- **넓힐 수 있게 둔다**(ADR-145) — 응답은 객체로 두어 칸을 더해도 화면이 깨지지 않게 하고, 화면은 모르는 칸 · 값을 무시한다. 화면과 서버는 따로 내보낸다. 예: 크레딧 충전을 더하면 「나」에 충전 몫이 더해진다(ADR-131).

### 1.1 인증 — 구글 로그인 + 쿠키 세션(DB 저장)

- 구글 로그인 뒤 서버는 로그인 세션을 쿠키로 주고, 세션은 DB 에 둔다 — 어느 서버가 받아도 같은 세션을 본다(ADR-057 · ADR-142 · ADR-133).
- 세션에는 누구인지만 둔다. 신청 · 승인 · 거절됨은 요청마다 사용자에서 읽는다 — 운영자가 승인하면 다음 요청부터 반영된다(ADR-060).
- 기존 `1-auth` 의 구글 로그인과 DB 세션을 그대로 쓴다(ADR-146). 허용 목록은 신청 흐름으로 바꾼다(ADR-147).
- **구글 로그인은 매번 계정을 고르게 한다** — 신청 화면의 「다른 계정으로 로그인」이 로그아웃 뒤 로그인을 다시 여는 것으로 끝난다(ADR-092 · 사용자 확인 2026-11-08).
- **승인되지 않은 사람(신청 전 · 신청 · 거절됨)은 「나」 읽기 · 신청 · 로그아웃만 쓴다** — 나머지 API 는 기본으로 막아, 뒤에 생기는 API 가 따로 막지 않아도 된다(ADR-055 · 사용자 확인 2026-11-08).
- 쿠키를 쓰므로 요청 위조를 막는 장치가 필요하다.

### 1.2 오류 — 묶음 이름과 서버가 정한 문구

**오류는 모두 한 모양으로, 화면이 할 일을 가르는 묶음 이름과 서버가 경우에 맞게 정한 안내 문구를 싣는다**(ADR-145).

- 화면은 문구를 그대로 보이고, 버튼(다시 시도 · 버리기 …)은 묶음 이름으로 고른다.
- 문구에 든 값(크레딧 부족의 다음에 채워지는 날 — ADR-113)은 서버가 넣는다.
- 묶음은 사용자가 할 일이 달라지는 만큼만 나눈다(ADR-088). 정한 것은 「지금은 만들 수 없음」 둘이다(ADR-116).

| 묶음 | 언제 | 알리는 것 |
|---|---|---|
| 크레딧 부족 | 크레딧이 모자랄 때(ADR-107) | 다음에 채워지는 날(ADR-113) |
| 만드는 중인 것이 많음 | 한 사람이 동시에 걸 수 있는 수를 넘었을 때(ADR-104) | 지금 만드는 것이 끝나면 할 수 있다 |

- 만들기 실패는 일시적(다시 시도 · 버리기) · 입력 문제(버리기만 — 다시 해도 같은 실패다. 문구로 「주제를 좁혀 새로 만들어 주세요」 같이 안내한다) 둘로 보인다 — 서버가 실패한 까닭([02 §8.5](./02-domain-data.md#85-만들기--호출))으로 고른다(ADR-163 · ADR-174). 나머지 묶음(요청 칸 검사 등)은 TASK 에서 맞춘다.
- 계정 TASK 구현: `ACCOUNT_NOT_APPROVED`(403)은 신청 전 · 신청 · 거절됨인 사람이 허용된 계정 동작 밖의 API를 부를 때 반환한다. 문구는 「승인된 계정이 필요합니다」이며, 화면은 「나」를 다시 읽어 상태 화면으로 간다.
- 만드는 작업 TASK(`6-job-worker` step 1) 구현: 「크레딧 부족」은 `CREDIT_INSUFFICIENT`(409)이며 문구는 「크레딧이 부족합니다. {yyyy-MM-dd}에 다시 채워집니다」다. `{yyyy-MM-dd}`에는 `credit_refill_at`을 `Asia/Seoul`로 바꾼 한국 날짜를 넣는다. 서버 문구는 `ApiException.withMessage(ErrorCode, String)`로 넘기고 `ApiExceptionHandler` → `ApiErrorResponses.of(ApiException)`의 공통 경로로 응답한다. 기존 생성자는 고정 문구와 `retryAfterSeconds` 동작을 유지하며 예외 자체의 메시지는 code만 담는다.
- 만드는 작업 TASK(`6-job-worker` step 2) 구현: 「만드는 중인 것이 많음」은 `TOO_MANY_IN_PROGRESS`(409), 문구는 「지금 만드는 것이 끝나면 다시 시도해 주세요」다. 입력이 너무 큰 실패의 다시 시도 거절은 `JOB_RETRY_NOT_ALLOWED`(409), 「주제를 좁혀 새로 만들어 주세요」다. 실패가 아닌 작업의 버리기 거절은 `JOB_DISCARD_NOT_ALLOWED`(409), 「실패한 만들기만 버릴 수 있습니다」다. 실패가 아닌 작업의 다시 시도는 현재 작업을 그대로 돌려준다.
- 만드는 작업 TASK 구현: `6-job-worker` step 3의 작업 읽기는 실패한 작업도 HTTP 200의 `data.error`에 공통 `ApiError` 객체(`code` · `message`)를 싣는다. `INPUT_TOO_LARGE`는 `JOB_INPUT_FAILURE`, 「주제를 좁혀 새로 만들어 주세요」이며 버리기만 가능하다. 나머지 세 실패 까닭은 `JOB_TEMPORARY_FAILURE`, 「만들지 못했습니다. 다시 시도하거나 버려 주세요」이며 다시 시도 · 버리기가 가능하다. 원래 실패 까닭은 반환하지 않는다.
- 강의 목록 TASK(`7-course-planning` step 4) 구현: 로드맵 코스 받기의 상태 거절은 `COURSE_PLAN_NOT_ALLOWED`(409), 「지금은 이 코스의 강의 목록을 만들 수 없어요」다. 앞선 짜기 전 코스가 있거나 바로 앞 코스가 다 마침이 아니면 `PREVIOUS_COURSE_NOT_COMPLETED`(409), 「앞 코스를 다 마치면 만들 수 있어요」다. 첫 코스의 받기에서 성공하지 않은 로드맵 다시 짜기가 있으면 `REPLAN_IN_PROGRESS`(409), 「다시 짜는 중에는 시작할 수 없어요」다(브랜치 리뷰에서 `ROADMAP_REPLAN_IN_PROGRESS` 를 코스 · 로드맵 공통 이름으로 바꿨다 — ADR-182). 각각 현재 코스 상태 확인 · 앞 코스 학습 완료 · 다시 짜기 종료(실패면 다시 시도 또는 버리기)를 안내한다. 단일 코스를 로드맵 코스 받기의 대상으로 보내면 `NOT_FOUND`(404)다.
- 강의 목록 TASK(`7-course-planning` step 5) 구현: 코스 · 로드맵의 다시 짜기 받기는 검토 중이 아니면 `REPLAN_NOT_ALLOWED`(409), 「검토 중일 때만 다시 짤 수 있어요」로 거절한다. 화면에서 현재 대상 상태를 확인하도록 두 종류가 같은 묶음을 쓴다.
- 강의 목록 TASK(`7-course-planning` step 6) 구현: 짜기 전 코스의 「이대로 시작」은 `COURSE_START_NOT_ALLOWED`(409), 「강의 목록이 만들어진 뒤 시작할 수 있어요」로 거절한다. 검토 중 코스에 성공하지 않은 코스 다시 짜기(`QUEUED` · `RUNNING` · `FAILED`)가 있으면 같은 `REPLAN_IN_PROGRESS`(409)로 거절한다. 제목의 누락 · 빈 글 · 공백만 있는 글은 `FIELD_INVALID`(400)의 `title: REQUIRED`, 100 코드 포인트 초과는 `title: TOO_LONG`이다.
- 강의 목록 TASK(`7-course-planning` step 7) 구현: 칸 오류(`FIELD_INVALID`)의 서버 문구는 「입력을 확인해 주십시오」 하나이고, 칸 옆의 설명은 화면이 `reason`(`REQUIRED` · `TOO_LONG` · `INVALID_VALUE` · `NOT_ALLOWED`)으로 고른다(`frontend/src/components/FieldError.tsx`). 오류 code · `reason` 자체는 화면에 보이지 않는다(§1.4).
- 강의 자료 TASK(`8-material-cards` step 3) 구현: 강의를 열 수 없으면 — 코스가 학습 중 · 다 마침이 아니거나 앞 순서 강의 가운데 학습 완료하지 않은 것이 있으면 — `LESSON_NOT_OPEN`(409), 「코스를 시작하고 앞 강의를 학습 완료하면 열 수 있어요」로 거절한다. 강의 자료 받기(`MATERIAL`) · 강의 읽기 · 학습 완료가 같은 code 를 쓴다.
- 강의 자료 TASK(`8-material-cards` step 4) 구현: 강의 자료가 없는 강의의 학습 완료는 `LESSON_MATERIAL_REQUIRED`(409), 「강의 자료가 만들어진 뒤 학습 완료할 수 있어요」로 거절한다. 화면은 강의를 다시 읽어 자료 또는 작업을 확인한다.
- 복습 TASK(`9-card-review` step 2) 구현: 보이는 카드라도 지금 볼 수 있는 일정이 아니면 평가는 `CARD_REVIEW_NOT_ALLOWED`(409), 「지금은 이 카드를 복습할 수 없어요. 목록을 다시 확인해 주세요」로 거절한다. 화면은 문구를 보이고 오늘 복습 목록을 다시 읽는다.
- 지우기 TASK(`12-delete-withdraw` step 0) 구현: 로드맵의 코스 하나 지우기는 `COURSE_DELETE_NOT_ALLOWED`(409), 「로드맵의 코스는 로드맵을 지워야 해요」로 거절한다. 없는 코스 · 남의 코스 · 두 번째 지우기는 `NOT_FOUND`(404)다.
- 기술적인 원인(응답이 잘림 · 서버 응답 없음 등)은 싣지 않는다(ADR-088).
- 만들기 실패도 작업을 읽을 때 같은 모양으로 싣는다.

### 1.3 소유권 — 남의 것은 없는 것으로

주소로 찾은 리소스는 주인을 확인하고, 남의 것이면 없는 것과 같이 답한다(ADR-149 — 기존 코드 규칙을 남긴다).

### 1.4 사용자 응답에 싣지 않는 것

토큰 · 비용 · 모델 이름 · 다시 시도 횟수 · 오류 code · 대기 순서 숫자 · 다른 사람의 활동(ADR-089). 크레딧은 「나」에서만 — 남은 크레딧과 다음에 채워지는 날(ADR-113).

## 2. 만드는 작업 — 받기 하나 · 다시 읽기

**새로 배우기(코스 · 로드맵) · 다시 짜기 · 첫 코스 · 다음 코스 · 강의 자료를 모두 「만드는 작업 받기」 하나로 받는다**(ADR-143).

| 동작 | 보내는 것 | 결과 |
|---|---|---|
| 만드는 작업 받기 | 종류 · 대상(다시 짤 코스 · 로드맵, 만들 코스, 강의) · 입력 · 피드백 | 받으면 작업 · 못 받으면 오류 묶음(§1.2) |
| 작업 하나 읽기 | — | 상태(기다림 · 만드는 중 · 성공 · 실패) · 무엇을 만드는 중인지 · 성공하면 결과 · 실패하면 오류 |
| 다시 시도 | — | 실패한 작업을 기다림으로(ADR-086) |
| 버리기 | — | 실패한 작업을 지운다 — 만들기 전으로(ADR-086) |

- 서버는 받는 자리에서 한 사람이 건 수(ADR-104)를 확인하고 크레딧을 쓴다 — 실패하면 복구한다(ADR-112 · ADR-166).
- **로드맵의 「이대로 시작」은 첫 코스의 받기다** — 받으면 로드맵이 진행 중이 되고(ADR-136), 못 받으면 아무것도 바뀌지 않는다.
- **강의 자료는 화면이 건다** — 강의를 열었을 때 자료도 작업도 없으면 화면이 받기를 부른다. 강의를 가져오는 것만으로는 만들지 않는다.
- `8-material-cards` step 3부터 `MATERIAL`을 받는다. 주인을 먼저 확인하고, 코스가 `LEARNING` 또는 `COMPLETED`이며 앞 순서 강의가 모두 학습 완료일 때만 허용한다. 열 수 없으면 `LESSON_NOT_OPEN`(409), 남의 강의와 없는 강의는 `NOT_FOUND`(404)다. 자료 유무로 거절하지 않으며, 같은 강의의 작업이 있으면 성공한 작업까지 그대로 반환하고 크레딧을 쓰지 않는다. 동시 받기도 사용자 행 잠금과 기존 고유 색인으로 작업 · 크레딧 사용 하나만 남긴다.
- 기다리는 화면은 작업을 정한 간격으로 다시 읽고, 성공하면 작업이 가리키는 결과로 넘어간다(ADR-077).

- 만드는 작업 TASK 구현: `6-job-worker` step 7의 기다리는 화면은 작업을 **2초 간격**으로 다시 읽는다. `QUEUED` · `RUNNING`일 때만 이어 읽고, 성공 · 실패 또는 읽기 오류가 되면 멈춘다. 읽기 오류는 화면의 「다시 시도」로 다시 읽고, 실패한 작업의 다시 시도가 성공하면 응답으로 읽기 캐시를 교체해 간격 읽기를 재개한다. 버리기 성공은 해당 작업 캐시를 지운다.

- 만드는 작업 TASK 구현: `6-job-worker` step 3의 네 주소는 아래와 같다. 받기와 다시 시도는 step 2의 트랜잭션이 커밋된 뒤 작업을 다시 읽어 같은 응답을 반환한다. `7-course-planning` step 3부터 `NEW_COURSE` · `NEW_ROADMAP`, step 4부터 `PLAN_ROADMAP_COURSE`는 운영 받기 · 생성 처리기로 받는다. step 5부터 `REPLAN_COURSE` · `REPLAN_ROADMAP`도 검토 중인 대상을 운영 받기 · 생성 처리기로 받는다. `8-material-cards` step 3부터 `MATERIAL`도 운영 받기 · 생성 처리기로 받는다. 새로 배우기 · 다시 짜기의 HTTP 받기 · 크레딧 사용은 운영 처리기로 검증한다.

| 동작 | 메서드 · 경로 | 성공 |
|---|---|---|
| 받기 | `POST /api/jobs` | 200 · `ApiResponse<JobResponse>` |
| 작업 하나 읽기 | `GET /api/jobs/{id}` | 200 · `ApiResponse<JobResponse>` |
| 다시 시도 | `POST /api/jobs/{id}/retry` | 200 · `ApiResponse<JobResponse>` · 요청 본문 없음 |
| 버리기 | `POST /api/jobs/{id}/discard` | 204 · 본문 없음 · 요청 본문 없음 |

- 만드는 작업 TASK 구현: 받기 JSON은 입력을 중첩하지 않고 `type` · `courseId` · `roadmapId` · `lessonId` · `topic` · `level` · `levelOther` · `purpose` · `purposeOther` · `memo` · `feedback`을 최상위 칸으로 받는다. `JobReceiveRequest`의 `@ValidJobRequest`가 [02 §8.1](./02-domain-data.md#81-공통-규칙) · [§8.5](./02-domain-data.md#85-만들기--호출)의 길이 · 빈 글 · 칸 사이 CHECK를 검사하고, `toJobRequest()`가 step 2의 값으로 변환한다. 모르는 칸은 무시한다.

| 종류 | 필수 칸 | 그 밖의 입력 |
|---|---|---|
| `NEW_COURSE` · `NEW_ROADMAP` | `topic` · `level` · `purpose` | `memo` · 수준이 `OTHER`이면 `levelOther` 필수 · 목적이 `OTHER`이면 `purposeOther` 필수 |
| `PLAN_ROADMAP_COURSE` | `courseId` | — |
| `REPLAN_COURSE` | `courseId` | `feedback` |
| `REPLAN_ROADMAP` | `roadmapId` | `feedback` |
| `MATERIAL` | `lessonId` | — |

- 만드는 작업 TASK 구현: 위 종류에 해당하지 않는 입력 · 대상 · 피드백 칸과 `OTHER`가 아닌데 보낸 기타 글은 비어 있어야 한다. 칸 오류는 `FIELD_INVALID`(400), 「입력을 확인해 주십시오」와 `fields: [{"field":"칸 이름","reason":"까닭"}]`로 반환한다. 빠진 필수 값 · 빈 글은 `REQUIRED`, 글자 수 상한 초과는 `TOO_LONG`, 모르는 `type` · `level` · `purpose`는 `INVALID_VALUE`, 비어 있어야 할 칸은 `NOT_ALLOWED`다. 길이는 PostgreSQL `char_length`처럼 유니코드 코드 포인트 수로 센다. 깨진 JSON은 `INVALID_REQUEST`(400)이며 요청 원문은 응답 · 로그에 남기지 않는다.

```json
{"type":"NEW_COURSE","topic":"자바","level":"BEGINNER","purpose":"WORK","memo":"예제를 많이 보고 싶어요"}
```

- 만드는 작업 TASK 구현: 성공 응답의 `data`는 `id` · `type` · `status` · `subject`와, 성공일 때만 `result`, 실패일 때만 `error`를 싣는다. 비어 있는 칸은 생략한다. `subject`는 새로 배우기라면 `topic` · `level` · `purpose`와 해당하는 `levelOther` · `purposeOther`이고, 대상이 있는 종류라면 `title` 하나다(코스 · 로드맵 · 강의의 현재 제목). 메모 · 피드백 · 사용자 ID · 횟수 · 시한 · 시작/끝 시각은 반환하지 않는다. `result`는 코스면 `{"type":"COURSE","id":코스ID}`, 로드맵이면 `{"type":"ROADMAP","id":로드맵ID}`, 강의 자료면 `{"type":"LESSON","id":강의ID,"courseId":코스ID}`다. 실패 `error`의 두 code와 문구는 [§1.2](#12-오류--묶음-이름과-서버가-정한-문구)에 적었다.

```json
{"data":{"id":11,"type":"NEW_COURSE","status":"QUEUED","subject":{"topic":"자바","level":"BEGINNER","purpose":"WORK"}}}
```

## 3. 읽기 — 화면마다 한 번

**화면마다 읽기 한 번으로 그린다. 홈만 칸마다 따로 읽고, 고르는 규칙은 서버에 둔다**(ADR-144).

| 읽기 | 담는 것 | 쓰는 화면 |
|---|---|---|
| 나 | 누구인지 · 신청 전 · 신청 · 승인 · 거절됨 · 남은 크레딧 · 다음에 채워지는 날 | 로그인 전 화면 · 내 정보 탭 |
| 홈의 칸마다 | 오늘 복습(카드 수) · 스트릭(연속 일수 · 오늘 채웠는지 — ADR-096 · 계산은 서버 — ADR-178) · 이어서 배우기 · 할 일 · 만드는 중(내 작업 가운데 성공하지 않은 것)을 칸마다 따로 — 고르는 규칙(ADR-099)은 서버의 그 읽기에. 다른 화면의 읽기를 함께 쓸 수 있다(ADR-144) | 홈 |
| 작업 하나 | §2 | 기다리는 화면 |
| 코스 목록 | 강의 목록이 생긴 코스 — 짜기 전 코스는 빼고(ADR-072) | 코스 탭 |
| 로드맵 목록 | 로드맵마다 제목과 진행 한 줄 | 로드맵 탭 |
| 코스 하나 | 상태 · 제목 · 강의 목록 · 숙련도 반영 내역(검토 중 — ADR-090) · 진도 · 이 코스의 오늘 복습할 카드 수(ADR-172) | 코스 화면 |
| 로드맵 하나 | 상태 · 계획 · 코스마다 진행(짜기 전 · 만드는 중 · 실패 · 만들어짐) · 「다음 코스 만들기」를 보일지 | 로드맵 화면 |
| 강의 하나 | 강의 자료 · 학습 완료 여부 · 강의 자료의 작업이 있으면 그 작업 | 강의 화면 |
| 오늘 복습할 카드 | 카드들 — 질문 · 답 · 평가마다 다음 간격(ADR-170) · 몇 장 남았는지(ADR-014 · ADR-017). 코스를 주면 그 코스의 카드만(ADR-172). 화면은 받은 카드를 다 보면 다시 읽는다 — 같은 날 다시 보는 카드가 돌아온다(ADR-164) | 복습 탭 |

- 홈 · 크레딧 TASK(`11-home-credits` step 4) 구현: 「이어서 배우기」는 `GET /api/home/continue-learning`의 `data: {course: {id, title, nextLesson: {id, title}}}`다. 대상 코스가 없으면 `course`를 생략해 `data: {}`로 반환한다. 학습 중 코스의 마지막 강의 열기 시각을 우선하고, 모두 비었을 때만 시작 시각을 쓰며 같은 시각에는 큰 ID를 고른다. 다음 강의는 코스 하나의 `nextLessonId`와 같은 판정을 쓴다. 고른 코스에 다음 강의가 없으면(정상 흐름에서는 생기지 않는다) `nextLesson`을 생략하고, 화면은 이 칸을 숨긴다. 「할 일」은 별도 `GET /api/home/todos`의 `data: {items: [{type, targetId, title}]}`이며 비었을 때 `items: []`다. `type`은 검토 중 코스(단일 · 로드맵 코스)의 `COURSE_REVIEW`, 검토 중 로드맵의 `ROADMAP_REVIEW`, 다음 코스가 활성화된 진행 중 로드맵의 `ROADMAP_NEXT_COURSE`다. 앞의 종류는 코스 ID · 코스 제목, 나머지는 로드맵 ID · 로드맵 제목을 싣는다. 모든 항목을 생성 시각 내림차순 → 대상 ID 내림차순 → 종류 이름 오름차순으로 섞으며 나눠 받지 않는다. 두 읽기 모두 HTTP 200의 `ApiResponse`로 감싸며 본인의 결과만 읽고, 상태나 시각을 바꾸지 않는다.
- 홈 · 크레딧 TASK(`11-home-credits` step 3) 구현: 홈의 「오늘 복습」은 `GET /api/home/reviews`의 `data: {remainingCount: 정수}`를 쓴다 — `TodayReviewService.count(userId, null)`로 코스 거르기 없이 세며 0도 싣는다. 스트릭은 별도 `GET /api/home/streak`의 `data: {days: 정수, filledToday: 불리언}`으로 읽는다. 둘 다 HTTP 200의 `ApiResponse`로 감싸며, 서로 다른 경로라 화면이 각 영역의 실패와 다시 시도를 나눌 수 있다. 카드 본문과 간격 계산이 필요 없는 홈을 위해 복습 목록 대신 수만 읽는다.
- 홈 스트릭 구현: `card.service.StreakReadService`가 `Clock` 빈과 `SeoulClock.today()`로 오늘을 정하고 `card.repository.StreakActivityRepository`의 날짜들을 계산한다. 네이티브 SQL로 `reviews` · `cards`와 `lessons` · `courses`에서 본인의 활동을 읽어 `AT TIME ZONE 'Asia/Seoul'`과 4시간 빼기로 날짜를 합친다 — `course` Java 타입 의존은 없다. 스트릭을 저장하거나 사용자 ID · 날짜별 기록을 반환하지 않고, 읽기로 데이터와 시각을 바꾸지 않는다. 정원 10명인 현재 범위에서는 색인을 추가하지 않았다.
- 계정 TASK 구현: 「나」는 `GET /api/me`이며, 성공 응답 `data`에 `status` · `email` · `name`을 싣는다. 홈 · 크레딧 TASK(`11-home-credits` step 2)에서 `APPROVED`일 때만 `credit: {balance, nextRefillDate}`를 더했다 — `balance`는 DB의 남은 크레딧 정수, `nextRefillDate`는 다음에 채우는 때를 `Asia/Seoul`로 바꾼 `yyyy-MM-dd` 날짜 문자열이다. 그 밖 상태에서는 `credit` 칸을 생략한다.
- 홈 · 크레딧 TASK(`11-home-credits` step 5) 구현: 「만드는 중 · 실패」는 별도 `GET /api/home/jobs`의 HTTP 200 `data: {jobs: [JobResponse]}`로 읽으며 비었을 때 `jobs: []`다. 기존 홈 칸 경로와 맞추고 칸마다 읽기 실패 · 다시 시도를 나누도록 이 경로를 택했다. 본인의 `QUEUED` · `RUNNING` · `FAILED`만 `created_at DESC, id DESC`로 나눠 받지 않고 읽는다. 각 항목은 작업 하나 읽기와 같은 변환을 써서 `id` · `type` · `status` · `subject`와 실패일 때만 `error`를 싣는다([§2](#2-만드는-작업--받기-하나--다시-읽기)). 상태 · 시각 · 크레딧을 바꾸지 않는다.
- 만드는 작업 TASK 구현: 「작업 하나」는 `GET /api/jobs/{id}`이며, `job.service.JobReadService.read(userId, jobId)`가 주인을 확인한 뒤 [§2](#2-만드는-작업--받기-하나--다시-읽기)의 응답을 만든다. 남의 작업과 없는 작업 모두 `NOT_FOUND`(404), 「찾을 수 없습니다」다. 조회는 상태 · Auditing 시각 · 크레딧을 바꾸지 않는다.
- 강의 목록 TASK(`7-course-planning` step 6) 구현: 아래 읽기 넷은 모두 HTTP 200의 `ApiResponse`로 감싼다. 목록은 나눠 받지 않고 `created_at DESC, id DESC`로 정렬하며, 코스 · 강의의 내부 순서는 `position ASC`다. 없는 대상과 남의 대상은 `NOT_FOUND`(404)다. 읽기는 상태 · 시각 · 작업 · 크레딧을 바꾸지 않는다.

| 읽기 | 메서드 · 경로 | 성공 응답 `data`의 칸 |
|---|---|---|
| 코스 목록 | `GET /api/courses` | `courses: [{id, title, status}]` — 본인의 단일 코스와 로드맵 코스 중 `NOT_PLANNED` 제외 |
| 로드맵 목록 | `GET /api/roadmaps` | `roadmaps: [{id, title, description, status, completedCourses, totalCourses}]` |
| 코스 하나 | `GET /api/courses/{id}` | `id` · `status` · `title` · `roadmapId` · `lessons` · `progress` · `nextLessonId` · `completedAt` · `todayReviewCount` · `masteryReport` |
| 로드맵 하나 | `GET /api/roadmaps/{id}` | `id` · `status` · `title` · `description` · `courses` · `nextCourse` |

- 코스 하나의 `lessons`는 `[{id, position, title, description, concepts: [{name, definition}], completed}]`다. `completed`는 학습 완료 여부다. `progress`는 `{completedLessons, totalLessons}`이며 짜기 전에는 빈 강의 목록과 0 / 0이다. `roadmapId`는 로드맵의 코스일 때, `nextLessonId`는 `LEARNING`에서 완료하지 않은 가장 앞 강의가 있을 때, `completedAt`은 `COMPLETED`일 때만 싣는다. `completedAt`은 UTC ISO-8601 시각이다. 비어 있는 선택 칸은 생략한다.
- 로드맵 하나의 `courses`는 `[{id, position, title, description, concepts: [{name, definition}], status, progress, jobId}]`다. `description` · `concepts`는 계획의 설명 · 개념이다. 코스의 `status`는 [02 §8.7](./02-domain-data.md#87-값-목록)을 그대로 쓰고, `progress`는 `NOT_PLANNED`(짜기 전) · `GENERATING`(만드는 중) · `FAILED`(실패) · `GENERATED`(만들어짐)다. 짜기 전 코스의 `PLAN_ROADMAP_COURSE` 작업이 `QUEUED` · `RUNNING`이면 `GENERATING`, `FAILED`이면 `FAILED`이고 그때만 `jobId`를 싣는다. 코스가 `REVIEWING` · `LEARNING` · `COMPLETED`이면 `GENERATED`다.
- `nextCourse`는 `{courseId, enabled}`다. `IN_PROGRESS` 로드맵에서 성공하지 않은 강의 목록 작업이 없는 짜기 전 코스 가운데 가장 앞의 코스를 가리킨다. 첫 코스이거나 바로 앞 코스가 `COMPLETED`이면 `enabled: true`, 그 전에는 `false`다. 대상이 없거나 로드맵이 `REVIEWING` · `COMPLETED`이면 칸을 생략한다. 검토 중 로드맵의 「이대로 시작」 대상은 `courses`의 첫 코스다.
- 위 네 응답에는 입력(주제 · 수준 · 목적 · 메모) · 사용자 ID · 시스템 정보를 싣지 않는다.
- 숙련도 고리 TASK(`10-mastery-loop`) — 코스 하나의 `masteryReport`는 `{shortened, revisited}` 두 정수다 — 저장된 숙련도 반영 내역([03 §5](./03-generation.md#5-숙련도-반영-내역--llm-이-적고-서버는-확인하지-않는다))의 짧게 다룬 개념 수 · 다시 짚은 개념 수를 서버가 확인하지 않고 그대로 센다(ADR-090 · ADR-121). **상태가 `REVIEWING`이고 내역이 저장돼 있을 때만** 싣고, 두 수가 0이어도 싣는다 — 0 / 0을 보일지는 화면이 정한다. 개념 이름 · 판정 이름 · 숙련도 수치는 싣지 않는다(06 §7). 「이대로 시작」 · 제목 고치기가 돌려주는 같은 응답도 같은 규칙이다. 다른 응답(코스 목록 · 로드맵)에는 싣지 않는다.
- 숙련도 고리 TASK(`10-mastery-loop` step 6) 구현: `course.service.CourseReadService.read(userId, courseId)`가 위 `masteryReport`를 `CourseResponse.MasteryReportCounts`로 채운다. `CourseCommandService.start` · `rename`도 이 읽기를 호출해 같은 규칙의 응답을 만든다.
- 강의 자료 TASK(`8-material-cards` step 4) 구현: 「강의 하나」는 `GET /api/lessons/{id}`이며 HTTP 200의 `ApiResponse<LessonResponse>`로 감싼다. `data`는 `id` · `courseId` · `courseTitle` · `position` · `title` · `completed`를 담는다. 자료가 있으면 `material: {body}`를 싣고, 자료가 없고 그 강의의 `MATERIAL` 작업이 있으면 `job: {id, status}`를 싣는다. `status`는 `QUEUED` · `RUNNING` · `SUCCEEDED` · `FAILED`이며 작업의 오류 · 횟수 · 시한은 싣지 않는다. 비어 있는 선택 칸은 생략한다. 주인과 열기 조건을 확인하며 거절은 §1.2 · §1.3을 따른다. 성공한 읽기는 코스의 `last_lesson_opened_at`과 JPA Auditing의 수정 시각만 적고, 자료 · 작업을 만들거나 크레딧을 바꾸지 않는다.
- 복습 TASK(`9-card-review` step 1) 구현: 「오늘 복습할 카드」는 `GET /api/reviews/today`이며, 선택 쿼리 `courseId`를 주면 그 코스의 강의에 달린 카드만 읽는다. HTTP 200의 `ApiResponse<TodayReviewResponse>`로 감싸고 `data`에 `cards` · `remainingCount`를 싣는다. `cards`는 `[{id, question, answer, nextIntervalsSeconds: {AGAIN, HARD, GOOD, EASY}}]`이며, 네 등급의 간격은 이 요청에서 한 번 읽은 「지금」으로 `FsrsCalculator.preview`를 부른 **초 단위 정수**다. 문구로 바꾸는 것은 화면의 일이다. `remainingCount`는 이번 목록의 카드 수이며 현재 카드도 포함한다. 목록은 나눠 받지 않고 `due_at ASC, id ASC`로 정렬한다.
- 복습 TASK 구현: 코스로 거르면 `courseTitle`도 싣고, 전체 복습에서는 생략한다. 없는 코스와 남의 코스는 `NOT_FOUND`(404), 「찾을 수 없습니다」다. 내 코스에 카드가 없거나 오늘 볼 것이 없으면 `cards: []` · `remainingCount: 0`과 코스 제목을 돌려준다. 카드의 FSRS 칸 · 다음 볼 때 · 강의 및 코스 출처 · 시스템 정보는 싣지 않는다. 읽기는 카드 · 복습 기록 · 시각을 바꾸지 않는다.
- 복습 TASK 구현: `card.service.TodayReviewService.selectCards(userId, courseId, now)`가 [02 §3](./02-domain-data.md#3-학습과-복습)의 오늘 복습할 카드 선택과 코스별 앞당겨 보기를 한 곳에서 맡고, 목록과 `count(userId, courseId)`가 함께 쓴다. 코스 하나의 `todayReviewCount`는 이 코스로 거른 카드 수인 정수이며 0도 싣는다. 코스 시작 · 제목 고치기도 같은 칸을 채운다. 뒤 홈의 「오늘 복습」 수도 이 서비스를 쓴다.
- 「나」의 크레딧은 `account.service.MeReadService.read`가 요청마다 `users`에서 읽는다 — 상태 · 잔액 · 시각 · 기록을 바꾸거나 세션에 크레딧을 담지 않는다. 신청 응답도 같은 읽기와 DTO를 쓰므로 신청 직후 `APPLIED`에서는 크레딧 칸이 없다. 날짜 변환은 크레딧 부족 문구와 같은 `SeoulClock.calendarDate`를 쓴다(`11-home-credits` step 2 구현).
- 개인정보처리방침은 화면에 둔 글이라 읽기가 없다.

## 4. 보내는 동작

| 동작 | 대상 | 쓰는 화면 | 근거 |
|---|---|---|---|
| 구글 로그인 · 신청 · 로그아웃 | 나 | 로그인 전 화면 · 내 정보 탭 | ADR-056 · ADR-057 |
| 탈퇴 | 나 — 승인된 사람만 | 내 정보 탭 | ADR-059 |
| 만드는 작업 받기 · 다시 시도 · 버리기 | §2 | 새로 배우기 · 기다리는 화면 · 코스 화면 · 로드맵 화면 · 강의 화면 | ADR-143 |
| 이대로 시작 | 코스(검토 중 → 학습 중). 로드맵은 첫 코스의 받기(§2) | 코스 화면 | ADR-007 · ADR-078 |
| 코스 제목 고치기 | 코스 · 로드맵 계획의 코스 | 코스 화면 · 로드맵 화면 | ADR-007 · ADR-009 |
| 코스 지우기 | 단일 코스 | 코스 화면 | ADR-026 · ADR-100 |
| 로드맵 지우기 | 로드맵 — 상태와 상관없이(ADR-190) | 로드맵 화면 | ADR-027 · ADR-190 |
| 학습 완료 | 강의 — 응답에 코스가 다 마침이 됐는지를 싣는다(축하 화면 — ADR-081) | 강의 화면 | ADR-012 |
| 평가 보내기 | 카드 — 4단계 | 복습 탭 | ADR-015 |
| 카드 지우기 | 카드 | 복습 탭 | ADR-065 |

- 계정 TASK 구현: 신청은 본문 없는 `POST /api/account/apply`이며, 성공(200) 응답 `data`의 칸은 「나」와 같은 `status` · `email` · `name`이다. 회원 ID 세션은 현재 상태를 그대로 반환하고, 신청 전 신원 세션은 신청 뒤 사용자 ID 세션으로 바꾼다.
- 동시 신청 구현: `ApplyService`의 한 트랜잭션에서 `UserRepository`의 `UserIdentityLock` 조각이 `EntityManager` 네이티브 쿼리로 신원별 PostgreSQL 트랜잭션 advisory lock을 잡은 뒤 기존 행을 조회한다. 처음일 때만 사용자와 채움 기록을 함께 저장한다. `(provider, subject)` 고유 제약도 유지한다. 잠금은 커밋 · 롤백 때 풀리며 서버 메모리에 공유 상태를 두지 않는다.
- 강의 목록 TASK(`7-course-planning` step 6) 구현: 아래 두 동작은 성공(200)하면 [§3](#3-읽기--화면마다-한-번)의 코스 하나와 같은 `ApiResponse<CourseResponse>`를 반환한다. 남의 대상과 없는 대상은 `NOT_FOUND`(404)다.

| 동작 | 메서드 · 경로 | 요청 본문 |
|---|---|---|
| 코스 이대로 시작 | `POST /api/courses/{id}/start` | 없음 |
| 코스 제목 고치기 | `PATCH /api/courses/{id}` | `{"title":"바꿀 제목"}` — 모르는 칸은 무시 |

- 코스 시작은 `REVIEWING`에서 성공하지 않은 다시 짜기가 없을 때만 `LEARNING`과 `started_at`을 함께 적는다. `LEARNING` · `COMPLETED`는 원래 상태 · 시각을 그대로 돌려준다. 거절의 code와 문구는 §1.2, 받기와 경합을 막는 구현은 [04 §2.2](./04-jobs-and-cost.md#22-패키지-경계)에 있다. 시작은 강의를 열거나 만들기를 걸지 않는다.
- 제목 고치기는 단일 코스와 로드맵의 코스 모두 상태와 상관없이 받는다. `@Valid CourseTitleRequest`의 `@NotBlank` · `@CodePointLength(max = 100, message = "field.TOO_LONG")`로 검사하고, 공통 오류 경로가 칸별 reason으로 변환한다. 제목과 JPA Auditing의 수정 시각을 바꾸며 입력 · 계획 · 강의 목록 · 상태는 유지한다.
- 강의 자료 TASK(`8-material-cards` step 4) 구현: 학습 완료는 본문 없는 `POST /api/lessons/{id}/complete`다. 성공(200)은 `ApiResponse<LessonCompletionResponse>`로 감싸며 `data: {courseId, courseCompleted}`를 반환한다. `courseCompleted`는 이번 요청으로 코스가 다 마침이 되었을 때만 참이다. 주인 · 열 수 있는 강의 · 자료 존재를 확인하고, 남의 것과 없는 것은 `NOT_FOUND`(404), 열기 거절은 `LESSON_NOT_OPEN`(409), 자료가 없으면 `LESSON_MATERIAL_REQUIRED`(409)다. 이미 완료한 강의는 성공으로 `courseCompleted: false`를 반환하고 아무것도 바꾸지 않는다.
- 강의 읽기와 완료는 강의 ID와 사용자 ID로 코스 행을 `PESSIMISTIC_WRITE`로 먼저 잠근 뒤 강의를 읽는다. 동시 완료도 뒤 요청이 커밋을 기다린 뒤 완료 여부를 다시 확인하므로 한 번만 반영된다. 완료는 한 트랜잭션에서 강의의 `completed_at`, 그 강의의 `ACTIVE` 카드 중 빈 `due_at`과 `updated_at`을 같은 완료 시각으로 채운다. FSRS 상태와 기존 일정 · 복습 기록은 바꾸지 않는다. 모든 강의가 완료되면 코스의 `COMPLETED`와 `completed_at`을 함께 적고, 로드맵에 더 뒤 순서 코스가 없으면 그 로드맵도 `COMPLETED`로 바꾼다. 완료는 마지막으로 연 때를 적지 않는다.
- 복습 TASK(`9-card-review` step 2) 구현: 아래 두 동작은 모두 성공하면 **204 · 본문 없음**을 반환한다. 다음 카드는 반환하지 않으며, 화면은 받은 목록을 이어 보고 다 보면 다시 읽는다.

| 동작 | 메서드 · 경로 | 요청 본문 |
|---|---|---|
| 평가 보내기 | `POST /api/cards/{id}/reviews` | `{"rating":"GOOD"}` — `AGAIN` · `HARD` · `GOOD` · `EASY`, 모르는 칸은 무시 |
| 카드 지우기 | `DELETE /api/cards/{id}` | 없음 |

- 평가 본문은 `@Valid CardReviewRequest`의 `@NotNull`과 `@Pattern`으로 검사한다. 빠진 `rating` · `null`은 `FIELD_INVALID`(400)의 `rating: REQUIRED`, 모르는 값(빈 글 · 소문자 포함)은 `rating: INVALID_VALUE`다. 깨진 JSON · 없는 본문은 `INVALID_REQUEST`(400)다. 원문은 응답 · 로그에 남기지 않는다.
- 두 동작 모두 주인의 `ACTIVE` 카드이며 `due_at`이 있는 학습 완료 뒤 카드만 받는다. 없는 카드 · 남의 카드 · 학습 완료 전 · 코스 지워짐은 모두 `NOT_FOUND`(404), 「찾을 수 없습니다」다. 평가는 `REVIEW`의 `due_at`이 다음 하루의 시작(한국 시간 새벽 4시) **전**이거나, 학습 단계(`LEARNING` · `RELEARNING`)의 `due_at`이 지금부터 **20분 이내**일 때 받는다. 20분 한도는 읽기와 같은 상수이며, 다른 볼 카드 유무와 관계없이 이 범위 안의 평가를 받는다. 그 밖은 §1.2의 `CARD_REVIEW_NOT_ALLOWED`다.
- `card.service.CardCommandService`는 한 트랜잭션에서 카드 ID와 사용자 ID로 카드 행을 `PESSIMISTIC_WRITE`로 먼저 잠그고 조건을 확인한다. 평가는 잠금 뒤 `Clock`에서 한 번 받은 지금으로 `FsrsCalculator.review`를 계산하고, 여섯 FSRS 칸을 그대로 반영한 뒤 `Review.record(card, rating)`를 저장한다. 복습 기록의 평가한 때는 같은 `Clock`을 쓰는 JPA Auditing의 `created_at`이고, 카드의 `updated_at`도 Auditing이 채운다.
- 같은 카드의 동시 평가는 앞 트랜잭션의 커밋을 기다린 뒤 바뀐 상태로 조건과 FSRS를 다시 계산한다. 앞 평가로 허용 범위를 벗어나면 뒤 평가는 거절한다. 요청 ID나 서버 메모리 잠금은 두지 않는다. 카드 지우기도 같은 행 잠금을 먼저 잡고 `reviews` → `card_concepts` → `cards` 순서로 지우며, 개념 사전은 남긴다. 오늘 예정이 아닌 카드도 지울 수 있다. 지운 뒤의 평가와 두 번째 지우기는 `NOT_FOUND`다.
- **신청은 한 사람에 한 번이다** — 이미 신청한 사람이 다시 신청하면 새로 만들지 않고 같은 결과(신청 상태)를 성공으로 돌려준다. 같은 구글 계정의 두 세션이 동시에 신청해도 사용자 · 채움 기록은 하나다. 세션 하나로 동시에 보낸 두 요청 가운데 늦은 쪽의 응답은 정하지 않는다 — 신청할 때 세션 ID 를 바꾸기 때문이고, 화면은 요청 중 버튼을 막는다(ADR-102 · 사용자 확인 2026-11-08).
- 지우기 TASK(`12-delete-withdraw` step 0) 구현: 코스 지우기는 본문 없는 `DELETE /api/courses/{id}`이며 성공은 `ResponseEntity<Void>`의 204 · 본문 없음이다(카드 지우기와 같은 공통 응답 경로). 단일 코스는 모든 상태에서 지우고 로드맵의 코스는 §1.2의 오류로 거절한다. 한 트랜잭션에서 작업 행 → 사용자 행 순서로 잠근다.
- 지우기의 범위는 [02 §7](./02-domain-data.md#7-지우기--탈퇴--함께-사라지는-범위)(ADR-140).

## 5. 아직 정하지 않은 값

| 것 | 근거 |
|---|---|
| 경로 · 칸 이름 · 오류 묶음의 목록과 문구 — 만드는 작업 부분은 `6-job-worker` step 3, 코스 · 로드맵 읽기 넷과 코스 시작 · 제목 고치기는 `7-course-planning` step 6, 강의 읽기와 학습 완료는 `8-material-cards` step 4, 오늘 복습할 카드 읽기와 코스 응답의 `todayReviewCount`는 `9-card-review` step 1, 평가 보내기와 카드 지우기는 step 2, 코스 지우기는 `12-delete-withdraw` step 0에서 정했다(§1.2 ~ §4). 나머지는 각 TASK에서 정한다 | ADR-143 · ADR-145 |
| 쿠키 설정 · 요청 위조 막기 | ADR-142 |
| 기다리는 화면의 다시 읽는 간격은 `6-job-worker` step 7에서 정했다(§2). 대상이 없는 새로 배우기를 두 번 눌렀을 때는 화면이 요청 중 버튼을 막는 것만 하고 서버 장치는 두지 않는다 — 두 탭 · 재전송이면 둘이 생기는 것은 ADR-157 이 감수한 그대로다(README 2026-11-17) | ADR-143 · ADR-157 |
| 목록을 나눠 받을지 — 정원 10명이라 MVP 에서 나눌 만큼 길지 않다 | ADR-061 |
