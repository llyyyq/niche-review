package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.chat")
public class AiChatProperties {

    private String provider = "mock";

    private String baseUrl;

    private String apiKey;

    private String model;

    private Integer connectTimeoutMs = 10000;

    private Integer readTimeoutMs = 120000;

    private Long streamTimeoutMs = 120000L;

    private Integer contextMessageLimit = 6;

    private String systemPrompt = "你是小众点评的本地生活助手。回答应简洁、诚实；没有可靠业务资料时明确说明。";
}
