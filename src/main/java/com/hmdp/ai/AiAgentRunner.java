package com.hmdp.ai;

import com.hmdp.config.AiAgentProperties;
import com.hmdp.service.IAiReadOnlyToolService;
import com.hmdp.service.IAiTraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class AiAgentRunner {

    @Resource
    private AiToolPlanner aiToolPlanner;

    @Resource
    private IAiReadOnlyToolService aiReadOnlyToolService;

    @Resource
    private AiAgentProperties aiAgentProperties;

    @Resource
    private IAiTraceService aiTraceService;

    @Autowired(required = false)
    private List<AiExternalToolProvider> externalToolProviders = Collections.emptyList();

    public List<AiToolExecution> run(Long conversationId, Long userId, String question,
                                     Double x, Double y, List<ShopKnowledge> retrievedShops) {
        return run(null, conversationId, userId, question, x, y, retrievedShops);
    }

    public List<AiToolExecution> run(AiTraceContext traceContext,
                                     Long conversationId, Long userId, String question,
                                     Double x, Double y, List<ShopKnowledge> retrievedShops) {
        AiTraceSpanScope routeSpan = traceContext == null || aiTraceService == null
                ? null : aiTraceService.startSpan(traceContext, "AGENT_ROUTE");
        AiTraceContext routeContext = routeSpan == null ? traceContext : routeSpan.getContext();
        try {
            List<AiToolExecution> executions = doRun(routeContext, conversationId, userId,
                    question, x, y, retrievedShops);
            if (routeSpan != null) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("toolCount", executions.size());
                routeSpan.success(attributes);
            }
            return executions;
        } catch (RuntimeException e) {
            if (routeSpan != null) {
                routeSpan.failure(e);
            }
            throw e;
        }
    }

    private List<AiToolExecution> doRun(AiTraceContext traceContext,
                                        Long conversationId, Long userId, String question,
                                        Double x, Double y, List<ShopKnowledge> retrievedShops) {
        if (!Boolean.TRUE.equals(aiAgentProperties.getEnabled())) {
            return aiReadOnlyToolService.executeRelevantTools(
                    traceContext, conversationId, userId, question, x, y, retrievedShops);
        }
        if (Boolean.TRUE.equals(aiAgentProperties.getFastPathEnabled())
                && aiReadOnlyToolService.shouldUseDirectToolRouting(question, x, y)) {
            log.debug("AI direct tool routing selected, conversationId={}", conversationId);
            return aiReadOnlyToolService.executeRelevantTools(
                    traceContext, conversationId, userId, question, x, y, retrievedShops);
        }
        if (Boolean.TRUE.equals(aiAgentProperties.getFastPathEnabled()) && !requiresPlanning(question)) {
            log.debug("AI retrieval-only path selected, conversationId={}", conversationId);
            return Collections.emptyList();
        }
        List<String> availableTools = availableToolNames();
        List<AiToolExecution> executions = new ArrayList<>();
        Set<String> usedTools = new LinkedHashSet<>();
        int maxSteps = Math.max(1, aiAgentProperties.getMaxSteps());
        for (int step = 1; step <= maxSteps; step++) {
            AiToolPlan plan = aiToolPlanner.plan(traceContext, conversationId, userId, question,
                    availableTools, new ArrayList<>(usedTools), executions);
            if (plan == null) {
                log.info("Agent planning fell back to legacy routing, conversationId={}", conversationId);
                return executions.isEmpty()
                        ? aiReadOnlyToolService.executeRelevantTools(
                                traceContext, conversationId, userId, question, x, y, retrievedShops)
                        : executions;
            }
            if (plan.isFinished()) {
                log.debug("Agent terminated normally, conversationId={}, step={}", conversationId, step);
                return executions;
            }
            List<String> requested = selectUnusedTools(plan.getToolNames(), usedTools);
            if (requested.isEmpty()) {
                log.debug("Agent terminated because no executable tool remained, conversationId={}, step={}", conversationId, step);
                return executions;
            }
            List<AiToolExecution> stepResults = executeTools(
                    traceContext, conversationId, userId, requested, question, x, y, retrievedShops);
            executions.addAll(stepResults);
            usedTools.addAll(requested);
        }
        log.info("Agent stopped at configured step limit, conversationId={}, maxSteps={}", conversationId, maxSteps);
        return executions;
    }

    private boolean requiresPlanning(String question) {
        if (question == null) {
            return false;
        }
        String normalized = question.toLowerCase();
        return containsAny(normalized, "recommend", "compare", "versus", "suitable", "budget",
                "\u63a8\u8350", "\u5bf9\u6bd4", "\u6bd4\u8f83", "\u9002\u5408", "\u9884\u7b97", "\u805a\u9910", "\u7ea6\u4f1a", "\u600e\u4e48\u9009");
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<String> availableToolNames() {
        Set<String> names = new LinkedHashSet<>(aiReadOnlyToolService.supportedToolNames());
        for (AiExternalToolProvider provider : externalToolProviders) {
            names.addAll(provider.supportedToolNames());
        }
        return new ArrayList<>(names);
    }

    private List<String> selectUnusedTools(List<String> requested, Set<String> usedTools) {
        int limit = Math.max(1, aiAgentProperties.getMaxToolsPerStep());
        List<String> selected = new ArrayList<>();
        for (String tool : requested) {
            if (usedTools.contains(tool)) {
                continue;
            }
            selected.add(tool);
            if (selected.size() == limit) {
                break;
            }
        }
        return selected;
    }

    private List<AiToolExecution> executeTools(AiTraceContext traceContext,
                                               Long conversationId, Long userId, List<String> toolNames,
                                               String question, Double x, Double y, List<ShopKnowledge> retrievedShops) {
        List<String> localTools = new ArrayList<>();
        List<AiToolExecution> results = new ArrayList<>();
        for (String toolName : toolNames) {
            if (aiReadOnlyToolService.supportedToolNames().contains(toolName)) {
                localTools.add(toolName);
                continue;
            }
            executeExternalTool(results, new AiToolInvocation(
                    traceContext, conversationId, userId, toolName, question, x, y, retrievedShops));
        }
        results.addAll(aiReadOnlyToolService.executeTools(
                traceContext, conversationId, userId, localTools, x, y, retrievedShops));
        return results;
    }

    private void executeExternalTool(List<AiToolExecution> results, AiToolInvocation invocation) {
        for (AiExternalToolProvider provider : externalToolProviders) {
            if (!provider.supportedToolNames().contains(invocation.getToolName())) {
                continue;
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("toolName", invocation.getToolName());
            attributes.put("toolCallId", invocation.getToolCallId());
            attributes.put("provider", provider.getProviderName());
            AiTraceSpanScope toolSpan = invocation.getTraceContext() == null || aiTraceService == null
                    ? null : aiTraceService.startSpan(invocation.getTraceContext(), "TOOL_CALL", attributes);
            try {
                AiToolExecution result = provider.execute(invocation);
                if (result != null) {
                    results.add(result);
                }
                if (toolSpan != null) {
                    toolSpan.success(attributes);
                }
            } catch (Exception e) {
                if (toolSpan != null) {
                    toolSpan.failure(e);
                }
                log.warn("External AI tool failed, provider={}, toolName={}",
                        provider.getProviderName(), invocation.getToolName(), e);
            }
            return;
        }
    }
}
