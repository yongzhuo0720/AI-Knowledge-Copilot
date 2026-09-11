package com.aicopilot.user;

import com.aicopilot.common.cache.CacheService;
import com.aicopilot.common.exception.AuthenticationException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@Profile("local")
public class AuthenticationService {

    private static final Duration TOKEN_TTL = Duration.ofHours(8);
    private static final String TOKEN_PREFIX = "auth:token:";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CacheService cacheService;

    public AuthenticationService(UserRepository userRepository, PasswordEncoder passwordEncoder, CacheService cacheService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.cacheService = cacheService;
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .filter(candidate -> "ACTIVE".equals(candidate.status()))
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.passwordHash()))
                .orElseThrow(() -> new AuthenticationException("invalid email or password"));
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        cacheService.put(TOKEN_PREFIX + token, user.id().toString(), TOKEN_TTL);
        return new LoginResponse(token, UserResponse.from(user));
    }

    public Long authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthenticationException("authentication is required");
        }
        return cacheService.get(TOKEN_PREFIX + token)
                .map(Long::valueOf)
                .orElseThrow(() -> new AuthenticationException("invalid or expired access token"));
    }
}
