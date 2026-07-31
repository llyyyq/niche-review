package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.trace")
public class AiTraceProperties {

    private Boolean enabled = true;
    private Integer retentionDays = 30;
    private Integer cleanupBatchSize = 500;
}
