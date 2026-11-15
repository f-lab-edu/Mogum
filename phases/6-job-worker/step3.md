# Step 3: job-api

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/05-api.md` 전체 — 특히 §1(공통 · 넓힐 수 있게) · §1.2(오류 묶음 — 만들기 실패도 작업을 읽을 때 같은 모양) · §1.3 · §1.4(싣지 않는 것) · §2(**이 step 의 계약이다**) · §3 「작업 하나」 · §5
- `docs/06-screens.md` §6(기다리는 화면 — 무엇을 보이나 · 끝나면 어디로) · §2.4 · §2.6
- `docs/02-domain-data.md` §8.1(길이 상한) · §8.5(대상 · 결과 칸) · §8.7(`generation_jobs` 의 값 · `error_type` 과 묶음)
- `docs/04-jobs-and-cost.md` §4.2 · §4.6
- `docs/07-testing.md` §1 「남의 것」
- `docs/ADR.md` — ADR-077 · ADR-086 · ADR-089 · ADR-143 · ADR-145 · ADR-163 · ADR-174
- `docs/TEST_GUIDE.md` §1(컨트롤러 `@WebMvcTest` · 실제 HTTP `@IntegrationTest`) · §6(요청 도구)
- `AGENTS.md` — 금지 18 · 20 · 25 · 26
- step 0 ~ 2 가 만든 것: `backend/src/main/java/com/mogum/job/` 전체 · `account/service/`(크레딧) · `common/api/`(step 1 이 넓힌 곳) · 테스트 전용 처리기(step 2 summary)
- 본보기 컨트롤러 · 테스트: `backend/src/main/java/com/mogum/account/controller/ApplyController.java` · `MeController.java` · `account/dto/MeResponse.java` · `backend/src/test/java/com/mogum/account/controller/MeControllerTest.java` · `ApplyControllerIntegrationTest.java` · `support/TestRequests.java`
- `common/api/FieldViolation.java` — `reason` 종류

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

step 2 의 받기 · 다시 시도 · 버리기 서비스에 HTTP 를 붙이고, 기다리는 화면(step 7)이 몇 초마다 부를 **작업 읽기**를 만든다. 경로 · 칸 이름 · 오류 code 는 이 step 이 정해 05 에 적는다(05 §5).

**계약 문서는 이 phase 전에 정해 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 주소 넷 — `job.controller`

| 동작 | 하는 일 |
|---|---|
| 받기(보내기) | 요청 DTO(`@Valid`) → step 2 의 `JobRequest` → 받기. 응답은 작업 읽기와 같은 모양 |
| 작업 하나 읽기(가져오기) | 아래 §2 |
| 다시 시도(보내기) | step 2 |
| 버리기(보내기 — 지우기 방식은 정한다) | step 2. 성공하면 본문 없음 또는 빈 응답 — 공통 응답 규칙을 따른다 |

- 요청 칸 이름은 02 §8.5 의 칸 이름을 화면 관례(camelCase)로 쓴다. 값은 02 §8.7 의 코드 이름 그대로다(05 머리말).
- 길이 상한 · 빈 글은 DB 와 같은 값으로 검사하고(02 §8.1), 새 제약 종류를 쓰면 `FieldViolation.Reason` 을 더한다(금지 25). 「기타」 글이 `OTHER` 일 때만 있는 규칙처럼 칸 사이 규칙도 DB `CHECK` 와 같게 검사한다 — DB 오류(500)로 떨어지지 않게.
- 모르는 칸은 무시한다(05 §1 — 넓힐 수 있게). 모르는 `type` 값은 칸 오류다.
- 승인되지 않은 사람은 기존 보안 설정이 막는다(05 §1.1) — 새로 막지 마라. 확인만 한다.

### 2. 작업 읽기 — `job.service`

주인이 아니거나 없으면 `NOT_FOUND`(금지 18). 응답에 싣는 것(05 §2 · 06 §6):

- `id` · `type` · `status`(02 §8.7 의 코드 이름).
- **무엇을 만드는 중인지** — 화면이 그대로 보일 값.
    - `NEW_*` — 입력한 주제 · 수준 · 목적(기타면 그 글). 메모는 싣지 않는다(06 §6 은 주제 · 수준 · 목적만 보인다).
    - `MATERIAL` — 그 강의의 제목.
    - `PLAN_ROADMAP_COURSE` · `REPLAN_COURSE` — 그 코스의 제목. `REPLAN_ROADMAP` — 그 로드맵의 제목.
    - 대상 제목은 `course` 패키지의 레포지토리로 읽는다(`job → course` 참조는 허용된다 — `generation` 만 막힌다).
- **성공하면 결과** — 화면이 넘어갈 곳(06 §2.4): 코스(`NEW_COURSE` · `PLAN_ROADMAP_COURSE` · `REPLAN_COURSE`) · 로드맵(`NEW_ROADMAP` · `REPLAN_ROADMAP`) · 강의(`MATERIAL` — 화면 주소에 코스가 필요하면 코스 ID 도). 「무엇으로 · 어느 ID」를 객체로 싣는다 — 뒤에 결과 종류가 늘어도 칸만 더하게(05 §1).
- **실패하면 오류** — 05 §1.2 의 공통 오류 모양(code · 문구)으로 싣는다. code 는 묶음 둘이다 — 일시적(다시 시도 · 버리기) · 입력 문제(버리기만, `INPUT_TOO_LARGE`). 서버가 `error_type` 으로 고르고(02 §8.7), 문구는 05 §1.2 대로 서버가 정한다(입력 문제는 「주제를 좁혀 새로 만들어 주세요」 같이). 새 code 를 `ErrorCode` 에 넣는다.
- **싣지 않는 것**(금지 20 · 05 §1.4) — `error_type` 원래 값 · `claim_count` · `lease_until` · 다시 시도 횟수 · 대기 순서 · 사용자 ID · 시작/끝 시각.
- 읽기는 아무것도 바꾸지 않는다(05 §1 — 가져오기는 상태를 바꾸지 않는다).

### 3. 테스트

- **컨트롤러**(`@WebMvcTest`) — 요청 칸 검증(길이 상한 · 빈 글 · 기타 글 규칙 · 모르는 `type` → 칸 오류와 `reason`) · 응답 칸 · 싣지 않는 것이 없는지.
- **서비스**(작업 읽기) — 종류 여섯 × 상태별 응답(무엇을 만드는 중 · 결과 · 오류 묶음 둘). 상태를 바꾼 행은 step 0 픽스처의 테스트 SQL 로 만든다.
- **통합**(`@IntegrationTest` — 실제 HTTP, `/harness` 원칙 13)
    - 테스트 전용 처리기로 받기 → 읽기 → (테스트 SQL 로 실패) → 다시 시도 → (실패) → 버리기 → 읽기 `NOT_FOUND` 의 한 바퀴.
    - **처리기가 없는 종류**는 받기가 거절되고 작업 · 크레딧이 그대로다.
    - **남의 것**(07 §1) — 남의 작업 읽기 · 다시 시도 · 버리기가 `NOT_FOUND` 이고 응답에 그 작업의 무엇도 없다.
    - 「크레딧 부족」 · 「만드는 중인 것이 많음」이 공통 오류 모양 · code · 문구로 나간다.
    - CSRF 없이 보내면 막힌다 · 승인되지 않은 사람은 `ACCOUNT_NOT_APPROVED`.
    - 깨진 JSON · 칸 오류의 요청 본문에 넣은 표식 문자열이 로그에 남지 않는다(금지 26).

### 4. 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §2 · §3 — 경로 · 요청 칸 · 응답 칸 · 오류 code 를 「만드는 작업 TASK 구현:」 줄로 적는다(계정 TASK 의 줄처럼). §1.2 — 실패 묶음 둘의 code · 문구. §5 — 「경로 · 칸 이름」에서 작업 부분이 정해졌다고 적는다.
- `docs/04-jobs-and-cost.md` §2.3 — `job.controller` · 읽기 서비스.
- 규칙 문장은 고치지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.job.*' --tests 'com.mogum.common.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 컨트롤러가 `ApiResponse` 로 감싸고 오류는 `ApiException(ErrorCode)` 인가(금지 25)? 오류 JSON 을 직접 쓰지 않는가?
   - 응답에 05 §1.4 의 것(원래 실패 까닭 · 횟수 · 시한 · 순서)이 없는가?
   - 실패의 code 가 묶음 둘(일시적 · 입력 문제)이고 `error_type` 으로 골랐는가?
   - 남의 것 · 없는 것이 같은 `NOT_FOUND` 인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/6-job-worker/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 네 경로와 메서드 · 요청 · 응답 JSON 의 칸(무엇을 만드는 중 · 결과 · 오류의 모양 — step 7 화면이 쓴다) · 새 오류 code 와 문구 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 내 작업 목록(홈의 「만드는 중」) 읽기를 만들지 마라. 이유: SCHEDULE §3 상 홈 · 크레딧 TASK 에서 만든다.
- 응답에 크레딧 · 이 만들기에 드는 크레딧을 싣지 마라. 이유: 크레딧은 「나」에서만 보인다(05 §1.4 · 06 §2.6).
- 응답에 메모를 싣지 마라. 이유: 기다리는 화면은 주제 · 수준 · 목적만 보인다(06 §6).
- 워커를 만들지 마라. 이유: step 4 · 5 의 범위다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 요청은 접근 줄이 남긴다(금지 26).
- 계약 문서(05 의 규칙 문장 · 02 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
