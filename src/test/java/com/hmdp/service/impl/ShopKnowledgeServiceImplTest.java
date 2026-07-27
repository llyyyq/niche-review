package com.hmdp.service.impl;

import com.hmdp.ai.EmbeddingModelClient;
import com.hmdp.ai.QdrantKnowledgeClient;
import com.hmdp.ai.ShopKnowledge;
import com.hmdp.config.AiEmbeddingProperties;
import com.hmdp.config.AiKnowledgeProperties;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShopKnowledgeServiceImplTest {

    @Mock
    private IShopService shopService;

    @Mock
    private IShopTypeService shopTypeService;

    @Mock
    private IVoucherService voucherService;

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
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
        ReflectionTestUtils.setField(service, "blogService", blogService);
        ReflectionTestUtils.setField(service, "embeddingModelClient", embeddingModelClient);
        ReflectionTestUtils.setField(service, "qdrantKnowledgeClient", qdrantKnowledgeClient);
        ReflectionTestUtils.setField(service, "embeddingProperties", new AiEmbeddingProperties());
        ReflectionTestUtils.setField(service, "knowledgeProperties", knowledgeProperties);

        when(shopTypeService.list()).thenReturn(Collections.singletonList(
                new ShopType().setId(1L).setName("\u7f8e\u98df")
        ));
        when(voucherService.listEnabledVouchersForKnowledge()).thenReturn(Collections.emptyList());
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
        when(qdrantKnowledgeClient.search(anyString(), any(List.class), anyInt())).thenReturn(results);
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
