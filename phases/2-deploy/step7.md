# Step 7: runbook

## 배경 — 이 phase(`2-deploy`)가 하는 일

지금의 앱을 운영에 올린다(`docs/ADR.md` ADR-072). step 0~6 이 저장소 쪽을 모두 만들었다 —

- step 0: 헬스 `GET /actuator/health` · 정상 종료 설정. step 1: `imageTest`.
- step 2: `deploy/compose.yaml` · `deploy/Caddyfile` · `deploy/deploy.sh` · `deploy/mogum.env.example`.
- step 3: `deploy/backup.sh` · `deploy/restore.sh` · `deploy/systemd/`.
- step 4: `deploy/aws/`(OIDC 신뢰 · 배포 역할 · 인스턴스 역할 · ECR/S3 수명주기).
- step 5: `.github/workflows/deploy.yml`. step 6: `frontend/vercel.json`.
- 각 step 이 `deploy/README.md` 에 자기 절(서버 구성 · 백업 · 복원 · AWS 설정 · GitHub Actions 배포 · Vercel)을 썼다.

남은 것은 **사람이 실제로 올리는 일**이다 — 도메인 구매 · AWS 자원 · 서버 준비 · 저장소 변수 · Vercel · 구글 콘솔 · 첫 배포 · 운영 확인. Codex 는 AWS · DNS · Vercel 을 만질 수 없으므로, 이 step 은 사람이 위에서 아래로 따라 하면 되는 **처음 배포 절차서**로 `deploy/README.md` 를 완성하고 설계 문서를 맞춘다.

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md`
- `docs/ADR.md` — ADR-060 · ADR-062(관리자 이메일만 로그인) · ADR-069 · ADR-072
- `docs/01-overview.md` §10(공개 필수 목록 — 도메인 · HTTPS · DB 백업 · 운영 도메인 확인 줄)
- `docs/04-jobs-and-cost.md` §3 · §4.5
- `docs/05-api.md` §1.1(쿠키 속성 · CSRF · CORS · 운영 환경 변수 · 구글 운영 콜백)
- `docs/ARCHITECTURE.md`(디렉터리 구조 · 배포 절)
- `deploy/` 전체 · `deploy/aws/` 전체 · `.github/workflows/deploy.yml` · `frontend/vercel.json`
- `deploy/README.md` — 이전 step 들이 쓴 절과 「첫 배포 때 확인」 항목

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

### 1. `deploy/README.md` 완성

이전 step 의 절은 내용을 지키며 순서만 맞춘다. 맨 앞에 아래를 더한다.

**① 처음 구축 순서** — 번호 매긴 단계, 각 단계에 *할 일 · 쓸 파일 · 끝났는지 확인하는 방법*. 이 순서를 따른다:
1. 도메인 구매 — Cloudflare 에서 등록했고 DNS 도 Cloudflare 다(사용자 결정 2026-10-25). 이후 `{도메인}` 을 그 값으로 읽는다.
2. AWS(서울 리전): OIDC 공급자 → ECR `mogum-app`(태그 덮어쓰기 금지 · 수명주기) → S3 버킷(공개 차단 · 기본 암호화 · 버전 관리 · 수명주기) → 인스턴스 역할(+ `AmazonSSMManagedInstanceCore`) → EC2(`t4g.small` · Amazon Linux 2023 arm64 · 보안 그룹 인바운드 80/443 만 · 22 없음 · 인스턴스 역할 연결) → Elastic IP → 배포 역할(OIDC 신뢰 · 인스턴스 ID 를 넣은 권한).
3. DNS(Cloudflare): `api.{도메인}` → Elastic IP 인 A 레코드. **프록시를 끈다(DNS only · 회색 구름)** — 켜면 모든 요청이 Cloudflare IP 로 보여 IP 당 레이트리밋(05 §1.4)이 깨지고 Caddy 의 인증서 발급 경로가 바뀐다(ADR-060 이 Vercel 프록시를 뺀 것과 같은 이유). 레코드를 만든 뒤 프록시 상태를 확인하는 방법도 적는다. (화면 `{도메인}` 은 6 에서 Vercel 이 안내하는 값으로 — 그 레코드도 DNS only.)
4. 서버 준비(SSM Session Manager 로 접속): Docker · Compose 플러그인 설치 · `/opt/mogum` · `/etc/mogum/mogum.env`(견본에서 값 채우기 · root `600`) — 서버에 필요한 명령 목록과 설치 명령.
5. GitHub 저장소 변수(`DEPLOY_ENABLED` 는 마지막에 `true`).
6. Vercel 프로젝트 · 환경 변수 · 도메인.
7. 구글 콘솔: 승인된 리디렉션 URI `https://api.{도메인}/auth/google/callback` 추가(로컬 URI 는 그대로 둔다).
8. 첫 배포: `DEPLOY_ENABLED=true` 후 `workflow_dispatch`(빈 `sha`) — 워크플로 로그에서 SSM 출력 · 헬스 확인.

**② 운영 확인 목록** — 첫 배포 뒤 사람이 하나씩 체크한다. 각 항목에 *어떻게 보는지(브라우저 개발자 도구 · `curl` 명령)* 와 *기대하는 결과*를 적는다:
- `https://{도메인}` 이 뜨고, `/login` 에서 **새로고침**해도 U1 이 뜬다.
- `https://api.{도메인}/actuator/health` → `{"status":"UP"}` · 인증서가 유효하다.
- 관리자 이메일로 구글 로그인 → `/` 로 돌아온다 · `GET /api/me` 가 `200`. 관리자 목록에 없는 계정은 `/login?error=not_allowed`.
- 쿠키: 세션 쿠키가 API 호스트 전용(`Domain` 없음) · `HttpOnly` · `Secure` · `SameSite=Lax`. `XSRF-TOKEN` 은 `Domain={도메인}` · `HttpOnly` 아님 · `Secure`.
- CSRF: 로그아웃(`POST /api/auth/logout`)이 화면에서 성공(`204`)하고, 토큰 없이 보낸 같은 요청은 `403 CSRF_INVALID`.
- CORS: 다른 출처에서의 `/api` 요청은 허용 헤더가 없다.
- 배포 중 정상 종료: 서버에서 앱을 재시작해도 `kill -9` 경로가 아니다(종료 대기 150초 · 앱 로그의 정상 종료). 실행기의 반납 동작은 `4-job-queue` 에서 확인한다.
- 백업: `systemctl start mogum-backup.service` 로 한 번 돌려 S3 `backups/` 에 객체가 생긴다 · 타이머가 다음 실행 시각(한국 04:00)을 보여 준다.
- 복원 연습: `restore.sh latest` 가 테이블별 행 수를 보여 주고 확인용 DB 를 지운다. 결과를 절차서의 기록 칸에 적는다(날짜 · 객체 키 · 행 수).
- 되돌리기 연습(선택): 헬스가 실패하는 릴리스가 없어도, `workflow_dispatch` 에 직전 SHA 를 넣어 재배포가 되는지.

**③ 첫 배포 때 확인** — 이전 step 들이 「확인하지 못했다」 고 남긴 외부 사실을 한곳에 모은다(예: `ubuntu-24.04-arm` 러너 · 액션 태그 · 타이머 표기 · AWS CLI 명령 형식 · Vercel Hobby 의 비상업 조건). 각 항목에 *확인하는 방법*과 *틀렸을 때 고칠 곳*을 적는다.

**④ 비용(대략)** — EC2 `t4g.small` · EBS · Elastic IP(공인 IPv4) · S3 · ECR 의 월 대략치를 출처(AWS 요금 페이지)와 함께 적되, **확인한 값만 숫자로 적고 확인하지 못했으면 "요금 계산기로 확인" 으로 둔다.** 프리티어는 끝났다(04 §3).

### 2. 설계 문서 맞추기

- `docs/ARCHITECTURE.md` — 디렉터리 구조에 `deploy/`(운영 Compose · 스크립트 · `aws/` · `systemd/` · 절차서) · `.github/workflows/deploy.yml` · `frontend/vercel.json` 을 더한다. 「배포」 절에 절차서 링크.
- `docs/04-jobs-and-cost.md` §3 — 「배포 파일 · 워크플로 · 처음 배포 절차서는 `2-deploy` 에서 만든다(아직)」 를 *만들었다 · 실제 AWS · 운영 확인은 아직* 으로 바꾸고 절차서 링크를 단다.
- `docs/ADR.md` ADR-072 의 **검증** 문단 — 저장소 쪽(파일 · 워크플로 · 불변식 테스트 · `imageTest`)을 만들고 테스트로 확인했다는 것, **실제 AWS · 운영 도메인 배포와 복원 연습은 아직 수행하지 않았다**는 것을 적는다.
- `docs/05-api.md` §1.1 — `2-deploy` 에서 *"실제 값 설정"* 한다고 적은 두 곳(Vercel 변수 · `GOOGLE_REDIRECT_URI`)에 절차서 링크를 더한다. 실제로 설정했다고 쓰지 않는다.
- `docs/01-overview.md` §10 의 체크박스는 **바꾸지 않는다** — 운영에서 확인한 뒤 사람이 바꾼다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # 문서만 바꾼 step 이지만 기존 불변식 테스트가 그대로 통과하는지 확인한다
npm --prefix frontend run test
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 체크리스트를 확인한다:
   - 절차서의 파일 경로 · 변수 이름 · 명령이 실제 저장소 파일(`deploy/` · 워크플로 · `vercel.json`)과 같은가? (이름 하나라도 다르면 사람이 따라 하다 막힌다)
   - 실제로 수행하지 않은 것(AWS 배포 · 운영 확인 · 복원 연습)을 수행했다고 쓰지 않았는가?
   - 비밀 · 실제 계정 값이 문서에 없는가?
3. 결과에 따라 `phases/2-deploy/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 절차서의 절 목록 · 「첫 배포 때 확인」 항목 수 · 고친 설계 문서를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 배포 파일 · 스크립트 · 워크플로 · 정책 파일의 **동작**을 바꾸지 마라. 이유: 이 step 은 문서다. 절차서를 쓰다 파일의 결함을 찾으면 고치지 말고 `blocked` 로 사유를 적는다.
- `docs/01-overview.md` §10 체크박스를 체크하지 마라. 이유: 운영에서 사람이 확인한 뒤에 바꾼다 — 예정된 확인을 한 것처럼 쓰지 않는다.
- 확인하지 않은 요금 · 명령 형식 · 서비스 동작을 사실로 적지 마라. 이유: 사람이 그대로 따라 한다 — 모르면 「첫 배포 때 확인」 에 둔다.
- 실제 도메인 · 계정 ID · 버킷 · 이메일을 적지 마라. 이유: 공개 저장소다.
- `docs/PORTFOLIO.md` 를 고치지 마라. 이유: `AGENTS.md` — 사용자 확인을 받아 Claude 가 남긴다.
- `.env` 파일을 만들거나 읽지 마라. 이유: `AGENTS.md`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
