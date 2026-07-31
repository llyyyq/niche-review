package com.hmdp.service;

import com.hmdp.ai.AiTraceContext;

public interface IAiConversationMemoryService {

    void summarizeIfNeeded(Long conversationId, Long userId);

    void summarizeIfNeeded(AiTraceContext traceContext, Long conversationId, Long userId);
}
