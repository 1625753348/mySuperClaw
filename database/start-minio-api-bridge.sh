#!/usr/bin/env sh
set -eu

cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
name=enterprise-agent-minio-api
port="${ARTIFACT_MINIO_LOCAL_PORT:-19000}"

if ! docker inspect --format '{{.State.Running}}' milvus-minio | grep -qx true; then
    echo '请先确认现有 milvus-minio 容器能够启动。' >&2
    exit 1
fi
network="${ARTIFACT_MINIO_DOCKER_NETWORK:-$(docker inspect --format '{{range $name, $net := .NetworkSettings.Networks}}{{println $name}}{{end}}' milvus-minio)}"
if ! docker network inspect "$network" --format '{{range .Containers}}{{println .Name}}{{end}}' | grep -qx milvus-minio; then
    echo '请用 ARTIFACT_MINIO_DOCKER_NETWORK 指定现有 MinIO 所在的单个 Docker 网络。' >&2
    exit 1
fi

if docker container inspect "$name" >/dev/null 2>&1; then
    test "$(docker inspect --format '{{ index .Config.Labels "com.example.enterpriseagent" }}' "$name")" = minio-api
    test "$(docker inspect --format '{{.HostConfig.NetworkMode}}' "$name")" = "$network"
    test "$(docker inspect --format '{{(index (index .HostConfig.PortBindings "8080/tcp") 0).HostPort}}' "$name")" = "$port"
    docker start "$name"
else
    docker run -d --name "$name" --label com.example.enterpriseagent=minio-api \
        --network "$network" -p "127.0.0.1:$port:8080" \
        --mount "type=bind,src=$PWD/minio-api-proxy.conf,dst=/etc/nginx/conf.d/default.conf,readonly" \
        nginx:1.28-alpine
fi
