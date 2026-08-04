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
            rawPoint.put("payload", sanitizeJsonValue(point.getPayload()));
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

    public void deleteByShopId(String collectionName, Long shopId) throws Exception {
        if (shopId == null) {
            return;
        }
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("value", shopId);
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("key", "shopId");
        condition.put("match", match);
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("must", Collections.singletonList(condition));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filter", filter);
        request("POST", "/collections/" + collectionName + "/points/delete?wait=true", body);
    }

    public List<QdrantSearchResult> search(String collectionName, List<Float> vector, int limit) throws Exception {
        return search(collectionName, vector, limit, null);
    }

    public List<QdrantSearchResult> search(String collectionName, List<Float> vector, int limit,
                                            QdrantFilter filter) throws Exception {
        if (vector == null || vector.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", vector);
        body.put("limit", limit);
        body.put("with_payload", true);
        if (filter != null && !filter.isEmpty()) {
            body.put("filter", filter.toRequestBody());
        }
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
            byte[] requestBody = objectMapper.writeValueAsBytes(sanitizeJsonValue(body));
            // Fail locally with a clear error before sending a malformed request to Qdrant.
            objectMapper.readTree(requestBody);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestBody);
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

    @SuppressWarnings("unchecked")
    private Object sanitizeJsonValue(Object value) {
        if (value instanceof String) {
            return sanitizeUnicode((String) value);
        }
        if (value instanceof Map) {
            Map<Object, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                sanitized.put(entry.getKey(), sanitizeJsonValue(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof Iterable) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                sanitized.add(sanitizeJsonValue(item));
            }
            return sanitized;
        }
        return value;
    }

    /**
     * Java strings can contain an unpaired UTF-16 surrogate. MySQL text can preserve one,
     * while Qdrant's JSON parser rejects it. Replace only invalid code units at the boundary.
     */
    private String sanitizeUnicode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
                    result.append(current).append(value.charAt(++index));
                } else {
                    result.append('\uFFFD');
                }
                continue;
            }
            if (Character.isLowSurrogate(current)) {
                result.append('\uFFFD');
                continue;
            }
            result.append(current);
        }
        return result.toString();
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

    /** Payload constraints applied by Qdrant before vector ranking. */
    public static class QdrantFilter {
        private final List<Long> shopIds = new ArrayList<>();
        private final List<Long> excludedShopIds = new ArrayList<>();
        private final List<Long> typeIds = new ArrayList<>();
        private final List<String> areas = new ArrayList<>();
        private Long maxAveragePrice;

        public QdrantFilter requireShopIds(Iterable<Long> values) {
            addLongs(shopIds, values);
            return this;
        }

        public QdrantFilter excludeShopIds(Iterable<Long> values) {
            addLongs(excludedShopIds, values);
            return this;
        }

        public QdrantFilter requireTypeIds(Iterable<Long> values) {
            addLongs(typeIds, values);
            return this;
        }

        public QdrantFilter requireAreas(Iterable<String> values) {
            if (values != null) {
                for (String value : values) {
                    if (StrUtil.isNotBlank(value) && !areas.contains(value)) {
                        areas.add(value);
                    }
                }
            }
            return this;
        }

        public QdrantFilter maxAveragePrice(Long value) {
            this.maxAveragePrice = value;
            return this;
        }

        public boolean isEmpty() {
            return shopIds.isEmpty() && excludedShopIds.isEmpty() && typeIds.isEmpty()
                    && areas.isEmpty() && maxAveragePrice == null;
        }

        private Map<String, Object> toRequestBody() {
            Map<String, Object> filter = new LinkedHashMap<>();
            List<Map<String, Object>> must = new ArrayList<>();
            List<Map<String, Object>> mustNot = new ArrayList<>();
            addMatchCondition(must, "shopId", shopIds);
            addMatchCondition(must, "typeId", typeIds);
            addMatchCondition(must, "area", areas);
            if (maxAveragePrice != null) {
                Map<String, Object> range = new LinkedHashMap<>();
                range.put("lte", maxAveragePrice);
                Map<String, Object> condition = new LinkedHashMap<>();
                condition.put("key", "avgPrice");
                condition.put("range", range);
                must.add(condition);
            }
            addMatchCondition(mustNot, "shopId", excludedShopIds);
            if (!must.isEmpty()) {
                filter.put("must", must);
            }
            if (!mustNot.isEmpty()) {
                filter.put("must_not", mustNot);
            }
            return filter;
        }

        private void addLongs(List<Long> target, Iterable<Long> values) {
            if (values == null) {
                return;
            }
            for (Long value : values) {
                if (value != null && !target.contains(value)) {
                    target.add(value);
                }
            }
        }

        private void addMatchCondition(List<Map<String, Object>> conditions, String key,
                                       List<?> values) {
            if (values.isEmpty()) {
                return;
            }
            Map<String, Object> match = new LinkedHashMap<>();
            if (values.size() == 1) {
                match.put("value", values.get(0));
            } else {
                match.put("any", values);
            }
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put("key", key);
            condition.put("match", match);
            conditions.add(condition);
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
