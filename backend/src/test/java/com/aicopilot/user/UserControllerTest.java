package com.aicopilot.user;

import com.aicopilot.common.exception.RateLimitExceededException;
import com.aicopilot.common.ratelimit.RateLimitProperties;
import com.aicopilot.common.ratelimit.RateLimiter;
import com.aicopilot.common.security.ProxyTrustProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserControllerTest {

    private UserService userService;
    private RateLimiter rateLimiter;
    private RateLimitProperties properties;
    private UserController controller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        rateLimiter = mock(RateLimiter.class);
        properties = new RateLimitProperties();
        controller = new UserController(userService, mock(AuthenticationService.class), rateLimiter, properties,
                new ProxyTrustProperties());
    }

    @Test
    void limitsRegistrationByTheAddressForwardedByNginx() {
        when(userService.register(any())).thenReturn(new UserResponse(9L, "team", "team@example.com", "ACTIVE", false));
        HttpServletRequest request = mock(HttpServletRequest.class);
        // nginx 容器在 Docker 网段内，属于受信代理
        when(request.getRemoteAddr()).thenReturn("172.18.0.5");
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.9");

        controller.register(new CreateUserRequest("team", "team@example.com", "password-1"), request);

        verify(rateLimiter).consume("register", "203.0.113.9", properties.getRegister());
        verify(userService).register(any());
    }

    @Test
    void ignoresForwardedAddressWhenTheClientConnectsDirectly() {
        when(userService.register(any())).thenReturn(new UserResponse(9L, "team", "team@example.com", "ACTIVE", false));
        HttpServletRequest request = mock(HttpServletRequest.class);
        // 外部客户端直连 8080：即使带了伪造的 X-Real-IP，也只能按 socket 地址限流
        when(request.getRemoteAddr()).thenReturn("198.51.100.20");
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.99");

        controller.register(new CreateUserRequest("team", "team@example.com", "password-1"), request);

        verify(rateLimiter).consume("register", "198.51.100.20", properties.getRegister());
    }

    @Test
    void fallsBackToTheSocketAddressWithoutTheProxyHeader() {
        when(userService.register(any())).thenReturn(new UserResponse(9L, "team", "team@example.com", "ACTIVE", false));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        controller.register(new CreateUserRequest("team", "team@example.com", "password-1"), request);

        verify(rateLimiter).consume("register", "127.0.0.1", properties.getRegister());
    }

    @Test
    void rejectsRegistrationWhenTheIpBucketIsExhausted() {
        doThrow(new RateLimitExceededException("too many requests"))
                .when(rateLimiter).consume(any(), any(), any());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("172.18.0.5");
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.9");

        assertThatThrownBy(() -> controller.register(
                new CreateUserRequest("team", "team@example.com", "password-1"), request))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(userService);
    }
}
