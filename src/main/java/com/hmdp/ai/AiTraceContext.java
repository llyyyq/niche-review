package com.hmdp.ai;

/**
 * Immutable correlation context for one AI request and its current span.
 */
public final class AiTraceContext {

    private final String requestId;
    private final String traceId;
    private final String rootSpanId;
    private final String currentSpanId;
    private final String parentSpanId;
    private final Long conversationId;
    private final Long userId;
    private final Long userMessageId;
    private final Long assistantMessageId;
    private final long traceStartedNanos;

    public AiTraceContext(String requestId, String traceId, String rootSpanId, String currentSpanId,
                          String parentSpanId,
                          Long conversationId, Long userId, Long userMessageId, Long assistantMessageId) {
        this(requestId, traceId, rootSpanId, currentSpanId, parentSpanId,
                conversationId, userId, userMessageId, assistantMessageId, System.nanoTime());
    }

    public AiTraceContext(String requestId, String traceId, String rootSpanId, String currentSpanId,
                          String parentSpanId,
                          Long conversationId, Long userId, Long userMessageId, Long assistantMessageId,
                          long traceStartedNanos) {
        this.requestId = requestId;
        this.traceId = traceId;
        this.rootSpanId = rootSpanId;
        this.currentSpanId = currentSpanId;
        this.parentSpanId = parentSpanId;
        this.conversationId = conversationId;
        this.userId = userId;
        this.userMessageId = userMessageId;
        this.assistantMessageId = assistantMessageId;
        this.traceStartedNanos = traceStartedNanos;
    }

    public AiTraceContext child(String spanId) {
        return new AiTraceContext(requestId, traceId, rootSpanId, spanId, currentSpanId,
                conversationId, userId, userMessageId, assistantMessageId, traceStartedNanos);
    }

    public boolean isValid() {
        return requestId != null && traceId != null && currentSpanId != null;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getRootSpanId() {
        return rootSpanId;
    }

    public String getCurrentSpanId() {
        return currentSpanId;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getUserMessageId() {
        return userMessageId;
    }

    public Long getAssistantMessageId() {
        return assistantMessageId;
    }

    public long getTraceStartedNanos() {
        return traceStartedNanos;
    }
}
