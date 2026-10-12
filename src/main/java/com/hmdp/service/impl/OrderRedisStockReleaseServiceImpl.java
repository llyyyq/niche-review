package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.OrderRedisStockReleaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * Redis is deliberately changed only after the cancellation transaction commits.
 * The redis_stock_released column is the durable retry marker for this side effect.
 */
@Slf4j
@Service
public class OrderRedisStockReleaseServiceImpl implements OrderRedisStockReleaseService {

    private static final int BATCH_SIZE = 20;
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT;

    static {
        RELEASE_SCRIPT = new DefaultRedisScript<>();
        RELEASE_SCRIPT.setLocation(new ClassPathResource("seckill_close_order.lua"));
        RELEASE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void releaseAfterCommit(Long orderId, Long voucherId) {
        tryRelease(orderId, voucherId);
    }

    @Override
    @Scheduled(fixedDelay = 5000L)
    public void reconcilePendingReleases() {
        List<VoucherOrder> orders = voucherOrderMapper.selectList(new QueryWrapper<VoucherOrder>()
                .eq("status", VoucherOrder.STATUS_CANCELLED)
                .eq("redis_stock_released", 0)
                .orderByAsc("update_time")
                .last("LIMIT " + BATCH_SIZE));
        for (VoucherOrder order : orders) {
            tryRelease(order.getId(), order.getVoucherId());
        }
    }

    private void tryRelease(Long orderId, Long voucherId) {
        try {
            Long result = stringRedisTemplate.execute(RELEASE_SCRIPT, Collections.emptyList(),
                    String.valueOf(orderId), String.valueOf(voucherId));
            if (result == null || result < 0L) {
                throw new IllegalStateException("Redis seckill stock key is unavailable");
            }
            voucherOrderMapper.markRedisStockReleased(orderId);
            log.info("Redis stock release reconciled, orderId={}, voucherId={}, scriptResult={}",
                    orderId, voucherId, result);
        } catch (Exception e) {
            // The cancellation is already committed. Keep redis_stock_released=0 for the next scan.
            log.warn("Redis stock release pending retry, orderId={}, voucherId={}", orderId, voucherId, e);
        }
    }
}
