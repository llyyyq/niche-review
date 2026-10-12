package com.hmdp.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiChatProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenAiCompatibleChatModelClientTest {

    @Test
    void truncationDiagnosticsShouldIncludeBudgetAndUsageWithoutContent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree("{\"usage\":{\"completion_tokens\":512,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":500}},"
                + "\"choices\":[{\"message\":{\"content\":\"private-answer\","
                + "\"reasoning_content\":\"private-reasoning\"}}]}");
        String details = ReflectionTestUtils.invokeMethod(new OpenAiCompatibleChatModelClient(),
                "truncationDetails", root, root.path("choices").get(0),
                AiCompletionOptions.jsonObject(0D, 512, 8000));
        assertTrue(details.contains("requestedMaxTokens=512"));
        assertTrue(details.contains("completionTokens=512"));
        assertTrue(details.contains("reasoningTokens=500"));
        assertTrue(details.contains("contentChars=14"));
        assertFalse(details.contains("private-answer"));
        assertFalse(details.contains("private-reasoning"));
    }

    @Test
    void structuredCompletionShouldSendJsonObjectResponseFormat() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(readBody(exchange));
            byte[] response = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiChatProperties properties = new AiChatProperties();
            properties.setProvider("openai-compatible");
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1/chat/completions");
            properties.setApiKey("test-key");
            properties.setModel("test-model");

            OpenAiCompatibleChatModelClient client = new OpenAiCompatibleChatModelClient();
            ReflectionTestUtils.setField(client, "aiChatProperties", properties);
            ReflectionTestUtils.setField(client, "objectMapper", mapper);

            String result = client.complete(Collections.singletonList(
                            new AiPromptMessage("user", "return a JSON object")),
                    AiCompletionOptions.jsonObject(0D, 3000));

            assertEquals("{}", result);
            JsonNode payload = mapper.readTree(requestBody.get());
            assertNotNull(payload.get("response_format"));
            assertEquals("json_object", payload.path("response_format").path("type").asText());
            assertEquals("disabled", payload.path("thinking").path("type").asText());
            assertTrue(payload.get("max_tokens") == null);
        } finally {
            server.stop(0);
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
