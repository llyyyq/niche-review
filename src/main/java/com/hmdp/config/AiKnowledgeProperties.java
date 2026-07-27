package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.knowledge")
public class AiKnowledgeProperties {

    private String shopCollection = "shop_knowledge";

    private Integer retrieveLimit = 3;

    private Boolean rebuildOnStart = false;

    private Boolean incrementalSyncEnabled = true;

    private Integer syncRetryMaxAttempts = 5;

    private Long syncRetryBaseDelaySeconds = 10L;

    private Long syncRetryScanDelayMs = 10000L;

    private Long syncProcessingTimeoutSeconds = 120L;

    private Boolean keywordFallbackEnabled = true;

    private Double vectorMinScore = 0.35D;

    private Integer keywordFallbackLimit = 3;
}
