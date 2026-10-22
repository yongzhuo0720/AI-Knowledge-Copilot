package com.aicopilot.common.exception;

/**
 * 触发限流（速率超限 / 登录尝试次数超限）。
 *
 * <p>映射为 HTTP 429 Too Many Requests，和 401 / 403 / 400 都不同：
 * 它不是「你没权限」，而是「你先停一下」。前端拿到 429 应该提示稍后重试，
 * 而不是像 401 那样直接把人踢回登录页 —— 那样用户会以为账号出了问题。
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
