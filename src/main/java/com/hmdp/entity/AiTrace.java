package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("tb_ai_trace")
public class AiTrace implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String requestId;
    private String traceId;
    private String rootSpanId;
    private String traceType;
    private String linkedTraceId;
    private Long conversationId;
    private Long userId;
    private Long userMessageId;
    private Long assistantMessageId;
    private String status;
    private String outcome;
    private String currentStage;
    private String errorStage;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime firstTokenAt;
    private LocalDateTime completedAt;
    private Long totalMs;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
