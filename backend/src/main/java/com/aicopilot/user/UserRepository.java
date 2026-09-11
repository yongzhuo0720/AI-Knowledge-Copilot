package com.aicopilot.user;

public interface UserRepository {

    User save(User user);

    java.util.Optional<User> findByEmail(String email);
}
