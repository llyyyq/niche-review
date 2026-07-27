package com.hmdp.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "ai.embedding.provider", havingValue = "disabled", matchIfMissing = true)
public class DisabledEmbeddingModelClient implements EmbeddingModelClient {

    @Override
    public List<List<Float>> embed(List<String> texts) {
        throw new IllegalStateException("Embedding provider is disabled");
    }
}
