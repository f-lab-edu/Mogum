# Step 3: home-review-streak

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/02-domain-data.md` §3(학습과 복습 — **스트릭** 문단 · 오늘 복습할 카드) · §8.3(`lessons.completed_at`) · §8.4(`cards` · `reviews`)
- `docs/05-api.md` §1 · §1.3 · §1.4 · **§3(「홈의 칸마다」 줄 · 「오늘 복습할 카드」 구현 줄)**
- `docs/06-screens.md` §4(홈 — 스트릭 · 오늘 복습 · 비었을 때)
- `docs/04-jobs-and-cost.md` §2.2(패키지 경계)
- `docs/ADR.md` — ADR-016 · ADR-017 · ADR-096 · ADR-144 · ADR-178
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 7 · 17 · 18 · 20 · 25
- `backend/src/main/java/com/mogum/card/` — `service/TodayReviewService.java`(`count(userId, courseId)`) · `controller/` · `repository/JpaReviewCardRepository.java`(네이티브 SQL 로 `lessons` 를 읽는 방식) · `repository/ReviewRepository.java`
- `backend/src/main/java/com/mogum/common/time/SeoulClock.java`(`today()` · `startOfToday()`)
- `backend/src/test/java/com/mogum/ArchitectureTest.java` — `card` 는 `course` 에 의존하지 않는다

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

홈은 칸마다 따로 읽는다(ADR-144). 이 step 은 홈 맨 위의 둘 — **오늘 복습할 카드 수**와 **스트릭** — 의 읽기를 만든다. 스트릭은 저장하지 않고 복습 기록이 생긴 때(`reviews.created_at`)와 강의를 학습 완료한 때(`lessons.completed_at`)에서 계산한다(ADR-178).

**계약 문서는 이 phase 전에 정해 커밋했다(02 §3 · 05 §3 · 06 §4). 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

- 로그인한 사람의 홈 칸 읽기를 만든다 — 경로 · 칸 이름은 재량이다(05 머리말 — TASK 에서 정한다). 둘을 한 읽기로 묶을지, 칸마다 따로 둘지도 재량이지만 **오늘 복습 수와 스트릭은 서로 다른 칸**이므로 화면이 칸마다 실패를 나눌 수 있게 하는 쪽을 고른다(ADR-144). 결정과 이유를 summary 에 적는다.
- **오늘 복습** — `TodayReviewService.count(userId, null)` 을 그대로 쓴다. 오늘 복습할 카드를 고르는 규칙을 다시 쓰지 마라 — 한 곳에서만 고른다(05 §3 구현 줄). 기존 `GET /api/reviews/today` 를 화면이 그대로 써도 된다고 판단하면 새 읽기를 만들지 않아도 된다 — 그 경우 summary 에 「화면은 `GET /api/reviews/today` 의 `remainingCount` 를 쓴다」고 적는다.
- **스트릭** — 응답은 `{days, filledToday}`(이름 재량, 정수와 불리언)이다(05 §3 「연속 일수 · 오늘 채웠는지」).
  - 채운 날 = 하루(서울 새벽 4시 경계 — 금지 17)에 그 사람의 카드에 복습 기록이 1장 이상 생겼거나 그 사람의 강의를 하나 이상 학습 완료한 날(02 §3).
  - 오늘 채웠으면 오늘부터 거꾸로 이어진 채운 날 수. 오늘 아직이면 어제부터 이어진 수. 어제도 아니면 0.
  - 「오늘」은 `Clock` 빈 · `SeoulClock` 으로 정한다. 서버 · DB 기본 시간대에 기대지 마라 — SQL 에서 날짜로 바꾸면 `AT TIME ZONE 'Asia/Seoul'` 과 4시간 빼기를 명시한다.
  - 테이블 · 칸을 더하지 마라(ADR-178). 읽기에 색인이 필요하다고 판단하면 새 마이그레이션으로 색인만 더하고 summary 에 이유를 적는다(ADR-178 — 색인은 TASK 에서 정한다). 정원 10명이라 없어도 되면 더하지 않는다.
  - 지운 카드(복습 기록이 함께 지워짐)와 지운 코스(강의가 함께 지워짐)는 셀 수 없다 — 그대로 둔다(ADR-178 감수).
- 패키지 — `ArchitectureTest` 를 지킨다(`card` → `course` 금지). `card` 에서 `lessons` 를 읽어야 하면 기존 `JpaReviewCardRepository` 처럼 `EntityManager` 네이티브 SQL 로 읽고 `course` 클래스를 참조하지 마라. 두는 곳과 이유를 summary 에 적는다.
- 응답에 사용자 ID · 날짜별 기록(달력 · 점)을 싣지 마라(ADR-096 · 금지 20).

### 시험

- 스트릭 규칙(실제 DB · `TestClock`):
  - 기록 없음 → `{0, false}`.
  - 오늘 복습 1장 → `{1, true}` · 오늘 강의 학습 완료만 → `{1, true}`.
  - 어제 · 그제 채움, 오늘 아직 → `{2, false}` · 그제만 채움 → `{0, false}`(어제가 끊었다).
  - 오늘 · 어제 · 사흘 전 → `{2, true}`(사흘 전은 이어지지 않는다).
  - **경계** — 서울 03:59 의 복습은 전날이다. 지금이 서울 03:30 이면 「오늘」은 전날이다(02 §3 — 새벽 1시의 복습은 그날 밤).
  - 같은 날 여러 번 → 하루로 센다.
  - 남의 복습 · 남의 학습 완료는 세지 않는다.
- 오늘 복습 수 — 새 읽기를 만들었다면 `TodayReviewService` 와 같은 수(코스 거르기 없음)인지 · 0 도 싣는지.
- 컨트롤러(`@WebMvcTest`) — 응답 모양 · 로그인하지 않으면 기존 보안 규칙대로 거절.
- 읽기는 아무것도 바꾸지 않는다(카드 · 복습 기록 · 시각).

### 문서 — 구현으로 정해진 사실만

- `docs/05-api.md` §3 「홈의 칸마다」 아래에 구현 줄을 더한다 — 경로 · 칸 이름 · 모양 · 오늘 복습 수를 어디서 읽는지.
- 색인을 더했으면 `docs/02-domain-data.md` §8.4 의 그 테이블 색인 줄과 `docs/ERD.md` 를 고친다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.card.*' --tests '*ArchitectureTest'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 스트릭을 저장하지 않는가(금지 7 · ADR-178)? 경계가 서울 새벽 4시인가(금지 17)?
   - 오늘 복습할 카드를 고르는 규칙이 한 곳뿐인가?
   - `card` 가 `course` 클래스를 참조하지 않는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/11-home-credits/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 경로 · 칸 이름 · 모양(step 6 화면이 쓴다) · 오늘 복습 수를 화면이 어디서 읽는지 · 색인 여부 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 이어서 배우기 · 할 일 · 만드는 중 읽기를 만들지 마라. 이유: step 4 · 5 의 범위다.
- 화면(`frontend/`)을 고치지 마라. 이유: step 6 의 범위다.
- 스트릭을 저장하는 테이블 · 칸 · 캐시를 두지 마라. 이유: ADR-178 · 금지 7 · 22.
- 지난 며칠의 기록 · 가장 긴 연속 일수를 응답에 싣지 마라. 이유: ADR-096 · ADR-064 — 통계는 두지 않는다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓰고, 접근 줄이 요청을 남긴다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)의 규칙을 고치지 마라. 이유: phase 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
