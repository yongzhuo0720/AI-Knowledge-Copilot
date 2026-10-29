package com.aicopilot.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 可信代理网段配置，对应 {@code app.security.trusted-proxy-cidrs}。
 *
 * <p>{@code X-Real-IP} 是 nginx 覆写的（{@code $remote_addr}），本身可信；但 8080 端口
 * 同时发布到宿主机，**直连后端的人可以自己伪造这个头**。注册限流按 IP 计数，
 * 伪造就意味着无限建号。所以只有当请求确实来自受信代理（nginx 容器的网段）时，
 * 才采用这个头；其余情况一律用 socket 地址。
 *
 * <p>默认值只含回环与 Docker 网桥（172.16/12），**不含** 10/8 与 192.168/16：
 * 局域网客户端直连宿主机端口时，源地址就落在这两个网段里，把它们设为可信
 * 等于把伪造入口原样留着。换部署环境（代理不在 172.16/12）时按需覆盖。
 */
@ConfigurationProperties(prefix = "app.security")
public class ProxyTrustProperties {

    private List<String> trustedProxyCidrs = List.of("127.0.0.1/8", "172.16.0.0/12", "::1/128");

    public List<String> getTrustedProxyCidrs() {
        return trustedProxyCidrs;
    }

    public void setTrustedProxyCidrs(List<String> trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs == null ? List.of() : trustedProxyCidrs;
    }

    /** 地址能否解析、是否落在任一可信网段内。解析失败一律视为不可信。 */
    public boolean isTrustedProxy(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(candidate.trim());
            for (String cidr : trustedProxyCidrs) {
                if (matches(cidr, address)) {
                    return true;
                }
            }
        } catch (UnknownHostException exception) {
            return false;
        }
        return false;
    }

    private static boolean matches(String cidr, InetAddress address) {
        try {
            int slash = cidr.indexOf('/');
            if (slash < 0) {
                return false;
            }
            InetAddress network = InetAddress.getByName(cidr.substring(0, slash).trim());
            int prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
            byte[] expected = network.getAddress();
            byte[] actual = address.getAddress();
            if (expected.length != actual.length || prefix < 0 || prefix > expected.length * 8) {
                return false;
            }
            int fullBytes = prefix / 8;
            for (int index = 0; index < fullBytes; index++) {
                if (expected[index] != actual[index]) {
                    return false;
                }
            }
            int remainder = prefix % 8;
            if (remainder == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainder);
            return (expected[fullBytes] & mask) == (actual[fullBytes] & mask);
        } catch (UnknownHostException | NumberFormatException | ArrayIndexOutOfBoundsException exception) {
            return false;
        }
    }
}
