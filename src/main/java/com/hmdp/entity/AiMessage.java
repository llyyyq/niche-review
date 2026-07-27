package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("tb_ai_message")
public class AiMessage implements Serializable {

    public static final int ROLE_USER = 1;
    public static final int ROLE_ASSISTANT = 2;
    public static final int ROLE_SYSTEM = 3;

    public static final int STATUS_GENERATING = 0;
    public static final int STATUS_COMPLETED = 1;
    public static final int STATUS_FAILED = 2;

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long userId;

    private Integer role;

    private String content;

    private Integer status;

    private Integer inputTokens;

    private Integer outputTokens;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
