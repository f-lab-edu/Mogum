# Step 4: log-wiring

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 30(로그) · 26(`config` 의 빈 등록) · 20(한국 날짜) · 작업 방식(TDD 짝 규칙)
- `docs/04-jobs-and-cost.md` **§9.2 로그**(특히 「구현 위치」) · §2 패키지 경계(`config` · `auth` · `common` 의 역할)
- `docs/ADR.md` **ADR-075**(검증 기록)
- `docs/ARCHITECTURE.md` — 디렉터리 트리의 `config/` 줄
- `backend/src/main/java/com/mogum/common/logging/RequestLogFilter.java` — 필터 · 종료 리스너
- `backend/src/main/java/com/mogum/auth/SecurityConfiguration.java` — 46~58줄의 `requestLogFilter` · `requestLogCompletion` 빈
- `backend/src/main/java/com/mogum/auth/SessionUserFilter.java` — MDC 에 `userId` 를 넣는 곳(세션 회원을 DB 에서 읽는다)
- `backend/src/main/java/com/mogum/config/ClockConfiguration.java` — 같은 패키지의 설정 클래스 모양
- `backend/src/test/java/com/mogum/auth/SecurityConfigurationTest.java` — `요청_필터와_종료_리스너를_같은_인스턴스로_등록한다`
- `backend/src/test/java/com/mogum/common/logging/RequestLogFilterTest.java` · `RequestLogFilterIT.java`
- `backend/src/test/java/com/mogum/config/ClockConfigurationTest.java` — 같은 패키지의 설정 테스트 모양
- `backend/src/test/java/com/mogum/deploy/DeployFilesTest.java` — 72줄 메서드 이름
- `backend/build.gradle` — `tasks.withType(Test).configureEach`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

`2b-ops-logging` 브랜치 리뷰에서 네 가지를 찾았다.

1. 요청 로그 필터를 등록하는 코드가 보안 설정(`auth.SecurityConfiguration`)에 있다. `auth` 는 Spring Security · 로그인 담당이라, 요청 로그가 어디서 켜지는지 찾으려면 보안 설정까지 열어야 한다. 뒤 TASK 가 이 자리를 본떠 다른 공통 등록을 여기에 둘 수도 있다.
2. 예외가 필터 체인 밖으로 나가면 `RequestLogFilter.doFilter` 의 `finally` 가 MDC 를 먼저 지운다. Tomcat `StandardWrapperValve` 는 필터 체인이 던진 예외를 체인이 끝난 뒤 `ERROR` + 스택으로 남긴다. 그래서 그 줄에 `requestId` 가 없다. 이 경로는 세션 회원 조회(`SessionUserFilter` 의 `users.findById`)가 DB 장애로 실패할 때처럼 실제 장애가 지나는 곳이다. 지금은 Claude 가 코드를 읽고 추론한 것이고 실행해서 확인하지는 않았다. 아래 작업 2 의 테스트로 먼저 확인한다.
3. 접근 줄의 `+09:00` 단언(`RequestLogFilterIT.assertAccess`)은 지금 증명하는 게 없다. 개발 머신의 기본 시간대가 `Asia/Seoul` 이라 `logging.pattern.dateformat` 의 `,Asia/Seoul` 이 빠져도 통과한다. 운영 컨테이너(`eclipse-temurin:21-jre`, `TZ` 설정 없음)는 UTC 로 돈다.
4. `DeployFilesTest` 의 메서드 이름 `저널은_디스크에_보존하고_파일_회전까지_총_14일과_500MB_이하다` 가 실제 검사(500MiB)와 단위가 다르다.

## 확인한 사실 — 2026-10-27 Claude 확인

- 저장소에 아래 init 스크립트를 걸고 `./backend/gradlew -p backend test --rerun` 를 돌렸다. 서버 테스트 376개가 통과했다(실패 · 오류 · 건너뜀 0). init 스크립트는 `allprojects { tasks.withType(Test).configureEach { systemProperty 'user.timezone', 'UTC' } }` 이다. 같은 설정에서 테스트 작업의 `allJvmArgs` 에 `-Duser.timezone=UTC` 가 실리는 것도 확인했다. 그러므로 지금 테스트 중에 기본 시간대에 기대 깨지는 것은 없다.
- 테스트 JVM 기본 시간대를 실제로 출력해 확인하지는 않았다. 작업 3 의 단언으로 확인한다.

## 작업

### 1. 등록을 `config` 로 옮긴다

- **테스트 먼저** — `backend/src/test/java/com/mogum/config/RequestLogConfigurationTest.java` 를 만든다. `SecurityConfigurationTest.요청_필터와_종료_리스너를_같은_인스턴스로_등록한다` 를 옮긴 것이다. 확인할 것: 순서 `Ordered.HIGHEST_PRECEDENCE`, 디스패치 `REQUEST` · `ERROR`, 필터와 리스너가 같은 인스턴스. 옮긴 뒤 `SecurityConfigurationTest` 에서는 이 테스트를 지운다.
- `backend/src/main/java/com/mogum/config/RequestLogConfiguration.java`
    - `@Configuration(proxyBeanMethods = false)` 클래스다.
    - `SecurityConfiguration` 의 두 빈 메서드를 그대로 옮긴다. 이름과 시그니처도 그대로 둔다.
        - `FilterRegistrationBean<RequestLogFilter> requestLogFilter()`
        - `ServletListenerRegistrationBean<RequestLogFilter> requestLogCompletion(FilterRegistrationBean<RequestLogFilter>)`
    - 이유: `RequestLogFilterIT` 가 `context.getBean("requestLogFilter", …)` 로 이름을 찾는다.
    - 순서 · 디스패치 값은 바꾸지 않는다.
- `SecurityConfiguration` 에서 두 빈을 지우고, 그 때문에 쓰이지 않게 된 import 만 지운다.

### 2. 필터 체인 밖으로 예외가 나간 요청에서 컨테이너 `ERROR` 줄에 ID 를 남긴다

**요구** — 컨테이너가 남기는 `ERROR` 줄(스택 포함)에 그 요청의 `requestId` 가 붙어야 한다. 로그인한 요청이면 `userId` 도 붙는다.

**규칙**
- 예외로 끝난 디스패치는 `doFilter` 의 `finally` 에서 두 MDC 키를 지우지 않는다. 지우는 일은 `requestDestroyed` 가 한다.
- 정상으로 돌아온 디스패치는 지금처럼 `finally` 에서 지운다.
- 그대로 두는 것:
    - 접근 줄은 지금처럼 요청당 한 줄이고 `status=500` 이다.
    - 회원 ID 를 `state` 에 보관하는 동작.
    - 요청이 시작할 때 `REQUEST_ID` 를 덮어쓰고 `USER_ID` 를 지우는 동작. 다음 요청에 앞 요청의 ID 가 남지 않게 막는 장치가 이것이다.

**테스트 먼저 — `RequestLogFilterIT`**
- 테스트 전용 필터를 `@TestConfiguration` 으로 등록한다.
    - 경로 하나(예: `/api/test-request-log-filter-failure`)의 `REQUEST` 디스패치에서만, 표식 메시지를 담은 `RuntimeException` 을 던진다. `ERROR` 디스패치에서는 던지지 않는다.
    - 순서는 Spring Security 필터 체인(`securityFilterChainRegistration`)보다 뒤에 둔다. 그래야 로그인한 요청에서 `SessionUserFilter` 가 `userId` 를 넣은 뒤에 던진다.
    - 다른 경로는 그대로 통과시킨다. 이 클래스의 다른 테스트가 바뀌지 않게 하기 위해서다.
- 로그인한 요청(GET)으로 그 경로를 부르고 아래를 확인한다.
    - 응답은 `500` 이다.
    - 응답 헤더의 ID 로 찾은 접근 줄이 한 줄이고, `status=500` 이며 `userId` 가 붙어 있다. 기존 `assertAccess` 를 쓴다.
    - 같은 `requestId` 가 붙은 `ERROR` 줄이 있고, 그 줄에 `userId=<회원 ID>` 도 있다. 표식 메시지는 출력(스택)에 있다.
    - 같은 서버 스레드에서 이어 보낸 비로그인 요청의 접근 줄에는 `userId` 가 없고 ID 가 다르다. 기존 `같은_서버_스레드의…` 테스트처럼 `[스레드]` 로 같은 스레드인지 확인한다.
- 컨테이너 `ERROR` 줄을 고르는 문구(로거 이름 · 메시지)는 실제 실행 출력에서 옮겨 쓴다. 꾸며 쓰지 않는다.
- **지금 코드에서 이 테스트가 실패하는 것을 먼저 본다.** 실패할 때 출력에서 확인한 사실(ERROR 줄에 `requestId` 가 없음)을 summary 에 한 줄로 적는다.
    - 지금 코드에서도 통과하면(이미 ID 가 붙어 있으면) 필터를 고치지 않는다. `blocked` 로 멈추고, 실제 출력 줄을 사유에 적는다. 이유: 이 작업의 전제(배경 2)가 틀린 것이다.

**단위 테스트 — `RequestLogFilterTest`**
- `필터_밖으로_나가는_예외는_그대로_던지고_500_한_줄과_MDC_정리를_보장한다` 는 예외 직후 두 MDC 키가 비어 있다고 단언한다. 이 사실이 바뀌므로 테스트를 새 동작에 맞게 고친다.
    - 예외 직후에는 두 키가 그 요청의 값으로 남아 있다.
    - `requestDestroyed` 뒤에는 비어 있다.
    - 접근 줄은 한 줄 · `status=500` 이다.
    - 메서드 이름도 새 동작에 맞춘다.
- 정상 경로의 MDC 정리 단언은 바꾸지 않는다.

### 3. 테스트 JVM 의 기본 시간대를 UTC 로 둔다

- `backend/build.gradle` 의 `tasks.withType(Test).configureEach` 에 `systemProperty 'user.timezone', 'UTC'` 를 더한다. 왜 UTC 로 두는지 한 줄 주석을 단다. 주석에 담을 내용: 운영 컨테이너와 같은 기본 시간대라서, 한국 시간 머신에서도 기본 시간대에 기대는 코드 · 설정이 드러난다.
- `RequestLogFilterIT` 의 `+09:00` 단언 옆에서 `TimeZone.getDefault()` 가 UTC 임을 함께 단언한다. 이유: 이 단언이 있어야 `+09:00` 이 기본 시간대가 아니라 로그 설정에서 왔다는 것이 증명된다. 단언은 한 곳에만 둔다(예: `assertAccess` 또는 별도 테스트 하나).
- UTC 로 바꾼 뒤 전체 테스트에서 실패가 나면, 그 테스트 · 코드를 고치지 않는다. `blocked` 로 멈추고 실패한 테스트와 원인을 사유에 적는다. 이유: Claude 확인에서는 376개가 통과했다. 새로 실패한다면 이 step 의 다른 변경 탓이거나, 제품 코드가 기본 시간대에 기대는 것(금지 20)이다. 후자라면 범위를 따로 정해야 한다.

### 4. `DeployFilesTest` 메서드 이름

- `저널은_디스크에_보존하고_파일_회전까지_총_14일과_500MB_이하다` 를 `…_500MiB_이하다` 로 바꾼다. 검사 내용은 바꾸지 않는다.

### 5. 문서

- `docs/04-jobs-and-cost.md` §9.2 「구현 위치」
    - 등록하는 클래스를 `config.RequestLogConfiguration` 으로 고친다.
    - MDC 를 지우는 시점을 새 규칙대로 고친다. 정상으로 돌아온 디스패치는 `finally`, 예외로 끝난 디스패치는 `requestDestroyed` 이고, 그래서 컨테이너 `ERROR` 줄에 ID 가 붙는다.
- `docs/ARCHITECTURE.md` 디렉터리 트리의 `config/` 줄에 요청 로그 필터 등록을 더한다.
- `docs/ADR.md` ADR-075 「검증」에 `2b-ops-logging` step 4 기록을 한 줄로 더한다. 담을 것: 실제로 확인한 것, 테스트 수, 테스트 JVM 이 UTC 인 상태에서 `+09:00` 을 확인했다는 점. 서버에서의 실제 동작은 아직 확인하지 않았다는 문장은 그대로 둔다.
- `docs/07-testing.md` 에 테스트 실행 환경(JVM 설정)을 적는 곳이 있으면 테스트 JVM 이 UTC 로 돈다는 사실을 한 줄 더한다. 그런 곳이 없으면 새 절을 만들지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests '*RequestLog*' --tests '*SecurityConfiguration*' --tests '*DeployFilesTest'
./backend/gradlew -p backend build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 패키지 구조(도메인별 패키지 · 그 안은 `controller` · `service` · `repository` · `domain` · `dto` · `generation` 은 `worker` · `config` 만 참조)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
   - `auth` 에 요청 로그 등록이 남아 있지 않은가? `RequestLogFilter` 가 등록되는 곳이 한 곳뿐인가?
3. 결과에 따라 `phases/2b-ops-logging/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (API 키, 외부 인증, 수동 설정, 문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 로그 패턴(`logging.pattern.*`) · 접근 줄 필드 · 요청 ID 형식 · `X-Request-Id` 헤더 동작을 바꾸지 마라. 이유: 이 step 은 등록 위치와 실패 경로의 MDC 수명만 고친다. 형식은 ADR-075 · 04 §9.2 에 정해져 있다.
- `RequestLogFilter` 를 `@Component` 등 컴포넌트 스캔으로 올리지 마라. 이유: 등록이 두 곳이 되면 필터가 두 번 붙거나 순서 · 디스패치 설정이 빠진 등록이 생긴다. 등록은 `config.RequestLogConfiguration` 한 곳이다.
- `SecurityConfiguration` 의 다른 빈 · 보안 설정을 바꾸지 마라. 이유: 이 step 은 로그 등록만 옮긴다.
- 기본 시간대에 기대는 테스트 · 제품 코드를 이 step 에서 고치지 마라(위 작업 3 대로 `blocked`). 이유: 범위가 커지고 금지 20 판단이 필요하다.
- 테스트 전용 예외 필터를 `src/main` 에 두지 마라. 이유: 운영에 실패 경로를 만드는 코드가 들어간다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
