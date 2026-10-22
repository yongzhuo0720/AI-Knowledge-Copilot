package com.aicopilot.knowledge;

/**
 * 一条检索命中的文档片段。
 *
 * @param retriever 这条片段是被哪一路召回的：{@code vector} / {@code keyword} / {@code hybrid}。
 *                  混合检索下这个字段能直接看出两路召回各自的贡献，前端会展示。
 */
public record KnowledgeRetrievalChunk(
        String documentObjectKey,
        String content,
        double score,
        String retriever
) {
}
