package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.embedding")
public class AiEmbeddingProperties {

    private String provider = "disabled";

    private String baseUrl;

    private String apiKey;

    private String model;

    private Integer dimension = 1024;

    private Integer connectTimeoutMs = 10000;

    private Integer readTimeoutMs = 60000;
}
