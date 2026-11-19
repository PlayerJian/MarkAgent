# MinerU 解析服务部署(方案B:远程 VLM)

WSL/Docker 内跑 `mineru-kit api-server` + `mineru-kit file-parse-server`,
VLM 推理复用现有 vLLM 容器(`mineru-openai-server:30000`,MinerU2.5-Pro-2605-1.2B),
小模型(layout/OCR/表格)走 ONNX/CPU——不占 GPU(8GB 卡已被 vLLM 占 ~6GB)。

## 拓扑

```
mineru-openai-server   (vLLM, 30000, 网络 mineru_default, 已存在)
mineru-api-server      (mineru-parse:4, 127.0.0.1:8000, 内部)
mineru-file-parse-server (mineru-parse:4, 0.0.0.0:8003 → Windows localhost:8003)
```

## 首次部署

```bash
# 构建镜像
docker build --network host --build-arg HTTP_PROXY= --build-arg HTTPS_PROXY= -t mineru-parse:4 .

# 启动
docker compose -f deploy/mineru/docker-compose.yml up -d

# 下载小模型(一次性,落 mineru-home 卷;基本档包,standard 共用,VLM 走远程)
docker exec mineru-api-server mineru-kit models download --tier basic --source modelscope
```

## 验证

```bash
curl -X POST http://localhost:8003/v1/file_parse \
  -F "file=@deploy/mineru/testdata/sample.pdf" \
  -F "wait_timeout=180" -F "request_id=smoke-1" -o result.zip
# 200 → 响应体即产物 ZIP(markdown.md + structured_content.json + middle_json.json + images/)
# 202 → JSON {request_id,status,status_url};轮询 GET status_url 至 completed,再 GET .../result
```

## 环境注意

- WSL 仅 2 核 / 5.8G 内存:大 PDF 的 CPU 解析慢;高吞吐需调 `.wslconfig`
- 产物协议为 MinerU 4.x:块序列在 `structured_content.json`(pages[].blocks[],带 type/bbox),
  页数在 `metadata.document.page_count`;3.x 的 content_list.json 已不再输出
- Windows 8080 端口被 ERP 前端容器占用,MarkAgent 本地联调用 `--server.port=8090`
