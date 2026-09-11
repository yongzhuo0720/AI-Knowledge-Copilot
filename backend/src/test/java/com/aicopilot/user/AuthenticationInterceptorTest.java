package com.aicopilot.user;

import com.aicopilot.common.exception.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticationInterceptorTest {

    @Test
    void rejectsRequestWithoutBearerToken() {
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(null);

        assertThatThrownBy(() -> new AuthenticationInterceptor(authenticationService)
                .preHandle(request, mock(HttpServletResponse.class), new Object()))
                .isInstanceOf(AuthenticationException.class)
                .hasMessage("Bearer access token is required");
        verify(authenticationService, never()).authenticate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void storesAuthenticatedUserIdFromBearerToken() {
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer token-123");
        when(authenticationService.authenticate("token-123")).thenReturn(7L);

        boolean handled = new AuthenticationInterceptor(authenticationService)
                .preHandle(request, mock(HttpServletResponse.class), new Object());

        assertThat(handled).isTrue();
        verify(request).setAttribute(AuthenticationInterceptor.AUTHENTICATED_USER_ID, 7L);
    }
}
