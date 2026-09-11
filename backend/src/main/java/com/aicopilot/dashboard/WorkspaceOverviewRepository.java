package com.aicopilot.dashboard;

import java.util.List;

public interface WorkspaceOverviewRepository {

    WorkspaceOverview findByWorkspaceId(Long workspaceId, Long userId);

    List<Long> findKnowledgeBaseIds(Long workspaceId);
}
