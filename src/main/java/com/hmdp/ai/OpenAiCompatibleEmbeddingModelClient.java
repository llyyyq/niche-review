package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiEmbeddingProperties;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Component
@ConditionalOnProperty(name = "ai.embedding.provider", havingValue = "openai-compatible")
public class OpenAiCompatibleEmbeddingModelClient implements EmbeddingModelClient {

    @Resource
    private AiEmbeddingProperties embeddingProperties;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public List<List<Float>> embed(List<String> texts) throws Exception {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        validateConfiguration();

        HttpURLConnection connection = (HttpURLConnection) new URL(embeddingProperties.getBaseUrl()).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(embeddingProperties.getConnectTimeoutMs());
        connection.setReadTimeout(embeddingProperties.getReadTimeoutMs());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Authorization", "Bearer " + embeddingProperties.getApiKey());

        try {
            writeRequest(connection, texts);
            int status = connection.getResponseCode();
            if (status < HttpURLConnection.HTTP_OK || status >= HttpURLConnection.HTTP_MULT_CHOICE) {
                throw new IllegalStateException("Embedding provider returned HTTP " + status + ": "
                        + readBody(connection.getErrorStream()));
            }
            return parseEmbeddings(connection.getInputStream(), texts.size());
        } finally {
            connection.disconnect();
        }
    }

    private void writeRequest(HttpURLConnection connection, List<String> texts) throws Exception {
        JsonNode payload = objectMapper.valueToTree(new EmbeddingRequest(
                embeddingProperties.getModel(),
                texts,
                embeddingProperties.getDimension()
        ));
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(objectMapper.writeValueAsBytes(payload));
        }
    }

    private List<List<Float>> parseEmbeddings(InputStream inputStream, int expectedSize) throws Exception {
        JsonNode root = objectMapper.readTree(inputStream);
        List<EmbeddingResult> results = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            List<Float> vector = new ArrayList<>();
            for (JsonNode number : item.path("embedding")) {
                vector.add(number.floatValue());
            }
            results.add(new EmbeddingResult(item.path("index").asInt(), vector));
        }
        results.sort(Comparator.comparingInt(EmbeddingResult::getIndex));
        if (results.size() != expectedSize) {
            throw new IllegalStateException("Embedding provider returned unexpected vector count");
        }
        List<List<Float>> vectors = new ArrayList<>(results.size());
        for (EmbeddingResult result : results) {
            if (result.getVector().size() != embeddingProperties.getDimension()) {
                throw new IllegalStateException("Embedding dimension mismatch, expected="
                        + embeddingProperties.getDimension() + ", actual=" + result.getVector().size());
            }
            vectors.add(result.getVector());
        }
        return vectors;
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
        if (StrUtil.hasBlank(
                embeddingProperties.getBaseUrl(),
                embeddingProperties.getApiKey(),
                embeddingProperties.getModel()
        )) {
            throw new IllegalStateException("OpenAI compatible embedding configuration is incomplete");
        }
    }

    private static class EmbeddingRequest {
        private final String model;
        private final List<String> input;
        private final Integer dimensions;
        private final String encodingFormat = "float";

        private EmbeddingRequest(String model, List<String> input, Integer dimensions) {
            this.model = model;
            this.input = input;
            this.dimensions = dimensions;
        }

        public String getModel() {
            return model;
        }

        public List<String> getInput() {
            return input;
        }

        public Integer getDimensions() {
            return dimensions;
        }

        public String getEncodingFormat() {
            return encodingFormat;
        }
    }

    private static class EmbeddingResult {
        private final int index;
        private final List<Float> vector;

        private EmbeddingResult(int index, List<Float> vector) {
            this.index = index;
            this.vector = vector;
        }

        public int getIndex() {
            return index;
        }

        public List<Float> getVector() {
            return vector;
        }
    }
}
