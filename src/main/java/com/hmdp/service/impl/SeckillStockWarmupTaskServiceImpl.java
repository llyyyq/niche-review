package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.SeckillStockWarmupTask;
import com.hmdp.mapper.SeckillStockWarmupTaskMapper;
import com.hmdp.service.ISeckillStockWarmupTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * Transactional outbox for seckill stock warm-up.
 *
 * The task is written in the same transaction as the voucher, so a committed
 * voucher always has a warm-up that the scheduler replays until Redis accepts it.
 * The write uses SET NX: once seckill.lua has started deducting stock, a late
 * replay must never overwrite the live counter with the initial value.
 */
@Slf4j
@Service
public class SeckillStockWarmupTaskServiceImpl implements ISeckillStockWarmupTaskService {

    private static final int BATCH_SIZE = 20;
    private static final long RETRY_BASE_SECONDS = 10L;
    private static final int MAX_BACKOFF_SHIFT = 5;
    private static final long PROCESSING_TIMEOUT_SECONDS = 60L;
    private static final int MAX_ERROR_LENGTH = 512;
    private static final int MAX_ATTEMPTS = 10;

    @Resource
    private SeckillStockWarmupTaskMapper warmupTaskMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public void createPendingTask(Long voucherId, Integer stock) {
        SeckillStockWarmupTask task = new SeckillStockWarmupTask();
        task.setVoucherId(voucherId);
        task.setStock(stock);
        task.setStatus(SeckillStockWarmupTask.STATUS_PENDING);
        task.setRetryCount(0);
        task.setNextRetryAt(LocalDateTime.now());
        task.setVersion(0L);
        if (warmupTaskMapper.insert(task) != 1) {
            throw new IllegalStateException("Failed to create seckill stock warm-up task, voucherId=" + voucherId);
        }
    }

    @Override
    @Scheduled(fixedDelay = 1000L)
    public void processPendingTasks() {
        recoverTimedOutTasks();
        List<SeckillStockWarmupTask> tasks = warmupTaskMapper.selectList(
                new QueryWrapper<SeckillStockWarmupTask>()
                        .eq("status", SeckillStockWarmupTask.STATUS_PENDING)
                        .le("next_retry_at", LocalDateTime.now())
                        .orderByAsc("next_retry_at")
                        .last("LIMIT " + BATCH_SIZE));
        for (SeckillStockWarmupTask task : tasks) {
            processTask(task);
        }
    }

    private void processTask(SeckillStockWarmupTask task) {
        if (warmupTaskMapper.claim(task.getId(), task.getVersion()) != 1) {
            return;
        }
        long processingVersion = task.getVersion() + 1L;
        int attempt = retryCount(task) + 1;
        try {
            // SET NX returns false when the key already exists. That still counts as
            // done: the key holds the live counter and must not be overwritten.
            stringRedisTemplate.opsForValue().setIfAbsent(
                    SECKILL_STOCK_KEY + task.getVoucherId(), String.valueOf(task.getStock()));
            warmupTaskMapper.markCompleted(task.getId(), processingVersion);
            log.info("Seckill stock warm-up completed, taskId={}, voucherId={}, attempt={}",
                    task.getId(), task.getVoucherId(), attempt);
        } catch (Exception e) {
            if (attempt >= MAX_ATTEMPTS) {
                warmupTaskMapper.markFailed(task.getId(), processingVersion, limitError(e.getMessage()));
                log.error("Seckill stock warm-up exhausted retries, voucher stays unavailable until fixed, taskId={}, voucherId={}, attempt={}",
                        task.getId(), task.getVoucherId(), attempt, e);
                return;
            }
            LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds(attempt));
            warmupTaskMapper.markPendingForRetry(task.getId(), processingVersion, nextRetryAt, limitError(e.getMessage()));
            log.warn("Seckill stock warm-up failed and will retry, taskId={}, voucherId={}, attempt={}, nextRetryAt={}",
                    task.getId(), task.getVoucherId(), attempt, nextRetryAt, e);
        }
    }

    private void recoverTimedOutTasks() {
        int recovered = warmupTaskMapper.recoverTimedOutProcessing(
                LocalDateTime.now().minusSeconds(PROCESSING_TIMEOUT_SECONDS));
        if (recovered > 0) {
            log.warn("Recovered {} timed-out seckill stock warm-up task(s)", recovered);
        }
    }

    private int retryCount(SeckillStockWarmupTask task) {
        return task.getRetryCount() == null ? 0 : task.getRetryCount();
    }

    private long backoffSeconds(int attempt) {
        return RETRY_BASE_SECONDS * (1L << Math.min(Math.max(0, attempt - 1), MAX_BACKOFF_SHIFT));
    }

    private String limitError(String message) {
        if (message == null) {
            return "Unknown seckill stock warm-up failure";
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
