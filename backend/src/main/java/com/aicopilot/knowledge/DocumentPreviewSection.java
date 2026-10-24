package com.aicopilot.knowledge;

import java.util.List;

/**
 * 预览的一个小节。{@code titlePath} 是从文档根到当前小节的标题链
 * （空列表表示文档开头没有标题的部分）；相邻同属一节的块已合并，保持文档顺序。
 */
public record DocumentPreviewSection(
        List<String> titlePath,
        List<String> blocks
) {
}
