package com.hmdp.service;

public interface IAiConversationMemoryService {

    void summarizeIfNeeded(Long conversationId, Long userId);
}
