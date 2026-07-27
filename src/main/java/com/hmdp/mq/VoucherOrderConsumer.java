package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderCreateResult;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.MqConstants.VOUCHER_ORDER_CONSUMER_GROUP;
import static com.hmdp.utils.MqConstants.VOUCHER_ORDER_TOPIC;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = VOUCHER_ORDER_TOPIC,
        consumerGroup = VOUCHER_ORDER_CONSUMER_GROUP,
        maxReconsumeTimes = 5
)
public class VoucherOrderConsumer implements RocketMQListener<String> {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(String message) {
        log.info("Received voucher order message, message={}", message);
        VoucherOrder voucherOrder = JSONUtil.toBean(message, VoucherOrder.class);
        Long userId = voucherOrder.getUserId();

        if (voucherOrder.getId() == null || userId == null || voucherOrder.getVoucherId() == null) {
            log.error("Invalid voucher order message, skip it. message={}", message);
            return;
        }

        RLock lock = redissonClient.getLock("order:" + userId);
        boolean isLock = false;
        try {
            isLock = lock.tryLock(1, TimeUnit.SECONDS);
            if (!isLock) {
                throw new IllegalStateException("Failed to acquire user order lock, userId="
                        + userId + ", voucherId=" + voucherOrder.getVoucherId());
            }
            VoucherOrderCreateResult result = voucherOrderService.voucherOrder(voucherOrder);
            if (result == VoucherOrderCreateResult.DUPLICATE_USER_VOUCHER) {
                log.warn("Duplicate user/voucher message reconciled, orderId={}, userId={}, voucherId={}",
                        voucherOrder.getId(), userId, voucherOrder.getVoucherId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring user order lock, userId=" + userId, e);
        } finally {
            if (isLock && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
