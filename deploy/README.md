# Deploy

部署配置目录，提供本地基础设施和业务服务的 Docker Compose 编排。

## 启动

在仓库根目录执行：

```powershell
$dockerExe = "C:\Users\24774\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe"
mvn -s C:\Users\24774\.m2\settings.xml -f backend\pom.xml package -DskipTests
& $dockerExe compose --env-file deploy\.env -f deploy\docker-compose.yml up --build -d
```

## 服务入口

- 前端：`http://localhost`
- 后端：`http://localhost:8080`
- AI 服务：`http://localhost:8000`
- MinIO 控制台：`http://localhost:9001`
- Milvus：`localhost:19530`

## 端到端验证

将新的 `DEEPSEEK_API_KEY` 和 `DASHSCOPE_API_KEY` 写入本地 `deploy/.env` 后重建服务。不要把密钥写入 Git。

```powershell
$dockerExe = "C:\Users\24774\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe"
& $dockerExe compose --env-file .env -f docker-compose.yml up --build -d
```

在 `http://localhost` 创建知识库并上传一个 `.txt`、`.md`、`.pdf` 或 `.docx` 文件，依次点击“查询状态”、输入问题并点击“生成回答”。成功标准是文档状态为 `COMPLETED`、回答非空且回答卡片显示来源对象；随后点击“重新解析”，确认新任务完成且检索仍返回该文档。

知识库和工作空间接口需要 `X-User-Id` 请求头；前端知识库页面会要求填写当前用户 ID，并由后端校验其工作空间成员身份。非成员请求返回 `403 ACCESS_DENIED`。

也可以用容器日志确认任务恢复和失败重试：

```powershell
& $dockerExe logs -f ai-copilot-ai-service
```

## 停止

```powershell
& $dockerExe compose --env-file deploy\.env -f deploy\docker-compose.yml down
```
