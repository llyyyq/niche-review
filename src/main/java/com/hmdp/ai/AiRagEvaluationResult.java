package com.hmdp.ai;

import java.util.Collections;
import java.util.List;

/**
 * Runtime evidence returned by one offline end-to-end RAG evaluation case.
 */
public class AiRagEvaluationResult {

    private final String requestId;
    private final String traceId;
    private final String mode;
    private final List<String> retrievalQueries;
    private final boolean rewriteModelCalled;
    private final boolean validRewriteOutput;
    private final String outcome;
    private final String answer;
    private final List<ShopKnowledge> retrievedShops;
    private final List<AiToolExecution> toolExecutions;
    private final long retrievalMs;
    private final long toolMs;
    private final long firstTokenMs;
    private final long totalMs;
    private final String errorMessage;

    public AiRagEvaluationResult(String requestId, String traceId, String mode,
                                 List<String> retrievalQueries, boolean rewriteModelCalled,
                                 boolean validRewriteOutput, String outcome,
                                 String answer, List<ShopKnowledge> retrievedShops,
                                 List<AiToolExecution> toolExecutions, long retrievalMs,
                                 long toolMs, long firstTokenMs, long totalMs, String errorMessage) {
        this.requestId = requestId;
        this.traceId = traceId;
        this.mode = mode;
        this.retrievalQueries = retrievalQueries == null
                ? Collections.<String>emptyList() : retrievalQueries;
        this.rewriteModelCalled = rewriteModelCalled;
        this.validRewriteOutput = validRewriteOutput;
        this.outcome = outcome;
        this.answer = answer;
        this.retrievedShops = retrievedShops == null
                ? Collections.<ShopKnowledge>emptyList() : retrievedShops;
        this.toolExecutions = toolExecutions == null
                ? Collections.<AiToolExecution>emptyList() : toolExecutions;
        this.retrievalMs = retrievalMs;
        this.toolMs = toolMs;
        this.firstTokenMs = firstTokenMs;
        this.totalMs = totalMs;
        this.errorMessage = errorMessage;
    }

    public String getRequestId() { return requestId; }
    public String getTraceId() { return traceId; }
    public String getMode() { return mode; }
    public List<String> getRetrievalQueries() { return retrievalQueries; }
    public boolean isRewriteModelCalled() { return rewriteModelCalled; }
    public boolean isValidRewriteOutput() { return validRewriteOutput; }
    public String getOutcome() { return outcome; }
    public String getAnswer() { return answer; }
    public List<ShopKnowledge> getRetrievedShops() { return retrievedShops; }
    public List<AiToolExecution> getToolExecutions() { return toolExecutions; }
    public long getRetrievalMs() { return retrievalMs; }
    public long getToolMs() { return toolMs; }
    public long getFirstTokenMs() { return firstTokenMs; }
    public long getTotalMs() { return totalMs; }
    public String getErrorMessage() { return errorMessage; }
}
