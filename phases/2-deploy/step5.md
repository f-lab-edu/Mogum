# Step 5: deploy-workflow

## 배경 — 이 phase(`2-deploy`)가 하는 일

지금의 앱을 운영에 올린다(`docs/ADR.md` ADR-072). 이 step 은 **백엔드 자동 배포 워크플로**를 만든다 — `main` 병합 때 GitHub Actions 가

1. 서버와 같은 arm64 러너에서 테스트하고 앱 이미지를 **한 번** 빌드한다.
2. **그 이미지로** 이미지 검사(step 1 의 `imageTest`)를 통과시킨다.
3. 같은 이미지를 ECR `mogum-app:{전체 SHA}` 로 올리고 지문을 얻는다.
4. `deploy/` 와 지문 파일 `app-image.ref` 를 묶어 S3 `releases/{SHA}.tar.gz` 로 올린다.
5. SSM Run Command 로 운영 인스턴스에서 묶음을 `/opt/mogum/releases/{SHA}/` 에 풀고 `deploy/deploy.sh {SHA}` 를 실행한다.
6. 명령 결과와 공개 헬스(`https://api.{도메인}/actuator/health`)를 확인한다.

화면은 Vercel 의 Git 연동이 따로 배포한다 — 이 워크플로는 화면을 다루지 않는다.

이전 step 산출물 —
- step 0: `GET /actuator/health`(인증 없이 · 상태만).
- step 1: `./backend/gradlew -p backend imageTest -PappImage=<이미지>` — 주어진 이미지로 기동 · 헬스 · 비루트 · SIGTERM 정상 종료를 검사한다.
- step 2: `deploy/compose.yaml` · `deploy/Caddyfile` · `deploy/deploy.sh {SHA}`(묶음이 `/opt/mogum/releases/{SHA}/` 에 풀려 있다고 가정하고, `app-image.ref` 한 줄 `<계정 ID>.dkr.ecr.ap-northeast-2.amazonaws.com/mogum-app@sha256:<64자 hex>` 을 읽어 지문으로 받고 · 헬스 확인 · 실패하면 직전 릴리스로 되돌리고 실패 코드) · `deploy/mogum.env.example` · `deploy/README.md`.
- step 3: `deploy/backup.sh` · `deploy/restore.sh` · `deploy/systemd/` (`deploy.sh` 가 성공 뒤 타이머를 설치한다).
- step 4: `deploy/aws/` — OIDC 신뢰(`sub` = `repo:f-lab-edu/Mogum:ref:refs/heads/main` 정확히 일치) · 배포 역할 권한(ECR `mogum-app` 올리기 · S3 `releases/*` 쓰기/읽기 · `AWS-RunShellScript` 로 인스턴스 하나에 명령 · 결과 조회) · 인스턴스 역할 · 수명주기.

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 명령어 · 작업 방식
- `docs/ADR.md` — ADR-061 · ADR-072
- `docs/04-jobs-and-cost.md` §3(운영 구성과 배포 흐름 표)
- `.github/workflows/sonarcloud-analyze.yml` — 이미 있는 워크플로(건드리지 않는다)
- `deploy/` 전체 · `deploy/aws/` 전체 · `deploy/README.md`
- `backend/build.gradle` — `test` · `imageTest` 작업, `test` 의 입력 선언
- `backend/src/test/java/com/mogum/deploy/` 의 기존 테스트

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

### 1. `.github/workflows/deploy.yml`

**트리거 · 실행 조건**
- `main` 에 push 될 때, 단 바뀐 경로가 `backend/**` · `deploy/**` · 이 워크플로 파일 중 하나일 때만. 문서 · 화면만 바뀐 병합은 서버를 재시작하지 않는다.
- 수동 실행(`workflow_dispatch`) — 입력 `sha`(선택). 비어 있으면 push 와 같이 지금 커밋을 빌드 · 배포한다. 값이 있으면 **빌드 없이** 그 SHA 의 기존 묶음 · 이미지를 다시 배포한다(수동 롤백). 수동 실행은 `main` 에서만 허용한다(다른 브랜치에서 돌면 OIDC 가 거절하지만, 워크플로가 먼저 막는다).
- 저장소 변수 `DEPLOY_ENABLED` 가 `true` 일 때만 실행한다 — AWS 를 만들기 전의 병합이 실패로 쌓이지 않게.
- 동시 실행 그룹 하나 — 배포가 겹치지 않고, **진행 중인 배포를 취소하지 않는다**(중간에 끊긴 배포가 서버를 반쯤 바꾼 채 남지 않게).

**권한 · 자격**
- 워크플로 권한은 `contents: read` · `id-token: write` 만.
- AWS 자격은 OIDC 로 받는다(배포 역할 ARN 은 저장소 변수). 액세스 키 비밀을 쓰지 않는다.
- GitHub `environment` 를 쓰지 않는다. 이유: OIDC `sub` 가 `repo:f-lab-edu/Mogum:environment:…` 로 바뀌어 신뢰 정책(`ref:refs/heads/main`)과 맞지 않는다.

**저장소 변수(이름 고정 — 값은 사람이 넣는다)**: `DEPLOY_ENABLED` · `AWS_REGION`(`ap-northeast-2`) · `AWS_DEPLOY_ROLE_ARN` · `DEPLOY_BUCKET` · `DEPLOY_INSTANCE_ID` · `API_ORIGIN`(`https://api.{도메인}`). 비밀이 아니라 식별자라 `vars` 로 둔다. 필요한 변수가 비어 있으면 빌드 · AWS 호출 전에 **어떤 이름이 비었는지만** 말하고 실패한다.

**빌드 경로(push · `sha` 없는 수동 실행)**
- 러너: `ubuntu-24.04-arm`(서버와 같은 arm64. 공개 저장소에 무료로 제공된다고 알고 있다 — 확인하지 못했다면 절차서의 「첫 배포 때 확인」 목록에 적는다).
- Java 21 · `./backend/gradlew -p backend build`(테스트 포함 — Testcontainers 가 러너의 Docker 를 쓴다).
- `docker build` 로 `backend/` 이미지를 **한 번** 빌드하고, 그 이미지로 `./backend/gradlew -p backend imageTest -PappImage=<그 이미지>`.
- **검사한 그 이미지를** ECR `mogum-app:{전체 SHA}` 로 태그해 올리고 지문을 얻는다. 다시 빌드하지 않는다.
- 같은 SHA 를 다시 돌려도 실패하지 않아야 한다 — ECR 은 태그 덮어쓰기를 막는다. 이미 `mogum-app:{SHA}` 가 있으면 그 이미지는 앞선 실행에서 검사된 것이므로 빌드 · 검사 · 올리기를 건너뛰고 그 지문을 쓴다(방법은 재량).
- `deploy/` 와 `app-image.ref`(지문 한 줄)를 묶어 `s3://{DEPLOY_BUCKET}/releases/{SHA}.tar.gz` 로 올린다.

**배포 경로(공통)**
- 수동 재배포(`sha` 있음)는 그 SHA 의 묶음과 이미지가 둘 다 있는지 먼저 확인하고, 없으면 무엇이 없는지 말하고 실패한다(롤백 범위 — 묶음 30일 · 이미지 최근 30개).
- SSM `AWS-RunShellScript` 로 `DEPLOY_INSTANCE_ID` 에 명령을 보낸다: 묶음을 받아 `/opt/mogum/releases/{SHA}/` 에 풀고 `deploy/deploy.sh {SHA}` 를 root 로 실행. 명령이 끝날 때까지 상한(예: 20분) 안에서 기다리고, 성공이 아니면 워크플로를 실패시킨다. 명령의 표준 출력 · 오류를 워크플로 로그에 보여 준다(`deploy.sh` 는 비밀을 출력하지 않는다 — step 2).
- 마지막으로 `{API_ORIGIN}/actuator/health` 가 `200` 인지 짧은 상한 안에서 확인한다(외부에서 본 HTTPS · DNS 확인).

**보안 규칙**
- `${{ inputs.sha }}` · `${{ github.* }}` 같은 식을 `run:` 스크립트 본문에 **직접 끼워 넣지 않는다** — `env:` 로 넘기고 스크립트에서 변수로 쓴다(스크립트 주입 방지). `sha` 입력은 40자 소문자 hex 인지 검사한 뒤에만 쓴다.
- 비밀 · 토큰을 출력하지 않는다. `set -x` 를 쓰지 않는다.
- 쓰는 액션은 공식 액션(`actions/*` · `aws-actions/*` · `docker/*`)만, 존재를 확인한 메이저 태그로 쓴다. 확인하지 못한 태그 · 동작은 절차서의 「첫 배포 때 확인」 목록에 적는다.
- 러너에서 쓰는 명령은 GitHub 호스티드 우분투 러너에 기본으로 있는 것만 쓴다(`rg` 처럼 개발 기기에만 있을 수 있는 것에 기대지 않는다).

### 2. 불변식 테스트 — `backend/src/test/java/com/mogum/deploy/WorkflowFilesTest.java`

- **`.github/workflows/` 를 Gradle `test` 작업의 입력으로 선언한다**(테스트 작업 디렉터리는 `backend/` — `../.github/workflows/deploy.yml`). 안 하면 워크플로만 바꿨을 때 `test` 가 UP-TO-DATE 로 건너뛴다.
- YAML 은 파서로 읽는다. 확인할 것(최소): push 트리거가 `main` 뿐이고 경로 필터가 위 셋 · `workflow_dispatch` 의 `sha` 입력 · `DEPLOY_ENABLED` 조건 · 권한이 정확히 `contents: read` · `id-token: write` · 동시 실행 그룹이 있고 취소하지 않음 · 빌드 작업의 러너가 `ubuntu-24.04-arm` · 어떤 작업에도 `environment` 가 없음 · `imageTest` 를 `-PappImage` 와 함께 부름 · AWS 액세스 키 비밀(`aws-access-key-id` 등)을 쓰지 않음 · `run:` 본문에 `${{ inputs.` 가 없음 · 위 저장소 변수 이름을 씀 · 공식 액션만 씀.

### 3. 문서

- `deploy/README.md` — 「GitHub Actions 배포」 절: 트리거 · 경로 필터 · 저장소 변수 표(이름 · 값 예 · 어디서 얻나) · 흐름(위 1~6) · 수동 롤백 방법(`workflow_dispatch` 에 이전 SHA)과 롤백 범위 · Flyway 는 되돌리지 않음 · `DEPLOY_ENABLED` 로 켜고 끄기 · 실패했을 때 볼 곳(워크플로 로그의 SSM 출력).
- `docs/04-jobs-and-cost.md` §3 운영 구성 표의 「백엔드 배포」 행 — 워크플로 파일 링크와 저장소 변수 이름을 더한다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # WorkflowFilesTest 포함 통과
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - ADR-072 의 흐름(같은 arm64 러너에서 한 번 빌드 · 그 이미지로 검사 · ECR · S3 묶음 · SSM · 헬스 · 수동 롤백)과 맞는가?
   - 워크플로가 쓰는 AWS 동작이 step 4 의 배포 역할 권한 안에 있는가? 밖이면 이 step 에서 배포 역할 정책 파일과 `AwsFilesTest` 를 함께 고친다(최소 권한 · 삭제 금지 규칙은 유지).
3. 결과에 따라 `phases/2-deploy/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 워크플로 작업 구성 · 쓴 액션과 태그 · 저장소 변수 · 같은 SHA 재실행 처리 · 테스트 수를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `.github/workflows/sonarcloud-analyze.yml` 을 고치지 마라. 이유: 조직(f-lab)이 넣은 워크플로다.
- 서버에 SSH 로 접속하지 마라 · SSH 키 비밀을 쓰지 마라. 이유: SSM 으로 정했고 22번을 열지 않는다(ADR-072).
- 진행 중인 배포를 취소하게(`cancel-in-progress: true`) 두지 마라. 이유: 서버가 반쯤 바뀐 채 남는다.
- ECR 에 `latest` 같은 움직이는 태그를 올리지 마라 · 이미지를 두 번 빌드하지 마라. 이유: 검사한 이미지와 배포한 이미지가 같아야 한다.
- 화면(Vercel) 배포를 이 워크플로에 넣지 마라. 이유: Vercel Git 연동이 한다(ADR-072). 막히면 사람이 다시 정한다.
- 실제 계정 ID · 버킷 · 인스턴스 ID · 도메인을 파일에 적지 마라. 이유: 공개 저장소다 — 저장소 변수로 받는다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
