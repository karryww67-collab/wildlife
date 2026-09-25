#!/bin/bash
set -e

# 模型权重通过 docker-compose 的 ./ai-engine/models:/models:ro 挂载进容器，
# 目录结构为 <MODEL_ROOT>/<version>/best.pt（见 backend/sql/model_versions.sql）。
# 权重缺失不阻塞启动，只是识别时会回退到备用权重并打警告。
MODEL_ROOT="${MODEL_ROOT:-/models}"
ACTIVE_MODEL_VERSION="${ACTIVE_MODEL_VERSION:-wildlife-v1.0}"

if [ ! -f "${MODEL_ROOT}/${ACTIVE_MODEL_VERSION}/best.pt" ]; then
    echo "WARNING: model weight not found: ${MODEL_ROOT}/${ACTIVE_MODEL_VERSION}/best.pt" >&2
    echo "         Mount ./ai-engine/models to /models, or drop best.pt into <MODEL_ROOT>/<version>/." >&2
else
    echo "Model ready: ${MODEL_ROOT}/${ACTIVE_MODEL_VERSION}/best.pt"
fi

exec uvicorn app.main:app --host 0.0.0.0 --port 8001
