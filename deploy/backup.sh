#!/usr/bin/env bash
# systemd가 root로 실행한다. AWS 자격은 EC2 인스턴스 역할을 사용한다.
set -euo pipefail
umask 077

fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ $(id -u) == 0 ]] || fail 'Run backup as root.'
[[ -n ${MOGUM_BUCKET:-} ]] || fail 'MOGUM_BUCKET is required.'

dump=$(mktemp)
trap 'rm -f -- "$dump"' EXIT
trap 'exit 1' HUP INT TERM

# Compose를 파싱하지 않아 APP_IMAGE 없이도 재부팅 직후 실행할 수 있다.
container=''
for ((attempt = 0; attempt <= 24; attempt++)); do
    container=$(docker ps --filter label=com.docker.compose.project=mogum \
        --filter label=com.docker.compose.service=postgres --format '{{.ID}}')
    [[ $container != *$'\n'* ]] || fail 'Multiple operating Postgres containers.'
    if [[ -n $container ]] && docker exec "$container" sh -c \
        'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null 2>&1; then
        break
    fi
    (( attempt < 24 )) || fail 'Postgres not ready after 120 seconds.'
    sleep 5
done

docker exec "$container" sh -c \
    'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$dump" 2>/dev/null \
    || fail 'Postgres dump failed; nothing uploaded.'
[[ -s $dump ]] || fail 'Empty dump; nothing uploaded.'

key="backups/$(TZ=Asia/Seoul date +%Y%m%dT%H%M%S%z).dump"
aws s3 cp "$dump" "s3://${MOGUM_BUCKET}/${key}" --only-show-errors >/dev/null 2>&1 \
    || fail 'Backup upload failed.'
printf 'Backup s3://%s/%s (%s bytes).\n' "$MOGUM_BUCKET" "$key" "$(wc -c < "$dump")"
