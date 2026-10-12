package com.hmdp.ai;

import com.hmdp.service.IAiReadOnlyToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAgentStructuredRoutingTest {

    @Mock
    private IAiReadOnlyToolService toolService;

    private AiAgentRunner runner;

    @BeforeEach
    void setUp() {
        runner = new AiAgentRunner();
        ReflectionTestUtils.setField(runner, "aiReadOnlyToolService", toolService);
        lenient().when(toolService.executeTools(nullable(AiTraceContext.class), anyLong(), anyLong(), anyList(),
                any(), any(), any(AiToolQueryScope.class), anyList()))
                .thenReturn(Collections.singletonList(new AiToolExecution("voucherQuery", "ok")));
    }

    @Test
    void validatedFactsShouldRouteToolsWithoutSecondPlannerCall() {
        AiQueryTask source = new AiQueryTask(
                "FACT_LOOKUP", "炉鱼有什么优惠和营业时间？",
                Collections.singletonList("炉鱼"), Collections.<String>emptyList(),
                null, null, null, Collections.<String>emptyList(),
                Arrays.asList("ACTIVE_VOUCHER", "SHOP_DETAIL"));
        AiResolvedQueryTask task = new AiResolvedQueryTask(source,
                Collections.singletonList(7L), Collections.<Long>emptyList(),
                Collections.<Long>emptyList(), Collections.<String>emptyList());
        AiResolvedQueryPlan plan = new AiResolvedQueryPlan(AiQueryPlanStatus.READY,
                Collections.singletonList(task), null, null);

        List<AiToolExecution> result = runner.runPerTask(null, 1L, 2L, plan,
                null, null, Collections.singletonList(
                        new AiTaskRetrievalResult(1, task, Collections.<ShopKnowledge>emptyList())));

        assertEquals(1, result.size());
        verify(toolService, times(1)).executeTools(nullable(AiTraceContext.class), anyLong(), anyLong(),
                anyList(), any(), any(), any(AiToolQueryScope.class), anyList());
    }

    @Test
    void multiTaskRoutingShouldKeepCandidatesAndToolResultsInTaskScope() {
        AiQueryTask firstSource = new AiQueryTask(
                "FACT_LOOKUP", "炉鱼有什么优惠？", Collections.singletonList("炉鱼"),
                Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(), Collections.singletonList("ACTIVE_VOUCHER"));
        AiQueryTask secondSource = new AiQueryTask(
                "FACT_LOOKUP", "新白鹿餐厅几点关门？", Collections.singletonList("新白鹿餐厅"),
                Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(), Collections.singletonList("SHOP_DETAIL"));
        AiResolvedQueryTask firstTask = new AiResolvedQueryTask(firstSource,
                Collections.singletonList(7L), Collections.<Long>emptyList(),
                Collections.<Long>emptyList(), Collections.<String>emptyList());
        AiResolvedQueryTask secondTask = new AiResolvedQueryTask(secondSource,
                Collections.singletonList(8L), Collections.<Long>emptyList(),
                Collections.<Long>emptyList(), Collections.<String>emptyList());
        AiResolvedQueryPlan plan = new AiResolvedQueryPlan(AiQueryPlanStatus.READY,
                Arrays.asList(firstTask, secondTask), null, null);
        ShopKnowledge firstCandidate = new ShopKnowledge(7L, "store=7", 0.9D,
                Collections.<String, Object>emptyMap());
        ShopKnowledge secondCandidate = new ShopKnowledge(8L, "store=8", 0.9D,
                Collections.<String, Object>emptyMap());

        List<AiToolExecution> result = runner.runPerTask(null, 1L, 2L, plan, null, null,
                Arrays.asList(
                        new AiTaskRetrievalResult(1, firstTask, Collections.singletonList(firstCandidate)),
                        new AiTaskRetrievalResult(2, secondTask, Collections.singletonList(secondCandidate))
                ));

        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getTaskIndex());
        assertEquals(2, result.get(1).getTaskIndex());

        ArgumentCaptor<List> retrievedCandidates = ArgumentCaptor.forClass(List.class);
        verify(toolService, times(2)).executeTools(nullable(AiTraceContext.class), anyLong(), anyLong(),
                anyList(), any(), any(), any(AiToolQueryScope.class), retrievedCandidates.capture());
        assertEquals(Collections.singletonList(firstCandidate), retrievedCandidates.getAllValues().get(0));
        assertEquals(Collections.singletonList(secondCandidate), retrievedCandidates.getAllValues().get(1));
    }

    @Test
    void nearbyFactWithoutLocationShouldRecordSkippedExecutionWithoutCallingTools() {
        AiQueryTask source = new AiQueryTask(
                "FACT_LOOKUP", "附近有什么店？", Collections.<String>emptyList(),
                Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(), Collections.singletonList("NEARBY_SHOPS"));
        AiResolvedQueryTask task = new AiResolvedQueryTask(source,
                Collections.<Long>emptyList(), Collections.<Long>emptyList(),
                Collections.<Long>emptyList(), Collections.<String>emptyList());
        AiResolvedQueryPlan plan = new AiResolvedQueryPlan(AiQueryPlanStatus.READY,
                Collections.singletonList(task), null, null);

        List<AiToolExecution> result = runner.runPerTask(null, 1L, 2L, plan, null, null,
                Collections.singletonList(new AiTaskRetrievalResult(1, task,
                        Collections.<ShopKnowledge>emptyList())));

        assertEquals(1, result.size());
        assertTrue(result.get(0).isSkipped());
        assertEquals("nearbyShopSearch", result.get(0).getToolName());
        assertEquals(1, result.get(0).getTaskIndex());
        verify(toolService, never()).executeTools(nullable(AiTraceContext.class), anyLong(), anyLong(),
                anyList(), any(), any(), any(AiToolQueryScope.class), anyList());
    }

    @Test
    void structuredTaskShouldExecuteEveryValidatedRequiredTool() {
        AiQueryTask source = new AiQueryTask(
                "FACT_LOOKUP", "炉鱼的店铺、优惠券、探店笔记和附近门店",
                Collections.singletonList("炉鱼"), Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(),
                Arrays.asList("SHOP_DETAIL", "ACTIVE_VOUCHER", "PUBLIC_BLOG", "NEARBY_SHOPS"));
        AiResolvedQueryTask task = new AiResolvedQueryTask(source,
                Collections.singletonList(7L), Collections.<Long>emptyList(),
                Collections.singletonList(1L), Collections.<String>emptyList());
        AiResolvedQueryPlan plan = new AiResolvedQueryPlan(AiQueryPlanStatus.READY,
                Collections.singletonList(task), null, null);

        runner.runPerTask(null, 1L, 2L, plan, 120D, 30D,
                Collections.singletonList(new AiTaskRetrievalResult(1, task,
                        Collections.<ShopKnowledge>emptyList())));

        ArgumentCaptor<List> toolNames = ArgumentCaptor.forClass(List.class);
        verify(toolService).executeTools(nullable(AiTraceContext.class), anyLong(), anyLong(),
                toolNames.capture(), any(), any(), any(AiToolQueryScope.class), anyList());
        assertEquals(Arrays.asList("shopDetail", "voucherQuery", "blogSearch", "nearbyShopSearch"),
                toolNames.getValue());
    }
}
