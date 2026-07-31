package com.hmdp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableConfigurationProperties({
        AiChatProperties.class,
        AiEmbeddingProperties.class,
        AiQdrantProperties.class,
        AiKnowledgeProperties.class,
        AiEvaluationProperties.class,
        AiMemoryProperties.class,
        AiAgentProperties.class,
        AiQueryRewriteProperties.class,
        AiTraceProperties.class
})
public class AiChatConfig {

    @Bean("aiChatExecutor")
    public Executor aiChatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-chat-");
        executor.setTaskDecorator(this::decorateWithMdc);
        executor.initialize();
        return executor;
    }

    @Bean("aiKnowledgeExecutor")
    public Executor aiKnowledgeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-knowledge-");
        executor.setTaskDecorator(this::decorateWithMdc);
        executor.initialize();
        return executor;
    }

    private Runnable decorateWithMdc(Runnable task) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (callerContext == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(callerContext);
                }
                task.run();
            } finally {
                MDC.clear();
                if (previous != null) {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
