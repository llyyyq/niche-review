package com.hmdp.ai;

import java.util.Collections;
import java.util.List;

/**
 * Internal request used by the offline end-to-end RAG evaluator.
 * It deliberately does not create a user conversation or message record.
 */
public class AiRagEvaluationRequest {

    private final String question;
    private final List<AiPromptMessage> history;

    public AiRagEvaluationRequest(String question, List<AiPromptMessage> history) {
        this.question = question;
        this.history = history == null ? Collections.<AiPromptMessage>emptyList() : history;
    }

    public String getQuestion() {
        return question;
    }

    public List<AiPromptMessage> getHistory() {
        return history;
    }
}
