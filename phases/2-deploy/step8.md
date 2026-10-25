# Step 8: log-masking

## 배경 — 이 step 이 고치는 것

`2-deploy` 를 마친 뒤 브랜치 전체 리뷰(`/review 2-deploy`)에서 나온 수정이다. 사용자 결정(2026-10-26)이다.

이 저장소는 **공개 저장소**라 GitHub Actions 로그도 누구나 본다. 지금 배포 워크플로(`.github/workflows/deploy.yml`)는 식별자 여섯 개를 모두 저장소 변수(`vars.*`)로 받아 job `env` 에 넣는다. GitHub 은 각 단계 로그 머리에 `env` 값을 그대로 보여 주므로 **AWS 계정 ID(역할 ARN 안) · S3 버킷 이름 · EC2 인스턴스 ID 가 공개 로그에 찍힌다.** `docker push` 출력의 레지스트리 주소(`<계정 ID>.dkr.ecr.…`)에도 계정 ID 가 나온다. 이 프로젝트는 실제 계정 · 버킷 · 인스턴스 값을 공개된 곳에 남기지 않는다.

고치는 방향 — 세 식별자를 **저장소 Secrets** 로 받는다(GitHub 이 로그에서 값을 `***` 로 가린다). 계정 ID 는 ARN 의 일부로만 들어오므로 그것만으로는 레지스트리 주소의 계정 ID 가 가려지지 않는다 — **계정 ID 자체도 로그 가림 대상으로 등록한다.** 공개해도 되는 값(`DEPLOY_ENABLED` · `AWS_REGION` · `API_ORIGIN` — 리전과 공개 API 주소)은 변수로 둔다.

배포 흐름 · 권한 · 검사 순서는 바꾸지 않는다. 바뀌는 것은 **값을 어디서 읽느냐와 로그에서 가리느냐**뿐이다.

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 명령어 · 작업 방식
- `docs/ADR.md` — ADR-072(트레이드오프의 "공개 저장소라 Actions 로그와 SSM 출력이 공개된다")
- `docs/04-jobs-and-cost.md` §3 — 운영 구성 표의 「백엔드 배포」 행(저장소 변수 이름 목록)
- `.github/workflows/deploy.yml`
- `backend/src/test/java/com/mogum/deploy/WorkflowFilesTest.java`
- `deploy/README.md` — 「처음 구축 순서」 5번 · 「첫 배포 때 확인」 · 「GitHub Actions 배포」 절(변수 표)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

### 1. `.github/workflows/deploy.yml`

- **Secrets 로 옮기는 세 값**(이름은 그대로): `AWS_DEPLOY_ROLE_ARN` · `DEPLOY_BUCKET` · `DEPLOY_INSTANCE_ID` → `${{ secrets.… }}` 로 읽는다. `role-to-assume` 도 같은 Secret 을 쓴다.
- **변수로 두는 세 값**: `DEPLOY_ENABLED`(job `if` 조건) · `AWS_REGION` · `API_ORIGIN` → 지금처럼 `${{ vars.… }}`.
- **계정 ID 가림** — 역할 ARN 에서 계정 ID 를 뽑는 지금의 검사 단계에서, **무엇이든 출력하기 전에** 그 계정 ID 를 GitHub 로그 가림 대상으로 등록한다(워크플로 명령 `::add-mask::`). 이후 모든 단계의 로그(`docker push` 의 레지스트리 주소 · AWS CLI 오류 출력 등)에서 계정 ID 가 가려져야 한다. `aws-actions/configure-aws-credentials` 의 계정 ID 가림 입력(`mask-aws-account-id`)도 켠다.
- 빈 값 검사는 그대로 하되, 문구가 변수/Secret 을 구분하게 한다(예: `Missing repository secret: DEPLOY_BUCKET`). 지금처럼 **이름만** 출력하고 값은 출력하지 않는다.
- 그 밖의 동작 — 트리거 · 경로 필터 · 권한 · 동시 실행 · 빌드/검사/push 한 번 · 같은 SHA 재사용 · 수동 재배포 · SSM · 공개 헬스 — 은 바꾸지 않는다.

### 2. 불변식 테스트 — `WorkflowFilesTest`

지금 테스트는 워크플로에 `secrets.` 가 **전혀 없어야** 한다고 검사한다. 이를 다음으로 바꾼다:

- 워크플로가 참조하는 Secret 은 **정확히** `AWS_DEPLOY_ROLE_ARN` · `DEPLOY_BUCKET` · `DEPLOY_INSTANCE_ID` 세 개뿐이다(그 밖의 `secrets.*` 참조가 있으면 실패).
- `vars.` 로 읽는 것은 정확히 `DEPLOY_ENABLED` · `AWS_REGION` · `API_ORIGIN` 세 개다. 세 식별자를 `vars.` 로 읽으면 실패.
- AWS 액세스 키 입력(`aws-access-key-id` · `aws-secret-access-key`)은 여전히 없다.
- `role-to-assume` 은 `${{ secrets.AWS_DEPLOY_ROLE_ARN }}`, `aws-region` 은 `${{ vars.AWS_REGION }}`, 계정 ID 가림 입력이 켜져 있다.
- 계정 ID 를 `::add-mask::` 로 등록하는 줄이, 그 계정 ID 로 레지스트리 주소를 만들거나 AWS 를 부르는 어떤 단계보다 **앞**에 있다.
- 기존 검사(`run:` 본문에 `${{ inputs.` 없음 · `${{ secrets.` 도 `run:` 본문에 직접 넣지 않음 · `environment` 없음 · 공식 액션만 등)는 유지한다.

### 3. 문서

- `deploy/README.md`
  - 「GitHub Actions 배포」 절 — 변수 표를 **Variables 3개**(`DEPLOY_ENABLED` · `AWS_REGION` · `API_ORIGIN`)와 **Secrets 3개**(`AWS_DEPLOY_ROLE_ARN` · `DEPLOY_BUCKET` · `DEPLOY_INSTANCE_ID`)로 나눈다. 이유(공개 로그에서 가린다 · 계정 ID 도 가림 등록)를 한두 문장으로 적는다. "식별자이며 Secrets에 넣지 않는다" 같은 지금 문구는 바꾼다. AWS 장기 키 · Google 비밀은 여전히 넣지 않는다.
  - 「처음 구축 순서」 5번 — 등록 위치를 Variables 3개 + Secrets 3개로 고친다. **이미 Variables 로 넣은 세 값을 Secrets 로 옮기고 Variables 에서는 지운다**는 안내를 넣는다(사람이 한다). 5번의 기존 「수행 기록」 문단은 **고치지 않는다** — 사람이 옮긴 뒤 따로 기록한다.
  - 「첫 배포 때 확인」 — 첫 실행 로그에서 계정 ID · 버킷 · 인스턴스 ID 가 `***` 로 가려졌는지 확인하는 항목을 더한다(공개 로그 비밀 부재 확인 항목에 덧붙여도 된다).
- `docs/04-jobs-and-cost.md` §3 「백엔드 배포」 행 — 저장소 변수 목록을 Variables 3개 · Secrets 3개로 고친다.
- `docs/ADR.md` ADR-072 트레이드오프 — "공개 저장소라 Actions 로그와 SSM 출력이 공개된다" 뒤에, 계정 · 버킷 · 인스턴스 식별자는 Secrets 로 받고 계정 ID 를 로그 가림 대상으로 등록한다는 문장을 더한다(2026-10-26 · 브랜치 리뷰).

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # WorkflowFilesTest 포함 통과
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - ADR-072 의 배포 흐름이 그대로인가(값을 읽는 곳과 로그 가림만 바뀌었는가)?
   - 워크플로가 AWS 를 부르기 전에 빈 값 · 형식 검사를 하고, 그보다 먼저 계정 ID 가림을 등록하는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD — 테스트를 먼저 바꾼다)을 위반하지 않았는가?
3. 결과에 따라 `phases/2-deploy/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — Secrets/Variables 구분 · 계정 ID 가림 방식 · 바꾼 불변식 · 고친 문서 절을 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 실제 계정 ID · 버킷 · 인스턴스 ID · 도메인을 파일에 적지 마라. 이유: 공개 저장소다 — 이 step 이 막으려는 바로 그 노출이다.
- Secret 값이나 가림 대상 값을 `echo` · `printf` 로 그대로 출력하지 마라(`::add-mask::` 등록 줄 제외). `set -x` 를 쓰지 마라. 이유: 가림 등록 전에 찍힌 값은 가려지지 않는다.
- `${{ secrets.… }}` 를 `run:` 스크립트 본문에 직접 끼워 넣지 마라 — `env:` 로 넘긴다. 이유: 스크립트 주입 방지 규칙(step 5)과 같다.
- AWS 액세스 키 · 다른 Secret 을 새로 쓰지 마라. 이유: 자격은 OIDC 임시 자격만 쓴다(ADR-072).
- 배포 흐름 · 권한 · 정책 파일(`deploy/aws/`) · `deploy/deploy.sh` 를 바꾸지 마라. 이유: 이 step 은 값을 읽는 곳과 로그 가림만 고친다.
- `.github/workflows/sonarcloud-analyze.yml` 을 고치지 마라. 이유: 조직(f-lab)이 넣은 워크플로다.
- `deploy/README.md` 의 「수행 기록」 문단들을 고치지 마라. 이유: 사람이 실제로 한 일의 기록이다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
