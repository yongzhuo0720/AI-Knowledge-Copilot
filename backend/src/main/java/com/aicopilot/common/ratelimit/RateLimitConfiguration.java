package com.aicopilot.common.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 {@link RateLimitProperties} 的配置绑定。
 *
 * <p>为什么要单独一个类而不是在 {@code RateLimitProperties} 上直接打 {@code @Component}：
 * 这是本项目一贯的写法（见 {@code MinioStorageConfiguration} / {@code AiServiceConfiguration}）——
 * 属性类保持成**纯粹的 POJO**，只负责装值；「怎么装配进容器」这件事交给配置类。
 * 好处是属性类不依赖 Spring 注解，单元测试里 {@code new RateLimitProperties()} 就能用。
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {
}
