package com.hmdp.utils;

public class MqConstants {
    public static final String VOUCHER_ORDER_TOPIC = "hmdp-voucher-order-topic";
    public static final String VOUCHER_ORDER_CONSUMER_GROUP = "hmdp-voucher-order-consumer-group";
    public static final String VOUCHER_ORDER_DLQ_TOPIC = "%DLQ%" + VOUCHER_ORDER_CONSUMER_GROUP;
    public static final String VOUCHER_ORDER_DLQ_COMPENSATION_GROUP =
            "hmdp-voucher-order-dlq-compensation-group";

    public static final String ORDER_TIMEOUT_TOPIC = "hmdp-order-timeout-topic";
    public static final String ORDER_TIMEOUT_CONSUMER_GROUP = "hmdp-order-timeout-consumer-group";

    /**
     * 订单支付超时时间。
     * 当前设置为 15 分钟。RocketMQ 5.x 的 syncSendDelayTimeMills 支持毫秒级延迟。
     */
    public static final long ORDER_PAYMENT_TIMEOUT_MILLIS = 15 * 60 * 1000L;
}
