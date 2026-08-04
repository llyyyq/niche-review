package com.hmdp.service.impl;

import com.hmdp.ai.EmbeddingModelClient;
import com.hmdp.ai.QdrantKnowledgeClient;
import com.hmdp.ai.ShopKnowledge;
import com.hmdp.config.AiEmbeddingProperties;
import com.hmdp.config.AiKnowledgeProperties;
import com.hmdp.entity.Blog;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShopKnowledgeServiceImplTest {

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
        knowledgeProperties.setRetrieveLimit(3);
        knowledgeProperties.setKeywordFallbackLimit(3);
        knowledgeProperties.setVectorMinScore(0.35D);

        ReflectionTestUtils.setField(service, "shopService", shopService);
        ReflectionTestUtils.setField(service, "shopTypeService", shopTypeService);
        ReflectionTestUtils.setField(service, "blogService", blogService);
        ReflectionTestUtils.setField(service, "embeddingModelClient", embeddingModelClient);
        ReflectionTestUtils.setField(service, "qdrantKnowledgeClient", qdrantKnowledgeClient);
        ReflectionTestUtils.setField(service, "embeddingProperties", new AiEmbeddingProperties());
        ReflectionTestUtils.setField(service, "knowledgeProperties", knowledgeProperties);

        when(shopTypeService.list()).thenReturn(Collections.singletonList(
                new ShopType().setId(1L).setName("\u7f8e\u98df")
        ));
        when(blogService.list()).thenReturn(Collections.emptyList());
        when(embeddingModelClient.embed(anyList())).thenReturn(
                Collections.singletonList(Collections.singletonList(0.1F))
        );
    }

    @Test
    void hybridRetrievalShouldUseBusinessAreaKeywordsEvenWhenVectorScoreIsHigh() throws Exception {
        List<Shop> shops = Arrays.asList(
                shop(1L, "103\u8336\u9910\u5385", "\u8fd0\u6cb3\u4e0a\u8857", 80L),
                shop(3L, "\u65b0\u767d\u9e7f\u9910\u5385", "\u8fd0\u6cb3\u4e0a\u8857", 61L),
                shop(4L, "Mamala", "\u8fdc\u6d0b\u4e50\u5824\u6e2f", 290L),
                shop(7L, "\u7089\u9c7c", "\u5317\u90e8\u65b0\u57ce", 90L)
        );
        arrangeVectorResults(shops, 1L, 3L, 4L);

        List<ShopKnowledge> result = service.searchRelevantShops(
                "\u5317\u90e8\u65b0\u57ce\u6709\u4ec0\u4e48\u9910\u5385\uff1f", true
        );

        assertFalse(result.isEmpty());
        assertEquals(7L, result.get(0).getShopId());
    }

    @Test
    void hybridRetrievalShouldExcludeStoresAboveAveragePriceBudget() throws Exception {
        List<Shop> shops = Arrays.asList(
                shop(4L, "Mamala", "\u8fdc\u6d0b\u4e50\u5824\u6e2f", 290L),
                shop(1L, "103\u8336\u9910\u5385", "\u8fd0\u6cb3\u4e0a\u8857", 80L),
                shop(3L, "\u65b0\u767d\u9e7f\u9910\u5385", "\u8fd0\u6cb3\u4e0a\u8857", 61L)
        );
        arrangeVectorResults(shops, 4L, 1L, 3L);

        List<ShopKnowledge> result = service.searchRelevantShops(
                "\u63a8\u8350\u9002\u5408\u670b\u53cb\u805a\u9910\u3001"
                        + "\u4eba\u5747200\u7684\u9910\u5385\u3002", true
        );

        assertFalse(result.isEmpty());
        assertTrue(result.stream().noneMatch(item -> item.getShopId().equals(4L)));
    }

    @Test
    void hybridRetrievalShouldApplyExplicitExclusionConstraint() throws Exception {
        List<Shop> shops = Arrays.asList(
                shop(1L, "103\u8336\u9910\u5385", "\u8fd0\u6cb3\u4e0a\u8857", 80L),
                shop(3L, "\u65b0\u767d\u9e7f\u9910\u5385", "\u8fd0\u6cb3\u4e0a\u8857", 61L),
                shop(7L, "\u7089\u9c7c", "\u5317\u90e8\u65b0\u57ce", 90L)
        );
        arrangeVectorResults(shops, 1L, 3L, 7L);

        List<ShopKnowledge> result = service.searchRelevantShops(
                "\u63a8\u8350\u8fd0\u6cb3\u4e0a\u8857\u7684\u9910\u5385\uff0c"
                        + "\u6392\u9664103\u8336\u9910\u5385\u3002", true
        );

        assertFalse(result.isEmpty());
        assertTrue(result.stream().noneMatch(item -> item.getShopId().equals(1L)));
    }

    @Test
    void hybridRetrievalShouldBoostAnySharedBusinessPhraseWithoutTreatingItAsAnExactShopName() throws Exception {
        List<Shop> shops = Arrays.asList(
                shop(1L, "103\u8336\u9910\u5385", "\u5927\u5173", 80L),
                shop(2L, "\u8001\u5317\u4eac\u70e4\u8089", "\u62f1\u5bb8\u6865", 85L),
                shop(5L, "\u6d77\u5e95\u635e\u706b\u9505", "\u5927\u5173", 104L)
        );
        arrangeVectorResults(shops, 1L, 2L, 5L);

        List<ShopKnowledge> result = service.searchRelevantShops(
                "\u5927\u5173\u9644\u8fd1\u6709\u4ec0\u4e48\u706b\u9505\uff1f", true
        );

        assertFalse(result.isEmpty());
        assertEquals(5L, result.get(0).getShopId());
        assertTrue(result.stream().anyMatch(item -> item.getShopId().equals(1L)));
    }

    @Test
    void hybridRetrievalShouldRejectExplicitUnknownShop() throws Exception {
        List<Shop> shops = Arrays.asList(
                shop(1L, "103\u8336\u9910\u5385", "\u8fd0\u6cb3\u4e0a\u8857", 80L),
                shop(4L, "Mamala", "\u8fdc\u6d0b\u4e50\u5824\u6e2f", 290L)
        );
        arrangeVectorResults(shops, 4L, 1L);

        List<ShopKnowledge> result = service.searchRelevantShops(
                "\u4e0d\u5b58\u5728\u7684\u706b\u661f\u9910\u5385"
                        + "\u6709\u4ec0\u4e48\u4f18\u60e0\uff1f", true
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void pureVectorEvaluationPathShouldRemainUnchanged() throws Exception {
        List<Shop> shops = Arrays.asList(
                shop(1L, "103\u8336\u9910\u5385", "\u8fd0\u6cb3\u4e0a\u8857", 80L),
                shop(3L, "\u65b0\u767d\u9e7f\u9910\u5385", "\u8fd0\u6cb3\u4e0a\u8857", 61L),
                shop(4L, "Mamala", "\u8fdc\u6d0b\u4e50\u5824\u6e2f", 290L)
        );
        arrangeVectorResults(shops, 4L, 1L, 3L);

        List<ShopKnowledge> result = service.searchRelevantShops(
                "\u63a8\u8350\u4eba\u5747200\u7684\u9910\u5385", false
        );

        assertEquals(Arrays.asList(4L, 1L, 3L), shopIds(result));
    }

    @Test
    void multipleQueriesShouldUseOneEmbeddingBatchAndRoundRobinDeduplicate() throws Exception {
        List<Shop> shops = Arrays.asList(
                shop(1L, "103\u8336\u9910\u5385", "\u8fd0\u6cb3\u4e0a\u8857", 80L),
                shop(2L, "\u8001\u5317\u4eac\u70e4\u8089", "\u62f1\u5bb8\u6865", 85L),
                shop(3L, "\u65b0\u767d\u9e7f\u9910\u5385", "\u8fd0\u6cb3\u4e0a\u8857", 61L),
                shop(4L, "Mamala", "\u8fdc\u6d0b\u4e50\u5824\u6e2f", 290L)
        );
        when(shopService.list()).thenReturn(shops);
        List<String> questions = Arrays.asList(
                "\u8fd0\u6cb3\u4e0a\u8857\u9910\u5385",
                "\u6709\u4f18\u60e0\u5238\u7684\u9910\u5385"
        );
        when(embeddingModelClient.embed(questions)).thenReturn(Arrays.asList(
                Collections.singletonList(0.1F),
                Collections.singletonList(0.2F)
        ));
        when(qdrantKnowledgeClient.search(anyString(), any(List.class), anyInt(),
                any(QdrantKnowledgeClient.QdrantFilter.class)))
                .thenReturn(qdrantResults(shops, 1L, 2L, 3L))
                .thenReturn(Collections.emptyList())
                .thenReturn(qdrantResults(shops, 1L, 4L, 2L))
                .thenReturn(Collections.emptyList());

        List<ShopKnowledge> result = service.searchRelevantShops(questions, false);

        assertEquals(Arrays.asList(1L, 2L, 4L), shopIds(result));
        verify(embeddingModelClient, times(1)).embed(questions);
        verify(qdrantKnowledgeClient, times(4)).search(anyString(), any(List.class), anyInt(),
                any(QdrantKnowledgeClient.QdrantFilter.class));
    }

    @Test
    void rebuildShouldSeparateStableProfilesFromPublicBlogs() throws Exception {
        Shop shop = shop(1L, "103\u8336\u9910\u5385", "\u8fd0\u6cb3\u4e0a\u8857", 80L);
        Blog blog = new Blog()
                .setId(101L)
                .setShopId(1L)
                .setTitle("\u6e2f\u98ce\u8336\u9910\u5385\u63a2\u5e97")
                .setContent("\u83dc\u54c1\u4e30\u5bcc\uff0c\u9002\u5408\u670b\u53cb\u805a\u9910\u3002");
        when(shopService.list()).thenReturn(Collections.singletonList(shop));
        when(blogService.list()).thenReturn(Collections.singletonList(blog));
        when(embeddingModelClient.embed(anyList())).thenAnswer(invocation -> {
            List<?> documents = invocation.getArgument(0);
            List<List<Float>> vectors = new ArrayList<>();
            for (int index = 0; index < documents.size(); index++) {
                vectors.add(Collections.singletonList(0.1F));
            }
            return vectors;
        });

        assertEquals(1, service.rebuildShopKnowledge());

        ArgumentCaptor<String> collectionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List> pointsCaptor = ArgumentCaptor.forClass(List.class);
        verify(qdrantKnowledgeClient, times(2)).upsert(collectionCaptor.capture(), pointsCaptor.capture());

        int profileIndex = collectionCaptor.getAllValues().indexOf("shop_knowledge");
        int blogIndex = collectionCaptor.getAllValues().indexOf("blog_knowledge");
        assertTrue(profileIndex >= 0);
        assertTrue(blogIndex >= 0);
        QdrantKnowledgeClient.QdrantPoint profilePoint = (QdrantKnowledgeClient.QdrantPoint)
                pointsCaptor.getAllValues().get(profileIndex).get(0);
        QdrantKnowledgeClient.QdrantPoint blogPoint = (QdrantKnowledgeClient.QdrantPoint)
                pointsCaptor.getAllValues().get(blogIndex).get(0);
        assertEquals("shop_profile", profilePoint.getPayload().get("documentType"));
        assertEquals("public_blog", blogPoint.getPayload().get("documentType"));
        assertEquals(101L, ((Number) blogPoint.getPayload().get("blogId")).longValue());
        assertNotNull(profilePoint.getPayload().get("content"));
        assertFalse(String.valueOf(profilePoint.getPayload().get("content"))
                .contains("Public blog title"));
    }

    private void arrangeVectorResults(List<Shop> shops, Long... resultIds) throws Exception {
        when(shopService.list()).thenReturn(shops);
        Map<Long, Shop> shopsById = new HashMap<>();
        for (Shop shop : shops) {
            shopsById.put(shop.getId(), shop);
        }
        List<QdrantKnowledgeClient.QdrantSearchResult> results = new ArrayList<>();
        for (Long resultId : resultIds) {
            Shop shop = shopsById.get(resultId);
            Map<String, Object> payload = new HashMap<>();
            payload.put("content", shop.getName());
            payload.put("avgPrice", shop.getAvgPrice());
            results.add(new QdrantKnowledgeClient.QdrantSearchResult(
                    resultId, 0.80D, payload
            ));
        }
        when(qdrantKnowledgeClient.search(anyString(), any(List.class), anyInt(),
                any(QdrantKnowledgeClient.QdrantFilter.class))).thenReturn(results);
    }

    private List<QdrantKnowledgeClient.QdrantSearchResult> qdrantResults(
            List<Shop> shops, Long... resultIds) {
        Map<Long, Shop> shopsById = new HashMap<>();
        for (Shop shop : shops) {
            shopsById.put(shop.getId(), shop);
        }
        List<QdrantKnowledgeClient.QdrantSearchResult> results = new ArrayList<>();
        for (Long resultId : resultIds) {
            Shop shop = shopsById.get(resultId);
            Map<String, Object> payload = new HashMap<>();
            payload.put("content", shop.getName());
            payload.put("avgPrice", shop.getAvgPrice());
            results.add(new QdrantKnowledgeClient.QdrantSearchResult(
                    resultId, 0.80D, payload
            ));
        }
        return results;
    }

    private Shop shop(Long id, String name, String area, Long avgPrice) {
        return new Shop()
                .setId(id)
                .setName(name)
                .setTypeId(1L)
                .setArea(area)
                .setAddress(area + "\u6d4b\u8bd5\u5730\u5740")
                .setAvgPrice(avgPrice)
                .setScore(45);
    }

    private List<Long> shopIds(List<ShopKnowledge> knowledgeList) {
        List<Long> ids = new ArrayList<>();
        for (ShopKnowledge knowledge : knowledgeList) {
            ids.add(knowledge.getShopId());
        }
        return ids;
    }
}
