package com.hmdp.ai;

import java.util.List;

public interface AiChatModelClient {

    void stream(List<AiPromptMessage> messages, AiStreamObserver observer) throws Exception;
}
