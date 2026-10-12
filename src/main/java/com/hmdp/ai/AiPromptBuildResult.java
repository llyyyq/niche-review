package com.hmdp.ai;

import java.util.List;
import java.util.Collections;

public class AiPromptBuildResult {

    private final List<AiPromptMessage> messages;
    private final long retrievalMs;
    private final long toolMs;
    private final int inputTokens;
    private final String directResponse;
    private final String outcome;
    private final AiQueryPlan structuredQueryPlan;
    private final List<ShopKnowledge> retrievedShops;
    private final List<AiToolExecution> toolExecutions;
    private final List<AiTaskRetrievalResult> taskRetrievalResults;
    private final String failureStage;

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
                null, null, null,
                Collections.<AiTaskRetrievalResult>emptyList());
    }

    public AiPromptBuildResult(List<AiPromptMessage> messages, long retrievalMs, long toolMs,
                               int inputTokens, String directResponse, String outcome,
                               AiQueryPlan structuredQueryPlan,
                               List<ShopKnowledge> retrievedShops,
                               List<AiToolExecution> toolExecutions,
                               List<AiTaskRetrievalResult> taskRetrievalResults) {
        this(messages, retrievalMs, toolMs, inputTokens, directResponse, outcome,
                structuredQueryPlan, retrievedShops, toolExecutions,
                taskRetrievalResults, null);
    }

    public AiPromptBuildResult(List<AiPromptMessage> messages, long retrievalMs, long toolMs,
                               int inputTokens, String directResponse, String outcome,
                               AiQueryPlan structuredQueryPlan,
                               List<ShopKnowledge> retrievedShops,
                               List<AiToolExecution> toolExecutions,
                               List<AiTaskRetrievalResult> taskRetrievalResults,
                               String failureStage) {
        this.messages = messages;
        this.retrievalMs = retrievalMs;
        this.toolMs = toolMs;
        this.inputTokens = inputTokens;
        this.directResponse = directResponse;
        this.outcome = outcome;
        this.structuredQueryPlan = structuredQueryPlan;
        this.retrievedShops = retrievedShops;
        this.toolExecutions = toolExecutions;
        this.taskRetrievalResults = taskRetrievalResults == null
                ? Collections.<AiTaskRetrievalResult>emptyList() : taskRetrievalResults;
        this.failureStage = failureStage;
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

    public AiQueryPlan getStructuredQueryPlan() {
        return structuredQueryPlan;
    }

    public List<ShopKnowledge> getRetrievedShops() {
        return retrievedShops;
    }

    public List<AiToolExecution> getToolExecutions() {
        return toolExecutions;
    }

    public List<AiTaskRetrievalResult> getTaskRetrievalResults() {
        return taskRetrievalResults;
    }

    public String getFailureStage() {
        return failureStage;
    }
}
