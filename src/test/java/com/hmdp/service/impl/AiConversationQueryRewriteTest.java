package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.ai.AiAgentRunner;
import com.hmdp.ai.AiPromptBuildResult;
import com.hmdp.ai.AiQueryPreprocessor;
import com.hmdp.ai.AiQueryRewriteMode;
import com.hmdp.ai.AiRetrievalQueryPlan;
import com.hmdp.ai.AiTokenEstimator;
import com.hmdp.config.AiChatProperties;
import com.hmdp.config.AiMemoryProperties;
import com.hmdp.entity.AiConversation;
import com.hmdp.entity.AiMessage;
import com.hmdp.service.IAiMessageService;
import com.hmdp.service.IShopKnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiConversationQueryRewriteTest {

    @Mock
    private IAiMessageService aiMessageService;

    @Mock
    private AiQueryPreprocessor queryPreprocessor;

    @Mock
    private IShopKnowledgeService shopKnowledgeService;

    @Mock
    private AiAgentRunner aiAgentRunner;

    private AiConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiConversationServiceImpl();
        ReflectionTestUtils.setField(service, "aiMessageService", aiMessageService);
        ReflectionTestUtils.setField(service, "aiQueryPreprocessor", queryPreprocessor);
        ReflectionTestUtils.setField(service, "shopKnowledgeService", shopKnowledgeService);
        ReflectionTestUtils.setField(service, "aiAgentRunner", aiAgentRunner);
        ReflectionTestUtils.setField(service, "aiChatProperties", new AiChatProperties());
        ReflectionTestUtils.setField(service, "aiMemoryProperties", new AiMemoryProperties());
        ReflectionTestUtils.setField(service, "aiTokenEstimator", new AiTokenEstimator());
    }

    @Test
    void clarificationShouldSkipRetrievalAndTools() {
        Page<AiMessage> page = new Page<>(1, 6);
        page.setRecords(Collections.<AiMessage>emptyList());
        when(aiMessageService.page(any(Page.class), any())).thenReturn(page);
        when(queryPreprocessor.preprocess(
                any(), any(), any(), any(), any(), anyList()
        )).thenReturn(new AiRetrievalQueryPlan(
                AiQueryRewriteMode.CLARIFY,
                Collections.<String>emptyList(),
                "\u8bf7\u544a\u8bc9\u6211\u5177\u4f53\u6307\u7684\u662f\u54ea\u5bb6\u5e97\u3002",
                false,
                true,
                0L,
                7,
                0
        ));

        AiConversation conversation = new AiConversation();
        conversation.setId(1L);
        conversation.setUserId(2L);
        AiPromptBuildResult result = ReflectionTestUtils.invokeMethod(
                service,
                "buildPrompt",
                conversation,
                20L,
                10L,
                "\u5b83\u6709\u4ec0\u4e48\u4f18\u60e0\uff1f",
                null,
                null
        );

        assertNotNull(result);
        assertTrue(result.getMessages().isEmpty());
        assertEquals(0, result.getInputTokens());
        assertNotNull(result.getDirectResponse());
        verify(shopKnowledgeService, never()).searchRelevantShops(anyList());
        verify(aiAgentRunner, never()).run(any(), any(), any(), any(), any(), anyList());
    }
}
