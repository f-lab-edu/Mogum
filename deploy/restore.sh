#!/usr/bin/env bash
# 연습 전용. 운영 DB로 복원하는 옵션을 추가하지 않는다.
set -euo pipefail
umask 077

fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ $(id -u) == 0 ]] || fail 'Run restore check as root.'
[[ $# == 1 ]] || fail 'Usage: restore.sh <backups/object.dump | latest>'
[[ -n ${MOGUM_BUCKET:-} ]] || fail 'MOGUM_BUCKET is required.'

key=$1
if [[ $key == latest ]]; then
    # JSON은 CLI가 모든 페이지를 모은 뒤 query를 적용한다. 키는 아래 형식만 받는다.
    key=$(aws s3api list-objects-v2 --bucket "$MOGUM_BUCKET" --prefix backups/ \
        --query "sort_by(Contents[?ends_with(Key, '.dump')] || \`[]\`, &LastModified)[-1].Key" \
        --output json 2>/dev/null) || fail 'Could not list backups.'
    key=${key#\"}
    key=${key%\"}
fi
[[ $key =~ ^backups/[0-9]{8}T[0-9]{6}\+0900\.dump$ ]] || fail 'No backup or invalid backup key.'

container=$(docker ps --filter label=com.docker.compose.project=mogum \
    --filter label=com.docker.compose.service=postgres --format '{{.ID}}')
[[ -n $container && $container != *$'\n'* ]] || fail 'Expected one operating Postgres container.'
dump=$(mktemp)
cleanup_db=false
cleanup() {
    local status=$?
    trap - EXIT
    if [[ $cleanup_db == true ]]; then
        if ! docker exec "$container" sh -c \
            'exec dropdb -U "$POSTGRES_USER" --if-exists --force mogum_restore_check' >/dev/null 2>&1; then
            printf '%s\n' 'Restore check DB cleanup failed.' >&2
            status=1
        fi
    fi
    rm -f -- "$dump" || status=1
    exit "$status"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

aws s3 cp "s3://${MOGUM_BUCKET}/${key}" "$dump" --only-show-errors >/dev/null 2>&1 \
    || fail 'Backup download failed.'
[[ -s $dump ]] || fail 'Downloaded backup is empty.'
cleanup_db=true
docker exec "$container" sh -c \
    'exec dropdb -U "$POSTGRES_USER" --if-exists --force mogum_restore_check' >/dev/null 2>&1 \
    || fail 'Could not remove old restore check DB.'
docker exec "$container" sh -c \
    'exec createdb -U "$POSTGRES_USER" --template=template0 mogum_restore_check' >/dev/null 2>&1 \
    || fail 'Could not create restore check DB.'
docker exec -i "$container" sh -c \
    'exec pg_restore --exit-on-error --no-owner --no-privileges -U "$POSTGRES_USER" -d mogum_restore_check' \
    < "$dump" >/dev/null 2>&1 || fail 'Restore check failed.'

printf 'Restored %s into mogum_restore_check; table row counts:\n' "$key"
docker exec -i "$container" sh -c \
    'exec psql -X --set=ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d mogum_restore_check' 2>/dev/null <<'SQL' \
    || fail 'Could not count restored tables.'
SELECT format('SELECT %L AS table_name, count(*) AS rows FROM %I.%I;',
              schemaname || '.' || tablename, schemaname, tablename)
FROM pg_tables
WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
ORDER BY schemaname, tablename
\gexec
SQL
