package com.hmdp.service.impl;

import com.hmdp.ai.EmbeddingModelClient;
import com.hmdp.ai.QdrantKnowledgeClient;
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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void setUp() {
        service = new ShopKnowledgeServiceImpl();
        AiKnowledgeProperties knowledgeProperties = new AiKnowledgeProperties();
        knowledgeProperties.setTaskCandidateLimit(5);
        knowledgeProperties.setVectorMinScore(0.35D);

        ReflectionTestUtils.setField(service, "shopService", shopService);
        ReflectionTestUtils.setField(service, "shopTypeService", shopTypeService);
        ReflectionTestUtils.setField(service, "blogService", blogService);
        ReflectionTestUtils.setField(service, "embeddingModelClient", embeddingModelClient);
        ReflectionTestUtils.setField(service, "qdrantKnowledgeClient", qdrantKnowledgeClient);
        ReflectionTestUtils.setField(service, "embeddingProperties", new AiEmbeddingProperties());
        ReflectionTestUtils.setField(service, "knowledgeProperties", knowledgeProperties);

        when(shopTypeService.list()).thenReturn(Collections.singletonList(
                new ShopType().setId(1L).setName("美食")
        ));
    }

    @Test
    void rebuildShouldSeparateStableProfilesFromPublicBlogs() throws Exception {
        Shop shop = shop(1L, "103茶餐厅", "运河上街", 80L);
        Blog blog = new Blog()
                .setId(101L)
                .setShopId(1L)
                .setTitle("港风茶餐厅探店")
                .setContent("菜品丰富，适合朋友聚餐。");
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

    private Shop shop(Long id, String name, String area, Long avgPrice) {
        return new Shop()
                .setId(id)
                .setName(name)
                .setTypeId(1L)
                .setArea(area)
                .setAddress(area + "测试地址")
                .setAvgPrice(avgPrice)
                .setScore(45);
    }
}
