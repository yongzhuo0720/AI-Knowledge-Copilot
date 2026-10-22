package com.aicopilot.user;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.aicopilot.common.cache.CacheService;
import com.aicopilot.common.ratelimit.RateLimitProperties;
import com.aicopilot.common.ratelimit.RateLimiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    /**
     * 本测试只关心「注册 / 登录写 token / 校验 token」三件事，不关心限流，
     * 所以这里塞一个真实的限流器 + 默认策略，但底下的 CacheService 是 mock：
     * mock 的 `get` 默认返回 `Optional.empty()`、`increment` 默认返回 0，
     * 等价于「窗口内一次都没记过」，永远不会触发限流，正好不干扰这里的断言。
     */
    private AuthenticationService authenticationService(
            UserRepository repository, PasswordEncoder passwordEncoder, CacheService cacheService) {
        return new AuthenticationService(repository, passwordEncoder, cacheService,
                new RateLimiter(cacheService), new RateLimitProperties());
    }

    @Test
    void registersUserWithEncodedPasswordAndNormalizedEmail() {
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        User savedUser = new User(1L, "alice", "alice@example.com", "encoded", "ACTIVE", false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(repository.save(any(User.class))).thenReturn(savedUser);

        UserResponse response = new UserService(repository, passwordEncoder)
                .register(new CreateUserRequest(" alice ", "ALICE@EXAMPLE.COM", "password123"));

        assertThat(response).isEqualTo(new UserResponse(1L, "alice", "alice@example.com", "ACTIVE", false));
        verify(passwordEncoder).encode("password123");
        verify(repository).save(new User(null, "alice", "alice@example.com", "encoded", "ACTIVE", false));
    }

    @Test
    void logsInAndStoresBearerTokenInCache() {
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        CacheService cacheService = mock(CacheService.class);
        User user = new User(7L, "alice", "alice@example.com", "encoded", "ACTIVE", false);
        when(repository.findByEmail("alice@example.com")).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("password123", "encoded")).thenReturn(true);

        LoginResponse response = authenticationService(repository, passwordEncoder, cacheService)
                .login(new LoginRequest("ALICE@EXAMPLE.COM", "password123"));

        assertThat(response.user()).isEqualTo(UserResponse.from(user));
        assertThat(response.accessToken()).isNotBlank();
        verify(cacheService).put(
                org.mockito.ArgumentMatchers.startsWith("auth:token:"),
                org.mockito.ArgumentMatchers.eq("7"),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class)
        );
    }

    @Test
    void rejectsInvalidBearerToken() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.get("auth:token:bad")).thenReturn(java.util.Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        authenticationService(mock(UserRepository.class), mock(PasswordEncoder.class), cacheService)
                                .authenticate("bad"))
                .isInstanceOf(com.aicopilot.common.exception.AuthenticationException.class);
    }
}
