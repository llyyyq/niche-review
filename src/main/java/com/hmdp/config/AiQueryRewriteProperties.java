package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.query-rewrite")
public class AiQueryRewriteProperties {

    private Boolean enabled = true;

    private Integer longQueryThresholdChars = 160;

    private Integer maxQueryChars = 160;

    private Integer maxSubQueries = 3;

    private Integer contextMessageLimit = 4;

    private Integer maxContextChars = 2400;

    private Integer maxOutputTokens = 256;

    private Integer readTimeoutMs = 8000;
}
