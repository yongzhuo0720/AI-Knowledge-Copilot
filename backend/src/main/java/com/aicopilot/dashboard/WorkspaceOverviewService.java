package com.aicopilot.dashboard;

import com.aicopilot.common.exception.AccessDeniedException;
import com.aicopilot.workspace.WorkspaceRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Profile("local")
public class WorkspaceOverviewService {

    private final WorkspaceOverviewRepository overviewRepository;
    private final WorkspaceRepository workspaceRepository;
    private final IndexedChunkClient indexedChunkClient;

    public WorkspaceOverviewService(WorkspaceOverviewRepository overviewRepository,
                                    WorkspaceRepository workspaceRepository,
                                    IndexedChunkClient indexedChunkClient) {
        this.overviewRepository = overviewRepository;
        this.workspaceRepository = workspaceRepository;
        this.indexedChunkClient = indexedChunkClient;
    }

    @Transactional(readOnly = true)
    public WorkspaceOverview findForUser(Long userId, Long workspaceId) {
        if (userId == null || !workspaceRepository.isMember(workspaceId, userId)) {
            throw new AccessDeniedException("user is not a member of this workspace");
        }
        WorkspaceOverview overview = overviewRepository.findByWorkspaceId(workspaceId, userId);
        List<Long> knowledgeBaseIds = overviewRepository.findKnowledgeBaseIds(workspaceId);
        Long indexedChunkCount = indexedChunkClient.countChunks(knowledgeBaseIds);
        return new WorkspaceOverview(
                overview.workspaceId(), overview.knowledgeBaseCount(), overview.documentCount(), indexedChunkCount,
                overview.conversationCount(), overview.processingTaskCount(), overview.recentKnowledgeBases(),
                overview.recentDocuments(), overview.recentConversations()
        );
    }
}
