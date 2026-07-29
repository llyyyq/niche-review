package com.hmdp.ai;

import com.hmdp.config.AiChatProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

@Component
@ConditionalOnProperty(name = "ai.chat.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiChatModelClient implements AiChatModelClient {

    @Resource
    private AiChatProperties aiChatProperties;

    @Override
    public void stream(List<AiPromptMessage> messages, AiStreamObserver observer) throws Exception {
        String question = messages.get(messages.size() - 1).getContent();
        String response = "这是本地 Mock 模型的流式回复。你刚才的问题是：" + question
                + "。下一步配置 OpenAI 兼容模型后，系统会使用真实大模型生成回答。";
        for (int start = 0; start < response.length(); start += 12) {
            int end = Math.min(start + 12, response.length());
            observer.onDelta(response.substring(start, end));
            Thread.sleep(40L);
        }
    }

    @Override
    public String complete(List<AiPromptMessage> messages, AiCompletionOptions options) {
        return "{}";
    }
}
