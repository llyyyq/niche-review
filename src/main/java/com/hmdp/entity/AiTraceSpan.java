package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("tb_ai_trace_span")
public class AiTraceSpan implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String stageName;
    private String status;
    private String attributesJson;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;
    private LocalDateTime createTime;
}
