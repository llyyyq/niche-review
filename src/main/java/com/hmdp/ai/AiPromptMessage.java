package com.hmdp.ai;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AiPromptMessage {

    private String role;

    private String content;
}
