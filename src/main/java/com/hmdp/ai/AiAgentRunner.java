package com.hmdp.ai;

import com.hmdp.service.IAiReadOnlyToolService;
import com.hmdp.service.IAiTraceService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Executes only tools declared by a program-validated query plan. Tool choice
 * is deterministic: the query-plan model describes required live facts, then
 * Java maps those facts to the local read-only tool whitelist.
 */
@Component
public class AiAgentRunner {

    private static final String NO_LOCATION_REASON =
            "Not queried: the browser did not provide a valid location. "
                    + "Do not infer that no nearby store exists.";

    @Resource
    private IAiReadOnlyToolService aiReadOnlyToolService;

    @Resource
    private IAiTraceService aiTraceService;

    /**
     * Executes each task in isolation so evidence and live facts from one
     * request goal cannot be attributed to another goal in the final prompt.
     */
    public List<AiToolExecution> runPerTask(AiTraceContext traceContext,
                                            Long conversationId, Long userId,
                                            AiResolvedQueryPlan resolvedPlan,
                                            Double x, Double y,
                                            List<AiTaskRetrievalResult> taskRetrievalResults) {
        AiTraceSpanScope routeSpan = traceContext == null || aiTraceService == null
                ? null : aiTraceService.startSpan(traceContext, "AGENT_ROUTE",
                routeAttributes(resolvedPlan));
        AiTraceContext routeContext = routeSpan == null ? traceContext : routeSpan.getContext();
        try {
            if (resolvedPlan == null || !resolvedPlan.isReady()) {
                completeRoute(routeSpan, Collections.<AiToolExecution>emptyList());
                return Collections.emptyList();
            }

            List<List<String>> toolsByTask = planTools(routeContext, resolvedPlan, x, y);
            List<AiToolExecution> executions = new ArrayList<>();
            List<AiResolvedQueryTask> tasks = resolvedPlan.getTasks();
            for (int index = 0; index < tasks.size(); index++) {
                AiResolvedQueryTask task = tasks.get(index);
                if (!hasValidLocation(x, y) && task.getRequiredFacts().contains("NEARBY_SHOPS")) {
                    executions.add(AiToolExecution.skipped("nearbyShopSearch", NO_LOCATION_REASON)
                            .withTaskIndex(index + 1));
                }
                List<String> toolNames = toolsByTask.get(index);
                if (toolNames.isEmpty()) {
                    continue;
                }
                AiTaskRetrievalResult taskResult = taskResultAt(taskRetrievalResults, index + 1);
                List<ShopKnowledge> candidates = taskResult == null
                        ? Collections.<ShopKnowledge>emptyList() : taskResult.getCandidates();
                AiTraceContext taskContext = routeContext == null
                        ? null : routeContext.withTaskIndex(index + 1);
                AiToolQueryScope scope = new AiToolQueryScope(
                        task.getShopIds(), task.getExcludedShopIds(), task.getTypeIds(),
                        task.getAreas(), task.getMaxAveragePrice());
                List<AiToolExecution> taskExecutions = aiReadOnlyToolService.executeTools(
                        taskContext, conversationId, userId, toolNames, x, y, scope, candidates);
                if (taskExecutions == null) {
                    continue;
                }
                for (AiToolExecution execution : taskExecutions) {
                    if (execution != null) {
                        executions.add(execution.withTaskIndex(index + 1));
                    }
                }
            }
            completeRoute(routeSpan, executions);
            return executions;
        } catch (RuntimeException e) {
            if (routeSpan != null) {
                routeSpan.failure(e);
            }
            throw e;
        }
    }

    private List<List<String>> planTools(AiTraceContext routeContext,
                                         AiResolvedQueryPlan resolvedPlan,
                                         Double x, Double y) {
        AiTraceSpanScope planSpan = routeContext == null || aiTraceService == null
                ? null : aiTraceService.startSpan(routeContext, "TOOL_ROUTE",
                routeAttributes(resolvedPlan));
        try {
            List<List<String>> toolsByTask = new ArrayList<>();
            int plannedToolCount = 0;
            for (AiResolvedQueryTask task : resolvedPlan.getTasks()) {
                List<String> taskTools = toolsForTask(task, x, y);
                toolsByTask.add(taskTools);
                plannedToolCount += taskTools.size();
            }
            if (planSpan != null) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("structuredPlan", true);
                attributes.put("taskCount", toolsByTask.size());
                attributes.put("toolCount", plannedToolCount);
                planSpan.success(attributes);
            }
            return toolsByTask;
        } catch (RuntimeException e) {
            if (planSpan != null) {
                planSpan.failure(e);
            }
            throw e;
        }
    }

    private List<String> toolsForTask(AiResolvedQueryTask task, Double x, Double y) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (task == null) {
            return new ArrayList<>();
        }
        for (String fact : task.getRequiredFacts()) {
            if ("SHOP_DETAIL".equals(fact)) {
                names.add("shopDetail");
            } else if ("ACTIVE_VOUCHER".equals(fact)) {
                names.add("voucherQuery");
            } else if ("PUBLIC_BLOG".equals(fact)) {
                names.add("blogSearch");
            } else if ("NEARBY_SHOPS".equals(fact) && hasValidLocation(x, y)) {
                names.add("nearbyShopSearch");
            }
        }
        return new ArrayList<>(names);
    }

    private boolean hasValidLocation(Double x, Double y) {
        return x != null && y != null
                && x >= -180D && x <= 180D
                && y >= -90D && y <= 90D;
    }

    private AiTaskRetrievalResult taskResultAt(List<AiTaskRetrievalResult> taskResults, int taskIndex) {
        if (taskResults == null) {
            return null;
        }
        for (AiTaskRetrievalResult taskResult : taskResults) {
            if (taskResult != null && taskResult.getTaskIndex() == taskIndex) {
                return taskResult;
            }
        }
        return null;
    }

    private Map<String, Object> routeAttributes(AiResolvedQueryPlan plan) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("structuredPlan", true);
        attributes.put("taskCount", plan == null ? 0 : plan.getTasks().size());
        attributes.put("requiredFactCount", plan == null ? 0 : plan.requiredFacts().size());
        return attributes;
    }

    private void completeRoute(AiTraceSpanScope routeSpan, List<AiToolExecution> executions) {
        if (routeSpan == null) {
            return;
        }
        int skippedCount = 0;
        for (AiToolExecution execution : executions) {
            if (execution.isSkipped()) {
                skippedCount++;
            }
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("toolCount", executions.size());
        attributes.put("skippedToolCount", skippedCount);
        attributes.put("structuredPlan", true);
        routeSpan.success(attributes);
    }
}
