package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.qdrant")
public class AiQdrantProperties {

    private String url;

    private String apiKey;

    private Integer connectTimeoutMs = 5000;

    private Integer readTimeoutMs = 30000;
}
