package com.aicopilot.workspace;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@Profile("local")
public class JdbcWorkspaceRepository implements WorkspaceRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcWorkspaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Workspace save(Workspace workspace) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO workspace (name, owner_user_id) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, workspace.name());
            statement.setLong(2, workspace.ownerUserId());
            return statement;
        }, keyHolder);

        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("database did not return generated workspace id");
        }
        return new Workspace(id.longValue(), workspace.name(), workspace.ownerUserId());
    }

    @Override
    public void addMember(WorkspaceMember member) {
        jdbcTemplate.update(
                "INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, ?)",
                member.workspaceId(),
                member.userId(),
                member.role()
        );
    }

    @Override
    public List<WorkspaceMember> findMembers(Long workspaceId) {
        return jdbcTemplate.query(
                "SELECT workspace_id, user_id, role, joined_at FROM workspace_member "
                        + "WHERE workspace_id = ? ORDER BY joined_at, user_id",
                (resultSet, rowNumber) -> new WorkspaceMember(
                        resultSet.getLong("workspace_id"),
                        resultSet.getLong("user_id"),
                        resultSet.getString("role"),
                        toInstant(resultSet.getTimestamp("joined_at"))
                ),
                workspaceId
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
