package com.aicopilot.user;

import com.aicopilot.common.cache.CacheService;
import com.aicopilot.common.exception.AuthenticationException;
import com.aicopilot.common.exception.RateLimitExceededException;
import com.aicopilot.common.ratelimit.RateLimitProperties;
import com.aicopilot.common.ratelimit.RateLimiter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthenticationService {

    private static final Duration TOKEN_TTL = Duration.ofHours(8);
    private static final String TOKEN_PREFIX = "auth:token:";

    /** 限流「桶」名。同一个限流器管多个场景，靠桶名隔开，互不影响。 */
    private static final String LOGIN_BUCKET = "login";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CacheService cacheService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public AuthenticationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            CacheService cacheService,
            RateLimiter rateLimiter,
            RateLimitProperties rateLimitProperties
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.cacheService = cacheService;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    /**
     * 登录。带**失败次数限流**，防止密码爆破。
     *
     * <p>限流的对象是「邮箱」而不是「IP」，这是一个有意识的取舍：
     * <ul>
     *   <li>按邮箱记：攻击者想爆破某个账号，无论换多少台机器、多少代理，都会被拦。
     *       代价是攻击者可以故意把某个账号锁住 15 分钟（一种 DoS）。因为窗口很短，
     *       而且只影响登录、不影响已登录的会话，这个代价可以接受。</li>
     *   <li>按 IP 记：绕过成本极低（换代理即可），但不会误伤他人。</li>
     * </ul>
     * 生产环境通常两个维度都上，本项目按邮箱这一个维度已经能挡住脚本爆破。
     *
     * <p>还有一个细节：**只统计失败**。如果每次请求都计数，一个正常用户在
     * 15 分钟里刷新登录页 5 次就会被自己锁住。所以成功登录会把计数清零。
     */
    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        RateLimitProperties.Policy policy = rateLimitProperties.getLogin();

        // 先过闸门，再比对密码：被限流时连 bcrypt 都不算，
        // 既省 CPU，也不给攻击者任何「这个密码对不对」的反馈。
        rateLimiter.assertNotBlocked(LOGIN_BUCKET, email, policy);

        Optional<User> candidate = userRepository.findByEmail(email)
                .filter(user -> "ACTIVE".equals(user.status()))
                .filter(user -> passwordEncoder.matches(request.password(), user.passwordHash()));

        if (candidate.isEmpty()) {
            long failures = rateLimiter.record(LOGIN_BUCKET, email, policy);
            if (failures >= policy.getMaxRequests()) {
                throw new RateLimitExceededException(
                        "too many failed login attempts, please retry in " + policy.describeWindow());
            }
            throw new AuthenticationException("invalid email or password");
        }

        User user = candidate.get();
        // 密码已经证明「你是谁」。此刻清掉失败计数，因为后面即使被拒，
        // 也不是「密码错」——不该让正确的密码替别人背锅。
        rateLimiter.clear(LOGIN_BUCKET, email);

        // 再检查「你够不够格」——顺序不能反，否则会泄露账号是否存在。
        if (request.requiresAdmin() && !user.admin()) {
            throw new AuthenticationException("administrator privilege is required");
        }

        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        cacheService.put(TOKEN_PREFIX + token, user.id().toString(), TOKEN_TTL);
        return new LoginResponse(token, UserResponse.from(user));
    }

    public Long authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthenticationException("authentication is required");
        }
        Long userId = cacheService.get(TOKEN_PREFIX + token)
                .map(Long::valueOf)
                .orElseThrow(() -> new AuthenticationException("invalid or expired access token"));
        // Token 有效不等于账号有效：账号可能已被封禁或删除。
        // 如果不回查数据库，封禁后旧 token 最长还能继续用 8 小时，封禁形同虚设。
        return userRepository.findById(userId)
                .filter(candidate -> "ACTIVE".equals(candidate.status()))
                .orElseThrow(() -> new AuthenticationException("account is inactive or no longer exists"))
                .id();
    }

    /**
     * 登出：把 token 从 Redis 删除，让它立刻失效。
     *
     * <p>返回 {@code true} 表示确实删掉了一个有效 token；{@code false} 表示请求头里
     * 没有合法 Bearer token（已经登出过 / 已过期）。后者**不抛异常**——登出应该是
     * 幂等的，重复登出不该报错，否则前端重试会看到错误信息。
     */
    public boolean logout(String authorization) {
        String token = extractBearerToken(authorization);
        if (token == null) {
            return false;
        }
        cacheService.evict(TOKEN_PREFIX + token);
        return true;
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
