package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.query-rewrite")
public class AiQueryRewriteProperties {

    private Boolean enabled = true;

    private Integer maxQueryChars = 160;

    /** Maximum independent business tasks accepted from one model response. */
    private Integer maxTasks = 3;

    /** One retry is enough to handle transient provider/format failures without hiding latency. */
    private Integer maxAttempts = 2;

    private Integer contextMessageLimit = 4;

    private Integer maxContextChars = 2400;

    public static final int DEFAULT_READ_TIMEOUT_MS = 15000;

    private Integer readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
}
