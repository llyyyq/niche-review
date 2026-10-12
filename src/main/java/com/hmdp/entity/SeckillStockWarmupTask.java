package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable handoff from a committed seckill voucher to its Redis stock counter.
 */
@Data
@TableName("tb_seckill_stock_warmup_task")
public class SeckillStockWarmupTask {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PROCESSING = 1;
    public static final int STATUS_COMPLETED = 2;
    public static final int STATUS_FAILED = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long voucherId;
    private Integer stock;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime processingAt;
    private String lastError;
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
