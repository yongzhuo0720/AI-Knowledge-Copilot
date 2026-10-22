package com.aicopilot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文冒烟测试：验证「生产配置」下整条装配链（Controller → Service → Repository）
 * 是完整、无环的。
 *
 * <p>只排除 Flyway —— 它会在启动时真的连上 MySQL 执行迁移，单元测试不该依赖一个
 * 活的数据库。DataSource 本身不再排除：HikariCP 在第一次 {@code getConnection()}
 * 之前不会建立物理连接，所以 Context 能照常启动，同时还能验证 datasource 配置项
 * 真的能被解析。
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class ApplicationTest {

    @Test
    void contextLoads() {
    }
}
