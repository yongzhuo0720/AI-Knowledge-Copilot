package com.aicopilot.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Springdoc OpenAPI 配置。
 *
 * <p>暴露三个端点：
 * <ul>
 *   <li>{@code /swagger-ui.html} —— 可视化调试页面</li>
 *   <li>{@code /v3/api-docs} —— OpenAPI 3.0 JSON 描述</li>
 *   <li>{@code /v3/api-docs.yaml} —— YAML 版本</li>
 * </ul>
 *
 * <p>绝大多数业务接口需要在请求头带 {@code Authorization: Bearer <token>}，
 * 这里把它声明为全局安全方案，Swagger UI 上点一次「Authorize」即可全局复用。
 */
@Configuration
public class OpenApiConfiguration {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Bean
    public OpenAPI copilotOpenApi() {
        Server server = new Server().url(contextPath.isEmpty() ? "/" : contextPath).description("当前实例");
        return new OpenAPI()
                .info(new Info()
                        .title("AI Knowledge Copilot API")
                        .version("v1")
                        .description("""
                                企业知识库 Copilot 后端接口。

                                ## 认证方式
                                1. 调用 `POST /api/v1/users/login` 获取 `accessToken`
                                   - 追加 `"admin": true` 表示走管理员登录，非管理员会被拒
                                2. 点击右上角 **Authorize**，填入 `Bearer <accessToken>`
                                3. 之后所有接口自动带上该请求头

                                ## 分层
                                - `/api/v1/users/**` 账号与登录
                                - `/api/v1/workspaces/**` 工作空间与成员
                                - `/api/v1/knowledge-bases/**` 知识库、文档、问答与检索
                                - `/api/v1/admin/**` 后台管理（**要求管理员权限**）
                                """)
                        .license(new License().name("Private").url("https://example.com")))
                .servers(List.of(server))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME, bearerScheme()))
                // 声明为全局要求：Swagger UI 会对所有接口附带 token
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }

    private SecurityScheme bearerScheme() {
        return new SecurityScheme()
                .name(SECURITY_SCHEME_NAME)
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("登录接口返回的 accessToken（本项目为 UUID，非标准 JWT 签名串）");
    }
}
