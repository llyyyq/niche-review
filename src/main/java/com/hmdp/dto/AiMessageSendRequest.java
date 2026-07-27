package com.hmdp.dto;

import lombok.Data;

@Data
public class AiMessageSendRequest {

    private String content;

    /** Optional client longitude, used only for nearby-store read queries. */
    private Double x;

    /** Optional client latitude, used only for nearby-store read queries. */
    private Double y;
}
