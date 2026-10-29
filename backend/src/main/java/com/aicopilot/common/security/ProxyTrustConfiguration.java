package com.aicopilot.common.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用 {@link ProxyTrustProperties} 的配置绑定，写法与 {@code RateLimitConfiguration} 一致。 */
@Configuration
@EnableConfigurationProperties(ProxyTrustProperties.class)
public class ProxyTrustConfiguration {
}
