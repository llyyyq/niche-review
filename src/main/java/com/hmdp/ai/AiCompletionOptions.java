package com.hmdp.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class AiCompletionOptions {

    private final Double temperature;
    private final Integer maxTokens;
    private final Integer readTimeoutMs;
    private final Map<String, Object> responseFormat;
    private final Map<String, Object> thinking;

    public AiCompletionOptions(Double temperature, Integer maxTokens, Integer readTimeoutMs) {
        this(temperature, maxTokens, readTimeoutMs, null, null);
    }

    private AiCompletionOptions(Double temperature, Integer maxTokens, Integer readTimeoutMs,
                                Map<String, Object> responseFormat,
                                Map<String, Object> thinking) {
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.readTimeoutMs = readTimeoutMs;
        this.responseFormat = responseFormat == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(responseFormat));
        this.thinking = thinking == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(thinking));
    }

    /**
     * Requests an OpenAI-compatible JSON object response. The caller still owns
     * schema and business validation because JSON mode only guarantees syntax.
     */
    public static AiCompletionOptions jsonObject(Double temperature, Integer maxTokens,
                                                 Integer readTimeoutMs) {
        return jsonObject(temperature, maxTokens, readTimeoutMs, false);
    }

    private static AiCompletionOptions jsonObject(Double temperature, Integer maxTokens,
                                                  Integer readTimeoutMs,
                                                  boolean thinkingEnabled) {
        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_object");
        Map<String, Object> thinking = new LinkedHashMap<>();
        thinking.put("type", thinkingEnabled ? "enabled" : "disabled");
        return new AiCompletionOptions(temperature, maxTokens, readTimeoutMs,
                responseFormat, thinking);
    }

    /**
     * Requests JSON mode without imposing a client-side output-token cap. The
     * structured response parser still enforces the actual field and size
     * contract after the provider returns.
     */
    public static AiCompletionOptions jsonObject(Double temperature, Integer readTimeoutMs) {
        return jsonObject(temperature, null, readTimeoutMs);
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

    public Map<String, Object> getResponseFormat() {
        return responseFormat;
    }

    public Map<String, Object> getThinking() {
        return thinking;
    }
}
