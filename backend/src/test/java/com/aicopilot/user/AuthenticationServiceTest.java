package com.aicopilot.user;

import com.aicopilot.common.cache.CacheService;
import com.aicopilot.common.cache.InMemoryCacheService;
import com.aicopilot.common.exception.AuthenticationException;
import com.aicopilot.common.exception.RateLimitExceededException;
import com.aicopilot.common.ratelimit.RateLimitProperties;
import com.aicopilot.common.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录限流（防密码爆破）的行为测试。
 *
 * <p>这里用内存版 CacheService 而不是 mock，因为要断言的正是「第几次会变脸」——
 * 那是计数的真实累积过程，用 mock 硬编返回值等于把要测的东西替掉了。
 */
class AuthenticationServiceTest {

    private static final String EMAIL = "alice@example.com";
    private static final String RIGHT_PASSWORD = "right-password";
    private static final int MAX_ATTEMPTS = 5;

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private CacheService cacheService;
    private AuthenticationService authenticationService;

    private static final User ALICE = new User(7L, "alice", EMAIL, "encoded", "ACTIVE", false);

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        cacheService = new InMemoryCacheService();
        authenticationService = new AuthenticationService(
                userRepository, passwordEncoder, cacheService,
                new RateLimiter(cacheService), new RateLimitProperties());

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(ALICE));
        when(passwordEncoder.matches(RIGHT_PASSWORD, "encoded")).thenReturn(true);
    }

    @Test
    void letsTheUserTryUpToTheLimit() {
        // 前 4 次是「密码错」，返回的是 401 语义的 AuthenticationException，不是限流
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            assertThatThrownBy(this::loginWithWrongPassword)
                    .isInstanceOf(AuthenticationException.class);
        }
    }

    @Test
    void locksTheAccountOnceFailuresReachTheLimit() {
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            assertThatThrownBy(this::loginWithWrongPassword).isInstanceOf(AuthenticationException.class);
        }

        // 第 5 次失败即达到上限：从此返回 429，语义从「密码错」变成「先别试了」
        assertThatThrownBy(this::loginWithWrongPassword)
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("15 minutes");
    }

    @Test
    void refusesEvenTheCorrectPasswordWhileLockedOut() {
        exhaustTheLimit();

        // 这条是整个限流的成败关键：如果锁上之后正确密码还能进来，
        // 攻击者只要一直猜就总有命中一次的机会，限流形同虚设。
        assertThatThrownBy(() -> authenticationService.login(new LoginRequest(EMAIL, RIGHT_PASSWORD)))
                .isInstanceOf(RateLimitExceededException.class);

        // 而且不该走到密码比对 —— 被限流时连 bcrypt 都不算，顺手挡掉「用爆破拖垮 CPU」
        verify(passwordEncoder, never()).matches(RIGHT_PASSWORD, "encoded");
    }

    @Test
    void successfulLoginClearsTheFailureCounter() {
        assertThatThrownBy(this::loginWithWrongPassword).isInstanceOf(AuthenticationException.class);
        assertThatThrownBy(this::loginWithWrongPassword).isInstanceOf(AuthenticationException.class);

        assertThatCode(() -> authenticationService.login(new LoginRequest(EMAIL, RIGHT_PASSWORD)))
                .doesNotThrowAnyException();

        // 计数已清零，所以接下来还能再错 4 次才被锁；
        // 如果没有清零，这里第一次失败就会把计数推到 3、第二次 4……很快误伤。
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            assertThatThrownBy(this::loginWithWrongPassword).isInstanceOf(AuthenticationException.class);
        }
    }

    @Test
    void caseVariationsOfTheSameEmailShareOneCounter() {
        // 邮箱大小写不该成为绕过限流的办法：'ALICE@…' 和 'alice@…' 是同一个账号
        String[] spellings = {"ALICE@EXAMPLE.COM", "Alice@Example.com", "aLiCe@ExAmPlE.cOm", EMAIL};
        for (String spelling : spellings) {
            assertThatThrownBy(() -> authenticationService.login(new LoginRequest(spelling, "wrong")))
                    .isInstanceOf(AuthenticationException.class);
        }

        assertThatThrownBy(() -> authenticationService.login(new LoginRequest("ALICE@example.COM", "wrong")))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void lockoutIsScopedToTheAttackedAccountOnly() {
        exhaustTheLimit();

        when(userRepository.findByEmail("bob@example.com"))
                .thenReturn(Optional.of(new User(8L, "bob", "bob@example.com", "encoded", "ACTIVE", false)));

        // 有人拿 alice 的邮箱乱试，不该把 bob 也挡在门外
        assertThatCode(() -> authenticationService.login(new LoginRequest("bob@example.com", RIGHT_PASSWORD)))
                .doesNotThrowAnyException();
    }

    @Test
    void aCorrectPasswordClearsTheCounterEvenWhenTheAdminCheckThenFails() {
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            assertThatThrownBy(this::loginWithWrongPassword).isInstanceOf(AuthenticationException.class);
        }

        // 密码是对的，只是这个人不是管理员。这不算「密码错误」，
        // 不该让一个正确的密码替别人背锅、把账号推向锁定。
        assertThatThrownBy(() -> authenticationService.login(new LoginRequest(EMAIL, RIGHT_PASSWORD, true)))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("administrator");

        // 计数已清零：接下来仍有完整的 4 次重试机会
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            assertThatThrownBy(this::loginWithWrongPassword).isInstanceOf(AuthenticationException.class);
        }
    }

    @Test
    void inactiveAccountFailuresAlsoCountTowardTheLimit() {
        when(userRepository.findByEmail(EMAIL))
                .thenReturn(Optional.of(new User(7L, "alice", EMAIL, "encoded", "DISABLED", false)));

        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            assertThatThrownBy(this::loginWithWrongPassword).isInstanceOf(AuthenticationException.class);
        }

        // 已停用的账号同样受保护：否则「账号存在但已停用」会成为唯一不限流的分支
        assertThatThrownBy(this::loginWithWrongPassword).isInstanceOf(RateLimitExceededException.class);
    }

    private void loginWithWrongPassword() {
        authenticationService.login(new LoginRequest(EMAIL, "wrong-password"));
    }

    private void exhaustTheLimit() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                loginWithWrongPassword();
            } catch (RuntimeException ignored) {
                // 第 5 次会抛 RateLimitExceededException，这里只想把计数撞到上限
            }
        }
    }
}
