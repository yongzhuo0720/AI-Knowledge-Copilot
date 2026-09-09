package com.aicopilot.user;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

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
}
