# Step 10: oidc-immutable-sub

## 배경 — 이 step 이 고치는 것

`2-deploy` 를 병합한 뒤 2026-10-26 첫 운영 배포에서 OIDC 단계가 `Not authorized to perform sts:AssumeRoleWithWebIdentity` 로 거절됐다. 원인은 **GitHub 토큰의 `sub` 형식**이다.

이 저장소는 GitHub OIDC 의 **불변 subject** 를 쓴다. Claude 가 2026-10-26 확인한 사실이다:

- `gh api repos/f-lab-edu/Mogum/actions/oidc/customization/sub` → `{"use_default":true,"use_immutable_subject":true,"sub_claim_prefix":"repo:f-lab-edu@54677968/Mogum@1378404194"}`
- `gh api repos/f-lab-edu/Mogum` → 저장소 ID `1378404194`, 조직(`f-lab-edu`) ID `54677968`
- CloudTrail 의 `AssumeRoleWithWebIdentity` 실패 이벤트 세 건의 `principalId` 가 모두 `…:sts.amazonaws.com:repo:f-lab-edu@54677968/Mogum@1378404194:ref:refs/heads/main` 이었다(`aud` 는 `sts.amazonaws.com` 으로 맞았다).

그래서 `main` 실행의 실제 `sub` 는 **`repo:f-lab-edu@54677968/Mogum@1378404194:ref:refs/heads/main`** 이다. 신뢰 견본 · 테스트 · 절차서는 이름만 쓰는 형식(`repo:f-lab-edu/Mogum:ref:refs/heads/main`)을 적었다. 운영자가 실제 IAM 역할의 신뢰 정책을 위 값으로 바꿔 배포가 성공했다(사용자 결정 2026-10-26 — GitHub 설정은 그대로 두고 신뢰 정책을 맞춘다). 이 step 은 **저장소 쪽 견본 · 불변식 · 절차서를 실제 값에 맞춘다.**

불변 형식은 이름을 쓰는 형식보다 안전하다 — 저장소가 지워지거나 이름이 바뀐 뒤 같은 이름의 다른 저장소가 생겨도 ID 가 달라 신뢰를 얻지 못한다. 조직 · 저장소 ID 는 GitHub API 로 누구나 볼 수 있는 공개 값이라 공개 저장소에 적어도 된다(AWS 계정 ID 와 다르다).

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 명령어 · 작업 방식
- `deploy/aws/github-oidc-trust.json`
- `backend/src/test/java/com/mogum/deploy/AwsFilesTest.java` — 「OIDC는_계정_공급자의_main_브랜치와_STS_audience만_정확히_허용한다」
- `deploy/README.md` — 「처음 구축 순서」 2번 표의 「배포 역할」 행 · 「AWS 설정」 절의 「배포 역할 `mogum-deploy`」 항목 · 「GitHub Actions 배포」 절의 `environment` 문장 · 「수동 롤백 · 실패 확인」 절

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

### 1. 불변식 테스트 — `AwsFilesTest` (먼저)

OIDC 신뢰 검사의 기대 `sub` 를 `repo:f-lab-edu@54677968/Mogum@1378404194:ref:refs/heads/main` 으로 바꾼다. 나머지(공급자 주체 · 동작 · `aud` · `StringEquals` · 문장 하나)는 그대로 정확히 일치해야 한다.

### 2. `deploy/aws/github-oidc-trust.json`

`token.actions.githubusercontent.com:sub` 값만 위 값으로 바꾼다. `ACCOUNT_ID` 자리표시 · `aud` · 연산자 · 구조는 그대로다.

### 3. 문서 — `deploy/README.md`

- 「처음 구축 순서」 2번 표 「배포 역할」 행과 「AWS 설정」 절 「배포 역할 `mogum-deploy`」 항목 — `sub` 값을 새 값으로 고친다. 이 저장소가 불변 subject 를 쓰므로 `sub` 에 조직 · 저장소 ID 가 들어간다는 것과, 형식을 확인하는 명령(`gh api repos/f-lab-edu/Mogum/actions/oidc/customization/sub` 의 `sub_claim_prefix` 뒤에 `:ref:refs/heads/main`)을 한두 문장으로 적는다.
- 「GitHub Actions 배포」 절의 `environment` 문장 — "기존 `ref:refs/heads/main` 신뢰 조건" 같은 표현이 새 형식과 어긋나지 않게 고친다(environment 를 쓰면 `sub` 끝이 `environment:…` 로 바뀐다는 내용은 유지).
- 「수동 롤백 · 실패 확인」 절 — OIDC 단계가 `Not authorized to perform sts:AssumeRoleWithWebIdentity` 로 실패할 때 확인할 것을 짧게 더한다: CloudTrail 이벤트 기록에서 이벤트 이름 `AssumeRoleWithWebIdentity` 의 실패 이벤트를 열어 `userIdentity.principalId` 끝의 실제 `sub` 를 신뢰 정책과 비교한다(배포 리전에서 조회). 실제 계정 ID 는 적지 않는다.
- 「처음 구축 순서」 8번의 「수행 기록」 문단과 「운영 확인 목록」 · 「첫 배포 때 확인」의 2026-10-26 기록은 **고치지 않는다** — Claude 가 이미 남긴 사람의 실행 기록이다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # AwsFilesTest 포함 통과
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 새 `sub` 가 배경의 값과 글자 하나까지 같은가? 조직 · 저장소 ID 를 바꾸거나 추측하지 않았는가?
   - 신뢰 정책의 다른 조건(`aud` · `StringEquals` · 공급자 주체 · 문장 하나)과 배포 역할 권한 · 워크플로가 그대로인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD — 테스트를 먼저 바꾼다)을 위반하지 않았는가?
3. 결과에 따라 `phases/2-deploy/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 바꾼 sub · 바꾼 불변식 · 고친 문서 절을 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 실제 AWS 계정 ID · 버킷 · 인스턴스 ID · 역할 ARN 을 파일에 적지 마라(`ACCOUNT_ID` 자리표시 유지). 이유: 공개 저장소다. 조직 · 저장소 ID 는 공개 값이라 예외다.
- `deploy/aws/` 의 다른 정책 파일 · `.github/workflows/deploy.yml` · `deploy/*.sh` 를 바꾸지 마라. 이유: 이 step 은 신뢰 `sub` 만 고친다.
- 신뢰 조건을 `StringLike` · 와일드카드로 넓히지 마라. 이유: `main` 한 브랜치만 정확히 허용하는 것이 ADR-072 의 결정이다.
- `deploy/README.md` 의 「수행 기록」 문단과 2026-10-26 확인 기록을 고치지 마라. 이유: 사람이 실제로 한 일의 기록이다.
- `phases/2-deploy/step4.md` · `step5.md` 등 이전 step 파일을 고치지 마라. 이유: 당시 지시의 기록이다.
- `.github/workflows/sonarcloud-analyze.yml` · `sonar-project.properties` 를 고치지 마라.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
