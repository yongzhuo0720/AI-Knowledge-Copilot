package com.aicopilot.user;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.aicopilot.common.cache.CacheService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    @Test
    void registersUserWithEncodedPasswordAndNormalizedEmail() {
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        User savedUser = new User(1L, "alice", "alice@example.com", "encoded", "ACTIVE");
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(repository.save(any(User.class))).thenReturn(savedUser);

        UserResponse response = new UserService(repository, passwordEncoder)
                .register(new CreateUserRequest(" alice ", "ALICE@EXAMPLE.COM", "password123"));

        assertThat(response).isEqualTo(new UserResponse(1L, "alice", "alice@example.com", "ACTIVE"));
        verify(passwordEncoder).encode("password123");
        verify(repository).save(new User(null, "alice", "alice@example.com", "encoded", "ACTIVE"));
    }

    @Test
    void logsInAndStoresBearerTokenInCache() {
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        CacheService cacheService = mock(CacheService.class);
        User user = new User(7L, "alice", "alice@example.com", "encoded", "ACTIVE");
        when(repository.findByEmail("alice@example.com")).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("password123", "encoded")).thenReturn(true);

        LoginResponse response = new AuthenticationService(repository, passwordEncoder, cacheService)
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
                        new AuthenticationService(mock(UserRepository.class), mock(PasswordEncoder.class), cacheService)
                                .authenticate("bad"))
                .isInstanceOf(com.aicopilot.common.exception.AuthenticationException.class);
    }
}
