package com.aicopilot.dashboard;

import java.util.List;

public interface IndexedChunkClient {

    Long countChunks(List<Long> knowledgeBaseIds);
}
