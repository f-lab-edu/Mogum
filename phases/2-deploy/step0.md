# Step 0: ops-config

## 배경 — 이 phase(`2-deploy`)가 하는 일

지금의 앱(구글 로그인 · U1)을 운영에 올린다. 구성은 `docs/ADR.md` ADR-072 에 정했다 — 서울 리전 EC2 한 대(arm64 · 2GB)의 Docker Compose 가 Caddy(HTTPS) · 앱(`backend/Dockerfile` 이미지) · Postgres 를 띄우고, `main` 병합 때 GitHub Actions 가 이미지를 ECR 에 올린 뒤 SSM 으로 서버의 배포 스크립트를 돌린다. 서버는 새 앱의 **헬스**를 확인해 실패하면 직전 릴리스로 되돌린다. 배포(SIGTERM)는 정상 종료 유예를 지켜야 한다(ADR-044).

이 step 은 그 흐름이 기대는 **앱 쪽 설정**만 한다 — 헬스 엔드포인트와 정상 종료 대기. 배포 파일 · 워크플로는 다음 step 들이 만든다.

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 · 작업 방식(TDD · 짝 테스트 규칙)
- `docs/ADR.md` — ADR-044(정상 종료 반납) · ADR-069(서버 이미지) · ADR-072(운영 배포 구성)
- `docs/04-jobs-and-cost.md` §3(배포 — 운영 구성과 배포 흐름 표) · §4.5(반납 · 회수 · 시한 — 「같이 맞출 설정」 표) · §9.1(알림 — 외부 헬스체크가 `/actuator/health` 를 본다)
- `docs/05-api.md` §1.1(인증 — 경로 규칙 · 보안 필터) · §1.2(에러)
- `backend/build.gradle` · `backend/src/main/resources/application.yml`
- `backend/src/main/java/com/mogum/auth/SecurityConfiguration.java` — 지금은 `/auth/google` · `/auth/google/callback` 만 열고 `/api/**` 는 인증, 그 밖은 `denyAll` 이다
- `backend/src/test/java/com/mogum/auth/SecurityConfigurationIT.java` — 실제 HTTP(`RANDOM_PORT`) 보안 테스트의 기존 방식

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

### 1. 헬스 엔드포인트 — `GET /actuator/health`

- `backend/build.gradle` 에 `org.springframework.boot:spring-boot-starter-actuator` 를 더한다. 버전은 적지 않는다 — Spring Boot BOM 이 관리한다.
- 웹으로 여는 actuator 엔드포인트는 **`health` 하나**다. 세부 정보(`components` · `details`)는 누구에게도 보이지 않는다. 응답 본문은 `{"status":"UP"}` 모양이다.
- 헬스는 DB 연결을 포함해 판정한다(프레임워크 기본 DB 헬스 지표를 끄지 않는다) — DB 가 죽으면 헬스가 실패해야 배포 스크립트가 직전 릴리스로 되돌린다.
- `SecurityConfiguration` 에서 **`GET /actuator/health` 만** 인증 없이 허용한다. 다른 `/actuator/**` 경로는 지금처럼 거절된다(`denyAll`). CORS · CSRF 설정은 바꾸지 않는다 — 헬스는 브라우저 화면이 아니라 배포 스크립트 · 외부 헬스체크가 부른다.

### 2. 정상 종료 대기 — 04 §4.5 「같이 맞출 설정」

`application.yml` 에 아래 둘을 **명시**한다. 프레임워크 기본값과 같더라도 적는다 — 04 §4.5 가 근거이고, 기본값이 바뀌어도 유예가 지켜져야 한다.

- `server.shutdown: graceful`
- `spring.lifecycle.timeout-per-shutdown-phase: 130s`

(프로세스 관리자의 강제 종료 대기 150초는 운영 Compose 파일의 일이다 — step 2.)

### 3. 테스트

TDD 가드: `SecurityConfiguration.java` 를 고치려면 같은 패키지의 짝 테스트(`SecurityConfiguration*Test.java` · `SecurityConfiguration*IT.java`)가 있어야 한다. 테스트를 먼저 쓰고 실패를 확인한 뒤 구현한다.

- **실제 HTTP 로 확인한다(`@SpringBootTest(webEnvironment = RANDOM_PORT)`).** MockMvc 는 서블릿 ERROR 디스패치 · 실제 컨테이너 동작을 거치지 않아, 보안 필터가 거절해도 통과하는 일이 있었다(`1-auth` step 1). 새 테스트 파일은 예: `backend/src/test/java/com/mogum/auth/SecurityConfigurationHealthIT.java`(짝 규칙에 맞는 이름).
  - 로그인 없이 `GET /actuator/health` → `200` · JSON 의 `status` 가 `UP` · 본문에 `components` · `details` 가 없다.
  - 로그인 없이 `GET /actuator` · `GET /actuator/info` · `GET /actuator/env` → `200` 이 아니다(허용 범위가 `health` 하나임을 고정한다). 응답 본문에 환경 변수 · 설정값이 실리지 않는다.
  - 기존 보안 동작이 그대로다 — 기존 `SecurityConfiguration*IT` 테스트를 깨뜨리지 않는다.
- 정상 종료 설정 — 스프링 컨텍스트에 바인딩된 값으로 확인한다(예: 컨텍스트의 `Environment` 나 바인딩된 설정 객체에서 `130s` · `graceful` 을 읽는다). 실제 SIGTERM 동작은 step 1(이미지 검사)이 확인한다.

### 4. 문서

- `docs/04-jobs-and-cost.md` §4.5 「같이 맞출 설정」 표 — 앞의 두 행에 *"`application.yml` 에 명시"* 를 덧붙인다(값은 그대로).
- `docs/04-jobs-and-cost.md` §9.1 — 헬스체크 행의 `/actuator/health` 가 **세부 정보 없이 상태만** 공개된다는 것을 한 구절로 덧붙인다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # 백엔드 테스트 통과 (Testcontainers — Docker 필요)
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/ARCHITECTURE.md` 패키지 구조(도메인별 패키지 · 그 안은 `controller` · `service` · `repository` · `domain` · `dto` · `generation` 은 `worker` · `config` 만 참조)를 따르는가?
   - `docs/ADR.md` 기술 스택을 벗어나지 않았는가? 새 의존성은 `spring-boot-starter-actuator` 하나뿐인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/2-deploy/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 바꾼 파일 · 헬스 경로와 응답 모양 · 종료 설정 · 테스트 수를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `health` 말고 다른 actuator 엔드포인트(`info` · `env` · `metrics` · `prometheus` …)를 웹에 열지 마라. 이유: 공개 저장소 · 공개 서버라 설정 · 환경 변수가 새고, 사용자에게 시스템 정보를 싣지 않는다(금지 목록 23).
- 헬스 세부 정보를 공개하지 마라(`show-details` 를 `always` · `when-authorized` 로 두지 마라). 이유: DB 종류 · 디스크 용량 같은 내부 정보가 인증 없이 보인다.
- actuator 를 별도 관리 포트(`management.server.port`)로 옮기지 마라. 이유: 배포 스크립트 · 외부 헬스체크가 같은 포트 · 같은 경로를 본다고 정했다(ADR-072 · 04 §9.1).
- 프록시 헤더(`server.forward-headers-strategy`) · 운영 프로필(`application-aws.yml`)을 이 step 에서 만들지 마라. 이유: 지금 기능이 쓰지 않는다 — 요청 IP 가 필요한 레이트리밋은 `10-cost-control` 에서 정한다.
- 기존 CORS · CSRF · 쿠키 설정을 바꾸지 마라. 이유: `1-auth` 에서 출처 분리를 테스트로 확정했다(05 §1.1).
- `.env` 파일을 만들거나 읽지 마라. 이유: `AGENTS.md`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
