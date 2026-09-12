# AI Knowledge Copilot

面向团队的 AI 知识库与 RAG 问答平台：上传企业文档，自动解析并建立向量索引，通过自然语言检索和问答获取带引用的答案。

当前仓库对应 GitHub 项目：<https://github.com/yongzhuo0720/AI-Knowledge-Copilot>

## 产品能力

- 用户注册、登录和 Redis-backed Bearer Token 认证。
- 工作空间、成员和知识库的权限隔离。
- TXT、Markdown、CSV、PDF、DOCX 文档上传。
- MinIO 保存原文件，MySQL 保存元数据和任务状态，Milvus 保存向量片段。
- AI Service 持久化解析任务、失败原因、重试次数和任务参数。
- 服务重启后自动恢复未完成任务，短暂异常自动重试。
- 文档重新解析：删除旧向量，并使用当前 Embedding 模型重建索引。
- 基于百炼 `text-embedding-v4` 的向量检索。
- 基于 DeepSeek `deepseek-chat` 的回答生成。
- 会话历史持久化，回答保存检索来源和引用对象。
- Vue 3 工作台：登录、注册、工作空间创建、上传、解析状态、检索和问答。

## 系统架构

```text
                         ┌──────────────────────┐
                         │   Vue 3 + Nginx      │
                         │   http://localhost   │
                         └──────────┬───────────┘
                                    │ /api
                         ┌──────────▼───────────┐
                         │ Spring Boot Backend  │
                         │ 认证 / 权限 / 会话   │
                         └──────┬───────┬───────┘
                                │       │
                    ┌───────────▼──┐ ┌──▼───────────┐
                    │    MySQL     │ │    Redis     │
                    │ 元数据/任务  │ │ Bearer Token │
                    └──────────────┘ └──────────────┘
                                │
                         ┌──────▼───────┐
                         │  AI Service   │
                         │ 解析/Embedding│
                         │ 检索/回答     │
                         └──┬─────────┬──┘
                            │         │
                  ┌─────────▼──┐  ┌──▼─────────┐
                  │   MinIO    │  │  Milvus    │
                  │ 原始文件   │  │ 向量索引   │
                  └────────────┘  └────────────┘
```

## 目录结构

```text
.
├─ backend/       Spring Boot API、认证、权限、会话和业务编排
├─ ai-service/    FastAPI 文档解析、Embedding、检索和回答服务
├─ frontend/      Vue 3 + TypeScript 工作台
├─ deploy/        Docker Compose、Nginx 和环境变量模板
└─ docs/          架构、数据库、API 和开发说明
```

## 快速启动：Docker Compose

### 1. 准备环境

建议版本：

| 工具 | 版本 |
| --- | --- |
| Docker Desktop | 4.x 或更高 |
| Docker Compose | v2 |
| Java | 21 |
| Maven | 3.9.x |
| Node.js | 22.x |
| Python | 3.11 或 3.12 |

复制环境变量模板：

```powershell
cd D:\ai_project\AI_agent
Copy-Item deploy\.env.example deploy\.env
```

编辑 `deploy/.env`，至少配置数据库、MinIO 凭据；如果要启用真实模型，再填入：

```env
DEEPSEEK_API_KEY=your-deepseek-key
DASHSCOPE_API_KEY=your-dashscope-key
```

真实密钥只允许保存在本地 `deploy/.env`，不要写入 README、`.env.example`、代码或 Git。

### 2. 构建并启动

必须在仓库根目录执行，避免 Compose 找不到 `deploy/.env`：

```powershell
cd D:\ai_project\AI_agent
mvn -s C:\Users\24774\.m2\settings.xml -f backend\pom.xml package -DskipTests
docker compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml up --build -d
```

如果 Docker 不在 PATH，可指定 Docker Desktop 的完整路径：

```powershell
$dockerExe = "C:\Users\24774\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe"
& $dockerExe compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml up --build -d
```

### 3. 访问服务

| 服务 | 地址 |
| --- | --- |
| 前端工作台 | <http://localhost> |
| 知识库页面 | <http://localhost/knowledge> |
| Backend API | <http://localhost:8080> |
| AI Service | <http://localhost:8000> |
| MinIO API | <http://localhost:9000> |
| MinIO Console | <http://localhost:9001> |
| Milvus | `localhost:19530` |

查看容器状态：

```powershell
docker compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml ps
```

停止服务：

```powershell
docker compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml down
```

## 第一次使用

1. 打开 <http://localhost/knowledge>。
2. 点击“注册”，创建用户；已有用户直接登录。
3. 登录后点击“一键创建”创建工作空间，页面会自动填入工作空间 ID。
4. 创建知识库。
5. 选择 `.txt`、`.md`、`.csv`、`.pdf` 或 `.docx` 文件并上传。
6. 在“文档资产”区域点击“查询”，直到状态变成 `COMPLETED`。
7. 在 AI 问答区域输入问题：
   - “检索”查看命中的文本片段。
   - “生成回答”调用模型生成答案。
8. 检查回答底部的“来源”，确认返回文档对象路径。
9. 对旧文档点击“重新解析”，验证旧向量删除后使用当前 Embedding 重建。

## 文档处理流程

```text
浏览器选择文件
  → Backend 上传到 MinIO
  → MySQL 保存文档和处理任务
  → AI Service 从 MinIO 下载并解析文本
  → 文本切片 + Embedding
  → Milvus 写入向量
  → 检索返回片段和来源
  → DeepSeek 根据片段生成回答
```

任务状态包括 `ACCEPTED`、`PROCESSING`、`COMPLETED`、`FAILED` 和 `RETRYING`。任务参数、失败原因、重试次数和状态保存在 MySQL，AI Service 重启后会恢复未完成任务。

## 认证与权限

注册和登录不需要 Bearer Token。工作空间、知识库和会话接口需要：

```http
Authorization: Bearer <access-token>
```

权限规则：

- 用户创建工作空间时，认证用户必须等于 `ownerUserId`。
- 只有工作空间成员可以访问该空间下的知识库。
- 只有 OWNER 或 ADMIN 可以管理工作空间成员。
- 会话按 `knowledgeBaseId + userId` 隔离。
- 缺少或无效 Token 返回 `401 AUTHENTICATION_REQUIRED`。
- 非成员访问返回 `403 ACCESS_DENIED`。
- 前端收到 `401` 会清理本地登录态，并自动回到登录入口。
- 前端不会使用 `X-User-Id` 作为身份来源。

## API 速查

### 用户与工作空间

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/users` | 注册 | 否 |
| `POST` | `/api/v1/users/login` | 登录并获取 Token | 否 |
| `POST` | `/api/v1/workspaces` | 创建工作空间 | 是 |
| `GET` | `/api/v1/workspaces` | 获取当前用户可访问的工作空间 | 是 |
| `GET` | `/api/v1/workspaces/{workspaceId}/overview` | 获取 Workspace 统计、最近知识库、文档和会话 | 是 |
| `GET` | `/api/v1/workspaces/{workspaceId}/documents` | 获取 Workspace 文档，可按文件名、状态和知识库筛选 | 是 |
| `GET` | `/api/v1/workspaces/{workspaceId}/members` | 查看成员 | 是 |
| `POST` | `/api/v1/workspaces/{workspaceId}/members` | 添加成员 | OWNER/ADMIN |

### 知识库与文档

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/knowledge-bases` | 创建知识库 |
| `GET` | `/api/v1/knowledge-bases?workspaceId={workspaceId}` | 获取工作空间下的知识库 |
| `POST` | `/api/v1/knowledge-bases/{id}/documents/upload` | 上传文档 |
| `GET` | `/api/v1/knowledge-bases/{id}/documents` | 获取文档列表 |
| `GET` | `/api/v1/knowledge-bases/{id}/documents/{documentId}/processing-status` | 刷新解析状态 |
| `POST` | `/api/v1/knowledge-bases/{id}/documents/{documentId}/reparse` | 删除旧向量并重新解析 |
| `POST` | `/api/v1/knowledge-bases/{id}/documents/{documentId}/processing-retry` | 重试失败任务 |
| `POST` | `/api/v1/knowledge-bases/{id}/search` | 检索知识片段 |
| `POST` | `/api/v1/knowledge-bases/{id}/answer` | 直接生成回答 |

### 会话

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/knowledge-bases/{id}/conversations` | 创建会话 |
| `GET` | `/api/v1/knowledge-bases/{id}/conversations` | 当前用户会话列表 |
| `GET` | `/api/v1/knowledge-bases/{id}/conversations/{sessionId}/messages` | 获取消息和引用 |
| `POST` | `/api/v1/knowledge-bases/{id}/conversations/{sessionId}/messages` | 提问并保存回答 |
| `POST` | `/api/v1/knowledge-bases/{id}/conversations/{sessionId}/messages/stream` | SSE 流式提问并保存回答 |

流式接口返回 `text/event-stream`，事件顺序通常为 `user`、多个 `delta`、`sources`、`complete`。前端停止生成时会中断 HTTP 请求，未完成的助手回答不会写入会话历史。Docker Nginx 已关闭代理缓冲，避免 SSE 被网关聚合后一次性返回。

### AI Service 内部接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/health` | 健康检查 |
| `POST` | `/api/v1/document-processing/tasks` | 创建解析任务 |
| `GET` | `/api/v1/document-processing/tasks/{taskId}` | 查询任务 |
| `POST` | `/api/v1/document-processing/tasks/{taskId}/retry` | 重试任务 |
| `POST` | `/api/v1/retrieval/search` | 向量检索 |
| `GET` | `/api/v1/retrieval/stats?knowledge_base_ids={ids}` | 查询 Milvus 已索引 Chunk 数 |
| `POST` | `/api/v1/answers` | 模型回答 |
| `POST` | `/api/v1/answers/stream` | SSE 流式模型回答 |

AI Service 流式回答同样返回 `text/event-stream`，事件包括 `sources`、多个 `delta` 和 `complete`；异常由 Backend 统一转发为 `error` 事件。

## 配置说明

Docker Compose 使用 `deploy/.env`：

| 变量 | 作用 | 默认示例 |
| --- | --- | --- |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | `change-me-root` |
| `MYSQL_DATABASE` | 业务数据库 | `ai_copilot` |
| `MYSQL_USER` / `MYSQL_PASSWORD` | 业务数据库账号 | `ai_copilot` / `change-me-app` |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | MinIO 凭据 | `minioadmin` / `change-me-minio` |
| `DEEPSEEK_API_KEY` | DeepSeek 对话模型密钥 | 空 |
| `DASHSCOPE_API_KEY` | 百炼 Embedding 密钥 | 空 |

AI Service 的任务参数在 Compose 中默认是：

```env
AI_SERVICE_TASK_STORE_ENABLED=true
AI_SERVICE_TASK_MAX_RETRIES=3
AI_SERVICE_TASK_RETRY_BACKOFF_SECONDS=2
AI_SERVICE_TASK_RECOVERY_INTERVAL_SECONDS=5
AI_SERVICE_TASK_PROCESSING_STALE_SECONDS=300
```

## 本地开发

### Backend

```powershell
mvn -s C:\Users\24774\.m2\settings.xml -f backend\pom.xml test
```

启动本地 Spring Boot 时需要 MySQL、Redis、MinIO、Milvus 和 AI Service；建议直接使用 Compose 基础设施。

### AI Service

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
pytest -q
uvicorn app.main:app --reload --port 8000
```

### Frontend

```powershell
cd frontend
npm ci
npm run build
npm run dev
```

开发服务器默认地址为 <http://localhost:5173>，`/api` 会代理到 `http://localhost:8080`。

## 验证清单

提交前建议执行：

```powershell
cd backend
mvn -s C:\Users\24774\.m2\settings.xml test

cd ..\ai-service
.\.venv\Scripts\python.exe -m pytest -q

cd ..\frontend
npm run build
```

Docker 端到端成功标准：

- 注册或登录成功。
- 能创建工作空间和知识库。
- 上传文件后任务最终变为 `COMPLETED`。
- 检索结果包含原文片段和对象路径。
- AI 回答非空，并展示引用来源。
- 重启 AI Service 后未完成任务能够恢复。
- 失败任务展示失败原因并支持重试。
- 重新解析后仍能检索到该文档。

## 常见问题

### `couldn't find env file`

Compose 的相对路径相对于当前终端目录。请回到仓库根目录执行：

```powershell
cd D:\ai_project\AI_agent
docker compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml up --build -d
```

### 登录返回 `401`

项目没有固定默认账号。先在 `/knowledge` 点击“注册”，再使用注册成功的邮箱和密码登录。若账号已存在，必须使用注册时的原密码。

### 创建知识库返回 `403`

当前用户必须是目标工作空间成员。推荐在登录后点击“一键创建”创建自己的工作空间；如果手动填写 ID，必须确认该用户已经加入对应工作空间。

### 文档一直不是 `COMPLETED`

查看 AI Service 日志和任务状态：

```powershell
docker logs -f ai-copilot-ai-service
```

同时确认 MinIO、Milvus、MySQL 均为运行状态，以及 `deploy/.env` 中的模型密钥没有多余引号或空格。

### 页面没有更新

重新构建前端容器并强制刷新浏览器：

```powershell
docker compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml up --build -d frontend
```

然后使用 `Ctrl + F5` 刷新。

## 数据与安全

- `deploy/.env`、`.env`、模型密钥和本地数据不提交 Git。
- 生产环境必须替换示例数据库和 MinIO 密码，并限制管理端口暴露范围。
- 当前 Token 存储在 Redis，默认有效期为 8 小时。
- 生产环境建议接入 HTTPS、密钥管理服务、审计日志、限流和 Token 撤销机制。
- MinIO、Milvus、MySQL、Redis 管理端口不应直接暴露到公网。

## Git 提交约定

每完成一个可验证的大步骤提交一次，提交前至少运行与改动相关的测试和 `git diff --check`。不要提交真实 API Key、数据库密码或运行时数据。

## 后续规划

- 完善工作空间选择器和成员管理页面。
- 增加 API 集成测试和 Docker Compose 烟囱测试。
- 增加文档预览、批量上传、解析进度和失败任务监控。
- 增加会话搜索、重命名和删除能力。
- 增加生产环境部署、HTTPS、审计和细粒度 RBAC。
