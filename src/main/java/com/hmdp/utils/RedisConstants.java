package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    /** Random offset (minutes) used to spread normal shop-cache expiration. */
    public static final Long CACHE_SHOP_TTL_RANDOM_MAX = 5L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String SHOP_BLOOM_FILTER_KEY = "bf:shop:id";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop:type";
    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    /**
     * One short-lived marker for every successful Redis reservation. It lets
     * compensation distinguish this order's reservation from an older order
     * made by the same user for the same voucher.
     */
    public static final String SECKILL_RESERVATION_KEY = "seckill:reservation:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    public static final String FOLLOWS_KEY = "follows:";

    /**
     * 订单操作的分布式锁 key。
     * 支付回调和超时关单共用同一把锁，锁粒度为订单 ID。
     * 两个操作争同一把锁，保证只有一个能成功修改订单状态，解决并发写冲突。
     */
    public static final String ORDER_LOCK_KEY = "lock:order:";
}
