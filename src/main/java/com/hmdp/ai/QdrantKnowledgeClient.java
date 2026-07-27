package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiQdrantProperties;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QdrantKnowledgeClient {

    @Resource
    private AiQdrantProperties qdrantProperties;

    @Resource
    private ObjectMapper objectMapper;

    public void ensureCollection(String collectionName, Integer dimension) throws Exception {
        try {
            request("GET", "/collections/" + collectionName, null);
            return;
        } catch (QdrantRequestException e) {
            if (e.getStatus() != HttpURLConnection.HTTP_NOT_FOUND) {
                throw e;
            }
        }

        Map<String, Object> vectorConfig = new LinkedHashMap<>();
        vectorConfig.put("size", dimension);
        vectorConfig.put("distance", "Cosine");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vectors", vectorConfig);
        request("PUT", "/collections/" + collectionName, body);
    }

    public void recreateCollection(String collectionName, Integer dimension) throws Exception {
        try {
            request("DELETE", "/collections/" + collectionName, null);
        } catch (QdrantRequestException e) {
            if (e.getStatus() != HttpURLConnection.HTTP_NOT_FOUND) {
                throw e;
            }
        }
        Map<String, Object> vectorConfig = new LinkedHashMap<>();
        vectorConfig.put("size", dimension);
        vectorConfig.put("distance", "Cosine");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vectors", vectorConfig);
        request("PUT", "/collections/" + collectionName, body);
    }

    public void upsert(String collectionName, List<QdrantPoint> points) throws Exception {
        if (points == null || points.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rawPoints = new ArrayList<>(points.size());
        for (QdrantPoint point : points) {
            Map<String, Object> rawPoint = new LinkedHashMap<>();
            rawPoint.put("id", point.getId());
            rawPoint.put("vector", point.getVector());
            rawPoint.put("payload", point.getPayload());
            rawPoints.add(rawPoint);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", rawPoints);
        request("PUT", "/collections/" + collectionName + "/points?wait=true", body);
    }

    public void deletePoint(String collectionName, Long pointId) throws Exception {
        if (pointId == null) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", Collections.singletonList(pointId));
        request("POST", "/collections/" + collectionName + "/points/delete?wait=true", body);
    }

    public List<QdrantSearchResult> search(String collectionName, List<Float> vector, int limit) throws Exception {
        if (vector == null || vector.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", vector);
        body.put("limit", limit);
        body.put("with_payload", true);
        JsonNode root = request("POST", "/collections/" + collectionName + "/points/query", body);
        List<QdrantSearchResult> results = new ArrayList<>();
        for (JsonNode point : root.path("result").path("points")) {
            Map<String, Object> payload = objectMapper.convertValue(point.path("payload"), Map.class);
            results.add(new QdrantSearchResult(point.path("id").asLong(), point.path("score").asDouble(), payload));
        }
        return results;
    }

    private JsonNode request(String method, String path, Object body) throws Exception {
        validateConfiguration();
        HttpURLConnection connection = (HttpURLConnection) new URL(normalizeBaseUrl() + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(qdrantProperties.getConnectTimeoutMs());
        connection.setReadTimeout(qdrantProperties.getReadTimeoutMs());
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("api-key", qdrantProperties.getApiKey());
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(objectMapper.writeValueAsBytes(body));
            }
        }

        try {
            int status = connection.getResponseCode();
            if (status < HttpURLConnection.HTTP_OK || status >= HttpURLConnection.HTTP_MULT_CHOICE) {
                throw new QdrantRequestException(status, readBody(connection.getErrorStream()));
            }
            return objectMapper.readTree(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private String normalizeBaseUrl() {
        String url = qdrantProperties.getUrl().trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void validateConfiguration() {
        if (StrUtil.hasBlank(qdrantProperties.getUrl(), qdrantProperties.getApiKey())) {
            throw new IllegalStateException("Qdrant configuration is incomplete");
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

    public static class QdrantPoint {
        private final Long id;
        private final List<Float> vector;
        private final Map<String, Object> payload;

        public QdrantPoint(Long id, List<Float> vector, Map<String, Object> payload) {
            this.id = id;
            this.vector = vector;
            this.payload = payload;
        }

        public Long getId() {
            return id;
        }

        public List<Float> getVector() {
            return vector;
        }

        public Map<String, Object> getPayload() {
            return payload;
        }
    }

    public static class QdrantSearchResult {
        private final Long id;
        private final Double score;
        private final Map<String, Object> payload;

        public QdrantSearchResult(Long id, Double score, Map<String, Object> payload) {
            this.id = id;
            this.score = score;
            this.payload = payload;
        }

        public Long getId() {
            return id;
        }

        public Double getScore() {
            return score;
        }

        public Map<String, Object> getPayload() {
            return payload;
        }
    }

    private static class QdrantRequestException extends RuntimeException {
        private final int status;

        private QdrantRequestException(int status, String body) {
            super("Qdrant returned HTTP " + status + ": " + body);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
