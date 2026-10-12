package com.hmdp.service.impl;

import com.hmdp.ai.AiTraceContext;
import com.hmdp.ai.AiTraceSpanScope;
import com.hmdp.ai.AiToolExecution;
import com.hmdp.ai.AiToolQueryScope;
import com.hmdp.ai.ShopKnowledge;
import com.hmdp.config.AiKnowledgeProperties;
import com.hmdp.entity.AiToolLog;
import com.hmdp.entity.Shop;
import com.hmdp.service.IAiToolLogService;
import com.hmdp.service.IAiTraceService;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiReadOnlyToolTraceTest {

    @Mock
    private IShopService shopService;

    @Mock
    private IVoucherService voucherService;

    @Mock
    private IBlogService blogService;

    @Mock
    private IAiToolLogService toolLogService;

    @Mock
    private IAiTraceService traceService;

    private AiReadOnlyToolServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiReadOnlyToolServiceImpl();
        ReflectionTestUtils.setField(service, "shopService", shopService);
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
        ReflectionTestUtils.setField(service, "blogService", blogService);
        ReflectionTestUtils.setField(service, "aiToolLogService", toolLogService);
        ReflectionTestUtils.setField(service, "aiTraceService", traceService);
        ReflectionTestUtils.setField(service, "knowledgeProperties", new AiKnowledgeProperties());
    }

    @Test
    void failedToolKeepsTraceCorrelationAndIsIsolatedFromAgentFlow() {
        AiTraceContext root = new AiTraceContext(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "1111111111111111",
                "1111111111111111",
                null,
                10L,
                20L,
                30L,
                40L
        );
        when(traceService.startSpan(eq(root), eq("TOOL_CALL"), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, Object> attributes = invocation.getArgument(2);
                    return new AiTraceSpanScope(
                            traceService,
                            root.child("2222222222222222"),
                            attributes
                    );
                });
        when(shopService.listByIds(anyList())).thenThrow(new IllegalStateException("database unavailable"));
        ShopKnowledge knowledge = new ShopKnowledge(
                1L, "shop", 0.9D, Collections.<String, Object>emptyMap());

        List<AiToolExecution> executions = service.executeTools(
                root,
                10L,
                20L,
                Collections.singletonList("shopDetail"),
                null,
                null,
                emptyScope(),
                Collections.singletonList(knowledge)
        );

        assertTrue(executions.size() == 1);
        assertTrue(!executions.get(0).isSuccessful());
        verify(traceService).completeSpan(
                argThat(context -> "2222222222222222".equals(context.getCurrentSpanId())),
                eq(false),
                argThat(attributes -> "shopDetail".equals(attributes.get("toolName"))
                        && attributes.get("toolCallId") != null),
                any(IllegalStateException.class),
                anyLong()
        );
        verify(toolLogService).save(argThat((AiToolLog toolLog) ->
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".equals(toolLog.getTraceId())
                        && "2222222222222222".equals(toolLog.getSpanId())
                        && toolLog.getToolCallId() != null
                        && toolLog.getAssistantMessageId().equals(40L)
                        && toolLog.getSuccess().equals(0)));
    }

    @Test
    void resolvedShopIdsShouldNotBeExpandedBySemanticCandidates() {
        when(shopService.listByIds(anyList())).thenReturn(Collections.emptyList());
        ShopKnowledge unrelatedCandidate = new ShopKnowledge(
                99L, "unrelated", 0.9D, Collections.<String, Object>emptyMap());

        service.executeTools(null, 10L, 20L,
                Collections.singletonList("shopDetail"), null, null,
                new AiToolQueryScope(Collections.singletonList(7L), Collections.<Long>emptyList(),
                        Collections.<Long>emptyList(), Collections.<String>emptyList(), null),
                Collections.singletonList(unrelatedCandidate));

        verify(shopService).listByIds(argThat(ids -> ids.equals(Collections.singletonList(7L))));
    }

    @Test
    void liveToolMustRespectResolvedBudgetBeforeQueryingStoreDetails() {
        Shop expensiveShop = new Shop()
                .setId(7L)
                .setName("炉鱼（拱墅万达广场店）")
                .setTypeId(1L)
                .setArea("北部新城")
                .setAvgPrice(200L);
        when(shopService.listByIds(anyList())).thenReturn(Collections.singletonList(expensiveShop));

        List<AiToolExecution> executions = service.executeTools(
                null, 10L, 20L, Collections.singletonList("shopDetail"), null, null,
                new AiToolQueryScope(Collections.singletonList(7L), Collections.<Long>emptyList(),
                        Collections.singletonList(1L), Collections.singletonList("北部新城"), 100),
                Collections.<ShopKnowledge>emptyList());

        assertEquals(1, executions.size());
        assertTrue(executions.get(0).isSkipped());
        assertEquals("shopDetail", executions.get(0).getToolName());
        verify(shopService).listByIds(argThat(ids -> ids.equals(Collections.singletonList(7L))));
    }

    @Test
    void toolCandidateSelectionShouldStopAtConfiguredLimit() {
        List<ShopKnowledge> candidates = Arrays.asList(
                knowledge(1L), knowledge(2L), knowledge(3L),
                knowledge(4L), knowledge(5L), knowledge(6L));
        when(shopService.listByIds(anyList())).thenReturn(Collections.<Shop>emptyList());

        service.executeTools(null, 10L, 20L,
                Collections.singletonList("shopDetail"), null, null, emptyScope(), candidates);

        verify(shopService).listByIds(argThat(ids -> ids.size() == 5 && !ids.contains(6L)));
    }

    @Test
    void nearbySearchWithoutCategoryShouldBeSkippedNotDropped() {
        List<AiToolExecution> executions = service.executeTools(
                null, 10L, 20L, Collections.singletonList("nearbyShopSearch"), 120D, 30D,
                emptyScope(), Collections.<ShopKnowledge>emptyList());

        assertEquals(1, executions.size());
        assertTrue(executions.get(0).isSkipped());
        assertEquals("nearbyShopSearch", executions.get(0).getToolName());
    }

    private ShopKnowledge knowledge(Long shopId) {
        return new ShopKnowledge(shopId, "shop-" + shopId, 0.9D,
                Collections.<String, Object>emptyMap());
    }

    private AiToolQueryScope emptyScope() {
        return new AiToolQueryScope(Collections.<Long>emptyList(), Collections.<Long>emptyList(),
                Collections.<Long>emptyList(), Collections.<String>emptyList(), null);
    }
}
