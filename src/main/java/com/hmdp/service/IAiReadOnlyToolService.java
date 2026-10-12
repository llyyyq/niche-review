package com.hmdp.service;

import com.hmdp.ai.AiToolExecution;
import com.hmdp.ai.AiToolQueryScope;
import com.hmdp.ai.ShopKnowledge;
import com.hmdp.ai.AiTraceContext;

import java.util.List;

public interface IAiReadOnlyToolService {

    /** Executes tools inside the resolved constraints of one task. */
    List<AiToolExecution> executeTools(AiTraceContext traceContext,
                                       Long conversationId, Long userId, List<String> toolNames,
                                       Double x, Double y, AiToolQueryScope scope,
                                       List<ShopKnowledge> retrievedShops);
}
