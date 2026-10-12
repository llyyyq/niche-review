package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderDispatchTask;
import com.hmdp.mapper.VoucherOrderDispatchTaskMapper;
import com.hmdp.service.IVoucherOrderDispatchTaskService;
import com.hmdp.utils.MqConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Transactional outbox for Redis-accepted seckill orders.
 *
 * A send timeout is an unknown result: the broker may already have accepted
 * the message. The task therefore remains retryable instead of immediately
 * releasing the Redis reservation. Repeated delivery is safe at the consumer.
 */
@Slf4j
@Service
public class VoucherOrderDispatchTaskServiceImpl implements IVoucherOrderDispatchTaskService {

    private static final int BATCH_SIZE = 20;
    private static final long RETRY_BASE_SECONDS = 10L;
    private static final int MAX_BACKOFF_SHIFT = 5;
    private static final long PROCESSING_TIMEOUT_SECONDS = 60L;
    private static final int MAX_ERROR_LENGTH = 512;
    private static final int MAX_ATTEMPTS = 10;

    @Resource
    private VoucherOrderDispatchTaskMapper dispatchTaskMapper;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Override
    @Transactional
    public void createPendingTask(VoucherOrder order) {
        VoucherOrderDispatchTask task = new VoucherOrderDispatchTask();
        task.setOrderId(order.getId());
        task.setUserId(order.getUserId());
        task.setVoucherId(order.getVoucherId());
        task.setStatus(VoucherOrderDispatchTask.STATUS_PENDING);
        task.setRetryCount(0);
        task.setNextRetryAt(LocalDateTime.now());
        task.setVersion(0L);
        if (dispatchTaskMapper.insert(task) != 1) {
            throw new IllegalStateException("Failed to persist voucher order dispatch task, orderId=" + order.getId());
        }
    }

    @Override
    public void markOrderCompleted(Long orderId) {
        dispatchTaskMapper.markCompletedByOrderId(orderId);
    }

    @Override
    public void markOrderCancelled(Long orderId, String reason) {
        dispatchTaskMapper.markCancelledByOrderId(orderId, limitError(reason));
    }

    @Override
    @Scheduled(fixedDelay = 1000L)
    public void processPendingTasks() {
        recoverTimedOutTasks();
        List<VoucherOrderDispatchTask> tasks = dispatchTaskMapper.selectList(
                new QueryWrapper<VoucherOrderDispatchTask>()
                        .eq("status", VoucherOrderDispatchTask.STATUS_PENDING)
                        .le("next_retry_at", LocalDateTime.now())
                        .orderByAsc("next_retry_at")
                        .last("LIMIT " + BATCH_SIZE));
        for (VoucherOrderDispatchTask task : tasks) {
            processTask(task);
        }
    }

    private void processTask(VoucherOrderDispatchTask task) {
        if (dispatchTaskMapper.claim(task.getId(), task.getVersion()) != 1) {
            return;
        }
        long processingVersion = task.getVersion() + 1L;
        int attempt = retryCount(task) + 1;
        try {
            VoucherOrder order = new VoucherOrder();
            order.setId(task.getOrderId());
            order.setUserId(task.getUserId());
            order.setVoucherId(task.getVoucherId());
            SendResult result = rocketMQTemplate.syncSend(MqConstants.VOUCHER_ORDER_TOPIC, JSONUtil.toJsonStr(order));
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Voucher order message was not accepted by RocketMQ");
            }
            dispatchTaskMapper.markSent(task.getId(), processingVersion);
            log.info("Voucher order dispatch task sent, taskId={}, orderId={}, attempt={}",
                    task.getId(), task.getOrderId(), attempt);
        } catch (Exception e) {
            if (attempt >= MAX_ATTEMPTS) {
                dispatchTaskMapper.markFailed(task.getId(), processingVersion, limitError(e.getMessage()));
                log.error("Voucher order dispatch exhausted retries, manual intervention required, taskId={}, orderId={}, attempt={}",
                        task.getId(), task.getOrderId(), attempt, e);
                return;
            }
            LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds(attempt));
            dispatchTaskMapper.markPendingForRetry(task.getId(), processingVersion, nextRetryAt, limitError(e.getMessage()));
            log.warn("Voucher order dispatch failed; delivery result may be unknown and will retry, taskId={}, orderId={}, attempt={}",
                    task.getId(), task.getOrderId(), attempt, e);
        }
    }

    private void recoverTimedOutTasks() {
        int recovered = dispatchTaskMapper.recoverTimedOutProcessing(
                LocalDateTime.now().minusSeconds(PROCESSING_TIMEOUT_SECONDS));
        if (recovered > 0) {
            log.warn("Recovered {} timed-out voucher order dispatch task(s)", recovered);
        }
    }

    private int retryCount(VoucherOrderDispatchTask task) {
        return task.getRetryCount() == null ? 0 : task.getRetryCount();
    }

    private long backoffSeconds(int attempt) {
        return RETRY_BASE_SECONDS * (1L << Math.min(Math.max(0, attempt - 1), MAX_BACKOFF_SHIFT));
    }

    private String limitError(String message) {
        if (message == null) {
            return "Unknown voucher order dispatch failure";
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
