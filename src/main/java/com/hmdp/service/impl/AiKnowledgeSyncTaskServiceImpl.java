package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.config.AiKnowledgeProperties;
import com.hmdp.entity.AiKnowledgeSyncTask;
import com.hmdp.mapper.AiKnowledgeSyncTaskMapper;
import com.hmdp.service.IAiKnowledgeSyncTaskService;
import com.hmdp.service.IShopKnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "ai.embedding", name = "provider", havingValue = "openai-compatible")
public class AiKnowledgeSyncTaskServiceImpl implements IAiKnowledgeSyncTaskService {

    private static final int BATCH_SIZE = 20;
    private static final int MAX_ERROR_LENGTH = 512;

    @Resource
    private AiKnowledgeSyncTaskMapper aiKnowledgeSyncTaskMapper;

    @Resource
    private IShopKnowledgeService shopKnowledgeService;

    @Resource
    private AiKnowledgeProperties knowledgeProperties;

    @Resource(name = "aiKnowledgeExecutor")
    private Executor aiKnowledgeExecutor;

    @Override
    public void enqueue(Long shopId) {
        if (shopId == null || !Boolean.TRUE.equals(knowledgeProperties.getIncrementalSyncEnabled())) {
            return;
        }
        aiKnowledgeSyncTaskMapper.upsertPending(shopId);
        aiKnowledgeExecutor.execute(this::processDueTasks);
    }

    @Override
    @Scheduled(fixedDelayString = "${ai.knowledge.sync-retry-scan-delay-ms:10000}")
    public void processDueTasks() {
        if (!Boolean.TRUE.equals(knowledgeProperties.getIncrementalSyncEnabled())) {
            return;
        }
        recoverTimedOutTasks();
        List<AiKnowledgeSyncTask> tasks = aiKnowledgeSyncTaskMapper.selectList(new QueryWrapper<AiKnowledgeSyncTask>()
                .eq("status", AiKnowledgeSyncTask.STATUS_PENDING)
                .le("next_retry_at", LocalDateTime.now())
                .orderByAsc("next_retry_at")
                .last("LIMIT " + BATCH_SIZE));
        for (AiKnowledgeSyncTask task : tasks) {
            processTask(task);
        }
    }

    private void processTask(AiKnowledgeSyncTask task) {
        if (aiKnowledgeSyncTaskMapper.claim(task.getId(), task.getVersion()) != 1) {
            return;
        }
        int attempt = safeRetryCount(task.getRetryCount()) + 1;
        try {
            shopKnowledgeService.syncShopKnowledge(task.getShopId());
            if (aiKnowledgeSyncTaskMapper.markSucceeded(task.getId(), task.getVersion()) == 1) {
                log.info("Knowledge sync task completed, taskId={}, shopId={}, attempt={}",
                        task.getId(), task.getShopId(), attempt);
            }
        } catch (Exception e) {
            int maxAttempts = Math.max(1, knowledgeProperties.getSyncRetryMaxAttempts());
            boolean exhausted = attempt >= maxAttempts;
            LocalDateTime nextRetryAt = exhausted ? null : LocalDateTime.now().plusSeconds(backoffSeconds(attempt));
            int status = exhausted ? AiKnowledgeSyncTask.STATUS_FAILED : AiKnowledgeSyncTask.STATUS_PENDING;
            aiKnowledgeSyncTaskMapper.markFailedOrPending(task.getId(), task.getVersion(), status,
                    nextRetryAt, limitError(e.getMessage()));
            if (exhausted) {
                log.error("Knowledge sync task exhausted retries, taskId={}, shopId={}, attempts={}",
                        task.getId(), task.getShopId(), attempt, e);
            } else {
                log.warn("Knowledge sync task failed and will retry, taskId={}, shopId={}, attempt={}, nextRetryAt={}",
                        task.getId(), task.getShopId(), attempt, nextRetryAt, e);
            }
        }
    }

    private void recoverTimedOutTasks() {
        long timeoutSeconds = Math.max(30L, knowledgeProperties.getSyncProcessingTimeoutSeconds());
        int recovered = aiKnowledgeSyncTaskMapper.recoverTimedOutProcessing(LocalDateTime.now().minusSeconds(timeoutSeconds));
        if (recovered > 0) {
            log.warn("Recovered {} timed-out knowledge sync task(s)", recovered);
        }
    }

    private long backoffSeconds(int attempt) {
        long base = Math.max(1L, knowledgeProperties.getSyncRetryBaseDelaySeconds());
        return base * (1L << Math.min(attempt - 1, 6));
    }

    private int safeRetryCount(Integer retryCount) {
        return retryCount == null ? 0 : retryCount;
    }

    private String limitError(String message) {
        if (message == null) {
            return "Unknown knowledge synchronization failure";
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
