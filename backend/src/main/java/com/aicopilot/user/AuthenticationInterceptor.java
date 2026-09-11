package com.aicopilot.user;

import com.aicopilot.common.exception.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@Profile("local")
public class AuthenticationInterceptor implements HandlerInterceptor {

    public static final String AUTHENTICATED_USER_ID = "authenticatedUserId";

    private final AuthenticationService authenticationService;

    public AuthenticationInterceptor(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AuthenticationException("Bearer access token is required");
        }
        request.setAttribute(AUTHENTICATED_USER_ID, authenticationService.authenticate(authorization.substring(7).trim()));
        return true;
    }
}
