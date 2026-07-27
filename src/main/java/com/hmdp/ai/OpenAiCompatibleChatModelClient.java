package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
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
            writeRequest(connection, messages);
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

    private void writeRequest(HttpURLConnection connection, List<AiPromptMessage> messages) throws Exception {
        JsonNode payload = objectMapper.valueToTree(new OpenAiRequest(aiChatProperties.getModel(), messages));
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

    private static class OpenAiRequest {
        private final String model;
        private final List<AiPromptMessage> messages;
        private final boolean stream = true;

        private OpenAiRequest(String model, List<AiPromptMessage> messages) {
            this.model = model;
            this.messages = messages;
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
    }
}
