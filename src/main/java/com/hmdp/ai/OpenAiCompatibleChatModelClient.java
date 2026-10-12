package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiChatProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@ConditionalOnProperty(name = "ai.chat.provider", havingValue = "openai-compatible")
public class OpenAiCompatibleChatModelClient implements AiChatModelClient {

    @Resource
    private AiChatProperties aiChatProperties;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public void stream(List<AiPromptMessage> messages, AiStreamObserver observer) throws Exception {
        validateConfiguration();
        HttpURLConnection connection = (HttpURLConnection) new URL(aiChatProperties.getBaseUrl()).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(aiChatProperties.getConnectTimeoutMs());
        connection.setReadTimeout(aiChatProperties.getReadTimeoutMs());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Authorization", "Bearer " + aiChatProperties.getApiKey());

        try {
            writeRequest(connection, messages, true, null);
            int status = connection.getResponseCode();
            if (status < HttpURLConnection.HTTP_OK || status >= HttpURLConnection.HTTP_MULT_CHOICE) {
                throw new IllegalStateException("AI provider returned HTTP " + status + ": "
                        + readBody(connection.getErrorStream()));
            }
            readStream(connection.getInputStream(), observer);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public String complete(List<AiPromptMessage> messages, AiCompletionOptions options) throws Exception {
        validateConfiguration();
        HttpURLConnection connection = (HttpURLConnection) new URL(aiChatProperties.getBaseUrl()).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(aiChatProperties.getConnectTimeoutMs());
        connection.setReadTimeout(options == null || options.getReadTimeoutMs() == null
                ? aiChatProperties.getReadTimeoutMs()
                : options.getReadTimeoutMs());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + aiChatProperties.getApiKey());

        try {
            writeRequest(connection, messages, false, options);
            int status = connection.getResponseCode();
            if (status < HttpURLConnection.HTTP_OK || status >= HttpURLConnection.HTTP_MULT_CHOICE) {
                throw new IllegalStateException("AI provider returned HTTP " + status + ": "
                        + readBody(connection.getErrorStream()));
            }
            JsonNode root = objectMapper.readTree(connection.getInputStream());
            JsonNode choice = root.path("choices").path(0);
            if (choice.isMissingNode() || choice.isNull()) {
                throw new IllegalStateException("AI provider returned no completion choice");
            }
            String finishReason = choice.path("finish_reason").asText("");
            if ("length".equalsIgnoreCase(finishReason)) {
                throw new IllegalStateException(truncationDetails(root, choice, options));
            }
            JsonNode content = choice.path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new IllegalStateException("AI provider returned an empty completion");
            }
            return content.asText();
        } finally {
            connection.disconnect();
        }
    }

    private String truncationDetails(JsonNode root, JsonNode choice, AiCompletionOptions options) {
        JsonNode usage = root.path("usage");
        JsonNode message = choice.path("message");
        // Keep provider diagnostics, never the prompt, answer or reasoning text.
        return "AI provider truncated the completion at max_tokens"
                + "; requestedMaxTokens=" + (options == null ? "unknown" : options.getMaxTokens())
                + "; completionTokens=" + usage.path("completion_tokens").asText("unknown")
                + "; reasoningTokens=" + usage.path("completion_tokens_details")
                .path("reasoning_tokens").asText("unknown")
                + "; contentChars=" + message.path("content").asText("").length()
                + "; reasoningChars=" + message.path("reasoning_content").asText("").length();
    }

    private void writeRequest(HttpURLConnection connection, List<AiPromptMessage> messages,
                              boolean stream, AiCompletionOptions options) throws Exception {
        JsonNode payload = objectMapper.valueToTree(new OpenAiRequest(
                aiChatProperties.getModel(),
                messages,
                stream,
                options == null ? null : options.getTemperature(),
                options == null ? null : options.getMaxTokens(),
                options == null ? null : options.getResponseFormat(),
                options == null ? null : options.getThinking()
        ));
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(objectMapper.writeValueAsBytes(payload));
        }
    }

    private void readStream(InputStream inputStream, AiStreamObserver observer) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    return;
                }
                JsonNode root = objectMapper.readTree(data);
                JsonNode content = root.path("choices").path(0).path("delta").path("content");
                if (!content.isMissingNode() && !content.isNull() && StrUtil.isNotBlank(content.asText())) {
                    observer.onDelta(content.asText());
                }
            }
        }
    }

    private String readBody(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            return body.toString();
        }
    }

    private void validateConfiguration() {
        if (StrUtil.hasBlank(aiChatProperties.getBaseUrl(), aiChatProperties.getApiKey(), aiChatProperties.getModel())) {
            throw new IllegalStateException("OpenAI compatible AI configuration is incomplete");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class OpenAiRequest {
        private final String model;
        private final List<AiPromptMessage> messages;
        private final boolean stream;
        private final Double temperature;
        private final Integer maxTokens;
        private final Object responseFormat;
        private final Object thinking;

        private OpenAiRequest(String model, List<AiPromptMessage> messages, boolean stream,
                              Double temperature, Integer maxTokens, Object responseFormat,
                              Object thinking) {
            this.model = model;
            this.messages = messages;
            this.stream = stream;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
            this.responseFormat = responseFormat;
            this.thinking = thinking;
        }

        public String getModel() {
            return model;
        }

        public List<AiPromptMessage> getMessages() {
            return messages;
        }

        public boolean isStream() {
            return stream;
        }

        public Double getTemperature() {
            return temperature;
        }

        @JsonProperty("max_tokens")
        public Integer getMaxTokens() {
            return maxTokens;
        }

        @JsonProperty("response_format")
        public Object getResponseFormat() {
            return responseFormat;
        }

        public Object getThinking() {
            return thinking;
        }
    }
}
