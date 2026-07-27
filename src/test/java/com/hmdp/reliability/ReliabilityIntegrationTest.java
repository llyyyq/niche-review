package com.hmdp.reliability;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.OrderTimeoutTask;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderTimeoutTaskMapper;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.VoucherOrderConsumer;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.SimpleRedisLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uses the local MySQL and Redis instances. Every test creates unique Redis
 * keys and the order test removes its temporary database records in @AfterEach.
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "hmdp.scheduling.enabled=false",
        "ai.embedding.provider=disabled",
        "ai.knowledge.rebuild-on-start=false"
})
@ActiveProfiles("local")
class ReliabilityIntegrationTest {

    private static final long EXISTING_SHOP_ID = 1L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private ShopMapper shopMapper;
    @Resource
    private VoucherOrderConsumer voucherOrderConsumer;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Resource
    private OrderTimeoutTaskMapper orderTimeoutTaskMapper;

    private Shop originalShop;
    private String originalShopCache;
    private Long temporaryVoucherId;
    private Long temporaryOrderId;

    @AfterEach
    void cleanUp() {
        if (temporaryOrderId != null) {
            orderTimeoutTaskMapper.delete(new QueryWrapper<OrderTimeoutTask>()
                    .eq("order_id", temporaryOrderId));
            voucherOrderMapper.deleteById(temporaryOrderId);
        }
        if (temporaryVoucherId != null) {
            seckillVoucherService.removeById(temporaryVoucherId);
            voucherService.removeById(temporaryVoucherId);
            stringRedisTemplate.delete(SECKILL_STOCK_KEY + temporaryVoucherId);
        }
        if (originalShop != null) {
            shopMapper.updateById(originalShop);
        }
        if (originalShopCache == null) {
            stringRedisTemplate.delete(CACHE_SHOP_KEY + EXISTING_SHOP_ID);
        } else {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + EXISTING_SHOP_ID, originalShopCache);
        }
    }

    @Test
    void shouldEvictShopCacheOnlyAfterSuccessfulCommit() {
        saveOriginalShopAndCache();
        String cacheKey = CACHE_SHOP_KEY + EXISTING_SHOP_ID;
        String cacheValue = "reliability-cache-before-commit";
        stringRedisTemplate.opsForValue().set(cacheKey, cacheValue);

        transactionTemplate.execute(status -> {
            assertTrue(shopService.update(shopWithTestName("rollback")).getSuccess());
            assertEquals(cacheValue, stringRedisTemplate.opsForValue().get(cacheKey));
            status.setRollbackOnly();
            return null;
        });
        assertEquals(cacheValue, stringRedisTemplate.opsForValue().get(cacheKey));
        assertEquals(originalShop.getName(), shopMapper.selectById(EXISTING_SHOP_ID).getName());

        transactionTemplate.execute(status -> {
            assertTrue(shopService.update(shopWithTestName("commit")).getSuccess());
            assertEquals(cacheValue, stringRedisTemplate.opsForValue().get(cacheKey));
            return null;
        });
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(cacheKey)));
    }

    @Test
    void shouldNotDeleteLockReacquiredByAnotherThread() throws Exception {
        String lockName = "reliability:mutex:" + System.nanoTime();
        String redisKey = "lock:" + lockName;
        SimpleRedisLock firstOwnerLock = new SimpleRedisLock(stringRedisTemplate, lockName);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch secondOwnerAcquired = new CountDownLatch(1);
        CountDownLatch releaseSecondOwner = new CountDownLatch(1);
        Future<Boolean> secondOwnerResult = null;
        try {
            assertTrue(firstOwnerLock.tryLock(1L));
            Thread.sleep(1200L);

            secondOwnerResult = executor.submit(() -> {
                SimpleRedisLock secondOwnerLock = new SimpleRedisLock(stringRedisTemplate, lockName);
                boolean acquired = secondOwnerLock.tryLock(5L);
                secondOwnerAcquired.countDown();
                if (!acquired) {
                    return false;
                }
                try {
                    assertTrue(releaseSecondOwner.await(5L, TimeUnit.SECONDS));
                    return true;
                } finally {
                    secondOwnerLock.unLock();
                }
            });

            assertTrue(secondOwnerAcquired.await(5L, TimeUnit.SECONDS));
            firstOwnerLock.unLock();
            assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(redisKey)));

            releaseSecondOwner.countDown();
            assertTrue(secondOwnerResult.get(5L, TimeUnit.SECONDS));
            assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(redisKey)));
        } finally {
            releaseSecondOwner.countDown();
            if (secondOwnerResult != null) {
                secondOwnerResult.cancel(true);
            }
            executor.shutdownNow();
            stringRedisTemplate.delete(redisKey);
        }
    }

    @Test
    void shouldCreateOnlyOneOrderWhenSameMessageIsConsumedTwice() {
        createTemporarySeckillVoucher();
        temporaryOrderId = System.currentTimeMillis() * 1_000L + (System.nanoTime() % 1_000L);
        long userId = 9_000_000_000L + (System.nanoTime() % 1_000_000L);

        VoucherOrder messageOrder = new VoucherOrder();
        messageOrder.setId(temporaryOrderId);
        messageOrder.setUserId(userId);
        messageOrder.setVoucherId(temporaryVoucherId);
        String message = JSONUtil.toJsonStr(messageOrder);

        voucherOrderConsumer.onMessage(message);
        voucherOrderConsumer.onMessage(message);

        VoucherOrder storedOrder = voucherOrderMapper.selectById(temporaryOrderId);
        assertNotNull(storedOrder);
        assertEquals(VoucherOrder.STATUS_UNPAID, storedOrder.getStatus());
        assertEquals(1L, voucherOrderMapper.selectCount(new QueryWrapper<VoucherOrder>()
                .eq("id", temporaryOrderId)).longValue());
        assertEquals(1L, orderTimeoutTaskMapper.selectCount(new QueryWrapper<OrderTimeoutTask>()
                .eq("order_id", temporaryOrderId)).longValue());
        assertEquals(0, seckillVoucherService.getById(temporaryVoucherId).getStock().intValue());
        assertEquals("1", stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + temporaryVoucherId));
    }

    private void saveOriginalShopAndCache() {
        originalShop = shopMapper.selectById(EXISTING_SHOP_ID);
        assertNotNull(originalShop, "Test requires tb_shop.id=1");
        originalShop = new Shop()
                .setId(originalShop.getId())
                .setName(originalShop.getName());
        originalShopCache = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + EXISTING_SHOP_ID);
    }

    private Shop shopWithTestName(String phase) {
        return new Shop()
                .setId(EXISTING_SHOP_ID)
                .setName(originalShop.getName() + "-reliability-" + phase + "-" + System.nanoTime());
    }

    private void createTemporarySeckillVoucher() {
        Voucher voucher = new Voucher();
        voucher.setShopId(EXISTING_SHOP_ID);
        voucher.setTitle("reliability-test-voucher-" + System.nanoTime());
        voucher.setSubTitle("integration test only");
        voucher.setRules("test only");
        voucher.setPayValue(1L);
        voucher.setActualValue(1L);
        voucher.setType(1);
        voucher.setStatus(1);
        assertTrue(voucherService.save(voucher));
        temporaryVoucherId = voucher.getId();

        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(temporaryVoucherId);
        seckillVoucher.setStock(1);
        seckillVoucher.setBeginTime(LocalDateTime.now().minusMinutes(1L));
        seckillVoucher.setEndTime(LocalDateTime.now().plusHours(1L));
        assertTrue(seckillVoucherService.save(seckillVoucher));
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + temporaryVoucherId, "1");
    }
}
