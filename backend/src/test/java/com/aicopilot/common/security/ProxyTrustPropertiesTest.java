package com.aicopilot.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyTrustPropertiesTest {

    private final ProxyTrustProperties properties = new ProxyTrustProperties();

    @Test
    void trustsLoopbackAndDockerBridgeOnly() {
        assertThat(properties.isTrustedProxy("127.0.0.1")).isTrue();
        assertThat(properties.isTrustedProxy("127.8.8.8")).isTrue();
        assertThat(properties.isTrustedProxy("172.16.0.1")).isTrue();
        assertThat(properties.isTrustedProxy("172.31.255.254")).isTrue();
        assertThat(properties.isTrustedProxy("::1")).isTrue();

        // 172.16/12 的边界外，以及常见的局域网段都不该被信任
        assertThat(properties.isTrustedProxy("172.15.255.255")).isFalse();
        assertThat(properties.isTrustedProxy("172.32.0.1")).isFalse();
        assertThat(properties.isTrustedProxy("10.0.0.8")).isFalse();
        assertThat(properties.isTrustedProxy("192.168.1.20")).isFalse();
        assertThat(properties.isTrustedProxy("198.51.100.20")).isFalse();
    }

    @Test
    void rejectsUnparseableOrMissingAddresses() {
        assertThat(properties.isTrustedProxy(null)).isFalse();
        assertThat(properties.isTrustedProxy("  ")).isFalse();
        assertThat(properties.isTrustedProxy("not-an-ip")).isFalse();
    }

    @Test
    void respectsConfiguredCidrs() {
        properties.setTrustedProxyCidrs(java.util.List.of("10.1.0.0/16"));

        assertThat(properties.isTrustedProxy("10.1.2.3")).isTrue();
        assertThat(properties.isTrustedProxy("10.2.0.1")).isFalse();
        assertThat(properties.isTrustedProxy("127.0.0.1")).isFalse();
    }
}
