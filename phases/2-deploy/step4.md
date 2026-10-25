# Step 4: aws-policies

## 배경 — 이 phase(`2-deploy`)가 하는 일

지금의 앱을 운영에 올린다(`docs/ADR.md` ADR-072). AWS 쪽 흐름은 이렇다 —

- GitHub Actions(이 저장소 `f-lab-edu/Mogum` 의 `main`)가 **OIDC** 로 배포 역할을 빌린다(GitHub 에 AWS 키를 저장하지 않는다).
- 그 역할로 앱 이미지를 ECR 저장소 **`mogum-app`** 에 올리고(태그 덮어쓰기 금지 · 전체 SHA 태그), 릴리스 묶음을 S3 버킷의 **`releases/{SHA}.tar.gz`** 에 올리고, SSM Run Command(`AWS-RunShellScript`)로 **운영 인스턴스 하나**에 배포 명령을 보낸다.
- 운영 인스턴스는 **인스턴스 역할**로 ECR 에서 이미지를 받고, `releases/` 묶음을 읽고, 매일 백업을 같은 버킷의 **`backups/`** 에 올리며, 복원 연습 때 `backups/` 를 읽는다. SSM 에이전트가 명령을 받으려면 AWS 관리형 정책 `AmazonSSMManagedInstanceCore` 가 붙어야 한다.
- 버킷은 버전 관리를 켠다. 백업 보관은 사용자가 정한 **총 7일 상한**이다 — 현재 버전 6일 + 이전 버전 1일.

이 step 은 위 흐름에 필요한 **AWS 설정 파일(견본)** 과 그 최소 권한을 지키는 테스트를 만든다. 실제 AWS 에 적용하는 것은 사람이 절차서(`deploy/README.md`)를 따라 한다.

step 2 · 3 에서 `deploy/compose.yaml` · `deploy/deploy.sh`(ECR 로그인 · 지문으로 받기 · 헬스 · 되돌리기) · `deploy/backup.sh` · `deploy/restore.sh` · `deploy/systemd/` · `deploy/mogum.env.example`(`MOGUM_BUCKET` 포함) · `deploy/README.md` 와 불변식 테스트(`DeployFilesTest` · `BackupFilesTest`)를 만들었다.

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 작업 방식
- `docs/ADR.md` — ADR-072(OIDC · ECR · SSM · 백업 7일 · 트레이드오프)
- `docs/04-jobs-and-cost.md` §3(운영 구성과 배포 흐름 표)
- step 2 · 3 산출물: `deploy/deploy.sh` · `deploy/backup.sh` · `deploy/restore.sh` — 이 스크립트들이 부르는 AWS API 가 곧 인스턴스 역할에 필요한 권한이다
- `deploy/README.md` · `backend/src/test/java/com/mogum/deploy/` 의 기존 테스트

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

모든 파일은 `deploy/aws/` 아래 JSON 이다. 계정 ID · 버킷 이름 · 인스턴스 ID 는 **자리표시**(예: `ACCOUNT_ID` · `BUCKET_NAME` · `INSTANCE_ID`)로 쓰고, 리전은 `ap-northeast-2` 로 고정한다. 자리표시를 실제 값으로 바꾸는 방법은 절차서에 적는다.

### 1. GitHub OIDC 신뢰 정책 — 배포 역할

- 신뢰 주체: 계정의 GitHub OIDC 공급자(`token.actions.githubusercontent.com`).
- 조건(정확히 일치): `aud` = `sts.amazonaws.com`, `sub` = **`repo:f-lab-edu/Mogum:ref:refs/heads/main`**. `main` 의 push 와 `main` 에서 돈 수동 실행(`workflow_dispatch`)이 이 `sub` 를 쓴다. 워크플로가 GitHub `environment` 를 쓰면 `sub` 형식이 `repo:f-lab-edu/Mogum:environment:{이름}` 으로 바뀌므로 워크플로는 `environment` 를 쓰지 않는다(step 5 가 지킨다).
- 와일드카드(`*`) · `StringLike` 로 넓히지 않는다 — 다른 브랜치 · 포크 · PR 이 배포 역할을 빌리면 안 된다.

### 2. 배포 역할 권한 정책

필요한 것만 — 이 저장소 · 이 접두사 · 이 인스턴스로 자원을 좁힌다.
- ECR: 인증 토큰 받기(자원 `*` 만 가능한 API), `mogum-app` 저장소에 이미지 올리기 · 있는지/지문 조회하기.
- S3: `releases/*` 에 쓰기 · 읽기(수동 재배포가 묶음이 있는지 확인한다).
- SSM: `AWS-RunShellScript` 문서로 **운영 인스턴스 하나**에만 명령 보내기, 보낸 명령의 결과 조회하기.
- **없어야 하는 것**: 이미지 · 객체 · 버전 삭제, 저장소 정책 · 수명주기 · 버킷 설정 변경, `backups/` 접근, 다른 인스턴스 · 다른 SSM 문서.

### 3. 인스턴스 역할 — 신뢰 정책과 권한 정책

- 신뢰: EC2 서비스.
- 권한: ECR 인증 토큰 받기 · `mogum-app` 이미지 받기, S3 `releases/*` 읽기, `backups/*` 쓰기 · 읽기, `backups/` 접두사 목록 보기(복원 연습의 `latest`). `AmazonSSMManagedInstanceCore` 는 관리형 정책을 붙이는 것으로 하고 절차서에 적는다(파일로 복제하지 않는다).
- **없어야 하는 것**: 이미지 올리기 · 삭제, 객체 · 버전 삭제, `releases/` 쓰기, 버전 관리 · 수명주기 변경.

### 4. ECR 수명주기 — 최근 이미지 30개만 남긴다

롤백할 수 있는 범위다. 태그 덮어쓰기 금지(`IMMUTABLE`)는 저장소를 만들 때 정하는 설정이라 절차서의 명령에 적는다.

### 5. S3 수명주기 — 버킷 하나에 규칙 둘

- `backups/` — 현재 버전 **6일** 뒤 만료 · 이전 버전 **1일** 뒤 영구 삭제 → **합계 7일을 넘지 않는다**(사용자 결정 — 이 숫자를 각각 7일로 맞추지 마라). 완료되지 않은 multipart 업로드 정리 · 만료된 삭제 표시 정리를 넣는다.
- `releases/` — 현재 버전 30일 · 이전 버전 1일(롤백 범위는 ECR 30개와 묶음 30일 중 먼저 끝나는 쪽).
- S3 수명주기의 삭제는 비동기라 정확한 시각에 지워지지 않는다 — 절차서에 적는다.

### 6. 불변식 테스트 — `backend/src/test/java/com/mogum/deploy/AwsFilesTest.java`

모든 JSON 이 파싱된다. 그리고 최소한:
- OIDC 신뢰: `aud` · `sub` 가 위 값과 **정확히 같고** 조건 연산자가 정확히 일치(`StringEquals`)다.
- 두 권한 정책 모두: `Effect` 가 `Allow` 인 문장에 삭제 계열 동작(`*Delete*` · `ecr:BatchDeleteImage` · `s3:DeleteObject*` …)과 설정 변경 계열(`*Put*Policy*` · `*Lifecycle*` · `s3:PutBucketVersioning` …)이 없다. 동작 와일드카드(`"ecr:*"` · `"s3:*"` · `"*"`)가 없다. 자원 `*` 는 그것만 가능한 API(ECR 인증 토큰 · SSM 명령 결과 조회 등)에만 있다.
- 배포 역할: S3 자원이 `releases/` 접두사뿐 · SSM 명령 보내기의 자원이 그 문서와 인스턴스 하나뿐.
- 인스턴스 역할: 이미지 올리기 동작이 없음 · `releases/` 에 쓰기가 없음.
- S3 수명주기: `backups/` 의 현재 + 이전 버전 일수 합 ≤ 7.
- ECR 수명주기: 남기는 개수 30.

### 7. 문서

- `deploy/README.md` — 「AWS 설정」 절: 파일마다 무엇에 쓰는지 · 자리표시 바꾸는 방법 · 만들 자원 목록과 이름(OIDC 공급자 · 배포 역할 · 인스턴스 역할(+ `AmazonSSMManagedInstanceCore`) · ECR `mogum-app`(태그 덮어쓰기 금지) · S3 버킷(공개 차단 · 기본 암호화 · 버전 관리 · 수명주기) · 운영 인스턴스) · 롤백 범위 · 수명주기 삭제의 지연. 콘솔 · CLI 중 어느 쪽으로 해도 되지만, 적는 명령은 AWS CLI v2 로 적고 **확인하지 못한 명령 형식은 「첫 배포 때 확인」 목록에 따로 적는다.** (처음 구축의 전체 순서는 step 7 이 정리한다.)

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # AwsFilesTest 포함 통과
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 두 역할이 ADR-072 의 흐름에 필요한 것만 갖는가? `deploy.sh` · `backup.sh` · `restore.sh` 가 부르는 AWS API 가 인스턴스 역할에 모두 있는가?
   - `AGENTS.md` 작업 방식을 위반하지 않았는가?
3. 결과에 따라 `phases/2-deploy/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 파일 목록 · 자리표시 이름 · 두 역할의 권한 요약 · 수명주기 값 · 테스트 수를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 실제 AWS 를 부르거나(`aws` CLI · SDK) 자원을 만들지 마라. 이유: 계정 · 권한이 없고, 적용은 사람이 절차서로 한다.
- 장기 자격(IAM 사용자 · 액세스 키)을 만들거나 절차서에 적지 마라. 이유: OIDC 와 인스턴스 역할로 키를 없애는 것이 이 결정의 이유다(ADR-072).
- `sub` 조건을 와일드카드로 넓히지 마라. 이유: 다른 브랜치 · PR 워크플로가 운영 배포 권한을 얻는다.
- 서버 · CI 역할에 삭제 · 설정 변경 권한을 주지 마라. 이유: 털린 서버나 잘못된 워크플로가 백업 · 이미지를 지우거나 보관 규칙을 바꾸지 못하게 한다.
- `backups/` 보관 일수를 현재 · 이전 각각 7일로 두지 마라. 이유: 사용자가 정한 것은 **합계** 7일이다.
- Terraform · CloudFormation · CDK 를 들이지 마라. 이유: 자원이 몇 개뿐이고 기술 스택에 없다(ADR-072 는 손으로 만든다).
- 실제 계정 ID · 버킷 이름 · 인스턴스 ID 를 적지 마라. 이유: 공개 저장소다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
