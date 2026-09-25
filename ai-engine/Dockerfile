FROM python:3.11-slim

WORKDIR /app

RUN sed -i 's|http://deb.debian.org|http://mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources && \
    apt-get update && apt-get install -y \
    libgl1 \
    libglib2.0-0 \
    libgomp1 \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# torch + torchvision CPU 版本，先装防止 ultralytics 拉 CUDA 版覆盖
RUN pip install --no-cache-dir \
    --index-url https://download.pytorch.org/whl/cpu \
    --trusted-host download.pytorch.org \
    torch==2.7.1 \
    torchvision==0.22.1

# Python 依赖（torch/torchvision 已装，ultralytics 不会再拉 CUDA 版）
COPY requirements.txt .
RUN pip install --no-cache-dir --timeout 300 --retries 5 -i https://pypi.tuna.tsinghua.edu.cn/simple/ --trusted-host pypi.tuna.tsinghua.edu.cn -r requirements.txt

# 模型权重不打进镜像（.dockerignore 排除了 models/），
# 运行时由 docker-compose 把 ./ai-engine/models 只读挂载到 /models：
#   /models/wildlife-v1.0/best.pt
#   /models/wildlife-v1.1/best.pt
#   /models/wildlife-v2.0/best.pt
RUN mkdir -p /data/chroma /models

COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

COPY . .

EXPOSE 8001

ENTRYPOINT ["/docker-entrypoint.sh"]
