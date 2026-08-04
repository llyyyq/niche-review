package com.hmdp.ai;

import java.util.List;

public class AiPromptBuildResult {

    private final List<AiPromptMessage> messages;
    private final long retrievalMs;
    private final long toolMs;
    private final int inputTokens;
    private final String directResponse;
    private final String outcome;
    private final AiRetrievalQueryPlan queryPlan;
    private final List<ShopKnowledge> retrievedShops;
    private final List<AiToolExecution> toolExecutions;

    public AiPromptBuildResult(List<AiPromptMessage> messages, long retrievalMs, long toolMs, int inputTokens) {
        this(messages, retrievalMs, toolMs, inputTokens, null, "ANSWERED");
    }

    public AiPromptBuildResult(List<AiPromptMessage> messages, long retrievalMs, long toolMs,
                               int inputTokens, String directResponse) {
        this(messages, retrievalMs, toolMs, inputTokens, directResponse,
                directResponse == null ? "ANSWERED" : "NO_EVIDENCE");
    }

    public AiPromptBuildResult(List<AiPromptMessage> messages, long retrievalMs, long toolMs,
                               int inputTokens, String directResponse, String outcome) {
        this(messages, retrievalMs, toolMs, inputTokens, directResponse, outcome,
                null, null, null);
    }

    public AiPromptBuildResult(List<AiPromptMessage> messages, long retrievalMs, long toolMs,
                               int inputTokens, String directResponse, String outcome,
                               AiRetrievalQueryPlan queryPlan, List<ShopKnowledge> retrievedShops,
                               List<AiToolExecution> toolExecutions) {
        this.messages = messages;
        this.retrievalMs = retrievalMs;
        this.toolMs = toolMs;
        this.inputTokens = inputTokens;
        this.directResponse = directResponse;
        this.outcome = outcome;
        this.queryPlan = queryPlan;
        this.retrievedShops = retrievedShops;
        this.toolExecutions = toolExecutions;
    }

    public List<AiPromptMessage> getMessages() {
        return messages;
    }

    public long getRetrievalMs() {
        return retrievalMs;
    }

    public long getToolMs() {
        return toolMs;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public String getDirectResponse() {
        return directResponse;
    }

    public String getOutcome() {
        return outcome;
    }

    public AiRetrievalQueryPlan getQueryPlan() {
        return queryPlan;
    }

    public List<ShopKnowledge> getRetrievedShops() {
        return retrievedShops;
    }

    public List<AiToolExecution> getToolExecutions() {
        return toolExecutions;
    }
}
