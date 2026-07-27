package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderCreateResult;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 秒杀下单：Lua 原子扣库存 + RocketMQ 异步落库
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 消费秒杀消息，将订单写入 MySQL
     */
    VoucherOrderCreateResult voucherOrder(VoucherOrder voucherOrder);

    /**
     * Roll back an order-level Redis reservation after its message can no
     * longer create a database order. Repeated invocations are safe.
     */
    boolean compensateSeckillReservation(Long voucherId, Long userId, Long orderId);

    /**
     * 超时关单：检查订单状态，如果仍为"未支付"，则取消订单并回滚库存。
     * 由 OrderTimeoutListener（消费 RocketMQ 延迟消息）调用。
     */
    void closeTimeoutOrder(Long orderId);
}
