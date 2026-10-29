package com.aicopilot.knowledge;

import com.aicopilot.common.exception.RateLimitExceededException;
import com.aicopilot.common.ratelimit.RateLimitProperties;
import com.aicopilot.common.ratelimit.RateLimiter;
import com.aicopilot.user.AuthenticationInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeControllerTest {

    private KnowledgeService knowledgeService;
    private RateLimiter rateLimiter;
    private RateLimitProperties properties;
    private KnowledgeController controller;

    @BeforeEach
    void setUp() {
        knowledgeService = mock(KnowledgeService.class);
        rateLimiter = mock(RateLimiter.class);
        properties = new RateLimitProperties();
        controller = new KnowledgeController(knowledgeService, rateLimiter, properties);
    }

    @Test
    void chargesAskQuotaBeforeRetrieval() {
        KnowledgeSearchRequest request = new KnowledgeSearchRequest("发布规则", 5, "hybrid");
        when(knowledgeService.search(7L, 20L, request)).thenReturn(List.of());

        controller.search(20L, authenticatedRequest(7L), request);

        verify(rateLimiter).consume("ask", "7", properties.getAsk());
        verify(knowledgeService).search(7L, 20L, request);
    }

    @Test
    void rejectsRetrievalWithoutTouchingTheServiceWhenQuotaIsExhausted() {
        doThrow(new RateLimitExceededException("too many requests"))
                .when(rateLimiter).consume(any(), any(), any());

        assertThatThrownBy(() -> controller.search(
                20L, authenticatedRequest(7L), new KnowledgeSearchRequest("发布规则", 5, null)))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(knowledgeService);
    }

    @Test
    void chargesUploadQuotaForMultipartUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "guide.pdf", "application/pdf", new byte[] {1});
        when(knowledgeService.uploadDocument(any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(new KnowledgeDocumentResponse(
                        30L, 20L, "guide.pdf", "kb/20/u/guide.pdf", "application/pdf", 1L,
                        "ACCEPTED", "task-1", java.time.Instant.now()));

        controller.uploadDocument(20L, authenticatedRequest(7L), file);

        verify(rateLimiter).consume("upload", "7", properties.getUpload());
    }

    @Test
    void chargesUploadQuotaWhenReparsing() {
        when(knowledgeService.reparseDocument(7L, 20L, 30L)).thenReturn(document());

        controller.reparseDocument(20L, 30L, authenticatedRequest(7L));

        // 重新解析同样会重跑 Embedding，必须和上传共用同一个桶
        verify(rateLimiter).consume("upload", "7", properties.getUpload());
        verify(knowledgeService).reparseDocument(7L, 20L, 30L);
    }

    @Test
    void rejectsReparseWithoutTouchingTheServiceWhenQuotaIsExhausted() {
        doThrow(new RateLimitExceededException("too many requests"))
                .when(rateLimiter).consume(any(), any(), any());

        assertThatThrownBy(() -> controller.reparseDocument(20L, 30L, authenticatedRequest(7L)))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(knowledgeService);
    }

    @Test
    void chargesUploadQuotaWhenRetryingProcessing() {
        when(knowledgeService.retryDocumentProcessing(7L, 20L, 30L)).thenReturn(document());

        controller.retryDocumentProcessing(20L, 30L, authenticatedRequest(7L));

        verify(rateLimiter).consume("upload", "7", properties.getUpload());
        verify(knowledgeService).retryDocumentProcessing(7L, 20L, 30L);
    }

    @Test
    void rejectsRetryWithoutTouchingTheServiceWhenQuotaIsExhausted() {
        doThrow(new RateLimitExceededException("too many requests"))
                .when(rateLimiter).consume(any(), any(), any());

        assertThatThrownBy(() -> controller.retryDocumentProcessing(20L, 30L, authenticatedRequest(7L)))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(knowledgeService);
    }

    private KnowledgeDocumentResponse document() {
        return new KnowledgeDocumentResponse(
                30L, 20L, "guide.pdf", "kb/20/u/guide.pdf", "application/pdf", 1L,
                "ACCEPTED", "task-1", java.time.Instant.now());
    }

    private HttpServletRequest authenticatedRequest(Long userId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthenticationInterceptor.AUTHENTICATED_USER_ID)).thenReturn(userId);
        return request;
    }
}
