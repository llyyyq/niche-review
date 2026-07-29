package com.hmdp.ai;

public class AiCompletionOptions {

    private final Double temperature;
    private final Integer maxTokens;
    private final Integer readTimeoutMs;

    public AiCompletionOptions(Double temperature, Integer maxTokens, Integer readTimeoutMs) {
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.readTimeoutMs = readTimeoutMs;
    }

    public Double getTemperature() {
        return temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public Integer getReadTimeoutMs() {
        return readTimeoutMs;
    }
}
