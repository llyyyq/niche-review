package com.hmdp.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiChatProperties;
import com.hmdp.config.AiQueryRewriteProperties;
import com.hmdp.service.IAiRequestLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiQueryPreprocessorTest {

    @Mock
    private AiChatModelClient chatModelClient;

    @Mock
    private IAiRequestLogService requestLogService;

    private AiQueryPreprocessor preprocessor;

    @BeforeEach
    void setUp() {
        preprocessor = new AiQueryPreprocessor();
        AiQueryRewriteProperties properties = new AiQueryRewriteProperties();
        properties.setLongQueryThresholdChars(160);
        properties.setMaxQueryChars(160);
        properties.setMaxSubQueries(3);
        properties.setContextMessageLimit(4);
        properties.setMaxContextChars(2400);
        properties.setMaxOutputTokens(256);
        properties.setReadTimeoutMs(8000);

        ReflectionTestUtils.setField(preprocessor, "aiChatModelClient", chatModelClient);
        ReflectionTestUtils.setField(preprocessor, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(preprocessor, "properties", properties);
        ReflectionTestUtils.setField(preprocessor, "chatProperties", new AiChatProperties());
        ReflectionTestUtils.setField(preprocessor, "tokenEstimator", new AiTokenEstimator());
        ReflectionTestUtils.setField(preprocessor, "aiRequestLogService", requestLogService);
    }

    @Test
    void shortExplicitQuestionShouldUsePassThroughWithoutModelCall() throws Exception {
        AiRetrievalQueryPlan result = preprocessor.preprocess(
                null, null, null,
                "103\u8336\u9910\u5385\u6709\u4ec0\u4e48\u4f18\u60e0\uff1f",
                null,
                Collections.<AiPromptMessage>emptyList()
        );

        assertEquals(AiQueryRewriteMode.PASS_THROUGH, result.getMode());
        assertEquals(Collections.singletonList("103\u8336\u9910\u5385\u6709\u4ec0\u4e48\u4f18\u60e0\uff1f"),
                result.getQueries());
        assertFalse(result.isModelCalled());
        verify(chatModelClient, never()).complete(any(), any());
    }

    @Test
    void unresolvedReferenceWithoutHistoryShouldAskForClarification() throws Exception {
        AiRetrievalQueryPlan result = preprocessor.preprocess(
                null, null, null,
                "\u5b83\u6709\u4ec0\u4e48\u4f18\u60e0\uff1f",
                null,
                Collections.<AiPromptMessage>emptyList()
        );

        assertEquals(AiQueryRewriteMode.CLARIFY, result.getMode());
        assertTrue(result.requiresClarification());
        assertTrue(result.getQueries().isEmpty());
        verify(chatModelClient, never()).complete(any(), any());
    }

    @Test
    void contextualReferenceShouldBeRewrittenAsStandaloneQuery() throws Exception {
        when(chatModelClient.complete(any(), any())).thenReturn(
                "{\"action\":\"rewrite\",\"queries\":[\"103\u8336\u9910\u5385\u6709\u4ec0\u4e48"
                        + "\u5f53\u524d\u6709\u6548\u4f18\u60e0\u5238\uff1f\"],\"clarification\":\"\"}"
        );

        AiRetrievalQueryPlan result = preprocessor.preprocess(
                null, null, null,
                "\u5b83\u6709\u4ec0\u4e48\u4f18\u60e0\uff1f",
                null,
                Collections.singletonList(new AiPromptMessage(
                        "assistant", "\u521a\u624d\u63a8\u8350\u4e86\u7b2c\u4e00\u5bb6\u5e97\uff1a103\u8336\u9910\u5385"
                ))
        );

        assertEquals(AiQueryRewriteMode.REWRITE, result.getMode());
        assertEquals(1, result.getQueries().size());
        assertTrue(result.getQueries().get(0).contains("103\u8336\u9910\u5385"));
        assertTrue(result.isValidModelOutput());
    }

    @Test
    void invalidModelOutputShouldCompressLongQuestionBeforeEmbedding() throws Exception {
        when(chatModelClient.complete(any(), any())).thenReturn("not-json");
        String longQuestion = repeat("\u6211\u5148\u4ecb\u7ecd\u4e00\u4e9b\u4e0e\u68c0\u7d22\u65e0\u5173\u7684\u80cc\u666f\u3002", 25)
                + "\u8bf7\u63a8\u8350\u8fd0\u6cb3\u4e0a\u8857\u4eba\u5747100\u5143\u4ee5\u5185\u7684\u9910\u5385\u3002";

        AiRetrievalQueryPlan result = preprocessor.preprocess(
                null, null, null, longQuestion, null,
                Collections.<AiPromptMessage>emptyList()
        );

        assertEquals(AiQueryRewriteMode.FALLBACK, result.getMode());
        assertFalse(result.getQueries().isEmpty());
        assertTrue(result.getQueries().get(0).length() <= 160);
        assertFalse(result.getQueries().get(0).equals(longQuestion));
        assertFalse(result.isValidModelOutput());
    }

    @Test
    void emptyModelOutputShouldAlsoUseSafeCompression() throws Exception {
        when(chatModelClient.complete(any(), any())).thenReturn("");
        String longQuestion = repeat("\u8fd9\u662f\u4e0e\u5e97\u94fa\u68c0\u7d22\u65e0\u5173\u7684\u80cc\u666f\u3002", 20)
                + "\u8bf7\u67e5\u8be2\u7089\u9c7c\u7684\u4eba\u5747\u6d88\u8d39\u3002";

        AiRetrievalQueryPlan result = preprocessor.preprocess(
                null, null, null, longQuestion, null,
                Collections.<AiPromptMessage>emptyList()
        );

        assertEquals(AiQueryRewriteMode.FALLBACK, result.getMode());
        assertFalse(result.getQueries().isEmpty());
        assertTrue(result.getQueries().get(0).length() <= 160);
        assertFalse(result.getQueries().get(0).equals(longQuestion));
    }

    @Test
    void decomposedQueriesShouldBeDeduplicatedLimitedAndTruncated() throws Exception {
        String oversized = repeat("\u8fd0\u6cb3\u4e0a\u8857\u9910\u5385", 30);
        when(chatModelClient.complete(any(), any())).thenReturn(
                "{\"action\":\"decompose\",\"queries\":["
                        + "\"\u8fd0\u6cb3\u4e0a\u8857\u9910\u5385\u63a8\u8350\","
                        + "\"\u8fd0\u6cb3\u4e0a\u8857\u9910\u5385\u63a8\u8350\","
                        + "\"" + oversized + "\","
                        + "\"103\u8336\u9910\u5385\u4f18\u60e0\u5238\","
                        + "\"\u7b2c\u56db\u6761\u4e0d\u5e94\u4fdd\u7559\"]}"
        );

        AiRetrievalQueryPlan result = preprocessor.preprocess(
                null, null, null,
                "\u8bf7\u63a8\u8350\u9910\u5385\uff0c\u540c\u65f6\u67e5\u4f18\u60e0\uff0c\u53e6\u5916\u770b\u63a2\u5e97\u7b14\u8bb0\u3002",
                null,
                Arrays.asList(new AiPromptMessage("assistant", "103\u8336\u9910\u5385"))
        );

        assertEquals(AiQueryRewriteMode.DECOMPOSE, result.getMode());
        assertEquals(3, result.getQueries().size());
        assertTrue(result.getQueries().stream().allMatch(query -> query.length() <= 160));
    }

    @Test
    void modelFailureForContextReferenceShouldClarifyInsteadOfEmbeddingRawPronoun() throws Exception {
        when(chatModelClient.complete(any(), any())).thenThrow(new RuntimeException("timeout"));

        AiRetrievalQueryPlan result = preprocessor.preprocess(
                null, null, null,
                "\u7b2c\u4e8c\u5bb6\u51e0\u70b9\u5173\u95e8\uff1f",
                null,
                Collections.singletonList(new AiPromptMessage(
                        "assistant", "\u7b2c\u4e00\u5bb6\u662f103\u8336\u9910\u5385\uff0c\u7b2c\u4e8c\u5bb6\u662f\u65b0\u767d\u9e7f\u9910\u5385"
                ))
        );

        assertEquals(AiQueryRewriteMode.CLARIFY, result.getMode());
        assertTrue(result.getQueries().isEmpty());
    }

    private String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
