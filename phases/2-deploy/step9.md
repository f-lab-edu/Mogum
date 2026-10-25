# Step 9: action-pinning

## 배경 — 이 step 이 고치는 것

`2-deploy` PR(#11)의 SonarCloud 품질 게이트가 실패했다. 새 코드 보안 등급이 C 다(게이트는 A). C 를 만든 지적은 `githubactions:S7637`(MAJOR) 하나다 — `.github/workflows/deploy.yml` 이 `aws-actions/configure-aws-credentials@v5` 처럼 **태그로 액션을 참조한다.** 사용자 결정(2026-10-26)이다.

태그는 액션 저장소 주인이 언제든 다른 커밋으로 옮길 수 있다. 태그가 악성 커밋으로 바뀌면 다음 실행부터 그 코드가 돈다. 이 워크플로는 OIDC 로 **AWS 배포 역할 자격을 받는 곳**이고, `configure-aws-credentials` 가 내보낸 자격은 그 뒤 단계의 환경에 남는다 — 자격 단계 **뒤에** 도는 `actions/checkout` · `actions/setup-java` 도 같은 위험이다(Sonar 는 GitHub 공식 액션을 잡지 않았지만 셋 다 고정한다). 바뀌지 않는 참조는 40자 커밋 SHA 뿐이다.

**지금 쓰는 버전 그대로** SHA 로 고정한다. 아래 값은 Claude 가 2026-10-26 GitHub API(`gh api repos/{저장소}/commits/v5` · 태그 목록)로 확인했다 — 각 `v5` 태그가 지금 가리키는 커밋이고, 그 커밋의 정확한 릴리스 태그다. 버전을 올리지 않으므로 실행되는 코드는 바뀌지 않는다.

| 액션 | 릴리스 | 커밋 SHA |
|---|---|---|
| `aws-actions/configure-aws-credentials` | `v5.1.1` | `61815dcd50bd041e203e49132bacad1fd04d2708` |
| `actions/checkout` | `v5.1.0` | `fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09` |
| `actions/setup-java` | `v5.7.0` | `b6effb05e454b25005698d916606bdc6ffcbf961` |

같은 게이트의 나머지 보안 지적(테스트 코드의 `java:S4036` 6개)은 Claude 가 `sonar-project.properties` 에서 처리했다 — 이 step 범위가 아니다.

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 명령어 · 작업 방식
- `.github/workflows/deploy.yml`
- `backend/src/test/java/com/mogum/deploy/WorkflowFilesTest.java` — 「공개_변수와_식별자_Secrets를_구분하고_OIDC와_공식_액션만_쓴다」의 액션 집합 검사
- `deploy/README.md` — 「첫 배포 때 확인」 2번 행 · 「GitHub Actions 배포」 절(`configure-aws-credentials@v5` 링크가 있는 문단)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

### 1. 불변식 테스트 — `WorkflowFilesTest` (먼저)

액션 집합 검사(`actions/checkout@v5` 등 세 개의 집합)를 다음으로 바꾼다:

- 워크플로의 모든 `uses:` 는 `소유자/저장소@<40자 소문자 16진수>` 형식이다. 태그 · 브랜치 · 짧은 SHA 면 실패.
- `uses:` 로 쓰는 액션은 정확히 위 표의 세 개이고, 각 SHA 가 표의 값과 같다.
- 워크플로 원문에서 각 `uses:` 줄 끝에 그 릴리스를 적은 주석(`# v5.1.1` 형식)이 있다. YAML 파서는 주석을 버리므로 원문 줄로 검사한다.
- 같은 테스트의 다른 검사(Variables · Secrets 집합 · 액세스 키 없음 · `run:` 본문의 `${{` · `set -x` 없음 · Java 21 · `role-to-assume` · `mask-aws-account-id` · 첫 단계 `validate`)는 유지한다.

### 2. `.github/workflows/deploy.yml`

- 세 `uses:` 를 표의 SHA 로 바꾸고 줄 끝에 릴리스 주석을 단다(예: `uses: actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09 # v5.1.0`).
- `with:` 입력 · 단계 순서 · 그 밖의 모든 동작은 바꾸지 않는다.

### 3. 문서 — `deploy/README.md`

- 「GitHub Actions 배포」 절 — 액션을 커밋 SHA 로 고정한다는 것과 이유(태그는 옮겨질 수 있다 · 이 워크플로는 AWS 자격을 받는다)를 한두 문장으로 적는다. 올리는 방법도 한 문장 — 새 릴리스 태그의 커밋 SHA 를 확인해 SHA · 릴리스 주석 · `WorkflowFilesTest` 기대값을 함께 바꾼다. 지금 `configure-aws-credentials@v5` 로 걸린 링크는 고정한 릴리스(`v5.1.1`)를 가리키게 바꾼다.
- 「첫 배포 때 확인」 2번 행 — 액션 표기를 고정한 릴리스와 SHA 기준으로 고친다(실제 실행 로그로 확인한다는 내용은 그대로).

## Acceptance Criteria

```bash
./backend/gradlew -p backend test   # WorkflowFilesTest 포함 통과
```

`actionlint` 가 설치돼 있으면 `actionlint .github/workflows/deploy.yml` 도 통과해야 한다(없으면 건너뛰고 summary 에 적는다).

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 세 액션의 SHA 가 표와 글자 하나까지 같은가? 버전을 올리지 않았는가?
   - ADR-072 의 배포 흐름 · 권한 · 로그 가림(step 8)이 그대로인가 — 바뀐 것은 `uses:` 참조뿐인가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD — 테스트를 먼저 바꾼다)을 위반하지 않았는가?
3. 결과에 따라 `phases/2-deploy/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 고정한 세 액션 · 바꾼 불변식 · 고친 문서 절 · actionlint 실행 여부를 넣는다
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 액션 버전을 올리지 마라(v6 · v7 등). 이유: 이 step 은 지금 실행되는 코드를 고정만 한다 — 업그레이드는 입력 · 동작 확인이 따로 필요하다.
- 표에 없는 SHA 를 쓰거나 SHA 를 추측하지 마라. 이유: 확인한 값만 쓴다 — 틀린 SHA 는 첫 배포에서야 드러난다.
- 배포 흐름 · 권한 · 단계 순서 · `with:` 입력 · 정책 파일(`deploy/aws/`) · `deploy/deploy.sh` 를 바꾸지 마라. 이유: 이 step 은 참조 방식만 고친다.
- `.github/workflows/sonarcloud-analyze.yml` · `sonar-project.properties` 를 고치지 마라. 이유: 앞의 것은 조직(f-lab)이 넣은 워크플로이고, 뒤의 것은 Claude 가 이미 고쳤다.
- `deploy/README.md` 의 「수행 기록」 문단들을 고치지 마라. 이유: 사람이 실제로 한 일의 기록이다.
- 실제 계정 ID · 버킷 · 인스턴스 ID · 도메인을 파일에 적지 마라. 이유: 공개 저장소다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
