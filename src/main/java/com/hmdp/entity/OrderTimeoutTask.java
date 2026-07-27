package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_order_timeout_task")
public class OrderTimeoutTask {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PROCESSING = 1;
    public static final int STATUS_SENT = 2;
    public static final int STATUS_CANCELLED = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private LocalDateTime dueAt;

    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    private LocalDateTime processingAt;

    private String lastError;

    private Long version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
