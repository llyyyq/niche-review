package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.OrderTimeoutTask;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderTimeoutTaskMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IOrderTimeoutTaskService;
import com.hmdp.utils.MqConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class OrderTimeoutTaskServiceImpl implements IOrderTimeoutTaskService {

    private static final int BATCH_SIZE = 20;
    private static final long RETRY_BASE_SECONDS = 10L;
    private static final int MAX_BACKOFF_SHIFT = 5;
    private static final long PROCESSING_TIMEOUT_SECONDS = 60L;
    private static final int MAX_ERROR_LENGTH = 512;

    @Resource
    private OrderTimeoutTaskMapper orderTimeoutTaskMapper;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Override
    public void createPendingTask(Long orderId, LocalDateTime dueAt) {
        OrderTimeoutTask task = new OrderTimeoutTask();
        task.setOrderId(orderId);
        task.setDueAt(dueAt);
        task.setStatus(OrderTimeoutTask.STATUS_PENDING);
        task.setRetryCount(0);
        task.setNextRetryAt(LocalDateTime.now());
        task.setVersion(0L);
        if (orderTimeoutTaskMapper.insert(task) != 1) {
            throw new IllegalStateException("Failed to create order timeout task, orderId=" + orderId);
        }
    }

    @Override
    public void cancelPendingTask(Long orderId) {
        if (orderId != null) {
            orderTimeoutTaskMapper.cancelPendingByOrderId(orderId);
        }
    }

    @Override
    @Scheduled(fixedDelay = 5000L)
    public void processDueTasks() {
        recoverTimedOutTasks();
        List<OrderTimeoutTask> tasks = orderTimeoutTaskMapper.selectList(new QueryWrapper<OrderTimeoutTask>()
                .eq("status", OrderTimeoutTask.STATUS_PENDING)
                .le("next_retry_at", LocalDateTime.now())
                .orderByAsc("next_retry_at")
                .last("LIMIT " + BATCH_SIZE));
        for (OrderTimeoutTask task : tasks) {
            processTask(task);
        }
    }

    private void processTask(OrderTimeoutTask task) {
        if (orderTimeoutTaskMapper.claim(task.getId(), task.getVersion()) != 1) {
            return;
        }
        long processingVersion = task.getVersion() + 1L;
        int attempt = retryCount(task) + 1;
        try {
            VoucherOrder order = voucherOrderMapper.selectById(task.getOrderId());
            if (order == null || !VoucherOrder.STATUS_UNPAID.equals(order.getStatus())) {
                orderTimeoutTaskMapper.markCancelled(task.getId(), processingVersion);
                return;
            }
            long remainingMillis = Math.max(0L, Duration.between(LocalDateTime.now(), task.getDueAt()).toMillis());
            SendResult sendResult;
            if (remainingMillis > 0L) {
                sendResult = rocketMQTemplate.syncSendDelayTimeMills(
                        MqConstants.ORDER_TIMEOUT_TOPIC,
                        String.valueOf(task.getOrderId()),
                        remainingMillis
                );
            } else {
                sendResult = rocketMQTemplate.syncSend(
                        MqConstants.ORDER_TIMEOUT_TOPIC,
                        String.valueOf(task.getOrderId()));
            }
            if (sendResult == null || sendResult.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Order timeout message was not accepted by RocketMQ");
            }
            if (orderTimeoutTaskMapper.markSent(task.getId(), processingVersion) == 1) {
                log.info("Order timeout task sent, taskId={}, orderId={}, attempt={}",
                        task.getId(), task.getOrderId(), attempt);
            }
        } catch (Exception e) {
            LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds(attempt));
            orderTimeoutTaskMapper.markPendingForRetry(task.getId(), processingVersion, nextRetryAt, limitError(e.getMessage()));
            log.warn("Order timeout task send failed and will retry, taskId={}, orderId={}, attempt={}, nextRetryAt={}",
                    task.getId(), task.getOrderId(), attempt, nextRetryAt, e);
        }
    }

    private void recoverTimedOutTasks() {
        int recovered = orderTimeoutTaskMapper.recoverTimedOutProcessing(
                LocalDateTime.now().minusSeconds(PROCESSING_TIMEOUT_SECONDS));
        if (recovered > 0) {
            log.warn("Recovered {} timed-out order timeout task(s)", recovered);
        }
    }

    private int retryCount(OrderTimeoutTask task) {
        return task.getRetryCount() == null ? 0 : task.getRetryCount();
    }

    private long backoffSeconds(int attempt) {
        return RETRY_BASE_SECONDS * (1L << Math.min(Math.max(0, attempt - 1), MAX_BACKOFF_SHIFT));
    }

    private String limitError(String message) {
        if (message == null) {
            return "Unknown order timeout task delivery failure";
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
