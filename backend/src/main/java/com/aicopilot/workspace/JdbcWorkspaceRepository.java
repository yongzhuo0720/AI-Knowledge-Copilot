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
    public List<Workspace> findByUserId(Long userId) {
        return jdbcTemplate.query(
                "SELECT w.id, w.name, w.owner_user_id FROM workspace w "
                        + "INNER JOIN workspace_member m ON m.workspace_id = w.id "
                        + "WHERE m.user_id = ? ORDER BY w.created_at, w.id",
                (resultSet, rowNumber) -> new Workspace(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getLong("owner_user_id")
                ),
                userId
        );
    }

    @Override
    public void addMember(WorkspaceMember member) {
        jdbcTemplate.update(
                "INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE role = VALUES(role)",
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

    @Override
    public boolean isMember(Long workspaceId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                Integer.class,
                workspaceId,
                userId
        );
        return count != null && count > 0;
    }

    @Override
    public boolean isOwnerOrAdmin(Long workspaceId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_member WHERE workspace_id = ? AND user_id = ? "
                        + "AND role IN ('OWNER', 'ADMIN')",
                Integer.class,
                workspaceId,
                userId
        );
        return count != null && count > 0;
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
