# Step 3: seoul-clock

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 20 · 25 · 26
- `docs/02-domain-data.md` §8.1(오늘의 복습 — "오늘"은 한국 날짜)
- `docs/ADR.md` ADR-016
- `docs/07-testing.md` §1.2 의 "한국 날짜" 행
- 이전 step 산출물: `src/main/java/com/mogum/MogumApplication.java` · `src/test/java/com/mogum/ArchitectureTest.java`(설정 배선 클래스의 위치 규칙)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

"오늘"을 `Asia/Seoul` 날짜로 계산하는 공통 시계를 만든다. 이후 오늘의 복습(`:tomorrowStart`) · 사용자 일일 쿼터 · 하루 횟수 제한 · `Retry-After`(한국 자정까지)가 모두 이것만 쓴다.

### 1. `src/main/java/com/mogum/core/SeoulClock.java`

```java
public final class SeoulClock {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public SeoulClock(Clock clock) { … }

    public Instant now();
    public LocalDate today();          // 한국 날짜
    public Instant startOfToday();     // 오늘 00:00 Asia/Seoul
    public Instant startOfTomorrow();  // 내일 00:00 Asia/Seoul
}
```

### 2. 빈 등록

- `java.time.Clock` 빈(`Clock.systemUTC()`)과 `SeoulClock` 빈을 등록한다.
- 설정 배선 클래스는 `com.mogum` 패키지에 둔다(하위 패키지에 두면 ArchUnit 규칙 1 에 걸린다). 짝 테스트가 필요하다(TDD 가드 — `MogumApplication` 만 예외).
- 테스트에서 고정 `Clock` 으로 바꿔 끼울 수 있어야 한다.

### 3. 테스트 — `src/test/java/com/mogum/core/SeoulClockTest.java` (고정 `Clock`)

- `2026-10-12T14:59:59Z`(한국 23:59:59) → `today()` = 2026-10-12, `startOfTomorrow()` = `2026-10-12T15:00:00Z`
- `2026-10-12T15:00:00Z`(한국 자정) → `today()` = 2026-10-13, `startOfToday()` = `2026-10-12T15:00:00Z`
- UTC 자정 전후(`2026-10-11T23:59:59Z` · `2026-10-12T00:00:00Z`) → 둘 다 `today()` = 2026-10-12(UTC 날짜가 바뀌어도 한국 날짜는 그대로)
- JVM 기본 타임존을 바꿔도 결과가 같다(테스트 안에서 바꾸고 반드시 되돌린다)

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 패키지 구조(`core` ← `api` · `worker` · `infra`)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/0-skeleton/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 운영 코드에 `ZoneId.systemDefault()` · `Clock.systemDefaultZone()` · 인자 없는 `LocalDate.now()` · `LocalDateTime.now()` 를 쓰지 마라. 이유: 금지 목록 20 — 서버 타임존이 UTC 면 쿼터가 오전 9시에 리셋되고 오늘의 복습이 한국 날짜와 어긋난다.
- DB 의 `CURRENT_DATE` · `now()::date` 로 날짜를 자르는 코드를 만들지 마라. 이유: DB 세션 타임존에 따라 결과가 바뀐다 — 경계 시각은 이 시계로 계산해 파라미터로 넘긴다(02 §8.1 의 `:tomorrowStart`).
- 운영 코드에서 JVM 기본 타임존(`TimeZone.setDefault` · `-Duser.timezone`)을 바꾸지 마라. 이유: 숨은 전역 상태다 — 시간대는 `SeoulClock.ZONE` 으로 명시한다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
