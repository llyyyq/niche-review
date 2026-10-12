package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.knowledge")
public class AiKnowledgeProperties {

    private String shopCollection = "shop_knowledge";

    /** One public-review document per point; kept separate from the stable shop profile. */
    private String blogCollection = "blog_knowledge";

    /** Per collection oversampling before payload filtering and shop-level deduplication. */
    private Integer vectorCandidateLimit = 10;

    /** Maximum evidence candidates retained for each independent query task. */
    private Integer taskCandidateLimit = 5;

    private Boolean rebuildOnStart = false;

    private Boolean incrementalSyncEnabled = true;

    private Integer syncRetryMaxAttempts = 5;

    private Long syncRetryBaseDelaySeconds = 10L;

    private Long syncRetryScanDelayMs = 10000L;

    private Long syncProcessingTimeoutSeconds = 120L;

    private Double vectorMinScore = 0.35D;
}
