package com.aicopilot.user;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

@Repository
@Profile("local")
public class JdbcUserRepository implements UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public User save(User user) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO app_user (username, email, password_hash, status) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, user.username());
            statement.setString(2, user.email());
            statement.setString(3, user.passwordHash());
            statement.setString(4, user.status());
            return statement;
        }, keyHolder);

        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("database did not return generated user id");
        }
        return new User(id.longValue(), user.username(), user.email(), user.passwordHash(), user.status());
    }

    @Override
    public java.util.Optional<User> findByEmail(String email) {
        return jdbcTemplate.query(
                "SELECT id, username, email, password_hash, status FROM app_user WHERE email = ?",
                (resultSet, rowNumber) -> new User(
                        resultSet.getLong("id"), resultSet.getString("username"), resultSet.getString("email"),
                        resultSet.getString("password_hash"), resultSet.getString("status")
                ), email
        ).stream().findFirst();
    }
}
