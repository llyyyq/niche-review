package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("tb_ai_request_log")
public class AiRequestLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long userId;

    private Long assistantMessageId;

    private String requestType;

    private String provider;

    private String model;

    private Long retrievalMs;

    private Long toolMs;

    private Long firstTokenMs;

    private Long totalMs;

    private Integer inputTokens;

    private Integer outputTokens;

    private Integer success;

    private String errorMessage;

    private LocalDateTime createTime;
}
