package com.aicopilot.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

@Repository
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
                    "INSERT INTO app_user (username, email, password_hash, status, is_admin) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, user.username());
            statement.setString(2, user.email());
            statement.setString(3, user.passwordHash());
            statement.setString(4, user.status());
            statement.setBoolean(5, user.admin());
            return statement;
        }, keyHolder);

        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("database did not return generated user id");
        }
        return new User(id.longValue(), user.username(), user.email(), user.passwordHash(), user.status(), user.admin());
    }

    @Override
    public java.util.Optional<User> findByEmail(String email) {
        return jdbcTemplate.query(
                "SELECT id, username, email, password_hash, status, is_admin FROM app_user WHERE email = ?",
                (resultSet, rowNumber) -> new User(
                        resultSet.getLong("id"), resultSet.getString("username"), resultSet.getString("email"),
                        resultSet.getString("password_hash"), resultSet.getString("status"),
                        resultSet.getBoolean("is_admin")
                ), email
        ).stream().findFirst();
    }

    @Override
    public java.util.Optional<User> findById(Long id) {
        return jdbcTemplate.query(
                "SELECT id, username, email, password_hash, status, is_admin FROM app_user WHERE id = ?",
                (resultSet, rowNumber) -> new User(
                        resultSet.getLong("id"), resultSet.getString("username"), resultSet.getString("email"),
                        resultSet.getString("password_hash"), resultSet.getString("status"),
                        resultSet.getBoolean("is_admin")
                ), id
        ).stream().findFirst();
    }
}
