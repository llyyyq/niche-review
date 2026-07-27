package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.memory")
public class AiMemoryProperties {

    /** Generate a rolling summary when unsummarized completed messages exceed this count. */
    private Integer summaryTriggerMessageCount = 12;

    private Integer maxSummaryChars = 2400;

    private Integer maxSummarySourceChars = 12000;

    private Integer maxRecentMessageChars = 6000;

    private Integer maxKnowledgeChars = 6000;

    private Integer maxToolResultChars = 4000;
}
