# Docker 部署

本目录提供 MySQL、Redis、MinIO、Milvus、AI Service、Backend 和 Frontend 的 Docker Compose 编排。

## 启动

必须在仓库根目录执行，避免 Compose 找不到环境变量文件：

```powershell
cd D:\ai_project\AI_agent
Copy-Item deploy\.env.example deploy\.env
# 编辑 deploy\.env，填写数据库、MinIO 和模型配置

mvn -s C:\Users\24774\.m2\settings.xml -f backend\pom.xml package -DskipTests
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
DASHSCOPE_API_KEY=
```

`DEEPSEEK_API_KEY` 用于回答生成，`DASHSCOPE_API_KEY` 用于 `text-embedding-v4` 向量生成。密钥为空时，AI Service 仍可启动，但真实模型调用无法完成。

## 端到端验证

1. 打开 <http://localhost/knowledge>。
2. 注册或登录用户。
3. 点击“一键创建”创建工作空间。
4. 创建知识库并上传 `.txt`、`.md`、`.csv`、`.pdf` 或 `.docx`。
5. 点击“查询”，直到文档状态为 `COMPLETED`。
6. 点击“检索”确认返回片段和对象路径。
7. 点击“生成回答”，确认回答非空且有来源引用。
8. 点击“重新解析”，确认任务重新完成且仍可检索。

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
