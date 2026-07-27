package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IOrderTimeoutTaskService;
import com.hmdp.service.IPaymentService;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.ORDER_LOCK_KEY;

@Slf4j
@Service
public class PaymentServiceImpl implements IPaymentService {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private IOrderTimeoutTaskService orderTimeoutTaskService;

    @Override
    public Result handlePaymentCallback(Long orderId) {
        RLock lock = redissonClient.getLock(ORDER_LOCK_KEY + orderId);
        boolean isLock = false;
        try {
            isLock = lock.tryLock(1, 30, TimeUnit.SECONDS);
            if (!isLock) {
                log.warn("Failed to acquire payment lock, orderId={}", orderId);
                return Result.fail("订单处理中，请稍后重试");
            }

            VoucherOrder order = voucherOrderService.getById(orderId);
            if (order == null) {
                return Result.fail("订单不存在");
            }
            if (VoucherOrder.STATUS_PAID.equals(order.getStatus())) {
                return Result.ok("订单已支付");
            }
            if (!VoucherOrder.STATUS_UNPAID.equals(order.getStatus())) {
                return Result.fail("订单状态不正确，无法支付");
            }

            boolean updated = voucherOrderService
                    .update()
                    .set("status", VoucherOrder.STATUS_PAID)
                    .set("pay_time", LocalDateTime.now())
                    .set("update_time", LocalDateTime.now())
                    .eq("id", orderId)
                    .eq("status", VoucherOrder.STATUS_UNPAID)
                    .update();
            if (!updated) {
                return Result.fail("订单状态已变化，请刷新后重试");
            }

            try {
                // A timeout message that has already been delivered remains
                // harmless because its consumer performs a status CAS check.
                orderTimeoutTaskService.cancelPendingTask(orderId);
            } catch (Exception e) {
                log.error("Failed to cancel pending timeout task after payment, orderId={}", orderId, e);
            }

            log.info("Payment callback completed, orderId={}", orderId);
            return Result.ok("支付成功");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while acquiring payment lock, orderId={}", orderId, e);
            return Result.fail("系统繁忙，请稍后重试");
        } catch (Exception e) {
            log.error("Payment callback failed, orderId={}", orderId, e);
            return Result.fail("支付回调处理失败");
        } finally {
            if (isLock && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
