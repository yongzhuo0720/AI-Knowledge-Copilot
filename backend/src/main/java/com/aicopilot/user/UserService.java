package com.aicopilot.user;

import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Profile("local")
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse register(CreateUserRequest request) {
        User user = new User(
                null,
                request.username().trim(),
                request.email().trim().toLowerCase(),
                passwordEncoder.encode(request.password()),
                "ACTIVE"
        );
        return UserResponse.from(userRepository.save(user));
    }
}
