# Docker 部署

本目录提供 MySQL、Redis、MinIO、Milvus、AI Service、Backend 和 Frontend 的 Docker Compose 编排。

## 启动

必须在仓库根目录执行，避免 Compose 找不到环境变量文件：

```powershell
cd D:\ai_project\AI_agent
Copy-Item deploy\.env.example deploy\.env
# 编辑 deploy\.env，填写数据库、MinIO 和模型配置

docker compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml up --build -d
```

如果 Docker 不在 PATH：

```powershell
$dockerExe = "C:\Users\24774\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe"
& $dockerExe compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml up --build -d
```

查看状态：

```powershell
docker compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml ps
```

Compose 会等待 MySQL、Redis、MinIO、etcd、Milvus、AI Service 和 Backend 健康后再启动依赖服务；首次启动 Milvus 可能需要几十秒，看到状态为 `healthy` 后再打开前端。

## 服务入口

- Frontend：<http://localhost>
- Knowledge workspace：<http://localhost/knowledge>
- Backend：<http://localhost:8080>
- AI Service：<http://localhost:8000>
- MinIO API：<http://localhost:9000>
- MinIO Console：<http://localhost:9001>
- Milvus：`localhost:19530`

## 环境变量

从 `.env.example` 创建 `.env`。真实密钥只放在本地 `.env`，不要提交 Git：

```env
MYSQL_ROOT_PASSWORD=change-me-root
MYSQL_DATABASE=ai_copilot
MYSQL_USER=ai_copilot
MYSQL_PASSWORD=change-me-app
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=change-me-minio
DEEPSEEK_API_KEY=
EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1
EMBEDDING_MODEL=BAAI/bge-m3
EMBEDDING_DIMENSIONS=0
EMBEDDING_API_KEY=
DASHSCOPE_API_KEY=
AI_SERVICE_INTERNAL_TOKEN=change-me-internal-token
```

`DEEPSEEK_API_KEY` 用于回答生成；`EMBEDDING_API_KEY` 用于向量生成（默认硅基流动免费 `BAAI/bge-m3`，1024 维，`EMBEDDING_DIMENSIONS=0` 表示不发送该参数）；`DASHSCOPE_API_KEY` 仅在启用 DashScope 远端 rerank 时需要。密钥为空时，AI Service 仍可启动，但真实模型调用无法完成。

> 换 Embedding 供应商会改变向量空间，必须对所有在用知识库重新解析入库；旧向量不会报错，只会让向量召回失真。

`AI_SERVICE_INTERNAL_TOKEN` 由 backend 与 ai-service 共享：ai-service 的除 `/api/v1/health` 外的全部接口都要求 `X-Internal-Token` 请求头。生成方式：`python -c "import secrets; print(secrets.token_urlsafe(32))"`，并保证 `.env` 中的值两端一致。留空会关闭校验（仅限本地开发），容器启动日志会给出明确警告。

## 端到端验证

1. 打开 <http://localhost/knowledge>。
2. 注册或登录用户。
3. 点击“一键创建”创建工作空间。
4. 已有工作空间和知识库会在页面刷新后自动恢复，也可以通过下拉框切换。
5. 创建知识库并上传受支持的文档（TXT / MD / CSV / TSV / PDF / DOCX / XLSX / XLSM / PPTX / HTML）。
6. 等待文档状态自动轮询到 `COMPLETED`（也可以手动点“查询”）。
7. 点击“检索”确认返回片段和对象路径。
8. 点击“生成回答”，确认回答非空且有来源引用。
9. 点击“重新解析”，确认任务重新完成且仍可检索。

也可以使用自动化冒烟脚本验证完整 API 链路。脚本会创建临时账号、工作空间、知识库和 Markdown 文档，不会读取或提交模型密钥：

```powershell
cd D:\ai_project\AI_agent
powershell -ExecutionPolicy Bypass -File .\deploy\smoke-test.ps1
```

如果使用自定义文档：

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\smoke-test.ps1 -FilePath .\docs\sample.md
```

脚本会额外验证重新解析，以及持久化会话中的 Agent 工具轨迹和引用。脚本兼容 Windows PowerShell 5.1 和 PowerShell 7+，并要求 `deploy/.env` 已配置有效的 DeepSeek 对话 Key 与 Embedding Key（默认硅基流动）。

任务恢复和重试日志：

```powershell
docker logs -f ai-copilot-ai-service
```

## 停止与清理

停止容器但保留数据卷：

```powershell
docker compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml down
```

如需删除本地数据库、对象存储和向量数据卷，必须明确确认后再执行：

```powershell
docker compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml down -v
```

## 常见问题

### `couldn't find env file`

当前终端目录不在仓库根目录。执行：

```powershell
cd D:\ai_project\AI_agent
docker compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml up --build -d
```

### 前端仍是旧版本

只重建 Frontend 并强制刷新浏览器：

```powershell
docker compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml up --build -d frontend
```

浏览器使用 `Ctrl + F5` 刷新。

### 文档解析失败

查看服务日志，检查 MinIO、Milvus、MySQL 和模型密钥：

```powershell
docker logs --tail 200 ai-copilot-ai-service
docker compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml ps
```

失败文档可以在页面点击“重试”；历史文档可以点击“重新解析”。
