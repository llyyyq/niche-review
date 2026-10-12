package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable handoff from a Redis seckill reservation to RocketMQ.
 */
@Data
@TableName("tb_voucher_order_dispatch_task")
public class VoucherOrderDispatchTask {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PROCESSING = 1;
    public static final int STATUS_SENT = 2;
    public static final int STATUS_COMPLETED = 3;
    public static final int STATUS_CANCELLED = 4;
    /** Retries exhausted; needs manual intervention. */
    public static final int STATUS_FAILED = 5;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime processingAt;
    private String lastError;
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
