package com.aicopilot.user;

import com.aicopilot.common.exception.RateLimitExceededException;
import com.aicopilot.common.ratelimit.RateLimitProperties;
import com.aicopilot.common.ratelimit.RateLimiter;
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
        controller = new UserController(userService, mock(AuthenticationService.class), rateLimiter, properties);
    }

    @Test
    void limitsRegistrationByTheAddressForwardedByNginx() {
        when(userService.register(any())).thenReturn(new UserResponse(9L, "team", "team@example.com", "ACTIVE", false));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.9");

        controller.register(new CreateUserRequest("team", "team@example.com", "password-1"), request);

        verify(rateLimiter).consume("register", "203.0.113.9", properties.getRegister());
        verify(userService).register(any());
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
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.9");

        assertThatThrownBy(() -> controller.register(
                new CreateUserRequest("team", "team@example.com", "password-1"), request))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(userService);
    }
}
