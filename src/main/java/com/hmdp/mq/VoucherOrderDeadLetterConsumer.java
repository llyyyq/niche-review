package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static com.hmdp.utils.MqConstants.VOUCHER_ORDER_DLQ_COMPENSATION_GROUP;
import static com.hmdp.utils.MqConstants.VOUCHER_ORDER_DLQ_TOPIC;

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

        boolean compensated = voucherOrderService.compensateSeckillReservation(
                voucherOrder.getVoucherId(), voucherOrder.getUserId(), voucherOrder.getId());
        if (!compensated) {
            throw new IllegalStateException("Redis reservation was not released for dead-letter orderId="
                    + voucherOrder.getId());
        }
        log.warn("Voucher-order dead-letter compensation finished, orderId={}, voucherId={}, userId={}",
                voucherOrder.getId(), voucherOrder.getVoucherId(), voucherOrder.getUserId());
    }
}
