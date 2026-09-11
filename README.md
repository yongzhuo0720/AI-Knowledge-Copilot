# AI Knowledge Copilot

企业级 AI 知识助手平台，包含用户与权限、工作空间、知识库、RAG 问答和 Agent 工具调用能力。

## 当前功能

- 用户、工作空间与知识库基础管理。
- 知识库接口按工作空间成员隔离，业务请求需携带 `X-User-Id` 请求头。
- 从前端选择真实文件并以 multipart 上传。
- 后端将原文件保存至 MinIO 的 `ai-copilot` bucket，并将文档元数据保存至 MySQL。
- 上传后自动创建 AI 解析任务；页面可查看文档列表、任务 ID 与最新解析状态。
- AI Service 从 MinIO 下载 TXT、Markdown、CSV、PDF、DOCX，抽取文本并切分为片段。
- 解析片段写入 Milvus，可按知识库检索并在前端展示命中内容与来源对象。

> 当前检索向量使用确定性的本地哈希嵌入，便于在不依赖外部模型时跑通流程；接入真实 Embedding 模型后可获得更好的语义检索质量。

已支持通过环境变量接入真实模型：DeepSeek `deepseek-chat` 生成基于资料的回答，百炼 `text-embedding-v4` 生成检索向量。模型回答会附带命中的文档对象来源。

## 技术基线

- Java 21
- Spring Boot 3.x
- Maven 3.9.x
- Vue 3 + TypeScript + Vite
- Python 3.11/3.12（AI 服务建议版本）
- MySQL 8
- Redis 7
- Milvus 2.x
- MinIO
- Docker Compose

## 目录

- `backend/`：Java 后端
- `ai-service/`：Python AI 服务
- `frontend/`：Vue 前端
- `deploy/`：容器化与部署配置
- `docs/`：架构、数据库、接口和面试文档

## 文档处理流程

```text
浏览器上传文件
  → Backend：MinIO 保存对象 + MySQL 保存元数据
  → AI Service：ACCEPTED → PROCESSING → COMPLETED / FAILED
  → MinIO 下载文件、抽取文本、切分片段
  → Milvus 写入向量
  → 知识库检索接口返回命中文本与来源
```

AI Service 将任务状态、失败原因、重试次数和任务参数持久化到 MySQL；服务启动后会恢复未完成任务。MinIO、Milvus、Embedding API 等短暂异常会自动重试，失败任务也可从文档列表手动重试。
“重新解析”会提交替换任务：任务成功时先删除该文档的旧 Milvus 片段，再使用当前配置的 Embedding 模型重建向量，适合真实 Embedding 接入后的历史文档迁移。

## 主要接口

除健康检查、用户注册外，工作空间和知识库业务接口均需要请求头：

```http
X-User-Id: 16
```

后端会校验用户是否属于目标工作空间；非成员访问返回 `403 ACCESS_DENIED`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/knowledge-bases` | 创建知识库 |
| `POST` | `/api/v1/knowledge-bases/{knowledgeBaseId}/documents/upload` | 上传文件并提交解析 |
| `GET` | `/api/v1/knowledge-bases/{knowledgeBaseId}/documents` | 获取文档列表 |
| `GET` | `/api/v1/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/processing-status` | 查询并同步解析状态 |
| `POST` | `/api/v1/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/reparse` | 删除旧向量并按当前模型重新解析 |
| `POST` | `/api/v1/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/processing-retry` | 重试失败的解析任务 |
| `POST` | `/api/v1/knowledge-bases/{knowledgeBaseId}/search` | 检索知识库片段 |
| `POST` | `/api/v1/knowledge-bases/{knowledgeBaseId}/answer` | 根据检索片段生成回答与引用 |
| `POST` | `/api/v1/document-processing/tasks` | AI Service：创建解析任务 |
| `GET` | `/api/v1/document-processing/tasks/{taskId}` | AI Service：获取任务状态 |
| `POST` | `/api/v1/document-processing/tasks/{taskId}/retry` | AI Service：重试失败任务 |
| `POST` | `/api/v1/retrieval/search` | AI Service：查询 Milvus 片段 |

## 本地运行与验证

1. 复制 `deploy/.env.example` 为 `deploy/.env`，并填入数据库和 MinIO 凭据。
2. 按 [部署说明](deploy/README.md) 启动 Docker Compose；Compose 会将 AI Service 连接到 MinIO 与 Milvus。
3. 访问前端 `http://localhost`，创建知识库后上传文档；点击“查询状态”确认解析完成，再输入关键词进行检索。旧文档可点击“重新解析”，失败文档可点击“重试任务”。

Docker Compose 会自动启用 MySQL 任务持久化。重新构建后，即使重启 AI Service，`ACCEPTED`、`RETRYING` 或已超时的 `PROCESSING` 任务也会被恢复执行。

若启用真实模型，在本机 `deploy/.env`（不要提交）加入：

```env
DEEPSEEK_API_KEY=你的新DeepSeek密钥
DASHSCOPE_API_KEY=你的百炼密钥
```

重新构建 AI Service 后，新上传的文档将使用百炼向量；在“生成回答”中会由 DeepSeek 根据命中的资料输出带编号引用的中文回答。请勿将真实密钥写入 `.env.example`、README 或 Git 提交。

常用验证命令：

```powershell
cd ai-service
.\.venv\Scripts\python.exe -m pytest -q

cd ..\frontend
npm run build

cd ..\backend
mvn test
```

首次执行 AI Service 时，需根据 `ai-service/pyproject.toml` 安装新增依赖（MinIO、PyPDF、python-docx、pymilvus）。
