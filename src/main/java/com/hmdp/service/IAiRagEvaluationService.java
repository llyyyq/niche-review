package com.hmdp.service;

import com.hmdp.ai.AiRagEvaluationRequest;
import com.hmdp.ai.AiRagEvaluationResult;

/**
 * Runs one full RAG answer without creating a normal user conversation.
 */
public interface IAiRagEvaluationService {

    AiRagEvaluationResult evaluate(AiRagEvaluationRequest request);
}
