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

## 停止

```powershell
& $dockerExe compose --env-file deploy\.env -f deploy\docker-compose.yml down
```
