package com.aicopilot.common.ratelimit;

import com.aicopilot.common.cache.CacheService;
import com.aicopilot.common.exception.RateLimitExceededException;
import org.springframework.stereotype.Service;

/**
 * 限流器：把「谁、在哪个场景、一个窗口内允许几次」翻译成 Redis 计数器。
 *
 * <p>它只管计数，不管策略 —— 策略由 {@link RateLimitProperties} 提供，
 * 由调用方决定在什么时机扣减。这样同一个限流器既能服务「登录失败计数」
 * （只在失败时加，成功时清零），也能服务「LLM 调用配额」（每次请求都扣）。
 *
 * <p>关于「Redis 挂了怎么办」：这里**不做 fail-open**（不吞掉异常放行）。
 * 因为本项目的 Redis 本来就在鉴权链路上 —— 每个请求都要用它校验 token，
 * 没有 Redis 时登录和调用接口全都跑不通。在这种情况下「限流器自己降级成不设防」
 * 并不能让系统可用，只会让故障更难被发现。真正要做的运维动作是把 Redis 加副本，
 * 而不是在应用层假装它没坏。
 */
@Service
public class RateLimiter {

    /** 限流计数器在 Redis 里的统一前缀，方便运维用 SCAN 一把捞出来排查。 */
    private static final String KEY_PREFIX = "rate-limit:";

    private final CacheService cacheService;

    public RateLimiter(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /**
     * 「先扣额度再放行」：自增计数器并把结果和上限比。超了抛 429。
     *
     * <p>为什么不是「先查后写」（先 peek 判断、通过后再 record）：那样两个并发请求
     * 可能同时通过判断，各自再加一次，一次配额被用了两次。这里靠单次原子自增
     * 完成判断，从结构上就没有这个窗口。
     */
    public void consume(String bucket, String subject, RateLimitProperties.Policy policy) {
        long used = record(bucket, subject, policy);
        if (used > policy.getMaxRequests()) {
            throw new RateLimitExceededException(tooManyRequestsMessage(policy));
        }
    }

    /**
     * 计数 +1 并返回窗口内累计次数。登录场景用它来「只在失败时记账」。
     */
    public long record(String bucket, String subject, RateLimitProperties.Policy policy) {
        return cacheService.increment(key(bucket, subject), policy.getWindow());
    }

    /**
     * 上游拦一道：已经达到上限就直接拒绝，不再继续做后面的校验。
     *
     * <p>登录场景先调它再比对密码，好处有两个：一是被限流时连 bcrypt 都不用算，
     * 顺手挡掉一类「拿密码爆破拖垮 CPU」的攻击；二是攻击者拿不到任何
     * 「这个密码对不对」的反馈。
     */
    public void assertNotBlocked(String bucket, String subject, RateLimitProperties.Policy policy) {
        if (peek(bucket, subject) >= policy.getMaxRequests()) {
            throw new RateLimitExceededException(tooManyRequestsMessage(policy));
        }
    }

    /** 当前窗口内已计数几次。没有副作用。 */
    public long peek(String bucket, String subject) {
        return cacheService.get(key(bucket, subject)).map(Long::valueOf).orElse(0L);
    }

    /**
     * 清零计数。用于「成功之后不再追究历史失败」——否则一个用户只要累计输错 5 次，
     * 哪怕中间成功登录过，后面的失败也会立刻把他锁掉。
     */
    public void clear(String bucket, String subject) {
        cacheService.evict(key(bucket, subject));
    }

    private String key(String bucket, String subject) {
        return KEY_PREFIX + bucket + ":" + subject;
    }

    private String tooManyRequestsMessage(RateLimitProperties.Policy policy) {
        return "too many requests, please retry in " + policy.describeWindow();
    }
}
