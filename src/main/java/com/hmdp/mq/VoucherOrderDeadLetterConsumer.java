package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherOrderDispatchTaskService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.MqConstants.VOUCHER_ORDER_DLQ_COMPENSATION_GROUP;
import static com.hmdp.utils.MqConstants.VOUCHER_ORDER_DLQ_TOPIC;
import static com.hmdp.utils.RedisConstants.ORDER_LOCK_KEY;

/**
 * Final safety net for messages that fail all normal order-consumption
 * attempts. The rollback script is keyed by order id, so duplicate dead-letter
 * deliveries cannot restore Redis stock more than once.
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = VOUCHER_ORDER_DLQ_TOPIC,
        consumerGroup = VOUCHER_ORDER_DLQ_COMPENSATION_GROUP
)
public class VoucherOrderDeadLetterConsumer implements RocketMQListener<String> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private IVoucherOrderDispatchTaskService voucherOrderDispatchTaskService;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public void onMessage(String message) {
        VoucherOrder voucherOrder;
        try {
            voucherOrder = JSONUtil.toBean(message, VoucherOrder.class);
        } catch (Exception e) {
            log.error("Invalid voucher-order dead-letter message; cannot compensate. message={}", message, e);
            return;
        }

        if (voucherOrder == null || voucherOrder.getId() == null
                || voucherOrder.getUserId() == null || voucherOrder.getVoucherId() == null) {
            log.error("Incomplete voucher-order dead-letter message; cannot compensate. message={}", message);
            return;
        }

        RLock lock = redissonClient.getLock(ORDER_LOCK_KEY + voucherOrder.getUserId());
        boolean locked = false;
        try {
            locked = lock.tryLock(1, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("Failed to acquire user order lock for dead-letter compensation, userId="
                        + voucherOrder.getUserId());
            }
            boolean compensated = voucherOrderService.compensateSeckillReservation(
                    voucherOrder.getVoucherId(), voucherOrder.getUserId(), voucherOrder.getId());
            if (!compensated) {
                throw new IllegalStateException("Redis reservation was not released for dead-letter orderId="
                        + voucherOrder.getId());
            }
            voucherOrderDispatchTaskService.markOrderCancelled(voucherOrder.getId(), "Order consumer exhausted retries");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during dead-letter compensation", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        log.warn("Voucher-order dead-letter compensation finished, orderId={}, voucherId={}, userId={}",
                voucherOrder.getId(), voucherOrder.getVoucherId(), voucherOrder.getUserId());
    }
}
