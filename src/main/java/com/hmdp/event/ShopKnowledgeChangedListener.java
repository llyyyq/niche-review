package com.hmdp.event;

import com.hmdp.service.IAiKnowledgeSyncTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.annotation.Resource;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.embedding", name = "provider", havingValue = "openai-compatible")
public class ShopKnowledgeChangedListener {

    @Resource
    private IAiKnowledgeSyncTaskService aiKnowledgeSyncTaskService;

    @Async("aiKnowledgeExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onShopKnowledgeChanged(ShopKnowledgeChangedEvent event) {
        try {
            aiKnowledgeSyncTaskService.enqueue(event.getShopId());
        } catch (Exception e) {
            log.error("Failed to enqueue incremental knowledge sync, shopId={}", event.getShopId(), e);
        }
    }
}
