#!/usr/bin/env bash
# root(SSM)로 실행한다. Flyway 마이그레이션은 되돌리지 않는다.
# 직전 앱이 새 스키마에서 기동할 수 있는지는 마이그레이션마다 확인해야 한다.
set -euo pipefail

fail() { printf '%s\n' "$1"; exit 1; }

[[ $# == 1 && $1 =~ ^[0-9a-f]{40}$ ]] || fail 'Invalid release SHA.'
for command in docker aws curl cat stat install readlink ln mv find sort rm sleep cmp systemctl timedatectl; do
    command -v "$command" >/dev/null 2>&1 || fail "Missing command: $command"
done
docker compose version >/dev/null 2>&1 || fail 'Missing command: docker compose'

releases=/opt/mogum/releases
current=/opt/mogum/current
env_file=/etc/mogum/mogum.env
caddy_dir=/etc/mogum/caddy
health_timeout=180
release="$releases/$1"
[[ -f $env_file && $(stat -c '%u:%a' "$env_file") == 0:600 ]] || fail 'Server env must be root-owned with mode 600.'

read_image() {
    local line image='' count=0
    [[ -f $1/app-image.ref ]] || return 1
    while IFS= read -r line || [[ -n $line ]]; do
        image=$line
        count=$((count + 1))
    done < "$1/app-image.ref"
    [[ $count == 1 && $image =~ ^[0-9]{12}\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/mogum-app@sha256:[0-9a-f]{64}$ ]] || return 1
    printf '%s' "$image"
}

image=$(read_image "$release") || fail 'Invalid release image reference.'
previous=''
previous_image=''
if [[ -L $current ]]; then
    previous=$(readlink -f "$current") || fail 'Invalid current release link.'
    [[ $previous == "$releases/${previous##*/}" && ${previous##*/} =~ ^[0-9a-f]{40}$ && -d $previous ]] || fail 'Invalid current release directory.'
    previous_image=$(read_image "$previous") || fail 'Invalid previous image reference.'
elif [[ -e $current ]]; then
    fail 'Current release must be a symbolic link.'
fi

compose() {
    local directory=$1 image_ref=$2
    shift 2
    APP_IMAGE="$image_ref" docker compose --project-name mogum --env-file "$env_file" \
        -f "$directory/deploy/compose.yaml" "$@"
}

apply_release() {
    local directory=$1 image_ref=$2 attempt
    shift 2
    install -d -m 755 "$caddy_dir" || return 1
    install -m 644 "$directory/deploy/Caddyfile" "$caddy_dir/Caddyfile" || return 1
    compose "$directory" "$image_ref" up -d --no-build "$@" >/dev/null 2>&1 || return 1
    # 고정 디렉터리를 마운트하므로 설정 파일이 바뀌어도 컨테이너를 재생성하지 않는다.
    # 첫 기동 직후에는 Caddy 관리 API가 열릴 때까지 잠깐 기다린다.
    for ((attempt = 0; attempt < 10; attempt++)); do
        if compose "$directory" "$image_ref" exec -T caddy caddy reload \
            --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

wait_for_health() {
    local deadline=$((SECONDS + health_timeout)) remaining status
    while (( SECONDS < deadline )); do
        remaining=$((deadline - SECONDS))
        status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
            --connect-timeout 2 --max-time "$remaining" http://127.0.0.1:8080/actuator/health 2>/dev/null) || status=000
        [[ $status == 200 ]] && return 0
        (( SECONDS >= deadline )) || sleep 1
    done
    return 1
}

deploy_release() {
    printf '%s\n' 'ECR login.'
    aws ecr get-login-password --region ap-northeast-2 2>/dev/null | \
        docker login --username AWS --password-stdin "${image%%/*}" >/dev/null 2>&1 || return 1
    printf '%s\n' 'Pull image by digest.'
    docker pull "$image" >/dev/null 2>&1 || return 1
    printf '%s\n' 'Apply release.'
    apply_release "$release" "$image" || return 1
    printf '%s\n' 'Wait for local health (maximum 180 seconds).'
    wait_for_health
}

if ! deploy_release; then
    printf '%s\n' 'Deployment failed.'
    if [[ -n $previous ]]; then
        printf '%s\n' 'Restore previous release using local images.'
        if apply_release "$previous" "$previous_image" --pull never && wait_for_health; then
            printf '%s\n' 'Rolled back to previous release.'
        else
            printf '%s\n' 'Rollback failed.'
        fi
    else
        printf '%s\n' 'No previous release to restore.'
    fi
    exit 1
fi

# 헬스 통과 뒤에만 링크를 바꾼다. 링크 갱신 실패도 이전 앱으로 복귀시킨다.
if ! { ln -sfn "$release" "$current.next" && mv -Tf "$current.next" "$current"; }; then
    printf '%s\n' 'Release link update failed.'
    if [[ -n $previous ]] && apply_release "$previous" "$previous_image" --pull never && wait_for_health; then
        printf '%s\n' 'Rolled back to previous release.'
    fi
    exit 1
fi
printf '%s\n' 'Deployment healthy; current release updated.'

# 사람이 읽는 서버 시각을 맞춘다. 컨테이너와 DB 세션의 시간대는 바꾸지 않는다.
timedatectl set-timezone Asia/Seoul

# 실패한 릴리스/복귀 경로에서는 설치하지 않는다. current를 먼저 바꿔 새 스크립트를 실행한다.
units_changed=false
for unit in mogum-backup.service mogum-backup.timer; do
    if ! cmp -s "$release/deploy/systemd/$unit" "/etc/systemd/system/$unit"; then
        install -m 644 "$release/deploy/systemd/$unit" "/etc/systemd/system/$unit"
        units_changed=true
    fi
done
if [[ $units_changed == true ]]; then
    systemctl daemon-reload
    systemctl enable --now mogum-backup.timer
fi
if ! cmp -s "$release/deploy/journald/mogum.conf" /etc/systemd/journald.conf.d/mogum.conf; then
    install -d -m 755 /etc/systemd/journald.conf.d
    install -m 644 "$release/deploy/journald/mogum.conf" /etc/systemd/journald.conf.d/mogum.conf
    systemctl restart systemd-journald
fi

cleanup() {
    local kept=1 timestamp name directory image_id active_id previous_id=''
    [[ -z $previous || $previous == "$release" ]] || kept=2
    # 현재/직전은 항상 보존하고, 나머지는 디렉터리 수정 시각의 최신 순으로 채운다.
    local directories
    directories=$(find "$releases" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %f\n' | sort -rn) || return 1
    while read -r timestamp name; do
        [[ $name =~ ^[0-9a-f]{40}$ ]] || continue
        directory="$releases/$name"
        [[ $directory == "$release" || $directory == "$previous" ]] && continue
        if (( kept < 3 )); then
            kept=$((kept + 1))
        else
            rm -rf -- "$directory" || return 1
        fi
    done <<< "$directories"

    # digest만 있는 직전 이미지도 ID로 보호한다. prune -a는 직전 이미지를 지울 수 있다.
    active_id=$(docker image inspect --format '{{.Id}}' "$image" 2>/dev/null) || return 1
    if [[ -n $previous_image ]]; then
        previous_id=$(docker image inspect --format '{{.Id}}' "$previous_image" 2>/dev/null) || return 1
    fi
    local image_ids
    image_ids=$(docker image ls --quiet --no-trunc | sort -u) || return 1
    while read -r image_id; do
        [[ -n $image_id && $image_id != "$active_id" && $image_id != "$previous_id" ]] || continue
        # 강제 삭제하지 않는다. 사용 중인 Caddy/Postgres 등의 이미지는 Docker가 보존한다.
        docker image rm "$image_id" >/dev/null 2>&1 || true
    done <<< "$image_ids"
}

if cleanup; then
    printf '%s\n' 'Release and unused image cleanup complete.'
else
    printf '%s\n' 'Cleanup incomplete; healthy deployment retained.'
fi
