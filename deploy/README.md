# 운영 절차서

이 문서는 **처음 구축 순서 → 운영 확인 목록 → 첫 배포 때 확인 → 비용 → 구성별 상세** 순서로 읽는다. 저장소의 배포 파일과 자동 테스트는 만들었고, [처음 구축 순서](#처음-구축-순서) 1~8을 마쳤다(2026-10-25~26) — 첫 배포가 성공했고 운영 도메인 · 백업 · 복원 연습 · 정상 종료를 확인했다. **운영 확인 목록의 목록 밖 계정 거절 · `SESSION` 쿠키 · CSRF/로그아웃 · 되돌리기 연습과, 첫 배포 때 확인의 거절 · 실패 경로는 아직 수행하지 않았다.** 아래 체크와 기록은 사람이 실행한 뒤 남긴다. 공개 여부는 [01 §10](../docs/01-overview.md#10-공개-필수-목록)의 별도 판단이다.

`{도메인}` · `ACCOUNT_ID` · `BUCKET_NAME` · `INSTANCE_ID`는 자리표시다. 실제 도메인 · 계정 · 버킷 · 이메일 · 자격 정보는 공개 저장소에 적지 않는다. 명령의 자리표시도 실행 전에 바꾼다. AWS 자원 생성/설정은 운영자의 관리자 임시 자격으로 수행하고, 앱 배포/백업은 OIDC와 인스턴스 역할을 쓴다.

## 처음 구축 순서

1. **도메인 구매 상태 확인.** 사용자 결정(2026-10-25)으로 **Cloudflare에서 등록했고 DNS도 Cloudflare**다. 새로 구매하지 않고 이후 `{도메인}`을 그 값으로 읽는다. 쓸 문서: [04 §3](../docs/04-jobs-and-cost.md#3-배포). 완료 확인: Cloudflare의 해당 영역이 Active이고 등록 도메인과 권한 있는 네임서버가 일치한다.

2. **AWS 자원 생성 — 서울 `ap-northeast-2`.** 쓸 파일: [aws/의 여섯 JSON 견본](#견본과-자리표시). 실제 값을 넣은 사본은 저장소 밖에서 만든다. 아래 순서로 생성하고 각 완료 조건을 확인한다([상세 설정](#aws-설정)).

   **계정 준비 — 새 가입 방식(Builder ID · 프로젝트) 계정이면 자원보다 먼저 한다.** 이 저장소의 운영 계정이 이 방식이다(2026-10-25). 운영 자원은 조직의 **멤버 계정(프로젝트)** 하나에 만들고, SCP 수정만 **관리 계정** 세션에서 한다.

   | 할 일 | 끝났는지 확인 |
   |---|---|
   | **서울 허용.** 프로젝트 리전은 연락처 국가로 정해져(한국 → `ap-southeast-2`) 조직 SCP가 다른 리전을 막는다. 관리 계정 → Organizations → 정책 → 서비스 제어 정책 → 리전 제한 정책(`AdvancedModeRegionRestriction…`)의 **`RegionFloor` 문장**(`StringNotEquals`) 리전 목록에만 `ap-northeast-2`를 넣는다. `UsEast1Partitional` · `UsWest2Partitional`(`StringEquals`)에 넣으면 서울에서 전역 서비스만 허용되어 EC2 · ECR · S3가 막힌다. 이 계정은 Paid plan 전환과 조직 고급 기능 활성화(되돌릴 수 없다) 뒤 고쳤다 | **멤버 계정** 자격으로 `aws ec2 describe-availability-zones --region ap-northeast-2` 성공, `aws ec2 run-instances --dry-run …`이 `DryRunOperation`. SCP는 관리 계정에 적용되지 않으므로 관리 계정에서 확인하지 않는다 |
   | **콘솔 표시 리전.** 서울 화면이 「리전 제한됨」이면 콘솔 표시 설정이다(API 권한과 무관). 「표시되는 리전 편집」에서 서울을 켠다 | 서울 콘솔 화면이 열린다 |
   | **EBS 기본 암호화.** 서울 EC2 → 계정 속성 → EBS 암호화를 켠다(키 `aws/ebs`). 루트 볼륨 암호화는 만든 뒤 켤 수 없다 | `aws ec2 get-ebs-encryption-by-default --region ap-northeast-2`가 `true` |
   | **월 예산 알림.** 멤버 계정 Budgets에서 비용 예산 · 월별 · 고정 금액([비용](#비용대략) 합계 + 여유 — 2026-10-25 $30) · 요금 집계 「일반 요금」(Unblended) · 범위 「특정 AWS 비용의 차원 필터링」 → 차원 「요금 유형」에서 **크레딧 · 환불 제외** · 알림 실제 100%와 예측 100% 이메일. 크레딧을 빼지 않으면 크레딧이 남는 동안 알림이 오지 않는다. **예산 작업은 연결하지 않는다**(자동 SCP 적용이 서버를 멈출 수 있다) | `aws budgets describe-budget`의 `FilterExpression`이 `Not RECORD_TYPE Credit, Refund`, 알림 2개 |

   | 순서 | 할 일 · 쓸 파일 | 끝났는지 확인 |
   |---|---|---|
   | OIDC 공급자 | IAM에 `https://token.actions.githubusercontent.com`, audience `sts.amazonaws.com` 등록 | 공급자 URL · audience · 계정 내 ARN 확인 |
   | ECR | 비공개 `mogum-app`, 태그 `IMMUTABLE` · 예외 없음, `aws/ecr-lifecycle.json` 적용 | 콘솔에서 태그 덮어쓰기 금지 · 최근 이미지 30개 규칙 확인 |
   | S3 | 전용 범용 버킷 · 공개 차단 네 항목 · Bucket owner enforced · 기본 SSE-S3 · 버전 관리 Enabled · `aws/s3-lifecycle.json` 적용 | 보안/속성/관리 탭에서 설정과 `backups/` · `releases/` 두 규칙 확인 |
   | 인스턴스 역할 | `mogum-instance`에 `aws/ec2-trust.json` · `aws/instance-policy.json`과 `AmazonSSMManagedInstanceCore` 연결, 인스턴스 프로파일 생성 | 역할의 신뢰/권한과 같은 이름 프로파일의 역할 연결 확인 |
   | EC2 | `t4g.small` · Amazon Linux 2023 **arm64** · 위 인스턴스 프로파일. 인터넷 게이트웨이 경로가 있는 공개 서브넷(기본 VPC), 외부 ECR/S3/SSM HTTPS 통신 가능. 보안 그룹 인바운드는 웹 TCP **80/443만**, **22와 5432는 열지 않는다**. 키 페어 없음 · IMDSv2 필수 · 종료 방지 켬(지우거나 다시 만들 때는 먼저 끈다) | EC2 상태 검사 통과 · 프로파일 연결 · SG 확인 · 루트 볼륨 암호화 · SSM 관리형 노드 Online · Session Manager 접속 성공 |
   | Elastic IP | 서울 리전 주소 할당 후 위 EC2에 연결 | 인스턴스의 공인 IPv4와 Elastic IP 연결 대상이 일치 |
   | 배포 역할 | `mogum-deploy`에 `aws/github-oidc-trust.json` · `aws/deploy-policy.json` 적용. 이제 얻은 `INSTANCE_ID`를 권한 사본에 넣는다. 콘솔에서는 「사용자 지정 신뢰 정책」에 치환한 신뢰 견본을 그대로 붙인다 | 신뢰 sub는 `repo:f-lab-edu@54677968/Mogum@1378404194:ref:refs/heads/main`, SSM 대상은 해당 한 대. 실제 수임/배포는 8번에서 확인 |

   이 저장소는 불변 subject를 쓰므로 `sub`에 조직 · 저장소 ID가 들어간다. `gh api repos/f-lab-edu/Mogum/actions/oidc/customization/sub`의 `sub_claim_prefix` 뒤에 `:ref:refs/heads/main`을 붙인 값이 신뢰 정책과 같은지 확인한다.

   EC2의 EBS는 OS · DB · 로컬 릴리스 3개 · 현재/직전 이미지 · 덤프/복원 임시 공간을 담아야 한다. **gp3 30GiB(기본 3000 IOPS · 125MiB/s) · 암호화**로 정했다(2026-10-26 — 기본 8GiB로는 부족하다). 루트 볼륨 손실 시 S3 백업으로 복구하므로 EBS만 백업으로 여기지 않는다.

   콘솔의 한국어 이름은 견본 용어와 다를 수 있다 — OIDC 공급자는 IAM 「ID 제공업체」, Bucket owner enforced는 S3 객체 소유권 「ACL 비활성화됨(권장)」, 미완료 multipart 정리는 S3 수명 주기 작업 「만료된 객체 삭제 마커 또는 완료되지 않은 멀티파트 업로드 삭제」 안의 「불완전 멀티파트 업로드 삭제」다(현재 버전 만료와 함께 고르면 삭제 마커 항목은 비활성이 맞다).

   **수행 기록(2026-10-25~26)** — 위 7개를 콘솔로 만들고 CLI 읽기로 견본과 대조했다: OIDC URL · audience, ECR IMMUTABLE · 예외 없음 · AES256 · 수명주기 일치, S3 공개 차단 4개 · `BucketOwnerEnforced` · SSE-S3 · 버전 관리 · 수명주기 일치, 두 역할의 신뢰 · 권한이 치환본과 일치, EC2 상태 검사 ok · SSM Online · 루트 볼륨 암호화, Elastic IP 연결. IAM 시뮬레이터로 두 역할의 허용 9건 · 거부 12건이 기대대로였다(아래 [첫 배포 때 확인](#첫-배포-때-확인) 11의 시뮬레이터 부분). 실제 수임 · 업로드 · 배포는 8번에서 확인한다. 실제 ID · 주소는 비공개 운영 기록에 둔다.

3. **Cloudflare DNS.** `api.{도메인}`의 **A 레코드 값은 위 Elastic IP**, 프록시는 **DNS only · 회색 구름**으로 저장한다. 쓸 파일: [Caddyfile](./Caddyfile)의 `API_DOMAIN`, [05 §1.4](../docs/05-api.md#14-우리-api-레이트리밋). 프록시를 켜면 모든 요청이 Cloudflare IP로 보여 IP당 레이트리밋이 깨지고 Caddy 인증서 발급 경로도 바뀐다(ADR-060이 Vercel API 프록시를 뺀 것과 같은 이유). 완료 확인: DNS → Records에서 해당 행의 Proxy status가 **DNS only**인지 다시 연다. 로컬의 `dig +short A api.{도메인}`이 Elastic IP인지 확인한다. 같은 호스트의 예전 A/AAAA 레코드가 다른 서버를 가리키면 정리한다. [Cloudflare 프록시 상태 문서](https://developers.cloudflare.com/dns/proxy-status/). 화면 `{도메인}` 레코드는 6번의 Vercel 안내값으로 만들며 그것도 DNS only다.

   **수행 기록(2026-10-26)** — `api` A 레코드를 DNS only로 저장했다. 공용 리졸버 세 곳과 Cloudflare 권한 네임서버가 모두 Elastic IP를 돌려주고 AAAA/CNAME은 없다(프록시였다면 Cloudflare IP와 AAAA가 나온다).

4. **서버 준비 — Session Manager로 접속.** 쓸 파일: [mogum.env.example](./mogum.env.example), [compose.yaml](./compose.yaml), [deploy.sh](./deploy.sh), [systemd/](./systemd/). 아래 설치/확인을 마친 뒤 env를 채운다. 서버에서 앱을 빌드하지 않는다.

   서버 명령은 Bash · Docker Engine/CLI · **Compose 플러그인(`docker compose`)** · AWS CLI **v2** · curl · systemd(`systemctl` · `journalctl` · `systemd-run` · `systemd-analyze`) · GNU coreutils(`cat` · `stat` · `install` · `readlink` · `ln` · `mv` · `sort` · `rm` · `sleep` · `id` · `mktemp` · `date` · `wc` · `chmod`) · diffutils(`cmp`) · findutils(`find`) · tar/gzip · grep다. 호스트 JDK · Gradle · Postgres 도구는 필요 없다.

   SSM 셸에서 먼저 `sudo -i`만 실행해 root 프롬프트(`#`)를 연 뒤, 아래 블록을 **통째로** 붙여 넣는다. `sudo -i`와 나머지를 한 번에 붙이면 뒤 줄이 새 셸에 전달되지 않고 아무것도 실행되지 않는다(2026-10-26 확인). 블록은 Bash 스크립트 하나라서 실패한 줄에서 멈추고 세션은 닫히지 않는다. [AWS의 AL2023 Docker 설치](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-docker.html), [AL2023 DNF](https://docs.aws.amazon.com/linux/al2023/ug/package-management.html), [Compose 수동 설치](https://docs.docker.com/compose/install/linux/#install-the-plugin-manually)를 근거로 한 명령이다. Compose는 전 사용자 경로에 arm64 파일을 설치한다. 아래 버전은 [공식 릴리스 v2.40.3](https://github.com/docker/compose/releases/tag/v2.40.3)이며, 2026-10-26 AL2023 aarch64에서 Docker 25.0.18 · Compose v2.40.3 · aws-cli 2.38.0으로 설치 · 자동 시작을 확인했다. 수동 설치 플러그인은 자동 갱신되지 않는다.

   ```bash
   bash -euo pipefail <<'EOF'
   test "$(uname -m)" = aarch64
   dnf install -y docker awscli-2 bash coreutils diffutils findutils tar gzip grep
   command -v curl
   systemctl enable --now docker
   install -d -m 755 /usr/local/lib/docker/cli-plugins
   curl --fail --location \
     https://github.com/docker/compose/releases/download/v2.40.3/docker-compose-linux-aarch64 \
     --output /usr/local/lib/docker/cli-plugins/docker-compose
   chmod 755 /usr/local/lib/docker/cli-plugins/docker-compose
   docker version
   docker compose version
   aws --version
   install -d -m 755 /opt/mogum /opt/mogum/releases
   install -d -m 700 /etc/mogum
   echo "=== SETUP OK ==="
   EOF
   ```

   AL2023 AMI의 기본 `curl-minimal`이 HTTPS curl을 제공한다([AWS 패키지 설명](https://docs.aws.amazon.com/linux/al2023/ug/curl-minimal.html)). `command -v curl` 실패나 패키지/다운로드 오류면 이 단계에서 멈추고 [첫 배포 때 확인](#첫-배포-때-확인)의 설치 항목을 해결한다. AWS CLI 출력은 `aws-cli/2…`여야 한다.

   로컬 저장소의 `deploy/mogum.env.example` **내용을 견본으로만** 서버 편집기에 옮겨 `/etc/mogum/mogum.env`에 저장한다. 최초 파일은 `umask 077; touch /etc/mogum/mogum.env`로 만들고 `vi /etc/mogum/mogum.env`로 채운다. 기존 파일이 있으면 비우거나 견본으로 덮어쓰지 않는다. 비밀을 명령 인자 · 셸 이력 · Actions 로그에 넣지 않는다. `DB_PASSWORD`는 사람이 알 필요가 없으므로 서버에서 `head -c 32 /dev/urandom | od -An -v -tx1 | tr -d ' \n'`(16진수 64자 — 두 파서가 따옴표 없이 같은 값으로 읽는다)으로 만들어 화면에 출력하지 않고 파일에 바로 넣어도 된다. 이 파일은 Compose와 systemd `EnvironmentFile`이 함께 읽으므로 `NAME=value` 형식이며 `export`/셸 치환은 쓰지 않는다. 두 파서에서 같은 값이 되는 인용 형식을 사용한다.

   | 이름 | 넣을 값 |
   |---|---|
   | `API_DOMAIN` | `api.{도메인}` — 스킴/경로 없음 |
   | `DB_USERNAME` · `DB_PASSWORD` | 정한 DB 사용자와 강한 비밀번호. 최초 볼륨 초기화에 쓰이며 기존 DB 비밀번호를 env 변경만으로 바꾸지 않는다 |
   | `GOOGLE_CLIENT_ID` · `GOOGLE_CLIENT_SECRET` | 기존 Google OAuth 웹 클라이언트 자격 |
   | `GOOGLE_REDIRECT_URI` | `https://api.{도메인}/auth/google/callback` |
   | `APP_WEB_ORIGIN` | `https://{도메인}` — 끝 `/` 없음 |
   | `APP_WEB_COOKIE_DOMAIN` | `{도메인}` — 스킴/경로 없음 |
   | `APP_AUTH_ADMIN_EMAILS` | 로그인할 관리자 이메일, 여러 개면 쉼표 구분. 1개 이상 필수 — 비우면 Compose가 기동을 거부해 배포가 `Deployment failed.`로 끝난다 |
   | `MOGUM_BUCKET` | 2번 S3 버킷 이름, `s3://` 없음 |

   `APP_IMAGE`는 넣지 않는다(배포가 `app-image.ref`로 주입). `DB_URL` · `SPRING_PROFILES_ACTIVE=aws`도 Compose가 고정한다. 완료 확인: 값 누락은 편집기에서 확인하고 내용은 출력하지 않는다. 아래는 권한/도구만 확인한다. `stat` 결과는 `0:600`, Docker 활성/자동 시작은 `active`/`enabled`여야 한다. `current`와 백업 unit은 첫 성공 배포가 만든다.

   ```bash
   chown root:root /etc/mogum/mogum.env
   chmod 600 /etc/mogum/mogum.env
   stat -c '%u:%a' /etc/mogum/mogum.env
   systemctl is-active docker
   systemctl is-enabled docker
   for tool in bash docker aws curl systemctl journalctl systemd-run systemd-analyze \
     cat stat install readlink ln mv sort rm sleep id mktemp date wc chmod cmp find tar gzip grep; do
     command -v "$tool" >/dev/null || exit 1
   done
   ```

   **수행 기록(2026-10-26)** — 위 설치와 env 작성을 마쳤다. 운영자 자격의 SSM Run Command로 아키텍처 · 버전 · Docker `active`/`enabled` · `/etc/mogum` `700 root` · env `0:600`, 필수 10개 항목이 한 줄씩 값이 있고 공백/따옴표가 없음, `APP_IMAGE` 없음, 도구 26개를 확인했다(값은 출력하지 않았다).

5. **GitHub 저장소 Variables · Secrets.** 쓸 파일: [deploy.yml](../.github/workflows/deploy.yml), [설정 표](#github-actions-배포). Actions **Variables 3개**(`DEPLOY_ENABLED` · `AWS_REGION` · `API_ORIGIN`)와 **Secrets 3개**(`AWS_DEPLOY_ROLE_ARN` · `DEPLOY_BUCKET` · `DEPLOY_INSTANCE_ID`)를 등록한다. 이미 Variables에 넣은 세 식별자는 **사람이 Secrets로 옮기고 Variables에서는 지운다.** `DEPLOY_BUCKET`은 서버 `MOGUM_BUCKET`과 같다. **`DEPLOY_ENABLED=false`로 유지**한다. AWS 장기 키나 Google 비밀을 등록하지 않는다. 완료 확인: Variables 3개와 Secrets 3개의 등록 위치/이름을 설정 화면에서 대조하고, 세 식별자가 Variables에 남지 않았는지 확인한다. 워크플로가 `main`에 있으며 조직이 러너/액션을 허용하는지도 확인한다.

   **수행 기록(2026-10-26)** — 여섯 변수를 등록했고(`DEPLOY_ENABLED=false`) `gh variable list`로 이름/값을 대조했다. step 8(로그 가림) 뒤 세 식별자를 Secrets로 옮기고 Variables에서 지웠다 — `gh secret list`로 이름 세 개, `gh variable list`로 남은 Variables 세 개의 이름/값을 확인했다(Secret 값은 읽을 수 없어 첫 배포의 형식 검사에서 확인된다). 워크플로가 `main`에 있는지와 조직의 러너/액션 허용은 병합 뒤 8번에서 확인한다.

6. **Vercel 프로젝트 · 환경 변수 · 도메인.** 쓸 파일: [frontend/vercel.json](../frontend/vercel.json), [Vercel 설정 표](#vercel화면). 이미 만든 Hobby 프로젝트를 사용한다. Root Directory `frontend` · Vite · Build Command `npm run build` · Output `dist` · Production Branch `main`. **Production에만** `VITE_API_BASE_URL=https://api.{도메인}` 등록 후 다시 빌드한다. Domains에 `{도메인}`을 추가하고 Vercel 안내 DNS 값을 Cloudflare에 **DNS only**로 넣는다. 완료 확인: Vercel 도메인 연결/인증서가 정상이며 운영 도메인의 `/login` 새로고침이 성공한다. Hobby 비상업 조건은 [첫 배포 때 확인](#첫-배포-때-확인)에서 서비스 적합성을 판단한다. `*.vercel.app` 미리보기에서 로그인은 검사하지 않는다.

   **수행 기록(2026-10-25~26)** — 프로젝트 · Production `VITE_API_BASE_URL` · 도메인 연결을 마쳤다. 운영 `/`는 200이고 번들에 API 출처가 들어 있다. `/login` 새로고침은 아직 404다 — SPA rewrite(`frontend/vercel.json`)가 `main`에 들어간 뒤 운영 확인 목록에서 다시 본다.

7. **Google 콘솔 운영 콜백.** 쓸 문서: [05 §1.1](../docs/05-api.md#11-인증--구글-oauth--쿠키-세션db-저장), 서버의 `GOOGLE_REDIRECT_URI`. 기존 OAuth 웹 클라이언트의 승인된 리디렉션 URI에 **`https://api.{도메인}/auth/google/callback`을 추가**한다. 로컬 **`http://localhost:5173/auth/google/callback`은 유지**한다. 완료 확인: 두 URI가 모두 저장되어 있고 서버 값과 스킴/호스트/경로가 정확히 같다. Google 동의 화면이 테스트 모드라면 검사할 계정이 테스트 사용자에 포함되는지도 확인한다.

8. **첫 배포.** 위 준비가 끝나면 `DEPLOY_ENABLED=true`로 바꾸고 Actions → **Deploy backend → Run workflow**에서 브랜치 **`main`**, **`sha`는 빈칸**으로 실행한다. 쓸 파일: [deploy.yml](../.github/workflows/deploy.yml), [deploy.sh](./deploy.sh). 완료 확인: 빌드/`imageTest`(새 이미지일 때) · ECR · S3 · **Deploy through SSM and print the command result**의 `Success`/종료 코드 0 · **Check public HTTPS health**의 200을 확인한다. SSM stdout에 `Deployment healthy; current release updated.`가 있어야 한다. 이후 아래 운영 확인을 수행한다. 실패하면 [수동 롤백 · 실패 확인](#수동-롤백--실패-확인)을 따르며 첫 배포에는 직전 릴리스가 없다.

   **수행 기록(2026-10-26)** — 병합 push 실행은 `DEPLOY_ENABLED=false`라 건너뛰었다. `true`로 바꾸고 Run workflow를 실행하자 **OIDC 단계가 `Not authorized to perform sts:AssumeRoleWithWebIdentity`로 실패**했다. 이 저장소는 GitHub OIDC의 불변 subject(`use_immutable_subject`)를 써서 토큰 `sub`가 `repo:f-lab-edu@54677968/Mogum@1378404194:ref:refs/heads/main`인데 신뢰 정책은 이름 형식(`repo:f-lab-edu/Mogum:…`)이었다. CloudTrail의 `AssumeRoleWithWebIdentity` 실패 이벤트 `principalId`에서 실제 sub를 확인하고, 운영자 승인 뒤 CLI(`iam update-assume-role-policy`)로 신뢰 정책의 sub만 바꿔 다시 실행했다. 재실행은 약 4분에 성공했다 — 빌드/`imageTest` 2분 35초 · ECR push · S3 묶음 · SSM `Success`(stdout `Deployment healthy; current release updated.`, 백업 timer 설치) · 공개 HTTPS 헬스 200. 공개 로그 전체에서 계정 ID · 버킷 · 인스턴스 ID는 `***`로 가려졌다.

## 운영 확인 목록

체크한 항목은 2026-10-26 첫 배포 뒤 확인했고, 체크하지 않은 항목은 아직 수행하지 않았다. 운영 도메인을 사용하고 브라우저 개발자 도구 Network의 Preserve log를 켠다. 응답/쿠키 값이 포함된 화면이나 HAR를 공개 저장소에 올리지 않는다.

- [x] **화면 · SPA 새로고침:** `https://{도메인}`을 열고 `/login`으로 이동한 뒤 새로고침한다. 기대: Vercel 404 없이 **U1 로그인 화면**. Network에서 `assets/…`가 HTML이 아닌 JS/CSS로 응답한다. `/curriculums/1` 직접 열기/새로고침도 SPA까지 도달해야 하며 미구현 화면은 기존 앱 경로 처리를 따른다. — 2026-10-26: `/login` · `/curriculums/1` 200 HTML, 애셋 JS/CSS 타입(curl)
- [x] **헬스 · 인증서:** 로컬에서 아래 명령을 실행한다. 기대: 인증 우회 옵션 없이 TLS 검증 성공 · HTTP **200**, 본문 **`{"status":"UP"}`**만. 브라우저 보안 정보에서도 API/화면 인증서의 호스트/유효기간을 확인한다. — 2026-10-26: 200 `{"status":"UP"}`, API 인증서 Let's Encrypt · 호스트 일치 · 90일(`openssl s_client`), HTTP는 308로 HTTPS 이동

  ```bash
  curl --fail-with-body --silent --show-error -i https://api.{도메인}/actuator/health
  ```

- [ ] **Google 로그인:** 관리자 목록 계정으로 U1에서 로그인한다. 기대: 화면 `/`로 복귀하고 Network의 **`GET https://api.{도메인}/api/me`가 200**. 시크릿 창에서 관리자 목록 밖 계정으로 시도하면 화면 **`/login?error=not_allowed`**. Google 테스트 모드의 계정 제한에 먼저 막히면 테스트 사용자 설정을 확인한 뒤 다시 검사한다. — 2026-10-26: 관리자 계정 로그인 성공(사람, 모바일 브라우저). 목록 밖 계정 거절은 아직
- [ ] **쿠키:** 로그인 직후 Network 콜백/API 응답의 **Set-Cookie**와 Application → Cookies를 함께 본다. 기대: `SESSION`은 **Domain 속성 없음(API 호스트 전용)** · **HttpOnly · Secure · SameSite=Lax**, `XSRF-TOKEN`은 **Domain={도메인} · HttpOnly 아님 · Secure · SameSite=Lax**. 브라우저의 표시상 선행 점은 허용한다. Domain 없음 여부는 Set-Cookie로 판단한다. — 2026-10-26: 비로그인 응답의 `XSRF-TOKEN` 속성은 기대와 같다(curl). `SESSION`은 로그인 뒤에만 생겨 아직
- [ ] **CSRF · 로그아웃:** 로그인한 **화면 출처**의 개발자 도구 Console에서 아래를 순서대로 실행하고 Network에서 확인한다(현재 화면에 로그아웃 버튼이 없어도 실행 가능). 기대: 헤더 없는 요청은 **403** · JSON `code=CSRF_INVALID`, 세션은 유지된다. 쿠키 토큰을 헤더에 넣은 같은 요청은 **204**, 세션 만료 후 `/api/me`는 **401**. 토큰/세션 값은 출력하지 않는다.

  ```javascript
  const apiOrigin = 'https://api.{도메인}';
  await fetch(`${apiOrigin}/api/me`, { credentials: 'include' });
  await fetch(`${apiOrigin}/api/auth/logout`, {
    method: 'POST', credentials: 'include',
  });
  const xsrf = document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='));
  if (!xsrf) throw new Error('XSRF-TOKEN 쿠키 없음');
  await fetch(`${apiOrigin}/api/auth/logout`, {
    method: 'POST', credentials: 'include',
    headers: { 'X-XSRF-TOKEN': decodeURIComponent(xsrf.slice('XSRF-TOKEN='.length)) },
  });
  await fetch(`${apiOrigin}/api/me`, { credentials: 'include' });
  ```

- [x] **CORS:** 아래 비허용 출처의 preflight와 실제 GET을 실행한다. 기대: **403**이고 **`Access-Control-Allow-Origin`/`Access-Control-Allow-Credentials` 없음**. 위 화면 출처의 정상 `/api/me` 응답에는 정확한 화면 Origin과 credentials `true`가 있어야 한다. — 2026-10-26: 비허용 출처 preflight · GET 모두 403, 두 헤더 없음. 화면 출처의 preflight · `/api/me`(비로그인 401)에 정확한 Origin과 credentials `true`

  ```bash
  curl --silent --show-error -i -X OPTIONS https://api.{도메인}/api/me \
    -H 'Origin: https://other.example' \
    -H 'Access-Control-Request-Method: GET' \
    -H 'Access-Control-Request-Headers: Content-Type,X-XSRF-TOKEN'
  curl --silent --show-error -i https://api.{도메인}/api/me \
    -H 'Origin: https://other.example'
  ```

- [x] **배포 중 정상 종료:** 한가할 때 SSM root Bash에서 아래를 실행한다. 기대: 종료 대기 설정 **150초**, 직전 컨테이너 종료 코드 **143** · OOM `false`, 앱 로그에 graceful shutdown 시작/완료. **137/SIGKILL이면 실패**이며 원인을 확인한다. 재시작 후 로컬/공개 헬스가 다시 UP이어야 한다. 실행기의 호출 완료/반납은 아직 없으므로 **`4-job-queue`에서 별도 확인**한다. — 2026-10-26(SSM Run Command): 150초 · 143 · OOM `false`, graceful shutdown 시작/완료 → JPA · 커넥션 풀 종료 로그, 정지 1초 · 재시작 12초 뒤 로컬 UP · 공개 200

  ```bash
  export APP_IMAGE="$(cat /opt/mogum/current/app-image.ref)"
  compose() {
    docker compose --project-name mogum --env-file /etc/mogum/mogum.env \
      -f /opt/mogum/current/deploy/compose.yaml "$@"
  }
  backend=$(compose ps -q backend)
  docker inspect --format '{{.Config.StopTimeout}}' "$backend"
  compose stop backend
  docker inspect --format '{{.State.ExitCode}} {{.State.OOMKilled}}' "$backend"
  docker logs --tail 80 "$backend"
  compose start backend
  curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health
  ```

  기동 직후 curl이 실패하면 UP까지 기다려 다시 확인한다. 앱 로그는 SSM에서만 보고 비밀이 섞이지 않았는지 확인한다.

- [x] **백업:** SSM에서 `sudo systemctl start mogum-backup.service`, 이어 `sudo journalctl -u mogum-backup.service --since '10 minutes ago' --no-pager`. 기대: 성공한 **`s3://…/backups/…dump`와 바이트 크기**, S3 콘솔에 같은 객체가 있다. `sudo systemctl is-enabled mogum-backup.timer`는 `enabled`, `sudo systemctl list-timers mogum-backup.timer --all`의 다음 실행은 **한국 04:00(UTC 19:00)**. [백업 상세](#백업--복원). — 2026-10-26(SSM Run Command): `backups/20261026T070915+0900.dump` 10,076바이트, S3 `head-object`로 크기 · SSE-S3 · 버전 대조, timer `enabled` · 다음 실행 19:00 UTC
- [x] **복원 연습:** [복원 연습 명령](#복원-연습)의 `restore.sh latest`를 실행한다. 기대: 종료 코드 0 · 사용한 객체 키 · 테이블별 행 수가 나오고 **`mogum_restore_check`가 제거**된다. 확인은 아래 명령의 결과가 빈 값인지 본다. 원본 `mogum`의 데이터는 바뀌지 않아야 한다. 아래 기록 칸은 실행 후 채운다.

  ```bash
  postgres=$(sudo docker ps --filter label=com.docker.compose.project=mogum \
    --filter label=com.docker.compose.service=postgres --format '{{.ID}}')
  sudo docker exec "$postgres" sh -c \
    'psql -X -At -U "$POSTGRES_USER" -d mogum -c "SELECT datname FROM pg_database WHERE datname = '\''mogum_restore_check'\''"'
  ```

  | 실행 날짜(한국) | 객체 키 | 테이블별 행 수 | 종료 코드 · 확인용 DB 삭제 · 원본 불변 확인 |
  |---|---|---|---|
  | 2026-10-26 07:10 | `backups/20261026T070915+0900.dump` | `flyway_schema_history` 6 · `spring_session` 2 · `spring_session_attributes` 2 · `users` 1 | 0 · 제거 확인 · 원본 `users` · `flyway_schema_history` 행 수 전후 같음 |

  실제 값이 든 운영 기록은 비공개 운영 기록에 남긴다. 이 표에는 비밀/실제 계정 값을 넣지 않는다.

- [ ] **되돌리기 연습(선택):** 호환되는 직전 릴리스가 생기면 Actions `workflow_dispatch`에 **직전 전체 SHA**를 넣는다. 기대: 기존 이미지/묶음 지문 검사 후 **빌드/검사/push 없이 재배포**, SSM 성공 · HTTPS 200. 헬스 실패 릴리스를 일부러 만들 필요는 없다. Flyway는 되돌아가지 않는다([롤백 범위](#보관과-롤백-범위)).

- [ ] **journald 로그 · 요청 추적:** 성공 배포 뒤 SSM에서 `systemd-analyze cat-config systemd/journald.conf`에 `Storage=persistent` · `SystemMaxUse=500M` · `MaxRetentionSec=13day` · `MaxFileSec=1day`가 보이는지 확인한다. journald 전환 후 다음 배포에서 `journalctl CONTAINER_NAME=mogum-backend-1`에 **배포 전 컨테이너의 로그가 남아 있고**, `docker logs --tail 100 mogum-backend-1`도 동작해야 한다. 브라우저 개발자 도구 Network에서 응답의 `X-Request-Id`를 읽고 그 값으로 해당 요청의 접근 줄을 찾는다. 줄 시각이 `+09:00`이며 이메일 · 쿼리가 없는지 확인한다([로그 보기](#로그-보기)). 최초 전환 직전의 json-file 로그는 보존되지 않는다. **서버에서의 실제 동작은 아직 확인하지 않았다.**

## 첫 배포 때 확인

이전 step에서 남긴 **외부 환경 미검증 항목 18개**를 아래로 모았다. 공식 문서의 형식 대조와 실제 계정/서버 실행은 구분한다. 아직 수행하지 않은 항목은 체크하지 않으며, 실행 결과는 비공개 운영 기록에 남긴다. 파일 결함을 발견하면 이 문서 step에서 동작을 바꾸지 않고 별도 수정 작업으로 넘긴다.

| # | 확인할 사실 · 방법 | 틀렸을 때 고칠 곳 |
|---|---|---|
| 1 | 조직 Actions 정책에서 `ubuntu-24.04-arm` 허용/사용 가능 여부, 실제 로그의 arm64와 무료 제공 조건 확인([러너 표](https://docs.github.com/en/actions/reference/runners/github-hosted-runners#standard-github-hosted-runners-for-public-repositories)) — 2026-10-26: 저장소 Actions 허용, 러너 배정 · 실행 성공(로그의 `JAVA_HOME_21_ARM64`) | 조직 Actions/결제 설정, 필요 시 `.github/workflows/deploy.yml`의 러너 선택을 별도 검토 |
| 2 | `actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09`(v5.1.0) · `actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961`(v5.7.0) · `aws-actions/configure-aws-credentials@61815dcd50bd041e203e49132bacad1fd04d2708`(v5.1.1)의 허용/해결 성공과 입력 동작을 실제 실행 로그로 확인 — 2026-10-26: 저장소 허용 액션 `all`, 세 액션 해결 · 입력 동작 성공 | 조직 허용 액션 설정 · `deploy.yml` |
| 3 | 러너 Docker · AWS CLI v2 · jq · curl · Bash · GNU 도구 · tar/grep, 전체 build와 같은 이미지 `imageTest` 성공 확인([도구 목록](https://github.com/actions/partner-runner-images/blob/main/images/arm-ubuntu-24-image.md)) — 2026-10-26: 전체 build · 같은 이미지 `imageTest` 성공(2분 35초) | 러너 준비 단계/`deploy.yml`, 필요 시 `backend/build.gradle` · `AppImageIT` |
| 4 | 위 AL2023 패키지 설치 · aarch64 Compose 다운로드 · root의 `docker compose version` · Docker 자동 시작을 실제 SSM 셸에서 확인 — 2026-10-26 수행 | 이 문서의 서버 설치 명령/버전, 호스트 패키지 설정 |
| 5 | env의 root `600` · 필수 값 · Compose/systemd의 인용값 해석, DB 초기화와 `aws` 프로필 기동 확인(내용 출력 금지) — 2026-10-26: 첫 배포 기동 · DB 초기화 · `aws` 프로필 헬스 UP, systemd `EnvironmentFile`로 백업 성공(내용 출력 없음) | 서버 `/etc/mogum/mogum.env`; 계약 문제면 견본/Compose/설정 별도 수정 |
| 6 | SSM Agent Online/접속, 인스턴스 프로파일 · `AmazonSSMManagedInstanceCore`, ECR/S3/SSM 외부 HTTPS 통신 확인 — Online · Session Manager · Run Command · 프로파일은 2026-10-26 수행, ECR/S3 통신은 2026-10-26 첫 배포 · 백업에서 확인 | IAM/EC2/네트워크 설정; 정책 견본 문제면 `aws/instance-policy.json` |
| 7 | Cloudflare API/화면 DNS only · Elastic IP/화면 안내 DNS · 80/443 접근 · Caddy 인증서 발급/reload · HTTPS 확인 — 2026-10-26: Caddy가 Let's Encrypt 인증서를 발급, HTTP→HTTPS 308 · HTTPS 200 | Cloudflare DNS/SG, `/etc/mogum/caddy/Caddyfile` 원본인 `deploy/Caddyfile` |
| 8 | 치환한 JSON 수락 · OIDC audience/sub · `main` 수임 성공, 다른 브랜치/PR/포크 거절 확인. 워크플로에 `environment` 없어야 함 — 2026-10-26: `main` 수임 성공. 첫 시도는 **토큰 sub가 불변 ID 형식**이라 거절돼 신뢰 정책을 고쳤다([처음 구축 순서](#처음-구축-순서) 8번 기록). 다른 브랜치/PR/포크 거절은 아직 | IAM 공급자/역할 신뢰 · `aws/github-oidc-trust.json` · `deploy.yml` |
| 9 | [AWS CLI v2 적용 예](#aws-cli-v2-적용-예)의 실제 성공과 SSM [send-command](https://docs.aws.amazon.com/cli/latest/reference/ssm/send-command.html)/[get-command-invocation](https://docs.aws.amazon.com/cli/latest/reference/ssm/get-command-invocation.html) 옵션/응답을 실행 로그로 확인 — 2026-10-26: 워크플로와 운영자 Run Command의 `send-command`/`get-command-invocation` 성공. 적용 예의 자원 생성 CLI는 콘솔로 만들어 실행하지 않았다 | 이 문서 CLI 예제/운영 CLI 버전 · `deploy.yml`의 명령/결과 처리 |
| 10 | 배포 역할의 ECR push/지문 조회 · releases 업로드/조회 · 지정 인스턴스 SSM/결과 조회 성공, 다른 문서/인스턴스/`backups/` 접근 거절 확인 — 2026-10-26: 성공 경로(ECR push/지문 · releases 업로드 · SSM/결과 조회) 확인. 거절 경로의 실제 호출은 아직 | IAM 연결 정책 · `aws/deploy-policy.json` |
| 11 | 인스턴스 역할의 ECR pull · releases 다운로드 · backups 업로드(multipart 포함)/목록/다운로드 성공. 두 역할 삭제/설정 변경 권한 부재를 IAM 시뮬레이터로 확인 — 시뮬레이터에는 `aws:RequestedRegion=ap-northeast-2` 문맥을 넣는다(없으면 조직 SCP `RegionFloor`가 모두 명시 거부로 평가한다). 시뮬레이터 부분은 2026-10-26 수행. ECR pull · releases 다운로드 · backups 업로드(단일 파트)/목록/다운로드는 2026-10-26 첫 배포 · 백업 · 복원 연습에서 확인, multipart는 아직 | IAM 연결 정책 · `aws/instance-policy.json` · `aws/deploy-policy.json` |
| 12 | `main` 실행의 OIDC → ECR → S3 → SSM → HTTPS 200, 같은 SHA 재실행의 빌드/검사/push 생략 확인 — 2026-10-26: OIDC → ECR → S3 → SSM → HTTPS 200 확인. 같은 SHA 재실행의 생략은 아직 | 저장소 Variables · Secrets · `deploy.yml` · `deploy.sh` |
| 13 | 보관된 이전 SHA 재배포, 실패 SSM 출력/실패 코드와 서버 자동 복귀, 로컬 직전 이미지/릴리스 보존, 공개 로그의 비밀 부재 확인. 첫 실행의 단계 env · docker push · AWS 오류 · SSM 출력에서 계정 ID · 버킷 이름 · 인스턴스 ID가 `***`로 가려졌는지 확인. 정상 수동 재배포만으로 실패 자동 복귀 검증을 대체하지 않음 — 2026-10-26: 첫 실행 공개 로그의 세 식별자 가림 확인(로그 전체에서 0건). 이전 SHA 재배포 · 실패 자동 복귀는 아직 | `deploy.yml` · `deploy.sh` · `aws/` 정책; Flyway 호환성은 해당 마이그레이션 |
| 14 | `systemd-analyze calendar '*-*-* 19:00:00 UTC'`와 `TZ=Asia/Seoul date`로 일정이 한국 04:00인지 확인 — 2026-10-26: `systemctl list-timers`의 다음 실행 `19:00 UTC`(한국 04:00)로 확인 | `systemd/mogum-backup.timer`, 이 문서의 시간 표기 |
| 15 | 성공 배포 후 unit 설치 · timer enabled/다음 실행 · `Persistent=true`의 놓친 실행 보충, 수동 백업/복원의 객체 키·행 수·DB 정리·원본 불변 확인 — 2026-10-26: unit 설치 · timer enabled/다음 실행 · 수동 백업/복원(객체 키 · 행 수 · 확인용 DB 제거 · 원본 불변) 확인. `Persistent=true` 보충은 아직 | `deploy.sh` · `systemd/` · `backup.sh` · `restore.sh` · 서버 env |
| 16 | ECR IMMUTABLE/최근 30개, S3 공개 차단/SSE-S3/버전 관리/두 수명주기 적용 확인. 시간이 지난 표본의 현재 만료 → 이전 버전 삭제 → 삭제 표시 정리 · 미완료 multipart 정리와 지연을 기록 | AWS 설정 · `aws/ecr-lifecycle.json` · `aws/s3-lifecycle.json`; 실제 지연은 [보관 설명](#보관과-롤백-범위)에 반영 |
| 17 | [Hobby 비상업 조건](https://vercel.com/docs/plans/hobby)에 서비스가 맞는지 공개 전에 판단. Hobby 조직 저장소 Git 연동은 2026-10-25에 성공했으나 약관 적합성은 미확인 | Vercel 요금제/화면 배포 방식은 사용자 결정, [01 §10](../docs/01-overview.md#10-공개-필수-목록) |
| 18 | Vercel main/Production 변수 · 커스텀 도메인/인증서 · SPA 직접 열기/새로고침/정적 파일 · 운영 Google 로그인/거절 · 쿠키/CSRF/CORS를 위 운영 목록으로 확인 — 2026-10-26: Production 변수 · 도메인/인증서 · SPA · 관리자 로그인 · CORS 확인. 목록 밖 계정 거절 · `SESSION` · CSRF는 아직 | Vercel 프로젝트/DNS/Google 콘솔/서버 env; SPA 규칙 문제면 `frontend/vercel.json` |

## 비용(대략)

**서울 리전 단가 — AWS Price List API(`aws pricing get-products`) 2026-10-25 조회 기준**, USD · 세금 제외. 운영 계정은 새 가입 방식이라 EC2 무료 사용량이 없어 처음부터 과금되고, 가입 크레딧이 남는 동안 청구액에서 빠진다([04 §3](../docs/04-jobs-and-cost.md#3-배포)). 단가는 바뀔 수 있으니 다시 계산할 때는 같은 API나 [요금 계산기](https://calculator.aws/)로 확인한다. EC2는 Linux 온디맨드 `t4g.small` 한 대 · 월 730시간, 공인 IPv4 한 개 기준이다.

| 항목 | 단가 | 월 대략치 | 출처 |
|---|---|---|---|
| EC2 `t4g.small` | $0.0208/시간 | × 730시간 = **$15.18** | [EC2 요금](https://aws.amazon.com/ec2/pricing/on-demand/) |
| CPU 크레딧(unlimited) | 초과분 $0.04/vCPU-시간 | 평소 $0. 24시간 평균이 기준(vCPU당 20%)을 넘을 때만 과금 — 한 달 내내 100%면 약 $46.7 | [EC2 요금](https://aws.amazon.com/ec2/pricing/on-demand/) |
| EBS gp3 | $0.0912/GB-월(3000 IOPS · 125MiB/s 기본 포함) | 30GiB = **$2.74** | [EBS 요금](https://aws.amazon.com/ebs/pricing/) |
| Elastic IP(공인 IPv4) | $0.005/시간 | × 730시간 = **$3.65**. 사용/미사용 모두 같은 단가 | [VPC 공인 IPv4 요금](https://aws.amazon.com/vpc/pricing/) |
| S3 Standard | $0.025/GB-월(첫 50TB) + 요청 | 백업 7일 · 릴리스 30일이라 $0.1 미만 예상(실측 전) | [S3 요금](https://aws.amazon.com/s3/pricing/) |
| ECR | $0.10/GB-월 | 최근 30개는 용량 상한이 아니며 공유 레이어라 수 GB — 약 $0.3 예상(실측 전) | [ECR 요금](https://aws.amazon.com/ecr/pricing/) |

합계는 약 **$22/월**(세금 제외 — 한국 청구지 부가세 10%는 별도)이다. 인터넷으로 나가는 전송 · 도메인 갱신 · OpenAI · Vercel 요금제 조건은 별도로 확인한다. 월 예산 알림([계정 준비](#처음-구축-순서))은 이 합계 위에 여유를 둔 금액으로 건다. 실제 운영 사용량이 생기면 Billing/Cost Explorer와 이 추정치를 비교한다.

## 서버 구성

서울 리전(`ap-northeast-2`) EC2 한 대(arm64 · 2GB · Amazon Linux 2023)에서 Docker Compose 프로젝트 **`mogum`** 하나를 실행한다. 화면은 Vercel에 둔다. 서버 접속과 명령 실행은 SSM을 사용한다(ADR-072). 처음에는 위 [구축 순서](#처음-구축-순서)와 [운영 확인 목록](#운영-확인-목록)을 따른다.

| 위치 | 용도 |
|---|---|
| `/opt/mogum/releases/{전체 40자 소문자 SHA}/` | S3 릴리스 묶음을 푸는 디렉터리. `deploy/`와 `app-image.ref`를 포함한다 |
| `/opt/mogum/releases/{SHA}/app-image.ref` | 한 줄의 `<계정 ID>.dkr.ecr.ap-northeast-2.amazonaws.com/mogum-app@sha256:<64자 소문자 hex>`. 태그로 실행하지 않는다 |
| `/opt/mogum/current` | 마지막으로 헬스를 통과한 릴리스의 심볼릭 링크 |
| `/etc/mogum/mogum.env` | root 소유 · 권한 **600**인 서버 환경 변수 파일. 릴리스 밖에 두며 배포가 덮어쓰지 않는다. 이름과 형식은 [mogum.env.example](./mogum.env.example) 참고 |
| `/etc/mogum/caddy/Caddyfile` | 배포 시 해당 릴리스에서 복사한 Caddy 설정. 고정 디렉터리를 읽기 전용 마운트하고 Caddy에 reload한다 |

| 서비스 | 이미지 | 호스트 포트 | 메모리 상한(잠정) | 영속 볼륨 |
|---|---|---|---|---|
| `backend` | `APP_IMAGE` 지문 | `127.0.0.1:8080`만 | 1024MiB | 없음 |
| `postgres` | `pgvector/pgvector:pg17` | 없음 | 512MiB | `mogum_postgres_data` |
| `caddy` | 공식 `caddy:2` | 80 · 443(TCP) | 128MiB | `mogum_caddy_data`(인증서) · `mogum_caddy_config` |

합계는 1664MiB이고 나머지 약 380MiB는 OS · Docker · SSM 에이전트 몫이다. 스왑 없이 측정하며 `16-capacity`가 같은 [compose.yaml](./compose.yaml)로 재서 상한을 확정한다. 앱 힙은 이미지의 `-XX:MaxRAMPercentage=75`로 컨테이너 상한의 75%를 사용한다. `backend`는 Postgres 준비 확인 후 `aws` 프로필로 기동하며, 종료 대기는 **150초**로 Spring의 130초보다 길다. 세 서비스 모두 `restart: always`로 재부팅과 비정상 종료에 대응한다.

환경 변수는 Compose의 `--env-file /etc/mogum/mogum.env`로 읽고 서비스마다 필요한 이름만 전달한다. `API_DOMAIN`은 Caddy에만, Google 비밀은 앱에만, DB 비밀번호는 앱과 Postgres에만 전달한다. `APP_IMAGE`는 `deploy.sh`가 지문 파일을 검증한 뒤 지정한다. DB와 인증서는 이름 있는 볼륨에 보존하며, 고정된 프로젝트 이름과 Caddy 설정 경로 덕분에 릴리스 디렉터리가 바뀌어도 정의가 같은 Postgres와 Caddy는 재생성되지 않는다.
이 변경을 처음 배포할 때는 세 서비스의 정의가 바뀌어 한 번 다시 만들어진다. 데이터 · 인증서는 볼륨에 남고, 그 직전까지의 json-file 로그는 그때 사라진다.

세 서비스의 로그는 Docker journald 드라이버로 서버 journald에 모은다. `deploy/journald/mogum.conf`를 `/etc/systemd/journald.conf.d/mogum.conf`에 설치하며, `Storage=persistent`로 디렉터리 존재와 무관하게 디스크에 보관한다. 보관 상한은 **총 14일 · 500MB 중 먼저 닿는 쪽**이다: `MaxRetentionSec=13day` + 파일 하나의 최대 기간 `MaxFileSec=1day` = **14일**, 크기는 `SystemMaxUse=500M`이다. 서버 전체 저널(백업 서비스 등)에도 적용된다. 헬스 통과 · `current` 갱신 뒤 파일이 바뀌었을 때만 설치하고 `systemctl restart systemd-journald`한다. systemd 258 미만은 SIGHUP으로 설정을 다시 읽지 못하므로 restart를 사용하며 기존 스트림 연결은 유지된다([journald 설정](https://www.freedesktop.org/software/systemd/man/latest/journald.conf.html) · [서비스 문서](https://www.freedesktop.org/software/systemd/man/latest/systemd-journald.service.html)). Postgres는 `log_error_verbosity=terse`로 실행해 오류의 `DETAIL` · `HINT` · `QUERY` · `CONTEXT`에 이메일 등 실제 값이 남지 않게 한다([Postgres 17 문서](https://www.postgresql.org/docs/17/runtime-config-logging.html)). Caddy 접근 로그는 켜지 않는다.

SSM은 릴리스 묶음을 푼 뒤 root로 해당 릴리스의 `deploy/deploy.sh {SHA}`를 실행한다. 스크립트는 다음 순서로 동작한다.

1. SHA · 필요한 명령 · 서버 env 파일 소유권/권한 · 이미지 지문을 검사한다.
2. 인스턴스 역할 자격으로 ECR에 로그인하고 지문으로 이미지를 받는다. 서버에서 빌드하지 않는다.
3. 고정 경로에 Caddyfile을 복사하고 해당 릴리스의 Compose로 `up -d --no-build`한다. Caddy 설정을 reload하며 첫 기동 준비는 최대 10회(1초 간격) 재확인한다.
4. DNS · HTTPS와 무관한 `http://127.0.0.1:8080/actuator/health`가 **200**이 될 때까지 최대 **180초** 기다린다.
5. 성공하면 `current` 링크를 교체한다. 이 릴리스의 `deploy/systemd/mogum-backup.service` · `mogum-backup.timer`를 `/etc/systemd/system/`과 비교해 다른 파일만 권한 644로 복사하고, 변경이 있을 때만 `systemctl daemon-reload`와 `systemctl enable --now mogum-backup.timer`를 실행한다. 이어 journald 설정을 `cmp`로 비교해 다를 때만 디렉터리를 만들고 권한 644로 설치한 뒤 journald를 restart한다. 각각 같으면 설치와 해당 systemctl 호출을 생략한다. 설정 설치나 systemctl 실패는 오류로 종료하며 갱신된 `current`는 유지한다. 헬스 실패로 복귀하거나 링크 갱신에 실패한 경로에서는 두 설정 모두 설치하지 않는다. 이어서 릴리스를 현재 · 직전을 포함해 최근 **3개**로 정리한다. 현재 · 직전 이미지 ID를 보호하고 나머지 이미지는 강제 삭제 없이 정리한다. 정리 실패는 정상 기동한 앱을 되돌리지 않고 결과만 알린다.
6. 실패하면 기존 `current`의 Compose · 지문 · Caddyfile로 복귀하고 같은 헬스 확인을 한다. 복귀에는 `--pull never`를 사용하므로 ECR 없이 로컬 이미지로 실행한다. 복귀 성공도 배포 자체는 **실패 코드**로 끝낸다. 첫 배포 실패는 링크를 만들지 않고 실패로 끝낸다.

Flyway 마이그레이션은 되돌리지 않는다. 직전 앱이 새 스키마에서 기동할 수 있는지는 마이그레이션마다 확인해야 한다. 이 배포는 앱을 하나만 실행하므로 배포 중 중단이 있다(ADR-061). 단계 이름과 결과만 표준 출력으로 내보내며 비밀 · 환경 변수 · env 파일 내용 · 로그인 토큰을 출력하지 않는다. 별도의 배포 로그 파일은 만들지 않는다.

서버에 필요한 명령은 **Bash**, **Docker Engine**, **Docker Compose 플러그인**(`docker compose`), **AWS CLI v2**(`aws`), **curl**, **systemd**(`systemctl`), 그리고 Amazon Linux 2023 기본 도구인 `cat` · `stat` · `install` · `readlink` · `ln` · `mv` · `sort` · `rm` · `sleep` · `id` · `mktemp` · `date` · `wc`(GNU coreutils), `cmp`(GNU diffutils), `find`(GNU findutils)다. Docker 데몬은 부팅 시 자동 시작하도록 활성화해야 한다. 설치와 초기화는 [처음 구축 순서](#처음-구축-순서)의 4번을 따른다. 스크립트는 AWS 키를 받지 않고 인스턴스 역할을 사용한다.

배포 파일 불변식과 명령 대체를 통한 성공/실패 경로는 저장소 루트에서 `./backend/gradlew -p backend test`로 검사한다. 실제 EC2 · ECR · 운영 도메인에서의 기동은 2026-10-26 첫 배포로 확인했다. 헬스 실패 시 자동 복귀는 아직 수행하지 않았다.

## 로그 보기

Session Manager에서 먼저 root 셸을 연다. 다음 블록과 나머지 명령은 따로 실행한다.

```bash
sudo -i
```

현재 컨테이너의 최근 로그는 `docker logs`로 본다. journald 드라이버에서도 동작한다([Docker 문서](https://docs.docker.com/engine/logging/drivers/journald/)).

```bash
docker logs --tail 100 mogum-backend-1
docker logs --tail 50 mogum-caddy-1
docker logs --tail 50 mogum-postgres-1
```

지난 배포를 포함한 로그는 시작 때의 컨테이너 이름(`CONTAINER_NAME`)으로 조회한다. 한국 시간으로 보고 날짜를 넣으려면 `TZ=Asia/Seoul`을 지정한다. 아래 날짜는 조회할 기간으로 바꾼다.

```bash
TZ=Asia/Seoul journalctl CONTAINER_NAME=mogum-backend-1 \
  --since '2026-10-27 09:00:00' --output short-iso --no-pager
```

요청 하나는 응답 헤더 `X-Request-Id`의 값을 `requestId=…`에, 회원 하나는 내부 회원 ID를 `userId=…`에 넣어 찾는다. 아래 ID는 꾸민 예시이며 회원 ID 뒤의 경계까지 검사해 다른 회원과 섞이지 않게 한다.

```bash
TZ=Asia/Seoul journalctl CONTAINER_NAME=mogum-backend-1 --since '2 days ago' --no-pager \
  | grep -F 'requestId=123e4567-e89b-42d3-a456-426614174000'
TZ=Asia/Seoul journalctl CONTAINER_NAME=mogum-backend-1 --since '2 days ago' --no-pager \
  | grep -E 'userId=42([,[:space:]]|$)'
journalctl --disk-usage
```

출력에는 내부 회원 ID 등 운영 데이터가 있으므로 로그를 공개 저장소 · 이슈 · PR에 붙이지 않는다. 서버에서 필요한 범위만 확인한다.

## AWS 설정

`2-deploy` step 4의 산출물은 아래 **JSON 견본**과 불변식 테스트다. 실제 AWS API 호출이나 적용은 수행하지 않았다. 운영자가 기존 관리자 세션의 임시 자격으로 설정하며, 배포 역할과 인스턴스 역할에 자원 생성/설정 권한을 추가하지 않는다. 처음 구축의 전체 순서는 [처음 구축 순서](#처음-구축-순서)에 있다. 리전은 **`ap-northeast-2`**로 고정한다.

### 견본과 자리표시

| 파일 | 적용 위치와 용도 |
|---|---|
| [github-oidc-trust.json](./aws/github-oidc-trust.json) | `mogum-deploy` 역할의 신뢰 정책. 계정의 GitHub OIDC 공급자에서 STS audience와 저장소 `main` sub가 정확히 일치할 때만 수임 |
| [deploy-policy.json](./aws/deploy-policy.json) | `mogum-deploy`의 인라인 권한 정책 `mogum-deploy-access`. `mogum-app` push/존재·지문 조회, `releases/*` 읽기/쓰기, 운영 인스턴스 한 대의 지정 SSM 문서 실행과 결과 조회 |
| [ec2-trust.json](./aws/ec2-trust.json) | `mogum-instance` 역할의 신뢰 정책. EC2 서비스만 수임 |
| [instance-policy.json](./aws/instance-policy.json) | `mogum-instance`의 인라인 권한 정책 `mogum-instance-access`. ECR pull, `releases/*` 읽기, `backups/*` 읽기/쓰기, `backups/` 목록 조회 |
| [ecr-lifecycle.json](./aws/ecr-lifecycle.json) | ECR `mogum-app` 수명주기. 태그 유무와 무관하게 최근 이미지 30개 보존 |
| [s3-lifecycle.json](./aws/s3-lifecycle.json) | 같은 S3 버킷의 `backups/`와 `releases/`에 적용하는 규칙 두 개 |

견본 여섯 개를 **저장소 밖 로컬 디렉터리**에 복사한 뒤 편집기의 전체 바꾸기로 다음 문자열을 치환한다. 실제 값을 넣은 사본은 커밋하지 않는다. 치환 뒤 JSON 파싱과 미치환 자리표시가 없는지 확인하고 콘솔에 붙여 넣거나 아래 CLI의 파일 입력으로 사용한다.

| 자리표시 | 치환할 값 |
|---|---|
| `ACCOUNT_ID` | 자원을 만드는 AWS 계정의 12자리 ID(모든 ARN에서 동일) |
| `BUCKET_NAME` | 서울 리전에 만들 전용 S3 버킷의 전역 고유 이름(`s3://` 없이). 서버 `MOGUM_BUCKET`에도 동일한 값 |
| `INSTANCE_ID` | 운영 EC2 한 대의 `i-`로 시작하는 인스턴스 ID |

리전 · 저장소 `mogum-app` · GitHub 저장소명 · OIDC 조건은 바꾸지 않는다. AWS 소유 SSM 문서 ARN의 계정 칸은 **비어 있는 것이 맞다**. `ACCOUNT_ID`를 여기에 추가하지 않는다.

### 만들 자원과 연결

아래는 자원별 설정 참조다. **생성 순서는 [처음 구축 순서](#처음-구축-순서) 2번을 따른다**(EC2 생성 후 얻은 인스턴스 ID로 배포 역할을 완성한다). 기존 자원이 있으면 같은 설정인지 확인한다.

1. **IAM OIDC 공급자** — URL `https://token.actions.githubusercontent.com`, audience `sts.amazonaws.com`. 배포 역할 신뢰 정책의 공급자 ARN은 이 계정의 공급자를 가리킨다.
2. **배포 역할 `mogum-deploy`** — `github-oidc-trust.json`과 `deploy-policy.json`을 각각 신뢰/인라인 정책에 적용한다. GitHub Actions가 이 역할 ARN으로 OIDC 임시 자격을 받는다. `aud`와 `sub`의 연산자는 `StringEquals`, sub는 **`repo:f-lab-edu@54677968/Mogum@1378404194:ref:refs/heads/main`** 그대로다. 이 저장소는 불변 subject를 쓰므로 조직 · 저장소 ID가 들어가며, 이름이 같아도 ID가 다른 저장소는 신뢰하지 않는다. `gh api repos/f-lab-edu/Mogum/actions/oidc/customization/sub`의 `sub_claim_prefix` 뒤에 `:ref:refs/heads/main`을 붙여 형식을 확인한다. `main` push와 `main`에서의 `workflow_dispatch`만 사용하며 워크플로에 GitHub `environment`를 지정하지 않는다(environment를 쓰면 sub 끝이 `environment:…`로 바뀐다).
3. **인스턴스 역할 `mogum-instance`** — `ec2-trust.json`과 `instance-policy.json`을 적용하고, AWS 관리형 정책 **`arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore`**를 추가로 연결한다. 관리형 정책은 파일로 복제하지 않는다. 인스턴스 프로파일 `mogum-instance`에 이 역할을 넣어 EC2에 연결한다(EC2용 역할을 콘솔에서 만들면 프로파일도 생성된다). 다른 권한 정책을 추가하지 않는다.
4. **ECR 비공개 저장소 `mogum-app`** — 태그 변경 불가 **`IMMUTABLE`**, 예외 없음. 이미지는 전체 40자 소문자 커밋 SHA 태그로 올리고 실행은 지문을 쓴다. `ecr-lifecycle.json`을 수명주기에 적용한다.
5. **S3 범용 버킷 `BUCKET_NAME`** — 전용 버킷 하나에 **퍼블릭 액세스 차단 네 항목 모두 활성화**, 객체 소유권은 **Bucket owner enforced(ACL 비활성화)**, 기본 암호화는 **SSE-S3(AES-256)**, **버전 관리 Enabled**. `s3-lifecycle.json` 전체를 적용한다. KMS 키는 사용하지 않으므로 두 역할에 KMS 권한이 필요하지 않다.
6. **운영 EC2 한 대** — 서울 · arm64 · `t4g.small` · Amazon Linux 2023, 인스턴스 프로파일 `mogum-instance`. SSM Agent가 실행되어 Systems Manager의 관리형 노드에 Online으로 보여야 한다. 보안 그룹은 웹 TCP 80/443만 열고 SSH와 Postgres는 열지 않는다. ECR/S3/SSM으로 나가는 HTTPS 통신이 가능해야 한다. 생성 후 나온 ID로 정책 사본의 `INSTANCE_ID`를 치환한다.

역할에 붙인 두 권한 정책에는 이미지/객체/버전 삭제나 저장소·버킷 정책/수명주기/버전 관리 변경이 없다. 배포 역할은 `backups/`에 접근하지 못하고, 서버 역할은 이미지를 올리거나 `releases/`에 쓰지 못한다. ECR 인증 `GetAuthorizationToken`과 SSM 결과 `GetCommandInvocation`만 자원 `*`를 쓴다. [SSM 권한 표](https://docs.aws.amazon.com/service-authorization/latest/reference/list_ssm.html)에서 결과 조회는 자원별 제한을 지원하지 않는다. 명령 전송은 AWS 소유 `AWS-RunShellScript` 문서와 운영 EC2 ARN 두 개로 제한한다.

`aws s3 cp`의 multipart 업로드 시작/조각 전송/완료는 `s3:PutObject`를 사용하며, 실패한 미완료 업로드를 취소하는 `s3:AbortMultipartUpload`도 쓰기 접두사에만 허용한다. 완성된 객체/버전 삭제 권한은 아니다([S3 multipart 권한](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html)). `restore.sh latest`의 `ListObjectsV2`는 버킷 ARN과 `StringEquals: s3:prefix=backups/`로 제한하며 전체 버킷/버전 목록 권한은 주지 않는다. SSM 에이전트 통신 권한은 위 AWS 관리형 정책이 담당한다.

### AWS CLI v2 적용 예

아래 명령은 **운영자가 적용할 예시이며 이 step에서 실행하지 않았다**. 저장소 루트에서 실행하되 `BUCKET_NAME`과 `file://` 뒤 경로를 치환한 사본의 경로로 바꾼다. 수명주기 적용은 기존 구성을 교체하므로 전용 저장소/버킷인지 먼저 확인한다. 명령 형식은 [ECR 생성](https://docs.aws.amazon.com/cli/latest/reference/ecr/create-repository.html) · [ECR 수명주기 적용](https://docs.aws.amazon.com/cli/latest/reference/ecr/put-lifecycle-policy.html) · [S3 수명주기 적용](https://docs.aws.amazon.com/cli/latest/reference/s3api/put-bucket-lifecycle-configuration.html)의 공식 CLI v2 문서와 대조했다.

```bash
aws ecr create-repository --region ap-northeast-2 \
  --repository-name mogum-app --image-tag-mutability IMMUTABLE
aws ecr put-lifecycle-policy --region ap-northeast-2 \
  --repository-name mogum-app \
  --lifecycle-policy-text file:///LOCAL/RENDERED/ecr-lifecycle.json
aws s3api put-bucket-lifecycle-configuration --region ap-northeast-2 \
  --bucket BUCKET_NAME \
  --lifecycle-configuration file:///LOCAL/RENDERED/s3-lifecycle.json
```

### 보관과 롤백 범위

백업의 설정상 보관은 **현재 6일 + 이전 1일 = 총 7일**이며 각각을 7일로 바꾸지 않는다. 현재 객체 만료 시 삭제 표시가 생기고 이전 버전이 된 덤프는 1일 뒤 영구 삭제 대상이 된다. `releases/`는 현재 30일 · 이전 1일이다. 두 접두사 모두 미완료 multipart 업로드는 시작 1일 뒤 정리한다. 이전 버전 개수 보존 조건을 추가하지 않는다.

삭제 표시는 `Expiration.Days`의 만료 동작으로 이전 버전이 모두 없어진 뒤 자동 정리된다([S3 삭제 표시 정리](https://docs.aws.amazon.com/AmazonS3/latest/userguide/ManagingDelMarkers.html)). `Expiration`에 `Days`와 `ExpiredObjectDeleteMarker`를 함께 넣을 수 없으므로 견본에는 `Days`만 사용한다([S3 수명주기 예제](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-configuration-examples.html#lifecycle-config-conceptual-ex6)). 따라서 규칙 두 개로 만료 · 이전 버전 삭제 · 삭제 표시 정리 · 미완료 업로드 정리를 모두 수행한다.

**S3 수명주기 삭제는 비동기다.** 일 단위 만료 계산과 처리 지연 때문에 정확히 업로드 168시간 안에 물리 삭제된다는 보장은 아니다. 총 7일은 설정한 현재/이전 일수의 합이며, 실제 삭제는 늦어질 수 있다([S3 만료 동작](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-expire-general-considerations.html)).

원격 수동 재배포 범위는 **ECR 최근 이미지 30개와 S3 릴리스 현재 객체 30일 중 먼저 끝나는 쪽**이다. ECR은 일수가 아니라 이미지 수 기준이다. S3 이전 버전을 읽는 권한은 주지 않는다. 서버의 자동 실패 복귀는 앞 절처럼 로컬 직전 이미지와 릴리스로 수행하며, 로컬 릴리스는 현재/직전 포함 최근 3개다. 어떤 복귀도 Flyway를 되돌리지 않는다.

### AWS 검증 범위

JSON 파싱과 권한 범위는 `AwsFilesTest`로 검증했으며 실제 IAM 평가 · AWS 자원 적용은 검증하지 않았다. CLI 예제의 명령/옵션 형식은 위 공식 문서로 확인했다. 실제 확인 방법과 틀렸을 때 고칠 곳은 [첫 배포 때 확인](#첫-배포-때-확인) 6 · 8~11 · 16번에 모았다.

## GitHub Actions 배포

[deploy.yml](../.github/workflows/deploy.yml)의 `Deploy backend`는 `main` push 중 **`backend/**` · `deploy/**` · `.github/workflows/deploy.yml`** 중 하나가 바뀐 경우에 실행한다. 문서나 화면만 바뀌면 서버를 재시작하지 않는다. `workflow_dispatch`는 경로와 무관하게 실행하며 `main`만 허용한다. 단일 `mogum-backend-deploy` 동시 실행 그룹에서 진행 중인 배포를 취소하지 않는다. GitHub 동시 실행 그룹의 대기 실행은 새 실행으로 교체될 수 있으므로 모든 중간 커밋의 순차 배포를 보장하지는 않는다([동시 실행 규칙](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)). 화면은 별도 Vercel Git 연동으로 배포한다.

저장소 **Settings → Secrets and variables → Actions**의 **Variables 3개**와 **Secrets 3개**에 아래 값을 나누어 등록한다. 공개 Actions 로그에서 역할 ARN · 버킷 이름 · 인스턴스 ID를 가리기 위해 식별자는 Secrets로 받는다. 역할 ARN에서 추출한 계정 ID도 첫 검사 단계에서 출력 전에 `::add-mask::`로 등록하고 `configure-aws-credentials`의 `mask-aws-account-id`를 켜서 레지스트리 주소와 AWS 출력에서도 가린다. AWS 장기 키 · Google 비밀은 등록하지 않는다. `DEPLOY_ENABLED`가 정확히 `true`일 때만 작업이 실행된다. 처음 AWS를 준비하는 동안에는 미설정 또는 `false`로 두고, 서버 env · AWS · DNS 설정이 끝난 뒤 `true`로 켠다. 끄면 다음 실행부터 건너뛰며 진행 중인 배포는 취소하지 않는다.

**Variables 3개**

| 이름 | 값 예 | 어디서 얻나 |
|---|---|---|
| `DEPLOY_ENABLED` | `true` / `false` | 운영자가 배포 활성화 여부를 정한다 |
| `AWS_REGION` | `ap-northeast-2` | 위 AWS 설정에서 정한 서울 리전 |
| `API_ORIGIN` | `https://api.{도메인}` | DNS · Caddy에 설정한 공개 API 출처. 끝 `/` 없이 입력 |

**Secrets 3개**

| 이름 | 값 예 | 어디서 얻나 |
|---|---|---|
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::<계정 ID>:role/mogum-deploy` | IAM의 `mogum-deploy` 역할 상세 ARN |
| `DEPLOY_BUCKET` | `<전용 버킷 이름>` | S3 버킷 이름. `s3://` 없이 서버 `MOGUM_BUCKET`과 같은 값 |
| `DEPLOY_INSTANCE_ID` | `i-<인스턴스 ID>` | EC2 인스턴스 상세 ID. 배포 정책의 대상 한 대 |

활성화된 작업은 빌드나 AWS 호출에 앞서 필수 Variables · Secrets의 빈 값을 검사하며, 비었으면 **종류(variable/secret)와 이름만** 출력하고 실패한다. 브랜치 · SHA · 리전 · 식별자 형식도 먼저 검사한다. 자격은 [configure-aws-credentials@v5.1.1](https://github.com/aws-actions/configure-aws-credentials/tree/v5.1.1)의 OIDC 임시 자격만 사용하며 워크플로 권한은 `contents: read` · `id-token: write`다. GitHub `environment`는 지정하지 않는다. 지정하면 OIDC sub 끝이 `environment:…`로 바뀌어 불변 subject의 `:ref:refs/heads/main`으로 끝나는 정확 일치 신뢰 조건과 맞지 않는다.

세 액션은 현재 사용 중인 v5 릴리스의 전체 40자 커밋 SHA로 고정하고 줄 끝 주석에 릴리스를 남긴다. 태그는 다른 커밋으로 옮겨질 수 있고 이 워크플로는 AWS 배포 자격을 받으므로, 자격을 받은 뒤 실행되는 액션까지 고정한다. 버전을 올릴 때는 새 릴리스 태그의 커밋 SHA를 확인해 워크플로 SHA · 릴리스 주석 · `WorkflowFilesTest` 기대값을 함께 바꾼다.

일반 push와 `sha` 없는 수동 실행은 다음 순서다.

1. 서버와 같은 **`ubuntu-24.04-arm`** 작업에서 ECR `mogum-app:{전체 40자 소문자 SHA}`를 조회한다. 있으면 이전 실행이 검사 후 올린 이미지이므로 빌드 · 이미지 검사 · push를 건너뛰고 지문을 재사용한다. 태그가 없다는 응답만 새 빌드로 진행하며 권한/통신 오류는 실패한다. 덮어쓰기 금지 태그를 다시 push하지 않는다.
2. 새 이미지가 필요하면 [checkout@v5.1.0](https://github.com/actions/checkout/tree/v5.1.0) · [setup-java@v5.7.0](https://github.com/actions/setup-java/tree/v5.7.0)(Temurin 21)를 사용해 `./backend/gradlew -p backend build`로 테스트와 빌드를 수행한다. Docker로 `backend/` 이미지를 **한 번** 빌드한 뒤 `./backend/gradlew -p backend imageTest -PappImage=<그 이미지>`로 검사한다. Testcontainers는 러너의 Docker를 사용한다.
3. 검사한 로컬 이미지를 ECR의 전체 SHA 태그로 바꿔 올리고 `DescribeImages`로 지문을 조회한다. 다시 빌드하지 않는다.
4. 해당 커밋의 `deploy/`와 지문 한 줄인 `app-image.ref`를 묶어 `s3://{DEPLOY_BUCKET}/releases/{SHA}.tar.gz`에 업로드한다. 같은 SHA 재실행은 동일 커밋의 배포 파일과 기존 지문으로 묶음을 다시 올릴 수 있어 이미지 push 후 업로드가 실패한 경우에도 재개할 수 있다.
5. SSM `AWS-RunShellScript`로 지정한 인스턴스 한 대에서 root로 묶음을 받는다. `/opt/mogum/releases/` 아래 임시 디렉터리에서 풀고 지문을 확인한 뒤 `/opt/mogum/releases/{SHA}/`로 옮겨 `bash deploy/deploy.sh {SHA}`를 실행한다. 같은 SHA의 디렉터리가 이미 있으면 지문을 비교하고 그대로 사용해 활성 디렉터리를 덮어쓰지 않는다. 임시 파일은 정리한다.
6. SSM 명령 ID를 출력하고 최대 **20분** 동안 결과를 조회한다(명령 전달 제한 60초 · 서버 실행 제한 1080초). 최초 `InvocationDoesNotExist`는 결과 반영 지연으로 재조회한다. 성공 상태와 종료 코드 0을 모두 확인하고 표준 출력 · 오류를 Actions 로그에 표시한다. 이어 공개 `{API_ORIGIN}/actuator/health`의 HTTPS 응답이 **200**인지 최대 **60초** 확인한다.

### 수동 롤백 · 실패 확인

Actions → **Deploy backend** → **Run workflow**에서 브랜치 `main`을 선택하고 `sha`에 이전 릴리스의 **전체 40자 소문자 SHA**를 넣는다. 비워 두면 현재 커밋의 일반 빌드/배포 경로다. SHA가 있으면 checkout · Java 설정 · 빌드 · 검사 · 업로드 없이, 해당 SHA의 ECR 이미지와 S3 기존 묶음을 먼저 조회하고 묶음의 지문까지 일치하는지 확인한다. 이미지/묶음이 없거나 읽을 수 없으면 어느 쪽인지 출력하고 SSM을 보내지 않는다. 보관 범위는 **S3 현재 릴리스 30일과 ECR 최근 이미지 30개 중 먼저 끝나는 쪽**이다([상세](#보관과-롤백-범위)). `Flyway` 마이그레이션은 되돌리지 않으므로 이전 앱과 현재 스키마의 호환성을 먼저 확인한다.

OIDC 단계가 `Not authorized to perform sts:AssumeRoleWithWebIdentity`로 실패하면 배포 리전(`ap-northeast-2`)의 CloudTrail 이벤트 기록에서 이벤트 이름 `AssumeRoleWithWebIdentity`의 실패 이벤트를 연다. `userIdentity.principalId` 끝의 실제 `sub`를 역할 신뢰 정책의 `token.actions.githubusercontent.com:sub`와 비교한다.

실패하면 워크플로의 **Deploy through SSM and print the command result** 단계에서 명령 ID · 상태 · stdout · stderr를 본다. `deploy.sh`가 로컬 헬스 실패로 직전 앱에 복귀했어도 워크플로는 실패한다. 결과 조회 상한을 넘기거나 조회 자체가 실패하면 로그의 명령 ID로 Systems Manager Run Command 상태를 확인한 뒤 재배포한다. 공개 헬스만 실패하면 DNS · HTTPS · Caddy를 확인한다. 이 경우 로컬 헬스를 통과한 서버의 `current`는 유지된다. 로그에 비밀/env 파일을 추가 출력하거나 `set -x`를 켜지 않는다.

`deploy.sh`는 공개 로그로 env 값이 새지 않도록 Compose · pull 출력을 버리고 단계 이름만 출력한다. 마지막으로 찍힌 단계(`ECR login.` · `Pull image by digest.` · `Apply release.` · `Wait for local health …`)가 실패한 곳이다. 자세한 원인은 Session Manager(비공개)에서 본다. 출력에는 이메일 같은 운영 데이터가 섞일 수 있으니 공개 저장소 · 이슈 · PR에 붙이지 않는다.

```bash
sudo -i
docker ps -a --filter label=com.docker.compose.project=mogum --format '{{.Names}}\t{{.Status}}'
docker logs --tail 100 mogum-backend-1
docker logs --tail 50 mogum-caddy-1
docker logs --tail 50 mogum-postgres-1
```

지난 배포의 로그는 [로그 보기](#로그-보기)의 `journalctl`로 조회한다.

`ECR login.` · `Pull image by digest.`에서 멈췄으면 인스턴스 역할과 외부 통신([첫 배포 때 확인](#첫-배포-때-확인) 6 · 11), `Apply release.`면 env 값과 Compose 해석(5), 헬스 대기면 위 backend 로그를 본다.

### 워크플로 검증 범위

불변식과 명령 대체 테스트는 `WorkflowFilesTest`로 실행한다. 액션의 릴리스 · 커밋 SHA와 입력은 공식 저장소에서 확인했다(SHA는 2026-10-26 GitHub API로 각 `v5` 태그가 가리키는 커밋으로 확인). `WorkflowFilesTest`는 고정 SHA · 릴리스 주석과 주요 입력을 검사한다. [GitHub 러너 표](https://docs.github.com/en/actions/reference/runners/github-hosted-runners#standard-github-hosted-runners-for-public-repositories)에서 `ubuntu-24.04-arm`의 공개 저장소 무료 제공을 확인했고, [arm64 이미지 도구 목록](https://github.com/actions/partner-runner-images/blob/main/images/arm-ubuntu-24-image.md)에서 Docker · AWS CLI · jq · curl을 확인했다. 스크립트는 Bash · GNU 기본 도구 · tar · grep도 사용하며 `rg`에 의존하지 않는다. SSM 옵션/결과 필드는 [send-command](https://docs.aws.amazon.com/cli/latest/reference/ssm/send-command.html) · [get-command-invocation](https://docs.aws.amazon.com/cli/latest/reference/ssm/get-command-invocation.html) 공식 문서와 대조했다. 실제 GitHub Actions · AWS 실행은 아직 수행하지 않았다. 실제 실행 확인은 [첫 배포 때 확인](#첫-배포-때-확인) 1~3 · 8~13번에 모았다.

## Vercel(화면)

프로젝트는 **2026-10-25에 Hobby 팀에서 이미 만들었다.** 다시 만들 때도 Vercel의 Git 가져오기에서 이 GitHub 저장소 **`f-lab-edu/Mogum`**을 연결하고 다음 값을 사용한다. 운영 브랜치는 **`main`**이며 병합마다 Vercel Git 연동이 화면을 빌드 · 배포한다(ADR-060 · ADR-072).

| 프로젝트 설정 | 값 |
|---|---|
| Root Directory | `frontend` |
| Framework Preset | `Vite` |
| Build Command | 덮어쓰기를 켜고 **`npm run build`** — `tsc --noEmit` 타입 검사 후 Vite 빌드 |
| Output Directory | `dist` — Vite 감지 기본값 유지 |
| Production Branch | `main` |

환경 변수 **`VITE_API_BASE_URL=https://api.{도메인}`**은 **Production에만** 등록하고 운영 배포를 다시 빌드한다. 브라우저는 이 출처의 `/api/*`와 `/auth/*`로 직접 요청한다. PR 미리보기(`*.vercel.app`)는 API와 다른 사이트라 세션 쿠키가 실리지 않으므로 로그인 확인에 사용하지 않는다([05 §1.1](../docs/05-api.md#11-인증--구글-oauth--쿠키-세션db-저장)).

프로젝트의 Domains에 **`{도메인}`**을 연결한다. Vercel이 안내하는 DNS 레코드 종류 · 이름 · 값을 Cloudflare에 그대로 등록하고 **프록시를 끈다(DNS only)**. Vercel이 인증서를 직접 받도록 앞에 프록시를 두지 않는다.

[frontend/vercel.json](../frontend/vercel.json)은 전체 경로를 `/index.html`로 보내는 rewrite 하나만 둔다. Vercel은 정적 파일을 먼저 찾으므로 `index.html` · `assets/…` 등 존재하는 빌드 파일은 그대로 제공하고, 없는 주소만 주소창 경로를 유지한 채 React Router로 처리한다([Vercel rewrite 우선순위](https://vercel.com/docs/project-configuration/vercel-json#rewrites) · [Vite SPA 설정](https://vercel.com/docs/frameworks/frontend/vite#using-vite-to-make-spas)). 빌드 명령 · 출력 디렉터리는 이 파일에 넣지 않는다. API 프록시 · redirect · 함수 · SSR 없이 정적 SPA로 배포한다.

### 요금제 확인 상태

**Hobby에서 조직 소유 저장소의 Git 연동은 확인했다** — 2026-10-25에 Hobby 팀으로 `f-lab-edu/Mogum`을 가져와 `main` 빌드 · 배포에 성공했다. **Hobby의 비상업 조건은 아직 확인하지 않았다.** 조직 저장소 연동 성공과 약관 적합성 확인은 별개다. 서비스 적합성과 운영 도메인 검증은 [첫 배포 때 확인](#첫-배포-때-확인) 17~18번과 [운영 확인 목록](#운영-확인-목록)에 모았다.

## 백업 · 복원

`mogum-backup.timer`가 매일 **한국 시각 04:00**에 `mogum-backup.service`(root · oneshot)를 실행한다. 일정은 서버 시간대와 무관한 `OnCalendar=*-*-* 19:00:00 UTC`이며 `Persistent=true`로 꺼져 있던 동안 놓친 실행은 켤 때 한 번 보충한다([systemd 시간 형식](https://freedesktop.org/software/systemd/man/latest/systemd.time.html) · [Persistent 규칙](https://www.freedesktop.org/software/systemd/man/252/systemd.timer.html)). 서비스는 `/etc/mogum/mogum.env`에서 환경을 받아 `/opt/mogum/current/deploy/backup.sh`를 실행한다. `MOGUM_BUCKET`에 백업과 릴리스가 공유하는 버킷 이름을 넣는다. 자격은 EC2 인스턴스 역할이며 키 파일은 두지 않는다.

백업은 Compose 프로젝트 `mogum` · 서비스 `postgres` 라벨로 실행 중 컨테이너를 찾는다. Compose 파일을 읽지 않으므로 `APP_IMAGE`가 없어도 실행한다. 재부팅 직후에는 5초 간격으로 최대 120초 준비를 기다린다. 컨테이너 안의 `pg_dump -Fc`로 운영 DB `mogum` 전체(스키마 · 데이터 · 시퀀스 · 확장)를 custom 형식으로 받아, 덤프가 성공하고 파일이 비어 있지 않을 때만 업로드한다. 호스트 임시 파일은 root 전용(`umask 077` · 600)이며 성공/실패 시 trap으로 지운다. 강제 전원 종료/SIGKILL은 trap을 실행할 수 없으므로 서버 재기동 후 남은 임시 파일 유무를 확인한다. 출력은 객체 경로와 바이트 크기 한 줄이다. 호스트에 PostgreSQL 도구를 설치하지 않는다.

객체 형식은 **`s3://${MOGUM_BUCKET}/backups/YYYYMMDDTHHMMSS+0900.dump`**(예: `backups/20261025T040000+0900.dump`)다. 키의 시각은 `TZ=Asia/Seoul`로 만든다. 배포 묶음은 같은 버킷의 `releases/`를 쓴다. 버전 관리를 켜고 **현재 버전 6일 + 이전 버전 1일, 합쳐 총 7일 상한**으로 보관한다(ADR-072). [AWS 설정](#aws-설정)의 수명주기 규칙과 IAM 정책 견본을 적용한다. 백업/연습 스크립트에는 S3 객체나 버전을 지우는 명령이 없으며 서버 역할에도 삭제 권한을 주지 않는다. 이 설정은 버킷에 적용한 뒤 첫 배포 때 확인해야 한다.

### 복원 연습

SSM에서 아래 명령을 실행한다. `systemd-run`은 서비스와 같은 서버 env 파일을 읽고 그 내용을 출력하지 않는다. `latest`는 `backups/`의 `.dump` 중 S3 `LastModified`가 가장 최근인 현재 객체를 고른다. [AWS CLI의 JSON 출력](https://docs.aws.amazon.com/cli/latest/userguide/cli-usage-output-format.html)을 사용해 전체 페이지를 모은 뒤 정렬한다. 특정 객체를 연습하려면 마지막 `latest`를 위 형식의 객체 키로 바꾼다. 동시에 여러 복원 연습을 실행하지 않는다.

```bash
sudo systemd-run --unit=mogum-restore-check --wait --pipe --collect \
  --property=Type=oneshot --property=EnvironmentFile=/etc/mogum/mogum.env \
  /opt/mogum/current/deploy/restore.sh latest
```

`restore.sh`는 백업을 root 전용 임시 파일로 받은 다음, 같은 Postgres 컨테이너의 **`mogum_restore_check`만** 지우고 다시 만들어 복원한다. 모든 사용자 스키마의 테이블 이름과 정확한 행 수만 출력한다. 운영 DB 이름을 받는 옵션은 없다. 성공 · 실패 시 연습 DB와 임시 파일을 정리하고, DB 정리가 실패해도 파일을 지우며 실패 코드와 메시지를 남긴다. 이때는 원인을 해결한 후 연습 DB가 남았는지 확인한다. 운영 데이터는 변경하지 않지만 디스크/CPU를 공유하므로 한가할 때 한 번 실행한다.

### 실제 재해 복구 — 사람이 수행

아래는 **운영 DB를 지우고 복원하는 명령**이다. 먼저 복구 시점 · 사용할 객체 · 그 이후의 탈퇴/삭제 목록을 확인한다. 연습 스크립트가 성공한 백업을 선택하며 복구 전에 생긴 최신 데이터는 잃는다(일일 백업 사이 최대 하루). 새 서버라면 같은 Postgres 이미지/사용자와 볼륨을 먼저 준비한다. 현재 릴리스는 선택한 백업 스키마에서 시작할 수 있어야 한다.

SSM의 root Bash(`sudo -i` 후 `bash`)에서 실행한다. 버킷/키 자리표시를 실제 선택값으로 바꾸되 저장소에 기록하지 않는다. 이 블록은 중간 명령이 실패하면 멈춘다. 실패 시 앱/타이머를 다시 켜지 말고 원인을 해결한다.

```bash
set -euo pipefail
umask 077
export MOGUM_BUCKET='버킷 이름'
key='backups/YYYYMMDDTHHMMSS+0900.dump'
export APP_IMAGE="$(cat /opt/mogum/current/app-image.ref)"
compose() {
  docker compose --project-name mogum --env-file /etc/mogum/mogum.env \
    -f /opt/mogum/current/deploy/compose.yaml "$@"
}
systemctl stop mogum-backup.timer mogum-backup.service
compose stop caddy backend
postgres=$(docker ps --filter label=com.docker.compose.project=mogum \
  --filter label=com.docker.compose.service=postgres --format '{{.ID}}')
[[ -n $postgres && $postgres != *$'\n'* ]]
dump=$(mktemp)
trap 'rm -f -- "$dump"' EXIT
aws s3 cp "s3://${MOGUM_BUCKET}/${key}" "$dump" --only-show-errors
[[ -s $dump ]]
docker exec -i "$postgres" pg_restore --list < "$dump" >/dev/null
docker exec "$postgres" sh -c 'dropdb -U "$POSTGRES_USER" --if-exists --force mogum'
docker exec "$postgres" sh -c 'createdb -U "$POSTGRES_USER" --template=template0 mogum'
docker exec -i "$postgres" sh -c \
  'pg_restore --exit-on-error --no-owner --no-privileges -U "$POSTGRES_USER" -d mogum' < "$dump"
rm -f -- "$dump"
trap - EXIT
```

**서비스 재개 전 탈퇴 · 삭제를 다시 적용한다.** 백업에 탈퇴/삭제 데이터가 최대 7일 남을 수 있다. [02 §7의 탈퇴 범위](../docs/02-domain-data.md#7-계정--정원--탈퇴)와 §6.2의 커리큘럼 완전 삭제 범위를 기준으로, 백업 시점부터 장애까지 접수한 탈퇴 · 삭제를 확인하고 같은 트랜잭션/잠금/원장 연결 해제 규칙으로 재적용한다. 자동 재적용 기능은 없다. 재적용을 확인하기 전에는 Caddy를 켜지 않는다. 복원으로 되살아난 로그인 세션도 모두 무효화한다.

```bash
docker exec "$postgres" sh -c \
  'psql -X --set=ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d mogum -c "DELETE FROM spring_session"'
# 위 탈퇴/삭제 재적용을 완료한 뒤 실행한다.
compose start backend
curl --fail --silent http://127.0.0.1:8080/actuator/health
# 아직 기동 중이면 200 UP이 될 때까지 기다리고 실패 원인을 확인한다.
compose start caddy
systemctl start mogum-backup.timer
```

### 백업 실패 확인

현재는 운영자가 systemd 상태와 저널을 확인한다. 별도 로그 파일은 만들지 않고 journald가 파일 회전 기간을 포함해 총 14일 · 500MB 중 먼저 닿는 쪽까지 보관한다([로그 보기](#로그-보기)). 슬랙 알림은 `10-cost-control`에서 연결한다.

```bash
sudo systemctl status mogum-backup.service mogum-backup.timer
sudo journalctl -u mogum-backup.service --since '2 days ago' --no-pager
sudo systemctl list-timers mogum-backup.timer --all
```

실제 AWS 백업 · 복원 연습과 Amazon Linux 2023 timer의 다음 실행(한국 04:00)은 2026-10-26 확인했다. `Persistent=true`의 놓친 실행 보충과 수명주기 만료 지연은 아직 확인하지 않았다. 확인 방법과 수정 위치는 [첫 배포 때 확인](#첫-배포-때-확인) 14~16번, 실행 명령과 복원 기록 칸은 [운영 확인 목록](#운영-확인-목록)에 모았다.
