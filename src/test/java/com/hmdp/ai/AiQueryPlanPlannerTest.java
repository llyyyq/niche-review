package com.hmdp.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiChatProperties;
import com.hmdp.config.AiQueryRewriteProperties;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IAiRequestLogService;
import com.hmdp.service.IShopTypeService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiQueryPlanPlannerTest {

    @Mock
    private AiChatModelClient chatModelClient;

    @Mock
    private IAiRequestLogService requestLogService;
    @Mock
    private IShopTypeService shopTypeService;

    private AiQueryPlanPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new AiQueryPlanPlanner();
        AiQueryRewriteProperties properties = new AiQueryRewriteProperties();
        properties.setEnabled(true);
        properties.setMaxQueryChars(160);
        properties.setMaxTasks(3);
        properties.setMaxAttempts(1);
        properties.setReadTimeoutMs(8000);

        ReflectionTestUtils.setField(planner, "aiChatModelClient", chatModelClient);
        ReflectionTestUtils.setField(planner, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(planner, "properties", properties);
        ReflectionTestUtils.setField(planner, "chatProperties", new AiChatProperties());
        ReflectionTestUtils.setField(planner, "tokenEstimator", new AiTokenEstimator());
        ReflectionTestUtils.setField(planner, "aiRequestLogService", requestLogService);
        ReflectionTestUtils.setField(planner, "shopTypeService", shopTypeService);
    }

    @Test
    void everyNonEmptyQuestionShouldStartWithStructuredModelPlan() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":[{"
                        + "\"intent\":\"FACT_LOOKUP\","
                        + "\"query\":\"炉鱼有什么优惠？\","
                        + "\"shopNames\":[\"炉鱼\"],\"excludedShopNames\":[],"
                        + "\"category\":null,\"area\":null,\"maxAveragePrice\":null,"
                        + "\"semanticPreference\":[],\"requiredFacts\":[\"ACTIVE_VOUCHER\"]}]}" );

        AiQueryPlan plan = planner.plan(null, null, null,
                "炉鱼有什么优惠？", null, Collections.<AiPromptMessage>emptyList());

        assertTrue(plan.isReady(), plan.getFailureReason());
        assertTrue(plan.isModelCalled());
        assertTrue(plan.isValidModelOutput());
        assertEquals("炉鱼", plan.getTasks().get(0).getShopNames().get(0));
        assertTrue(plan.getTasks().get(0).requiresFact("ACTIVE_VOUCHER"));
        verify(chatModelClient, times(1)).complete(anyList(), argThat(options -> options != null
                && options.getResponseFormat() != null
                && "json_object".equals(options.getResponseFormat().get("type"))
                && options.getThinking() != null
                && "disabled".equals(options.getThinking().get("type"))
                && options.getMaxTokens() == null));
    }

    @Test
    void plannerAcceptsFactOnlyTaskAndLeavesExecutionSafetyToResolver() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":[{"
                        + "\"intent\":\"FACT_LOOKUP\",\"query\":\"有什么优惠券？\","
                        + "\"shopNames\":[],\"excludedShopNames\":[],"
                        + "\"category\":null,\"area\":null,\"maxAveragePrice\":null,"
                        + "\"semanticPreference\":[],\"requiredFacts\":[\"ACTIVE_VOUCHER\"]}]}" );

        AiQueryPlan plan = planner.plan(null, null, null,
                "有什么优惠券？", null, Collections.<AiPromptMessage>emptyList());

        assertEquals(AiQueryPlanStatus.READY, plan.getStatus());
        assertTrue(plan.isModelCalled());
        assertTrue(plan.isValidModelOutput());
        assertEquals(1, plan.getTasks().size());
        assertTrue(plan.getTasks().get(0).requiresFact("ACTIVE_VOUCHER"));
        verify(chatModelClient, times(1)).complete(anyList(), any(AiCompletionOptions.class));
    }

    @Test
    void plannerKeepsModelStructuredFieldsWithoutReinterpretingTheQuestion() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":[{"
                        + "\"intent\":\"RECOMMEND\","
                        + "\"query\":\"适合约会的餐厅\","
                        + "\"shopNames\":[],\"excludedShopNames\":[],"
                        + "\"category\":\"餐厅\",\"area\":\"运河上街\","
                        + "\"maxAveragePrice\":150,"
                        + "\"semanticPreference\":[\"情侣约会\"],"
                        + "\"requiredFacts\":[]}]}");

        String longQuestion = repeat("我希望你结合位置、预算和环境给出一个稳妥建议，", 5)
                + "推荐运河上街150元以内适合情侣约会的餐厅。";
        AiQueryPlan plan = planner.plan(null, null, 99L, longQuestion,
                null, Collections.<AiPromptMessage>emptyList());

        assertTrue(plan.isReady());
        assertTrue(plan.isModelCalled());
        assertTrue(plan.isValidModelOutput());
        AiQueryTask task = plan.getTasks().get(0);
        assertEquals("运河上街", task.getArea());
        assertEquals("餐厅", task.getCategory());
        assertEquals(Integer.valueOf(150), task.getMaxAveragePrice());
        assertTrue(task.getSemanticPreferences().contains("情侣约会"));
    }

    @Test
    void invalidModelOutputShouldStopWithoutEnteringRetrieval() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn("not-json");
        String longQuestion = repeat("这是一段与店铺查询无关的背景描述，", 30)
                + "请推荐北部新城人均100元以内的餐厅。";

        AiQueryPlan plan = planner.plan(null, null, null, longQuestion,
                null, Collections.<AiPromptMessage>emptyList());

        assertEquals(AiQueryPlanStatus.FAILED, plan.getStatus());
        assertTrue(plan.isModelCalled());
        assertFalse(plan.isValidModelOutput());
        assertTrue(plan.getTasks().isEmpty());
        assertTrue(plan.getClarification().contains("繁忙"));
        verify(chatModelClient, times(1)).complete(anyList(), any(AiCompletionOptions.class));
    }

    @Test
    void modelFailureShouldRetryAndThenReturnServiceBusy() throws Exception {
        AiQueryRewriteProperties properties = new AiQueryRewriteProperties();
        properties.setEnabled(true);
        properties.setMaxAttempts(2);
        properties.setMaxQueryChars(160);
        properties.setMaxTasks(3);
        properties.setReadTimeoutMs(8000);
        ReflectionTestUtils.setField(planner, "properties", properties);
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class)))
                .thenThrow(new IllegalStateException("provider unavailable"))
                .thenThrow(new IllegalStateException("provider unavailable"));

        AiQueryPlan plan = planner.plan(null, null, null,
                "请推荐北部新城适合聚餐的餐厅", null,
                Collections.<AiPromptMessage>emptyList());

        assertEquals(AiQueryPlanStatus.FAILED, plan.getStatus());
        assertTrue(plan.getClarification().contains("繁忙"));
        verify(chatModelClient, times(2)).complete(anyList(), any(AiCompletionOptions.class));
    }

    @Test
    void tooManyValidTasksShouldClarifyWithoutRetry() throws Exception {
        AiQueryRewriteProperties properties = new AiQueryRewriteProperties();
        properties.setEnabled(true);
        properties.setMaxAttempts(2);
        properties.setMaxQueryChars(160);
        properties.setMaxTasks(3);
        properties.setReadTimeoutMs(8000);
        ReflectionTestUtils.setField(planner, "properties", properties);

        String task = "{\"intent\":\"RECOMMEND\",\"query\":\"\u8fd0\u6cb3\u4e0a\u8857\u63a8\u8350\","
                + "\"shopNames\":[],\"excludedShopNames\":[],\"category\":\"\u7f8e\u98df\","
                + "\"area\":\"\u8fd0\u6cb3\u4e0a\u8857\",\"maxAveragePrice\":null,"
                + "\"semanticPreference\":[],\"requiredFacts\":[]}";
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":[" + String.join(",", Collections.nCopies(4, task)) + "]}");

        AiQueryPlan plan = planner.plan(null, null, null,
                "\u5148\u5531\u6b4c\u3001\u5403\u996d\u3001\u6d17\u811a\u3001\u5403\u591c\u5bb5\uff0c\u5206\u522b\u63a8\u8350\u3002", null,
                Collections.<AiPromptMessage>emptyList());

        assertEquals(AiQueryPlanStatus.CLARIFY, plan.getStatus());
        assertTrue(plan.isModelCalled());
        assertTrue(plan.isValidModelOutput());
        assertTrue(plan.getTasks().isEmpty());
        assertEquals("too_many_tasks", plan.getFailureReason());
        assertTrue(plan.getClarification().contains("\u6700\u591a\u5904\u74063\u9879"));
        verify(chatModelClient, times(1)).complete(anyList(), any(AiCompletionOptions.class));
    }

    @Test
    void ambiguousReferenceShouldStopBeforeEmbeddingPath() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"CLARIFY\",\"tasks\":[],"
                        + "\"clarification\":\"请说明具体店名。\"}");
        AiQueryPlan plan = planner.plan(null, null, null,
                "它有什么优惠？", null,
                Collections.singletonList(new AiPromptMessage(
                        "assistant", "可以考虑炉鱼和新白鹿餐厅两家店。")));

        assertEquals(AiQueryPlanStatus.CLARIFY, plan.getStatus());
        assertTrue(plan.getTasks().isEmpty());
        assertNotNull(plan.getClarification());
        verify(chatModelClient, times(1)).complete(anyList(), any(AiCompletionOptions.class));
    }

    @Test
    void unrelatedQuestionShouldBecomeUnsupportedAfterPlanValidation() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"UNSUPPORTED\",\"tasks\":[],\"clarification\":\"\"}" );

        AiQueryPlan plan = planner.plan(null, null, null,
                "写一首关于春天的诗", null, Collections.<AiPromptMessage>emptyList());

        assertEquals(AiQueryPlanStatus.UNSUPPORTED, plan.getStatus());
        assertTrue(plan.isModelCalled());
        assertTrue(plan.isValidModelOutput());
        assertTrue(plan.getTasks().isEmpty());
        verify(chatModelClient, times(1)).complete(anyList(), any(AiCompletionOptions.class));
    }

    @Test
    void modelClarificationIsAValidModelDecision() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"CLARIFY\",\"tasks\":[],"
                        + "\"clarification\":\"请说明具体店名。\"}");

        String longQuestion = repeat("请根据上一轮内容继续判断，", 8) + "它适合约会吗？";
        AiQueryPlan plan = planner.plan(null, null, null, longQuestion,
                null, Collections.<AiPromptMessage>emptyList());

        assertEquals(AiQueryPlanStatus.CLARIFY, plan.getStatus());
        assertTrue(plan.isModelCalled());
        assertTrue(plan.isValidModelOutput());
        assertEquals("请说明具体店名。", plan.getClarification());
    }

    @Test
    void unsupportedClassificationShouldBeAcceptedAsAValidModelDecision() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"UNSUPPORTED\",\"tasks\":[],\"clarification\":\"\"}");

        AiQueryPlan plan = planner.plan(null, null, null,
                "炉鱼有什么优惠？", null, Collections.<AiPromptMessage>emptyList());

        assertEquals(AiQueryPlanStatus.UNSUPPORTED, plan.getStatus());
        assertTrue(plan.isValidModelOutput());
        verify(chatModelClient, times(1)).complete(anyList(), any(AiCompletionOptions.class));
    }

    @Test
    void plannerShouldRejectNonJsonTextAroundPlan() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "Here is the plan: {\"action\":\"READY\",\"tasks\":[{"
                        + "\"intent\":\"RECOMMEND\",\"query\":\"推荐餐厅\"}]}" );

        AiQueryPlan plan = planner.plan(null, null, null,
                "推荐餐厅", null, Collections.<AiPromptMessage>emptyList());

        assertEquals(AiQueryPlanStatus.FAILED, plan.getStatus());
        assertFalse(plan.isValidModelOutput());
    }

    @Test
    void plannerShouldNotReinterpretMissingRequiredFacts() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":[{"
                        + "\"intent\":\"FACT_LOOKUP\",\"query\":\"炉鱼\","
                        + "\"shopNames\":[\"炉鱼\"],\"excludedShopNames\":[],"
                        + "\"category\":null,\"area\":null,\"maxAveragePrice\":null,"
                        + "\"semanticPreference\":[],\"requiredFacts\":[]}]}");

        AiQueryPlan plan = planner.plan(null, null, null,
                "炉鱼有什么优惠和营业时间？", null,
                Collections.<AiPromptMessage>emptyList());

        assertEquals(AiQueryPlanStatus.READY, plan.getStatus());
        assertTrue(plan.isValidModelOutput());
    }

    @Test
    void textualNullAreaShouldBeTreatedAsAnAbsentOptionalFilter() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":[{"
                        + "\"intent\":\"RECOMMEND\",\"query\":\"推荐KTV\","
                        + "\"shopNames\":[],\"excludedShopNames\":[],\"category\":\"KTV\","
                        + "\"area\":\"null\",\"maxAveragePrice\":null,"
                        + "\"semanticPreference\":[],\"requiredFacts\":[]}]}" );

        AiQueryPlan plan = planner.plan(null, null, null,
                "查询一下KTV有什么推荐的吗", null, Collections.<AiPromptMessage>emptyList());

        assertTrue(plan.isReady(), plan.getFailureReason());
        assertTrue("KTV".equalsIgnoreCase(plan.getTasks().get(0).getCategory()));
        assertTrue(plan.getTasks().get(0).getArea() == null);
    }

    @Test
    void plannerShouldNotReinterpretModelCategory() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":[{"
                        + "\"intent\":\"RECOMMEND\",\"query\":\"推荐一家店\","
                        + "\"shopNames\":[],\"excludedShopNames\":[],\"category\":\"美食\","
                        + "\"area\":null,\"maxAveragePrice\":null,\"semanticPreference\":[],"
                        + "\"requiredFacts\":[]}]}");

        AiQueryPlan plan = planner.plan(null, null, null,
                "推荐一家店", null, Collections.<AiPromptMessage>emptyList());

        assertEquals(AiQueryPlanStatus.READY, plan.getStatus());
        assertEquals("美食", plan.getTasks().get(0).getCategory());
    }

    @Test
    void plannerShouldExposeCanonicalCategoryVocabularyAndRetryInvalidCategory() throws Exception {
        AiQueryRewriteProperties properties = new AiQueryRewriteProperties();
        properties.setEnabled(true);
        properties.setMaxAttempts(2);
        properties.setMaxQueryChars(160);
        properties.setMaxTasks(3);
        properties.setReadTimeoutMs(8000);
        ReflectionTestUtils.setField(planner, "properties", properties);
        when(shopTypeService.list()).thenReturn(Arrays.asList(
                new ShopType().setId(1L).setName("美食"),
                new ShopType().setId(5L).setName("按摩·足疗")));
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class)))
                .thenReturn("{\"action\":\"READY\",\"tasks\":[{" +
                        "\"intent\":\"RECOMMEND\",\"query\":\"洗脚店\"," +
                        "\"shopNames\":[],\"excludedShopNames\":[],\"category\":\"洗脚\"," +
                        "\"area\":null,\"maxAveragePrice\":null,\"semanticPreference\":[]," +
                        "\"requiredFacts\":[]}]}")
                .thenReturn("{\"action\":\"READY\",\"tasks\":[{" +
                        "\"intent\":\"RECOMMEND\",\"query\":\"洗脚店\"," +
                        "\"shopNames\":[],\"excludedShopNames\":[],\"category\":\"按摩·足疗\"," +
                        "\"area\":null,\"maxAveragePrice\":null,\"semanticPreference\":[\"洗脚\"]," +
                        "\"requiredFacts\":[]}]}");

        AiQueryPlan plan = planner.plan(null, null, null,
                "吃完饭我还想去洗脚有推荐的吗", null,
                Collections.<AiPromptMessage>emptyList());

        assertTrue(plan.isReady(), plan.getFailureReason());
        assertEquals("按摩·足疗", plan.getTasks().get(0).getCategory());
        assertTrue(plan.getTasks().get(0).getSemanticPreferences().contains("洗脚"));
        verify(chatModelClient, times(2)).complete(anyList(), any(AiCompletionOptions.class));
    }

    @Test
    void plannerShouldAcceptModelLiveFactWhenTaskHasACondition() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":[{"
                        + "\"intent\":\"RECOMMEND\",\"query\":\"推荐一家适合聚餐的店\","
                        + "\"shopNames\":[],\"excludedShopNames\":[],\"category\":null,"
                        + "\"area\":null,\"maxAveragePrice\":null,\"semanticPreference\":[\"适合聚餐\"],"
                        + "\"requiredFacts\":[\"ACTIVE_VOUCHER\"]}]}" );

        AiQueryPlan plan = planner.plan(null, null, null,
                "推荐一家适合聚餐的店", null, Collections.<AiPromptMessage>emptyList());

        assertEquals(AiQueryPlanStatus.READY, plan.getStatus());
        assertTrue(plan.getTasks().get(0).requiresFact("ACTIVE_VOUCHER"));
    }

    @Test
    void plannerShouldKeepIncludedAndExcludedStoresSeparate() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":[{"
                        + "\"intent\":\"RECOMMEND\",\"query\":\"推荐炉鱼，排除新白鹿餐厅\","
                        + "\"shopNames\":[\"炉鱼\"],\"excludedShopNames\":[\"新白鹿餐厅\"],"
                        + "\"category\":null,\"area\":null,\"maxAveragePrice\":null,"
                        + "\"semanticPreference\":[],\"requiredFacts\":[]}]}" );

        AiQueryPlan plan = planner.plan(null, null, null,
                "推荐炉鱼，排除新白鹿餐厅", null,
                Collections.<AiPromptMessage>emptyList());

        assertTrue(plan.isReady(), plan.getFailureReason());
        assertEquals(Collections.singletonList("炉鱼"), plan.getTasks().get(0).getShopNames());
        assertEquals(Collections.singletonList("新白鹿餐厅"),
                plan.getTasks().get(0).getExcludedShopNames());
    }

    @Test
    void independentTasksShouldKeepTheirOwnBudgetsAndCategories() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":["
                        + "{\"intent\":\"RECOMMEND\",\"query\":\"100元以内的餐厅\","
                        + "\"shopNames\":[],\"excludedShopNames\":[],\"category\":\"餐厅\","
                        + "\"area\":null,\"maxAveragePrice\":100,\"semanticPreference\":[],"
                        + "\"requiredFacts\":[]},"
                        + "{\"intent\":\"RECOMMEND\",\"query\":\"200元以内的KTV\","
                        + "\"shopNames\":[],\"excludedShopNames\":[],\"category\":\"KTV\","
                        + "\"area\":null,\"maxAveragePrice\":200,\"semanticPreference\":[],"
                        + "\"requiredFacts\":[]}]}");

        AiQueryPlan plan = planner.plan(null, null, null,
                "推荐100元以内的餐厅，另外推荐200元以内的KTV", null,
                Collections.<AiPromptMessage>emptyList());

        assertTrue(plan.isReady());
        assertEquals(2, plan.getTasks().size());
        assertEquals(Integer.valueOf(100), plan.getTasks().get(0).getMaxAveragePrice());
        assertEquals(Integer.valueOf(200), plan.getTasks().get(1).getMaxAveragePrice());
        assertEquals("餐厅", plan.getTasks().get(0).getCategory());
        assertTrue("KTV".equalsIgnoreCase(plan.getTasks().get(1).getCategory()));
    }

    @Test
    void swappedTaskBudgetsAreNotComparedWithASecondLanguageParser() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":["
                        + "{\"intent\":\"RECOMMEND\",\"query\":\"200元以内的餐厅\","
                        + "\"shopNames\":[],\"excludedShopNames\":[],\"category\":\"餐厅\","
                        + "\"area\":null,\"maxAveragePrice\":200,\"semanticPreference\":[],"
                        + "\"requiredFacts\":[]},"
                        + "{\"intent\":\"RECOMMEND\",\"query\":\"100元以内的KTV\","
                        + "\"shopNames\":[],\"excludedShopNames\":[],\"category\":\"KTV\","
                        + "\"area\":null,\"maxAveragePrice\":100,\"semanticPreference\":[],"
                        + "\"requiredFacts\":[]}]}");

        AiQueryPlan plan = planner.plan(null, null, null,
                "推荐100元以内的餐厅，另外推荐200元以内的KTV", null,
                Collections.<AiPromptMessage>emptyList());

        assertEquals(AiQueryPlanStatus.READY, plan.getStatus());
    }

    @Test
    void contextualBudgetShouldBeAcceptedFromRecentConversation() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":[{"
                        + "\"intent\":\"RECOMMEND\",\"query\":\"餐厅\","
                        + "\"shopNames\":[],\"excludedShopNames\":[],\"category\":\"餐厅\","
                        + "\"area\":null,\"maxAveragePrice\":100,\"semanticPreference\":[],"
                        + "\"requiredFacts\":[]}]}");

        AiQueryPlan plan = planner.plan(null, null, null,
                "推荐餐厅，预算沿用上一轮", null,
                Collections.singletonList(new AiPromptMessage("user", "上一轮预算是100元以内")));

        assertTrue(plan.isReady());
        assertEquals(Integer.valueOf(100), plan.getTasks().get(0).getMaxAveragePrice());
    }

    @Test
    void currentBudgetShouldOverrideInheritedBudget() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":[{"
                        + "\"intent\":\"RECOMMEND\",\"query\":\"200元以内的餐厅\","
                        + "\"shopNames\":[],\"excludedShopNames\":[],\"category\":\"餐厅\","
                        + "\"area\":null,\"maxAveragePrice\":200,\"semanticPreference\":[],"
                        + "\"requiredFacts\":[]}]}");

        AiQueryPlan plan = planner.plan(null, null, null,
                "预算改成200元，继续推荐餐厅", null,
                Collections.singletonList(new AiPromptMessage("user", "预算100元以内，推荐餐厅")));

        assertTrue(plan.isReady(), plan.getFailureReason());
        assertEquals(Integer.valueOf(200), plan.getTasks().get(0).getMaxAveragePrice());
    }

    @Test
    void plannerDoesNotInferBudgetFromAssistantMessage() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":[{"
                        + "\"intent\":\"RECOMMEND\",\"query\":\"100元以内的餐厅\","
                        + "\"shopNames\":[],\"excludedShopNames\":[],\"category\":\"餐厅\","
                        + "\"area\":null,\"maxAveragePrice\":100,\"semanticPreference\":[],"
                        + "\"requiredFacts\":[]}]}");

        AiQueryPlan plan = planner.plan(null, null, null,
                "预算沿用上一轮，继续推荐餐厅", null,
                Collections.singletonList(new AiPromptMessage("assistant", "预算按100元以内处理")));

        assertEquals(AiQueryPlanStatus.READY, plan.getStatus());
        assertEquals(Integer.valueOf(100), plan.getTasks().get(0).getMaxAveragePrice());
    }

    @Test
    void invalidArrayFieldTypeShouldBeRejectedInsteadOfSilentlyIgnored() throws Exception {
        when(chatModelClient.complete(anyList(), any(AiCompletionOptions.class))).thenReturn(
                "{\"action\":\"READY\",\"tasks\":[{"
                        + "\"intent\":\"RECOMMEND\",\"query\":\"推荐餐厅\","
                        + "\"shopNames\":7,\"excludedShopNames\":[],\"category\":null,"
                        + "\"area\":null,\"maxAveragePrice\":null,\"semanticPreference\":[],"
                        + "\"requiredFacts\":[]}]}");

        AiQueryPlan plan = planner.plan(null, null, null,
                "推荐餐厅", null, Collections.<AiPromptMessage>emptyList());

        assertEquals(AiQueryPlanStatus.FAILED, plan.getStatus());
        assertFalse(plan.isValidModelOutput());
        verify(chatModelClient, times(1)).complete(anyList(), any(AiCompletionOptions.class));
    }

    private String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
