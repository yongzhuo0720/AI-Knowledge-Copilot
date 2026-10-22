package com.aicopilot.common.ratelimit;

import com.aicopilot.common.cache.CacheService;
import com.aicopilot.common.cache.InMemoryCacheService;
import com.aicopilot.common.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimiterTest {

    private static final RateLimitProperties.Policy POLICY =
            new RateLimitProperties.Policy(3, Duration.ofMinutes(1));

    private final InMemoryCacheService cache = new InMemoryCacheService();
    private final RateLimiter rateLimiter = new RateLimiter(cache);

    @Test
    void allowsRequestsUpToTheLimit() {
        assertThatCode(() -> {
            rateLimiter.consume("ask", "7", POLICY);
            rateLimiter.consume("ask", "7", POLICY);
            rateLimiter.consume("ask", "7", POLICY);
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsTheRequestThatExceedsTheLimit() {
        rateLimiter.consume("ask", "7", POLICY);
        rateLimiter.consume("ask", "7", POLICY);
        rateLimiter.consume("ask", "7", POLICY);

        assertThatThrownBy(() -> rateLimiter.consume("ask", "7", POLICY))
                .isInstanceOf(RateLimitExceededException.class)
                // 429 的提示语要告诉用户等多久，否则他只能盲试
                .hasMessageContaining("1 minute");
    }

    @Test
    void keepsCountingSeparateSubjectsApart() {
        rateLimiter.consume("ask", "7", POLICY);
        rateLimiter.consume("ask", "7", POLICY);
        rateLimiter.consume("ask", "7", POLICY);

        // 用户 7 用满了额度，不该连累用户 8
        assertThatCode(() -> rateLimiter.consume("ask", "8", POLICY)).doesNotThrowAnyException();
    }

    @Test
    void keepsCountingSeparateBucketsApart() {
        rateLimiter.consume("ask", "7", POLICY);
        rateLimiter.consume("ask", "7", POLICY);
        rateLimiter.consume("ask", "7", POLICY);

        // 同一个用户，但换了一个桶（例如登录），额度互不影响
        assertThatCode(() -> rateLimiter.consume("login", "7", POLICY)).doesNotThrowAnyException();
    }

    @Test
    void usesASingleAtomicIncrementInsteadOfCheckThenWrite() {
        CacheService spied = spy(new InMemoryCacheService());
        RateLimiter limiter = new RateLimiter(spied);

        limiter.consume("ask", "7", POLICY);

        // 这是本类最重要的一条断言：判断超限必须建立在**一次原子自增的返回值**上。
        // 如果是「先 get 判断、再 put 写回」，两个并发请求会同时通过判断，
        // 一次配额被用掉两次 —— 限流器在临界点上少数，等于给了攻击者一把倍速钥匙。
        verify(spied, times(1)).increment(anyString(), any(Duration.class));
        verify(spied, never()).get(anyString());
    }

    @Test
    void assertNotBlockedOnlyRejectsOnceTheLimitIsReached() {
        rateLimiter.record("login", "a@b.com", POLICY);
        rateLimiter.record("login", "a@b.com", POLICY);

        // 2 < 3，还没到上限，放行
        assertThatCode(() -> rateLimiter.assertNotBlocked("login", "a@b.com", POLICY))
                .doesNotThrowAnyException();

        rateLimiter.record("login", "a@b.com", POLICY);

        // 3 >= 3，拦下
        assertThatThrownBy(() -> rateLimiter.assertNotBlocked("login", "a@b.com", POLICY))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void assertNotBlockedHasNoSideEffectOnTheCounter() {
        rateLimiter.record("login", "a@b.com", POLICY);

        rateLimiter.assertNotBlocked("login", "a@b.com", POLICY);
        rateLimiter.assertNotBlocked("login", "a@b.com", POLICY);

        // 光「看一眼」不该把计数推上去，否则成功登录的用户会被自己看死
        assertThat(rateLimiter.peek("login", "a@b.com")).isEqualTo(1);
    }

    @Test
    void clearResetsTheCounter() {
        rateLimiter.record("login", "a@b.com", POLICY);
        rateLimiter.record("login", "a@b.com", POLICY);

        rateLimiter.clear("login", "a@b.com");

        assertThat(rateLimiter.peek("login", "a@b.com")).isZero();
    }

    @Test
    void peekReturnsZeroForAnUnknownSubject() {
        assertThat(rateLimiter.peek("login", "never-seen@example.com")).isZero();
    }

    @Test
    void recordReturnsTheRunningCount() {
        assertThat(rateLimiter.record("login", "a@b.com", POLICY)).isEqualTo(1);
        assertThat(rateLimiter.record("login", "a@b.com", POLICY)).isEqualTo(2);
        assertThat(rateLimiter.record("login", "a@b.com", POLICY)).isEqualTo(3);
    }
}
