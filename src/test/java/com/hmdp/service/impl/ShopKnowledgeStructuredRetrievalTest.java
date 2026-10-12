package com.hmdp.service.impl;

import com.hmdp.ai.AiQueryPlan;
import com.hmdp.ai.AiQueryPlanStatus;
import com.hmdp.ai.AiQueryTask;
import com.hmdp.ai.AiResolvedQueryPlan;
import com.hmdp.ai.AiResolvedQueryTask;
import com.hmdp.ai.EmbeddingModelClient;
import com.hmdp.ai.QdrantKnowledgeClient;
import com.hmdp.ai.ShopKnowledge;
import com.hmdp.ai.AiTaskRetrievalResult;
import com.hmdp.config.AiEmbeddingProperties;
import com.hmdp.config.AiKnowledgeProperties;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = Strictness.LENIENT)
@Execution(ExecutionMode.SAME_THREAD)
class ShopKnowledgeStructuredRetrievalTest {

    @Mock
    private IShopService shopService;

    @Mock
    private IShopTypeService shopTypeService;

    @Mock
    private IBlogService blogService;

    @Mock
    private EmbeddingModelClient embeddingModelClient;

    @Mock
    private QdrantKnowledgeClient qdrantKnowledgeClient;

    private ShopKnowledgeServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ShopKnowledgeServiceImpl();
        AiKnowledgeProperties knowledgeProperties = new AiKnowledgeProperties();
        knowledgeProperties.setVectorCandidateLimit(10);
        knowledgeProperties.setTaskCandidateLimit(5);
        knowledgeProperties.setVectorMinScore(0.35D);

        ReflectionTestUtils.setField(service, "shopService", shopService);
        ReflectionTestUtils.setField(service, "shopTypeService", shopTypeService);
        ReflectionTestUtils.setField(service, "blogService", blogService);
        ReflectionTestUtils.setField(service, "embeddingModelClient", embeddingModelClient);
        ReflectionTestUtils.setField(service, "qdrantKnowledgeClient", qdrantKnowledgeClient);
        ReflectionTestUtils.setField(service, "embeddingProperties", new AiEmbeddingProperties());
        ReflectionTestUtils.setField(service, "knowledgeProperties", knowledgeProperties);

        List<Shop> shops = Arrays.asList(
                shop(7L, "炉鱼（拱墅万达广场店）", "北部新城", 85L),
                shop(8L, "新白鹿餐厅", "运河上街", 90L)
        );
        when(shopService.list()).thenReturn(shops);
        when(shopTypeService.list()).thenReturn(Collections.singletonList(
                new ShopType().setId(1L).setName("美食")
        ));
        when(embeddingModelClient.embed(anyList())).thenReturn(
                Collections.singletonList(Collections.singletonList(0.1F)));
    }

    @Test
    void structuredRetrievalShouldApplyResolvedHardConstraintsBeforeTop3() throws Exception {
        Map<String, Object> validPayload = payload(7L, 1L, "北部新城", 85L);
        // Simulate stale Qdrant metadata that still satisfies the requested hard filters.
        // MySQL is authoritative and currently places shop 8 in another area.
        Map<String, Object> wrongAreaPayload = payload(8L, 1L, "北部新城", 50L);
        when(qdrantKnowledgeClient.search(anyString(), any(List.class), anyInt(),
                any(QdrantKnowledgeClient.QdrantFilter.class)))
                .thenReturn(Arrays.asList(
                        new QdrantKnowledgeClient.QdrantSearchResult(8L, 0.99D, wrongAreaPayload),
                        new QdrantKnowledgeClient.QdrantSearchResult(7L, 0.80D, validPayload)
                ))
                .thenReturn(Collections.emptyList());

        AiQueryTask source = new AiQueryTask(
                "RECOMMEND", "北部新城适合约会的餐厅",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                "美食", "北部新城", 100,
                Collections.singletonList("情侣约会"), Collections.<String>emptyList());
        AiResolvedQueryTask task = new AiResolvedQueryTask(source,
                Collections.<Long>emptyList(), Collections.<Long>emptyList(),
                Collections.singletonList(1L), Collections.singletonList("北部新城"));
        AiResolvedQueryPlan plan = new AiResolvedQueryPlan(AiQueryPlanStatus.READY,
                Collections.singletonList(task), null,
                new AiQueryPlan(AiQueryPlanStatus.READY, Collections.singletonList(source),
                        null, false, true, 0L, 14, 14, null));

        List<ShopKnowledge> result = service.searchRelevantTaskResults(null, plan)
                .get(0).getCandidates();

        assertEquals(Collections.singletonList(7L), shopIds(result));
        verify(embeddingModelClient, times(1)).embed(
                Collections.singletonList("北部新城适合约会的餐厅"));

        ArgumentCaptor<QdrantKnowledgeClient.QdrantFilter> filterCaptor =
                ArgumentCaptor.forClass(QdrantKnowledgeClient.QdrantFilter.class);
        verify(qdrantKnowledgeClient, times(2)).search(anyString(), any(List.class), anyInt(),
                filterCaptor.capture());
        Method toRequestBody = QdrantKnowledgeClient.QdrantFilter.class
                .getDeclaredMethod("toRequestBody");
        toRequestBody.setAccessible(true);
        Map<?, ?> requestBody = (Map<?, ?>) toRequestBody.invoke(filterCaptor.getAllValues().get(0));
        assertTrue(requestBody.containsKey("must"));
    }

    @Test
    void structuredOnlyTaskShouldUseDeterministicMysqlPathWithoutEmbedding() throws Exception {
        AiQueryTask source = new AiQueryTask(
                "RECOMMEND", "推荐美食",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                "美食", null, null, Collections.<String>emptyList(),
                Collections.<String>emptyList());
        AiResolvedQueryTask task = new AiResolvedQueryTask(source,
                Collections.<Long>emptyList(), Collections.<Long>emptyList(),
                Collections.singletonList(1L), Collections.<String>emptyList());
        AiResolvedQueryPlan plan = new AiResolvedQueryPlan(AiQueryPlanStatus.READY,
                Collections.singletonList(task), null, null);

        List<ShopKnowledge> result = service.searchRelevantTaskResults(null, plan)
                .get(0).getCandidates();

        assertEquals(Arrays.asList(7L, 8L), shopIds(result));
        verify(embeddingModelClient, times(0)).embed(anyList());
        verify(qdrantKnowledgeClient, times(0)).search(anyString(), any(List.class), anyInt(),
                any(QdrantKnowledgeClient.QdrantFilter.class));
    }

    @Test
    void embeddingFailureShouldKeepStructuredCandidatesAndMarkSemanticDegraded() throws Exception {
        when(embeddingModelClient.embed(anyList()))
                .thenThrow(new IllegalStateException("embedding unavailable"));
        AiResolvedQueryPlan plan = semanticPlan();

        List<AiTaskRetrievalResult> result = service.searchRelevantTaskResults(null, plan);

        assertEquals(1, result.size());
        assertTrue(result.get(0).isSemanticDegraded());
        assertEquals(Arrays.asList(7L, 8L), shopIds(result.get(0).getCandidates()));
        verify(qdrantKnowledgeClient, times(0)).search(anyString(), any(List.class), anyInt(),
                any(QdrantKnowledgeClient.QdrantFilter.class));
    }

    @Test
    void qdrantFailureShouldKeepStructuredCandidatesAndMarkSemanticDegraded() throws Exception {
        when(qdrantKnowledgeClient.search(anyString(), any(List.class), anyInt(),
                any(QdrantKnowledgeClient.QdrantFilter.class)))
                .thenThrow(new IllegalStateException("qdrant unavailable"));

        List<AiTaskRetrievalResult> result = service.searchRelevantTaskResults(null, semanticPlan());

        assertEquals(1, result.size());
        assertTrue(result.get(0).isSemanticDegraded());
        assertEquals(Arrays.asList(7L, 8L), shopIds(result.get(0).getCandidates()));
    }

    private AiResolvedQueryPlan semanticPlan() {
        AiQueryTask source = new AiQueryTask(
                "RECOMMEND", "适合约会的美食",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                "美食", null, null, Collections.singletonList("情侣约会"),
                Collections.<String>emptyList());
        AiResolvedQueryTask task = new AiResolvedQueryTask(source,
                Collections.<Long>emptyList(), Collections.<Long>emptyList(),
                Collections.singletonList(1L), Collections.<String>emptyList());
        return new AiResolvedQueryPlan(AiQueryPlanStatus.READY,
                Collections.singletonList(task), null, null);
    }

    private Map<String, Object> payload(Long shopId, Long typeId, String area, Long avgPrice) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("shopId", shopId);
        payload.put("typeId", typeId);
        payload.put("area", area);
        payload.put("avgPrice", avgPrice);
        payload.put("content", "store=" + shopId);
        return payload;
    }

    private Shop shop(Long id, String name, String area, Long avgPrice) {
        return new Shop().setId(id).setName(name).setArea(area).setTypeId(1L).setAvgPrice(avgPrice);
    }

    private List<Long> shopIds(List<ShopKnowledge> knowledge) {
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        for (ShopKnowledge item : knowledge) {
            ids.add(item.getShopId());
        }
        return ids;
    }
}
