package com.hmdp.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiChatProperties;
import com.hmdp.entity.AiRequestLog;
import com.hmdp.service.IAiRequestLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class AiToolPlanner {

    @Resource
    private AiChatModelClient aiChatModelClient;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private AiChatProperties aiChatProperties;

    @Resource
    private AiTokenEstimator aiTokenEstimator;

    @Resource
    private IAiRequestLogService aiRequestLogService;

    public AiToolPlan plan(Long conversationId, Long userId, String question, List<String> availableTools, List<String> usedTools,
                           List<AiToolExecution> previousResults) {
        if (availableTools == null || availableTools.isEmpty()) {
            return new AiToolPlan(true, Collections.<String>emptyList());
        }
        long startedAt = System.currentTimeMillis();
        AtomicLong firstTokenMs = new AtomicLong(-1L);
        List<AiPromptMessage> messages = Arrays.asList(
                new AiPromptMessage("system", plannerInstruction()),
                new AiPromptMessage("user", plannerInput(question, availableTools, usedTools, previousResults))
        );
        StringBuilder output = new StringBuilder();
        try {
            aiChatModelClient.stream(messages, delta -> {
                firstTokenMs.compareAndSet(-1L, System.currentTimeMillis() - startedAt);
                output.append(delta);
            });
            AiToolPlan plan = parsePlan(output.toString(), availableTools);
            savePlanLog(conversationId, userId, messages, output.toString(), firstTokenMs.get(),
                    System.currentTimeMillis() - startedAt, plan == null ? 0 : 1,
                    plan == null ? "Planner returned invalid JSON" : null);
            return plan;
        } catch (Exception e) {
            savePlanLog(conversationId, userId, messages, output.toString(), firstTokenMs.get(),
                    System.currentTimeMillis() - startedAt, 0, e.getMessage());
            log.warn("AI tool planning failed; legacy routing will be used: {}", e.getMessage());
            return null;
        }
    }

    private String plannerInstruction() {
        return "You are a strict tool planner for a local-life assistant. "
                + "Choose tools only when live business data is needed. "
                + "Do not repeat an already used tool. "
                + "Return only one JSON object, with no markdown: "
                + "{\"action\":\"tool\"|\"final\",\"tools\":[\"toolName\"]}. "
                + "Use action=final when the existing information is enough.";
    }

    private String plannerInput(String question, List<String> availableTools, List<String> usedTools,
                                List<AiToolExecution> previousResults) {
        StringBuilder input = new StringBuilder();
        input.append("Question: ").append(question).append('\n');
        input.append("Available tools: ").append(availableTools).append('\n');
        input.append("Already used tools: ").append(usedTools).append('\n');
        if (previousResults != null && !previousResults.isEmpty()) {
            input.append("Live results were already obtained. Prefer final unless another unused tool is required.\n");
        }
        return input.toString();
    }

    private AiToolPlan parsePlan(String raw, List<String> availableTools) throws Exception {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode root = objectMapper.readTree(raw.substring(start, end + 1));
        String action = root.path("action").asText();
        if ("final".equalsIgnoreCase(action)) {
            return new AiToolPlan(true, Collections.<String>emptyList());
        }
        if (!"tool".equalsIgnoreCase(action) || !root.path("tools").isArray()) {
            return null;
        }
        List<String> tools = new ArrayList<>();
        for (JsonNode node : root.path("tools")) {
            String name = node.asText();
            if (availableTools.contains(name) && !tools.contains(name)) {
                tools.add(name);
            }
        }
        return new AiToolPlan(tools.isEmpty(), tools);
    }

    private void savePlanLog(Long conversationId, Long userId, List<AiPromptMessage> messages, String output,
                             long firstTokenMs, long totalMs, int success, String errorMessage) {
        try {
            AiRequestLog requestLog = new AiRequestLog();
            requestLog.setConversationId(conversationId);
            requestLog.setUserId(userId);
            requestLog.setRequestType("agent_plan");
            requestLog.setProvider(aiChatProperties.getProvider());
            requestLog.setModel(aiChatProperties.getModel());
            requestLog.setFirstTokenMs(firstTokenMs < 0 ? null : firstTokenMs);
            requestLog.setTotalMs(totalMs);
            requestLog.setInputTokens(aiTokenEstimator.estimateMessages(messages));
            requestLog.setOutputTokens(aiTokenEstimator.estimateText(output));
            requestLog.setSuccess(success);
            requestLog.setErrorMessage(limit(errorMessage));
            aiRequestLogService.save(requestLog);
        } catch (Exception e) {
            log.error("Failed to save agent planning request log, conversationId={}", conversationId, e);
        }
    }

    private String limit(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        return errorMessage.length() <= 512 ? errorMessage : errorMessage.substring(0, 512);
    }
}
