# AI Knowledge Copilot

[![CI](https://github.com/yongzhuo0720/AI-Knowledge-Copilot/actions/workflows/ci.yml/badge.svg)](https://github.com/yongzhuo0720/AI-Knowledge-Copilot/actions/workflows/ci.yml)

面向团队的 AI 知识库与 RAG 问答平台：上传企业文档，自动解析并建立向量索引，通过混合检索和 Tool Calling Agent 获取带引用、可解释的答案。

当前仓库对应 GitHub 项目：<https://github.com/yongzhuo0720/AI-Knowledge-Copilot>

## 核心特性

**知识与检索**

- 文档上传：TXT、Markdown、CSV、PDF、DOCX、XLSX、PPTX、HTML。
- 解析流水线：MinIO 存原文件 → 文本提取（含 DOCX 表格、xlsx 工作表、pptx 幻灯片、HTML 正文）→ 结构感知切片 → Embedding → Milvus 向量索引。
- 三路检索：向量（`text-embedding-v4`，1024 维）、BM25 关键词、混合（RRF 融合 + 标识符加法提权）。
- 结构感知切片：每个 chunk 携带标题链前缀，纯向量 Hit@1 从 60.7% 提升到 78.6%；混合检索在 28 题评估集上 Hit@1 100%（口径见 `docs/检索效果评估报告.md`）。
- 删除全链路：删除知识库/文档时同步清理 Milvus 向量、MinIO 对象与 MySQL 元数据，避免同名文档重新上传后检索到旧内容。
- 解析任务可靠执行：MySQL 条件 UPDATE 乐观抢占、失败自动重试、服务重启后恢复未完成任务。

**AI 问答与 Agent**

- SSE 流式问答：实时生成，保留每个来源片段；停止生成时不写入残缺回答。
- 知识库 Agent：模型决定调用 `knowledge_search` / `list_documents` / `document_outline` 工具，工具只访问服务端注入的知识库，返回带执行轨迹的可解释回答。
- 检索调试工作台：并排运行向量 / 关键词 / 混合检索，展示命中片段、相似度、上下文组装与提示词预览。

**平台与安全**

- 注册登录、Redis Bearer Token（8 小时）；**Token 校验回查数据库**——账号封禁后旧 Token 立即失效。
- 工作空间 / 成员 / 知识库三层权限隔离，授权 fail-closed；仅 OWNER 可管理空间与成员。
- 所有触发模型调用的端点共用同一 Redis 限流配额，扣配额发生在鉴权之后、模型调用之前。
- Backend ↔ AI Service 内部鉴权（共享 `AI_SERVICE_INTERNAL_TOKEN`），服务间调用不再裸奔。
- 后台管理：用户封禁/删除（级联清理跨用户会话）、工作空间与知识库治理。

## 系统架构

```text
                         ┌──────────────────────┐
                         │   Vue 3 + Nginx      │
                         │   http://localhost   │
                         └──────────┬───────────┘
                                    │ /api
                         ┌──────────▼───────────┐
                         │ Spring Boot Backend  │
                         │ 认证/权限/限流/编排   │
                         └──────┬───────┬───────┘
                                │       │
                    ┌───────────▼──┐ ┌──▼───────────┐
                    │    MySQL     │ │    Redis     │
                    │ 元数据/任务  │ │ Token/限流   │
                    └──────────────┘ └──────────────┘
                                │ X-Internal-Token
                         ┌──────▼───────┐
                         │  AI Service   │
                         │ 解析/切片     │
                         │ 检索/回答/Agent│
                         └──┬─────────┬──┘
                            │         │
                  ┌─────────▼──┐  ┌──▼─────────┐
                  │   MinIO    │  │  Milvus    │
                  │ 原始文件   │  │ 向量索引   │
                  └────────────┘  └────────────┘
```

Java 层不直接调用模型：**一个 AI 能力 = 一个函数式接口 + 一个 lambda `@Bean`**（`AiServiceConfiguration.java`，全部带降级开关），真正的模型调用在 Python 侧完成。语言边界即服务边界。

## 目录结构

```text
.
├─ backend/       Spring Boot API、认证、权限、限流与会话编排
├─ ai-service/    FastAPI 文档解析、切片、Embedding、检索、回答与 Agent
├─ frontend/      Vue 3 + TypeScript 工作台
├─ deploy/        Docker Compose、Nginx 和环境变量模板
├─ scripts/       端到端验证与契约探针（e2e_agent.py、verify_c_group.py）
└─ docs/          架构审查、检索效果评估、API 与数据库说明
    └─ rag-eval/  评估语料与题目集
```

## 快速启动：Docker Compose

### 1. 准备环境

建议版本：Docker Desktop 4.x、Compose v2、Java 21、Maven 3.9.x、Node.js 22.x、Python 3.11+。

复制环境变量模板：

```powershell
cd D:\ai_project\AI_agent
Copy-Item deploy\.env.example deploy\.env
```

编辑 `deploy/.env`，至少配置数据库、MinIO 凭据；如果要启用真实模型，再填入 `DEEPSEEK_API_KEY` 与 `DASHSCOPE_API_KEY`。

真实密钥只允许保存在本地 `deploy/.env`，不要写入 README、`.env.example`、代码或 Git。

### 2. 构建并启动

必须在仓库根目录执行，避免 Compose 找不到 `deploy/.env`：

```powershell
docker compose --env-file .\deploy\.env -f .\deploy\docker-compose.yml up --build -d
```

### 3. 访问服务

| 服务 | 地址 |
| --- | --- |
| 前端工作台 | <http://localhost> |
| Backend API | <http://localhost:8080> |
| AI Service | <http://localhost:8000> |
| MinIO Console | <http://localhost:9001> |
| Milvus | `localhost:19530` |

## 第一次使用

1. 打开 <http://localhost/knowledge>，注册并登录。
2. 「一键创建」工作空间，再创建知识库。
3. 上传文档（支持表格、演示文稿与网页格式），文档资产页每 5 秒自动轮询解析状态，直到 `COMPLETED`。
4. **AI 会话**页：基于当前知识库提问，回答实时生成并保留引用来源；支持搜索、重命名、删除会话与停止生成。
5. **知识库 Agent** 页：下达知识任务，查看执行轨迹（模型调了哪个工具、查了什么、命中多少片段）与最终回答。
6. **设置 → 检索调试**：并排对比三种检索模式的召回差异，观察一次 RAG 的完整输入输出。

## 认证与权限

注册和登录不需要 Token，其余接口需要：

```http
Authorization: Bearer <access-token>
```

- 用户必须创建或被加入工作空间；只有成员能访问该空间下的知识库与会话。
- 授权 fail-closed：未显式判定的访问一律拒绝；非成员访问返回 `403 ACCESS_DENIED`。
- 会话按 `knowledgeBaseId + userId` 隔离；删除用户时级联清理**其他成员**挂在其知识库下的会话。
- 前端收到 `401` 清理本地登录态并回到登录入口，不使用 `X-User-Id` 作为身份来源。

## API 速查

### 用户与工作空间

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/users` | 注册 | 公开 |
| `POST` | `/api/v1/users/login` | 登录获取 Token | 公开 |
| `POST` | `/api/v1/users/logout` | 撤销 Token | 登录 |
| `GET` / `POST` | `/api/v1/workspaces` | 我的工作空间 / 创建工作空间 | 登录 |
| `GET` | `/api/v1/workspaces/{id}/overview` | 空间统计、最近知识库/文档/会话 | 成员 |
| `GET` | `/api/v1/workspaces/{id}/documents` | 空间文档，可按文件名、状态、知识库筛选 | 成员 |
| `GET` / `POST` | `/api/v1/workspaces/{id}/members` | 查看 / 添加成员 | 查看：成员；添加：OWNER |

### 知识库与文档

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` / `POST` | `/api/v1/knowledge-bases?workspaceId={id}` | 列表 / 创建 |
| `POST` | `/api/v1/knowledge-bases/{id}/documents/upload` | 上传文档（multipart） |
| `GET` | `/api/v1/knowledge-bases/{id}/documents` | 文档列表 |
| `GET` | `.../documents/{documentId}/processing-status` | 刷新解析状态 |
| `POST` | `.../documents/{documentId}/reparse` | 删旧向量并按当前模型重建 |
| `POST` | `.../documents/{documentId}/processing-retry` | 重试失败任务 |
| `DELETE` | `.../documents/{documentId}` | 删除单篇文档（向量/对象/元数据一并清理） |
| `POST` | `/api/v1/knowledge-bases/{id}/search` | 检索（`mode=vector/keyword/hybrid`） |
| `POST` | `/api/v1/knowledge-bases/{id}/answer` | 带引用的直接回答 |

### 会话与 Agent

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` / `GET` | `/api/v1/knowledge-bases/{id}/conversations` | 创建 / 搜索（`query` 按标题或消息）分页列表 |
| `GET` | `.../conversations/{sessionId}/messages` | 消息分页（从最新往回数） |
| `PATCH` / `DELETE` | `.../conversations/{sessionId}` | 重命名 / 删除当前用户的会话 |
| `POST` | `.../conversations/{sessionId}/messages/stream` | SSE 流式问答 |
| `POST` | `.../conversations/{sessionId}/agent-messages` | 运行 Agent，返回用户消息、助手消息（含执行轨迹） |

流式接口返回 `text/event-stream`，事件顺序为 `user` → 多个 `delta` → `sources` → `complete`；Docker Nginx 已关闭代理缓冲。Agent 工具只访问服务端注入的知识库，失败收敛为四态 `ToolStatus`（`ok/empty/not_found/error`），只有 `knowledge_search` 的返回进入引用来源。

### 后台管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/admin/overview` · `/users` · `/workspaces` · `/knowledge-bases` | 概览与列表 |
| `PATCH` | `/api/v1/admin/users/{userId}/status` · `/admin` | 封禁 / 授权管理员 |
| `DELETE` | `/api/v1/admin/users/{userId}` | 删除用户（级联清理跨用户会话） |
| `DELETE` | `/api/v1/admin/workspaces/{id}` · `/knowledge-bases/{id}` | 治理删除 |

### AI Service 内部接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/health` | 健康检查（豁免内部鉴权） |
| `POST` / `GET` | `/api/v1/document-processing/tasks[...]` | 解析任务创建 / 查询 / 重试 |
| `POST` | `/api/v1/retrieval/search` | 向量 / 关键词 / 混合检索 |
| `GET` | `/api/v1/retrieval/stats` | Milvus 已索引 Chunk 数 |
| `DELETE` | `/api/v1/knowledge-bases/{id}/index` 等 | 索引清理（幂等） |
| `POST` | `/api/v1/answers/stream` · `/agents/knowledge` | 流式回答 / Tool Calling Agent |

除健康检查外全部要求 `X-Internal-Token` 头（与 backend 共享 `AI_SERVICE_INTERNAL_TOKEN`）。

## 检索质量评估

检索改动不是拍脑袋：仓库带一套可复跑的评估体系（53 道题、2 个评估知识库，账号与构建脚本见 `docs/rag-eval/`）。

| 评估集 | 规模 | 结果 |
| --- | --- | --- |
| 文本语料（10 篇 md → 44 chunk） | 28 题 + 12 道换说法题 | 混合检索 Hit@1 **100%**；纯向量 78.6%；纯关键词 85.7% |
| 新格式语料（xlsx/pptx/csv/html → 10 chunk） | 13 题 | Recall@5 **13/13 全召回** |

关键结论（完整口径与分析见 `docs/检索效果评估报告.md`）：

- **切片升级**（标题链前缀打进 chunk）：纯向量 Hit@1 60.7% → 78.6%，语义分组 87.5% → 100%。
- **加法提权而非全局重排**：弱重排器曾把 Hit@1 从 92.9% 拉到 89.3%；改为「无标识符时零干预」的加法提权后回到 100%，其余题目零退化。
- **Cross-Encoder 精排（已实现，默认关）**：换说法题集上 gte-rerank-v2 把 Hit@1 从 41.7% 提到 66.7%，但纯 CE 排序会让主集回归到 92.9%——修法是把 CE 分数与标识符加成做**加法组合**（强语义也不拿绝对推翻权）。启用门槛与完整数字见 `docs/检索效果评估报告.md` 第十三节。
- **查询改写默认关闭**：混合检索已到评估集天花板；换说法题集上它救回了唯一漏召回的一题，但排序提升不可复现，不值得每题多一次模型调用。

评估前必须先跑 `ai-service/scripts/verify_index.py`（索引计数一致 + 判分串字面命中），判分口径只有一份实现（`app/eval_judging.py`）。

## 配置说明

`deploy/.env` 关键变量：

| 变量 | 作用 |
| --- | --- |
| `MYSQL_*` / `MINIO_*` | 数据库与对象存储凭据 |
| `DEEPSEEK_API_KEY` | DeepSeek 对话模型密钥 |
| `DASHSCOPE_API_KEY` | 百炼 Embedding 密钥 |
| `AI_SERVICE_INTERNAL_TOKEN` | Backend ↔ AI Service 内部鉴权共享密钥 |

AI Service 任务参数（默认值见 `deploy/docker-compose.yml`）：`AI_SERVICE_TASK_MAX_RETRIES=5`、指数退避 3s 起步（共约 93s，覆盖一次 Milvus 冷启动）、每 5s 恢复未完成任务。切片与检索参数集中在 `ai-service/app/settings.py`。

## 可观测性

- Backend 暴露 `/actuator/prometheus`（只随 8080 暴露在 compose 网络内，nginx 不反代 actuator，公网不可达）：
  - `aicopilot_ai_request_seconds{capability,outcome}` —— 全部 10 个 AI 能力入口的耗时与成败（agent.run / answer.stream / retrieval.search / document.submit …），Prometheus 里可直接算每个能力的 P95 与失败率；
  - `aicopilot_ratelimit_rejected{bucket}` —— 限流拒绝数，判断「被刷」还是「策略配错」的第一信号。
- AI Service 解析任务按阶段打耗时日志（下载 / 切片 / 索引），慢在哪个阶段一眼可见。

## 本地开发

```powershell
# Backend（需 MySQL/Redis/MinIO/Milvus/AI Service，建议直接用 Compose 基础设施）
mvn -f backend/pom.xml test

# AI Service
cd ai-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
pytest -q

# Frontend（vitest 单测 + vue-tsc 类型检查构建）
cd frontend
npm ci
npm run test
npm run build
```

## 验证清单

提交前建议全量跑三层测试（当前基线：后端 121、前端 89、ai-service 220 用例全绿）。CI 会在每次 push / PR 上自动执行同样的三层测试，并用 Docker Compose 拉起真实依赖跑契约探针（模型密钥以 GitHub Secrets 提供时还会跑端到端）。部署后可运行：

```powershell
# 端到端：注册 → 建空间 → 建库 → 上传 → 解析 → 问答 → 校验执行轨迹落库
python scripts/e2e_agent.py

# 端点契约探针：分页契约、参数归一化、登录与 LLM 限流（33 项断言）
python scripts/verify_c_group.py

# API 冒烟（模型密钥只从本地 deploy/.env 进入容器）
powershell -ExecutionPolicy Bypass -File .\deploy\smoke-test.ps1
```

Docker 端到端成功标准：注册/登录成功 → 创建空间与知识库 → 上传解析到 `COMPLETED` → 三种检索返回片段与对象路径 → 回答带引用 → Agent 返回执行轨迹 → 删除文档后检索不到旧内容 → 核心容器 `healthy`。

## 常见问题

**`couldn't find env file`** —— 回到仓库根目录执行 compose 命令（相对路径基于当前目录）。

**登录返回 `401`** —— 项目没有默认账号，先注册；`401` 也可能是账号被封禁或 Token 过期（8 小时）。

**创建知识库返回 `403`** —— 当前用户不是目标工作空间成员；用「一键创建」或让 OWNER 添加成员。

**文档一直不是 `COMPLETED`** —— `docker logs -f ai-copilot-ai-service` 查看解析日志；确认 MinIO/Milvus/MySQL 在运行、模型密钥无多余引号。失败任务会展示失败原因并支持重试。

**检索不到刚上传的内容** —— 确认解析状态为 `COMPLETED`；检索模式建议用 `hybrid`；纯向量对编号、型号、错误码这类字面量不敏感，混合检索靠 BM25 补齐。

## 数据与安全

- `deploy/.env`、模型密钥和本地数据不提交 Git。
- 生产环境必须替换示例密码、接入 HTTPS 与密钥管理，并收紧 compose 中数据库/存储管理端口的暴露范围。
- Token 存 Redis，默认 8 小时；封禁用户后旧 Token 立即失效（校验回查数据库）。
- 建议生产环境补充审计日志、Token 撤销列表与更细粒度 RBAC。

## 后续规划

- Agent 工具执行历史与更多受权限保护的工具。
- 会话导出与跨知识库检索。
- 生产部署方案（HTTPS、审计、细粒度 RBAC）。
