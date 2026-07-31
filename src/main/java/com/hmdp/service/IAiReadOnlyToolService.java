package com.hmdp.service;

import com.hmdp.ai.AiToolExecution;
import com.hmdp.ai.ShopKnowledge;
import com.hmdp.ai.AiTraceContext;

import java.util.List;

public interface IAiReadOnlyToolService {

    List<AiToolExecution> executeRelevantTools(Long conversationId, Long userId, String question,
                                               Double x, Double y, List<ShopKnowledge> retrievedShops);

    List<AiToolExecution> executeRelevantTools(AiTraceContext traceContext,
                                               Long conversationId, Long userId, String question,
                                               Double x, Double y, List<ShopKnowledge> retrievedShops);

    /**
     * Whether a question has an unambiguous read-only intent and can skip a
     * separate model call for tool planning.
     */
    boolean shouldUseDirectToolRouting(String question, Double x, Double y);

    List<AiToolExecution> executeTools(Long conversationId, Long userId, List<String> toolNames,
                                       Double x, Double y, List<ShopKnowledge> retrievedShops);

    List<AiToolExecution> executeTools(AiTraceContext traceContext,
                                       Long conversationId, Long userId, List<String> toolNames,
                                       Double x, Double y, List<ShopKnowledge> retrievedShops);

    List<String> supportedToolNames();
}
