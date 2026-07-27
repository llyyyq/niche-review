package com.hmdp.ai;

import java.util.List;

public class AiPromptBuildResult {

    private final List<AiPromptMessage> messages;
    private final long retrievalMs;
    private final long toolMs;
    private final int inputTokens;
    private final String directResponse;

    public AiPromptBuildResult(List<AiPromptMessage> messages, long retrievalMs, long toolMs, int inputTokens) {
        this(messages, retrievalMs, toolMs, inputTokens, null);
    }

    public AiPromptBuildResult(List<AiPromptMessage> messages, long retrievalMs, long toolMs,
                               int inputTokens, String directResponse) {
        this.messages = messages;
        this.retrievalMs = retrievalMs;
        this.toolMs = toolMs;
        this.inputTokens = inputTokens;
        this.directResponse = directResponse;
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
}
