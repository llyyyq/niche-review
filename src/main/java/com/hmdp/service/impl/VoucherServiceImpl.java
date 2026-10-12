package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillStockWarmupTaskService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.event.ShopKnowledgeChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private ISeckillStockWarmupTaskService seckillStockWarmupTaskService;
    @Resource
    private ApplicationEventPublisher applicationEventPublisher;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    public List<Voucher> listEnabledVouchersForKnowledge(List<Long> shopIds) {
        return getBaseMapper().queryEnabledVouchersByShopIds(shopIds);
    }

    @Override
    @Transactional
    public void addVoucher(Voucher voucher) {
        if (!save(voucher)) {
            throw new IllegalStateException("Failed to save voucher");
        }
        publishKnowledgeChanged(voucher.getShopId());
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        if (voucher.getStock() == null) {
            throw new IllegalArgumentException("Seckill voucher stock is required");
        }
        // 保存优惠券
        if (!save(voucher)) {
            throw new IllegalStateException("Failed to save voucher");
        }
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("Failed to save seckill voucher");
        }
        // 与券在同一事务中记录预热任务，由调度器异步写入 Redis 库存
        seckillStockWarmupTaskService.createPendingTask(voucher.getId(), voucher.getStock());
        publishKnowledgeChanged(voucher.getShopId());
    }

    private void publishKnowledgeChanged(Long shopId) {
        if (shopId != null) {
            applicationEventPublisher.publishEvent(new ShopKnowledgeChangedEvent(shopId));
        }
    }
}
