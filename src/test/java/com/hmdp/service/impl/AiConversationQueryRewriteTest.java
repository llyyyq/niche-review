package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.ai.AiAgentRunner;
import com.hmdp.ai.AiPromptBuildResult;
import com.hmdp.ai.AiQueryPlan;
import com.hmdp.ai.AiQueryPlanPlanner;
import com.hmdp.ai.AiQueryPlanResolver;
import com.hmdp.ai.AiQueryPlanStatus;
import com.hmdp.ai.AiQueryTask;
import com.hmdp.ai.AiResolvedQueryPlan;
import com.hmdp.ai.AiResolvedQueryTask;
import com.hmdp.ai.AiTaskRetrievalResult;
import com.hmdp.ai.AiToolExecution;
import com.hmdp.ai.ShopKnowledge;
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
    private AiQueryPlanPlanner queryPlanPlanner;

    @Mock
    private AiQueryPlanResolver queryPlanResolver;

    @Mock
    private IShopKnowledgeService shopKnowledgeService;

    @Mock
    private AiAgentRunner aiAgentRunner;

    private AiConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiConversationServiceImpl();
        ReflectionTestUtils.setField(service, "aiMessageService", aiMessageService);
        ReflectionTestUtils.setField(service, "aiQueryPlanPlanner", queryPlanPlanner);
        ReflectionTestUtils.setField(service, "aiQueryPlanResolver", queryPlanResolver);
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
        when(queryPlanPlanner.plan(
                any(), any(), any(), any(), any(), anyList()
        )).thenReturn(new AiQueryPlan(
                AiQueryPlanStatus.CLARIFY,
                Collections.emptyList(),
                "\u8bf7\u544a\u8bc9\u6211\u5177\u4f53\u6307\u7684\u662f\u54ea\u5bb6\u5e97\u3002",
                false,
                true,
                0L,
                7,
                0,
                null
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
        assertEquals("QUERY_PREPROCESS", result.getFailureStage());
        verify(queryPlanResolver, never()).resolve(any(), any());
        verify(shopKnowledgeService, never()).searchRelevantTaskResults(any(), any());
        verify(aiAgentRunner, never()).runPerTask(any(), any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void failedPlanShouldStopAtQueryPreprocess() {
        Page<AiMessage> page = new Page<>(1, 6);
        page.setRecords(Collections.<AiMessage>emptyList());
        when(aiMessageService.page(any(Page.class), any())).thenReturn(page);
        when(queryPlanPlanner.plan(any(), any(), any(), any(), any(), anyList()))
                .thenReturn(new AiQueryPlan(AiQueryPlanStatus.FAILED,
                        Collections.emptyList(), "当前查询服务繁忙，请稍后重试。",
                        true, false, 10L, 8, 0, "provider timeout"));
        AiConversation conversation = new AiConversation();
        conversation.setId(1L);
        conversation.setUserId(2L);

        AiPromptBuildResult result = ReflectionTestUtils.invokeMethod(
                service, "buildPrompt", conversation, 20L, 10L,
                "推荐餐厅", null, null);

        assertEquals("FAILED", result.getOutcome());
        assertEquals("QUERY_PREPROCESS", result.getFailureStage());
        verify(queryPlanResolver, never()).resolve(any(), any());
        verify(shopKnowledgeService, never()).searchRelevantTaskResults(any(), any());
    }

    @Test
    void skippedToolsOnlyShouldKeepNoEvidenceShortcut() {
        Page<AiMessage> page = new Page<>(1, 6);
        page.setRecords(Collections.<AiMessage>emptyList());
        when(aiMessageService.page(any(Page.class), any())).thenReturn(page);
        AiQueryTask task = new AiQueryTask(
                "FACT_LOOKUP", "炉鱼有什么优惠？", Collections.singletonList("炉鱼"),
                Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(), Collections.singletonList("ACTIVE_VOUCHER"));
        AiQueryPlan ready = new AiQueryPlan(AiQueryPlanStatus.READY,
                Collections.singletonList(task), null, true, true, 0L, 8, 8, null);
        AiResolvedQueryTask resolvedTask = new AiResolvedQueryTask(task,
                Collections.singletonList(7L), Collections.<Long>emptyList(),
                Collections.<Long>emptyList(), Collections.<String>emptyList());
        AiResolvedQueryPlan resolvedPlan = new AiResolvedQueryPlan(AiQueryPlanStatus.READY,
                Collections.singletonList(resolvedTask), null, ready);
        when(queryPlanPlanner.plan(any(), any(), any(), any(), any(), anyList())).thenReturn(ready);
        when(queryPlanResolver.resolve(null, ready)).thenReturn(resolvedPlan);
        when(shopKnowledgeService.searchRelevantTaskResults(null, resolvedPlan)).thenReturn(
                Collections.singletonList(new AiTaskRetrievalResult(1, resolvedTask,
                        Collections.<ShopKnowledge>emptyList())));
        when(aiAgentRunner.runPerTask(any(), any(), any(), any(), any(), any(), anyList()))
                .thenReturn(Collections.singletonList(
                        AiToolExecution.skipped("voucherQuery", "Not queried.")));
        AiConversation conversation = new AiConversation();
        conversation.setId(1L);
        conversation.setUserId(2L);

        AiPromptBuildResult result = ReflectionTestUtils.invokeMethod(
                service, "buildPrompt", conversation, 20L, 10L,
                "炉鱼有什么优惠？", null, null);

        assertEquals("NO_EVIDENCE", result.getOutcome());
        assertNotNull(result.getDirectResponse());
    }

    @Test
    void resolverClarificationShouldStopAtQueryPlanResolve() {
        Page<AiMessage> page = new Page<>(1, 6);
        page.setRecords(Collections.<AiMessage>emptyList());
        when(aiMessageService.page(any(Page.class), any())).thenReturn(page);
        AiQueryTask task = new AiQueryTask(
                "COMPARE", "比较六家店", Collections.singletonList("店铺一"),
                Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(), Collections.singletonList("SHOP_DETAIL"));
        AiQueryPlan ready = new AiQueryPlan(AiQueryPlanStatus.READY,
                Collections.singletonList(task), null, true, true,
                5L, 5, 5, null);
        when(queryPlanPlanner.plan(any(), any(), any(), any(), any(), anyList()))
                .thenReturn(ready);
        when(queryPlanResolver.resolve(null, ready)).thenReturn(
                new AiResolvedQueryPlan(AiQueryPlanStatus.CLARIFY,
                        Collections.emptyList(), "一次最多处理5家明确店铺。", ready));
        AiConversation conversation = new AiConversation();
        conversation.setId(1L);
        conversation.setUserId(2L);

        AiPromptBuildResult result = ReflectionTestUtils.invokeMethod(
                service, "buildPrompt", conversation, 20L, 10L,
                "比较六家店", null, null);

        assertEquals("CLARIFIED", result.getOutcome());
        assertEquals("QUERY_PLAN_RESOLVE", result.getFailureStage());
        verify(shopKnowledgeService, never()).searchRelevantTaskResults(any(), any());
        verify(aiAgentRunner, never()).runPerTask(any(), any(), any(), any(), any(), any(), anyList());
    }
}
