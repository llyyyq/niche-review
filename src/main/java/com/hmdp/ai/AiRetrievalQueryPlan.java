package com.hmdp.ai;

import java.util.Collections;
import java.util.List;

public class AiRetrievalQueryPlan {

    private final AiQueryRewriteMode mode;
    private final List<String> queries;
    private final String clarification;
    private final boolean modelCalled;
    private final boolean validModelOutput;
    private final long rewriteMs;
    private final int originalChars;
    private final int rewrittenChars;

    public AiRetrievalQueryPlan(AiQueryRewriteMode mode, List<String> queries, String clarification,
                                boolean modelCalled, boolean validModelOutput, long rewriteMs,
                                int originalChars, int rewrittenChars) {
        this.mode = mode;
        this.queries = queries == null ? Collections.<String>emptyList() : queries;
        this.clarification = clarification;
        this.modelCalled = modelCalled;
        this.validModelOutput = validModelOutput;
        this.rewriteMs = rewriteMs;
        this.originalChars = originalChars;
        this.rewrittenChars = rewrittenChars;
    }

    public AiQueryRewriteMode getMode() {
        return mode;
    }

    public List<String> getQueries() {
        return queries;
    }

    public String getClarification() {
        return clarification;
    }

    public boolean isModelCalled() {
        return modelCalled;
    }

    public boolean isValidModelOutput() {
        return validModelOutput;
    }

    public long getRewriteMs() {
        return rewriteMs;
    }

    public int getOriginalChars() {
        return originalChars;
    }

    public int getRewrittenChars() {
        return rewrittenChars;
    }

    public boolean requiresClarification() {
        return mode == AiQueryRewriteMode.CLARIFY;
    }
}
