package com.aicopilot.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 限流策略配置，对应 application.yml 里的 {@code rate-limit.*}。
 *
 * <p>为什么要做成配置而不是写死在代码里：这些数字是**运营参数**，不是业务逻辑。
 * 上线后想从「5 次 / 15 分钟」调成「10 次 / 5 分钟」，改配置重启即可，
 * 不需要重新编译打包。面试里这也是常见追问：「这些阈值你怎么定的？」
 * —— 答案是「先按经验给一个保守值，然后按线上 429 的量和误伤率来调」。
 */
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private Policy login = new Policy(5, Duration.ofMinutes(15));
    private Policy ask = new Policy(20, Duration.ofMinutes(1));
    private Policy upload = new Policy(20, Duration.ofMinutes(1));
    private Policy register = new Policy(20, Duration.ofHours(1));

    public Policy getLogin() {
        return login;
    }

    public void setLogin(Policy login) {
        this.login = login;
    }

    public Policy getAsk() {
        return ask;
    }

    public void setAsk(Policy ask) {
        this.ask = ask;
    }

    public Policy getUpload() {
        return upload;
    }

    public void setUpload(Policy upload) {
        this.upload = upload;
    }

    public Policy getRegister() {
        return register;
    }

    public void setRegister(Policy register) {
        this.register = register;
    }

    /**
     * 一条具体策略：在 {@code window} 这个时间窗口内最多允许 {@code maxRequests} 次。
     *
     * <p>用「固定窗口」而不是「滑动窗口」/「令牌桶」是有意的：
     * 固定窗口只需要一次 Redis INCR，实现和排错都简单；代价是窗口边界处
     * 理论上能挤进 2 倍流量（比如 00:59 发 20 次、01:00 再发 20 次）。
     * 对「防爆破 / 防刷」这类目标完全够用 —— 它拦的是持续高频，不是峰值整形。
     */
    public static class Policy {

        private int maxRequests;
        private Duration window;

        public Policy() {
        }

        public Policy(int maxRequests, Duration window) {
            this.maxRequests = maxRequests;
            this.window = window;
        }

        public int getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(int maxRequests) {
            this.maxRequests = maxRequests;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        /** 供 429 响应体拼一句能看懂的话，例如「15 分钟」。 */
        public String describeWindow() {
            if (window == null) {
                return "a while";
            }
            long minutes = window.toMinutes();
            if (minutes >= 1) {
                return minutes + (minutes == 1 ? " minute" : " minutes");
            }
            return window.toSeconds() + " seconds";
        }
    }
}
