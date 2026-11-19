# MinerU 4.x 解析服务镜像（方案B：远程 VLM）
#
# 定位：跑 mineru-kit api-server + file-parse-server 两个服务；
# VLM 推理走外部 vLLM 容器（MinerU2.5-Pro-2605-1.2B，端口 30000），
# 小模型（layout/OCR/表格）走 ONNX/CPU——不占用 GPU，适配 2 核低配 WSL。
#
# 基础镜像用本地已有的 mineru:latest（python 3.12 + Ubuntu 22.04，
# 已带 opencv/onnxruntime 等运行时依赖），避免拉取 Docker Hub。
# 构建（守护进程代理故障时必须走 host 网络并清代理）：
#   docker build --network host --build-arg HTTP_PROXY= --build-arg HTTPS_PROXY= -t mineru-parse:4 .
FROM mineru:latest

# 清掉守护进程注入的失效代理（172.25.240.1:7890 已不可达），WSL 直连国内源
ENV HTTP_PROXY= \
    HTTPS_PROXY= \
    NO_PROXY=localhost,127.0.0.1 \
    PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple \
    MINERU_MODEL_SOURCE=modelscope \
    MINERU_MODEL_SMALL_BACKEND=onnx \
    MINERU_MODEL_ONNX_DEVICE=cpu \
    MINERU_HOME=/data/mineru-home

# 官方镜像同款版本区间；基础包自带 ONNX 小模型栈（替换镜像内自带 3.2.0）
RUN pip install --no-cache-dir "mineru>=4.0,<5"

# 小模型不烘进镜像：api-server 首次解析时按 MINERU_MODEL_SOURCE 下载到
# MINERU_HOME（compose 挂持久卷），一次性成本。
