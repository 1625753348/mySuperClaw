#!/usr/bin/env sh
set -eu

cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
name=enterprise-agent-postgres
if docker container inspect "$name" >/dev/null 2>&1; then
    test "$(docker inspect --format '{{ index .Config.Labels "com.example.enterpriseagent" }}' "$name")" = postgres
    docker start "$name"
    exit
fi

test -f .env.postgres.local
docker run -d --name "$name" \
    --label com.example.enterpriseagent=postgres \
    --env-file .env.postgres.local \
    -p 127.0.0.1:5432:5432 \
    --mount type=volume,src=enterprise-agent-postgres-data,dst=/var/lib/postgresql/data \
    --mount "type=bind,src=$PWD/schema.sql,dst=/docker-entrypoint-initdb.d/01-schema.sql,readonly" \
    --health-cmd 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    --health-interval 3s --health-timeout 2s --health-retries 15 \
    postgres:17-alpine
