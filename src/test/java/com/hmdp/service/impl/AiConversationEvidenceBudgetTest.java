package com.hmdp.service.impl;

import com.hmdp.ai.AiQueryTask;
import com.hmdp.ai.AiResolvedQueryTask;
import com.hmdp.ai.AiTaskRetrievalResult;
import com.hmdp.ai.AiToolExecution;
import com.hmdp.ai.ShopKnowledge;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiConversationEvidenceBudgetTest {

    private final AiConversationServiceImpl service = new AiConversationServiceImpl();

    @Test
    void longMultiTaskKnowledgeShouldRetainEveryTaskAndCandidate() {
        List<AiTaskRetrievalResult> results = Arrays.asList(
                taskResult(1, 1L, 2L),
                taskResult(2, 3L, 4L));

        String context = ReflectionTestUtils.invokeMethod(
                service, "buildTaskShopKnowledgeContext", results, 520);

        assertTrue(context.length() <= 520);
        assertTrue(context.contains("[任务1]"));
        assertTrue(context.contains("[任务2]"));
        assertTrue(context.contains("shop-1"));
        assertTrue(context.contains("shop-2"));
        assertTrue(context.contains("shop-3"));
        assertTrue(context.contains("shop-4"));
    }

    @Test
    void longMultiTaskToolResultsShouldRetainEveryExecutionStatus() {
        List<AiToolExecution> executions = Arrays.asList(
                new AiToolExecution(1, "shopDetail", repeat("detail", 40), true),
                new AiToolExecution(1, "voucherQuery", repeat("voucher", 40), false),
                new AiToolExecution(2, "blogSearch", repeat("blog", 40), true));

        String context = ReflectionTestUtils.invokeMethod(
                service, "buildToolContextByTask", executions, 500);

        assertTrue(context.length() <= 500);
        assertTrue(context.contains("[Task 1]"));
        assertTrue(context.contains("[Task 2]"));
        assertTrue(context.contains("[Tool shopDetail][SUCCESS]"));
        assertTrue(context.contains("[Tool voucherQuery][FAILED]"));
        assertTrue(context.contains("[Tool blogSearch][SUCCESS]"));
    }

    @Test
    void skippedToolShouldBeLabelledAsNotRun() {
        List<AiToolExecution> executions = Collections.singletonList(
                AiToolExecution.skipped("voucherQuery", "Not queried."));

        String context = ReflectionTestUtils.invokeMethod(
                service, "buildToolContextByTask", executions, 500);

        assertTrue(context.contains("[Tool voucherQuery][SKIPPED]"));
    }

    private AiTaskRetrievalResult taskResult(int taskIndex, Long first, Long second) {
        AiQueryTask source = new AiQueryTask(
                "RECOMMEND", "task-" + taskIndex,
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                null, null, null, Collections.singletonList("quiet"),
                Collections.<String>emptyList());
        AiResolvedQueryTask task = new AiResolvedQueryTask(source,
                Collections.<Long>emptyList(), Collections.<Long>emptyList(),
                Collections.<Long>emptyList(), Collections.<String>emptyList());
        return new AiTaskRetrievalResult(taskIndex, task, Arrays.asList(
                knowledge(first), knowledge(second)), true, false);
    }

    private ShopKnowledge knowledge(Long shopId) {
        return new ShopKnowledge(shopId,
                "shop-" + shopId + " " + repeat("evidence", 50),
                0.8D, Collections.<String, Object>emptyMap());
    }

    private String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
