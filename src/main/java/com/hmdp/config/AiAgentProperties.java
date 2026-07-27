package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.agent")
public class AiAgentProperties {

    private Boolean enabled = true;

    /**
     * A second planning call adds another model round trip. One step is enough
     * for the current read-only tools and keeps the chat response responsive.
     */
    private Integer maxSteps = 1;

    private Integer maxToolsPerStep = 3;

    private Boolean fastPathEnabled = true;
}
