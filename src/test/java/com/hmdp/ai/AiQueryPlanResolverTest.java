package com.hmdp.ai;

import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.config.AiKnowledgeProperties;
import com.hmdp.service.IShopService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AiQueryPlanResolverTest {

    @Mock
    private IShopService shopService;

    @Mock
    private IShopTypeService shopTypeService;

    private AiQueryPlanResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AiQueryPlanResolver();
        when(shopService.list()).thenReturn(Arrays.asList(
                shop(7L, "炉鱼（拱墅万达广场店）", "北部新城"),
                shop(8L, "炉鱼（城西店）", "城西")
        ));
        lenient().when(shopTypeService.list()).thenReturn(Collections.singletonList(
                new ShopType().setId(1L).setName("美食")
        ));
        ReflectionTestUtils.setField(resolver, "shopService", shopService);
        ReflectionTestUtils.setField(resolver, "shopTypeService", shopTypeService);
        ReflectionTestUtils.setField(resolver, "knowledgeProperties", new AiKnowledgeProperties());
    }

    @Test
    void uniqueFullNameShouldResolveToDatabaseId() {
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "FACT_LOOKUP", "炉鱼营业时间",
                Collections.singletonList("炉鱼（拱墅万达广场店）"),
                Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(), Collections.singletonList("SHOP_DETAIL")));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertTrue(resolved.isReady());
        assertEquals(Collections.singletonList(7L), resolved.candidateShopIds());
    }

    @Test
    void uniqueBrandCoreNameShouldResolveToSingleBranch() {
        when(shopService.list()).thenReturn(Collections.singletonList(
                shop(4L, "Mamala(杭州远洋乐堤港店)", "拱宸桥/上塘")));
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "FACT_LOOKUP", "Mamala有什么特色？", Collections.singletonList("Mamala"),
                Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(), Collections.singletonList("SHOP_DETAIL")));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertTrue(resolved.isReady());
        assertEquals(Collections.singletonList(4L), resolved.candidateShopIds());
    }

    @Test
    void conversationalShopDescriptorShouldResolveToBrandCoreName() {
        when(shopService.list()).thenReturn(Collections.singletonList(
                shop(4L, "Mamala(杭州远洋乐堤港店)", "拱宸桥/上塘")));
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "FACT_LOOKUP", "Mamala这家餐厅有什么特色？",
                Collections.singletonList("Mamala这家餐厅"), Collections.<String>emptyList(),
                null, null, null, Collections.<String>emptyList(),
                Collections.singletonList("SHOP_DETAIL")));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertTrue(resolved.isReady());
        assertEquals(Collections.singletonList(4L), resolved.candidateShopIds());
    }

    @Test
    void categoryShopDescriptorShouldResolveToBrandCoreName() {
        when(shopService.list()).thenReturn(Collections.singletonList(
                shop(4L, "Mamala(杭州远洋乐堤港店)", "拱宸桥/上塘")));
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "FACT_LOOKUP", "Mamala西餐厅有什么特色？",
                Collections.singletonList("Mamala西餐厅"), Collections.<String>emptyList(),
                null, null, null, Collections.<String>emptyList(),
                Collections.singletonList("SHOP_DETAIL")));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertTrue(resolved.isReady());
        assertEquals(Collections.singletonList(4L), resolved.candidateShopIds());
    }

    @Test
    void explicitAreaShouldDisambiguateBrandCoreName() {
        when(shopService.list()).thenReturn(Arrays.asList(
                shop(4L, "Mamala(杭州远洋乐堤港店)", "拱宸桥/上塘"),
                shop(5L, "Mamala(城西店)", "城西")));
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "FACT_LOOKUP", "拱宸桥/上塘的Mamala营业时间",
                Collections.singletonList("Mamala"), Collections.<String>emptyList(),
                null, "拱宸桥/上塘", null, Collections.<String>emptyList(),
                Collections.singletonList("SHOP_DETAIL")));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertTrue(resolved.isReady());
        assertEquals(Collections.singletonList(4L), resolved.candidateShopIds());
    }

    @Test
    void missingAreaShouldRemainAnOptionalFilter() {
        when(shopService.list()).thenReturn(Arrays.asList(
                shop(10L, "开乐迪KTV（运河上街店）", "运河上街").setTypeId(2L),
                shop(11L, "INLOVE KTV（水晶城店）", "水晶城").setTypeId(2L)
        ));
        when(shopTypeService.list()).thenReturn(Arrays.asList(
                new ShopType().setId(1L).setName("美食"),
                new ShopType().setId(2L).setName("KTV")
        ));
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "RECOMMEND", "推荐KTV", Collections.<String>emptyList(),
                Collections.<String>emptyList(), "KTV", null, null,
                Collections.<String>emptyList(), Collections.<String>emptyList()));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertTrue(resolved.isReady());
        assertEquals(Collections.singletonList(2L), resolved.getTasks().get(0).getTypeIds());
        assertTrue(resolved.getTasks().get(0).getAreas().isEmpty());
    }

    @Test
    void coreNameWithTwoBranchesShouldAskForClarification() {
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "FACT_LOOKUP", "炉鱼营业时间", Collections.singletonList("炉鱼"),
                Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(), Collections.singletonList("SHOP_DETAIL")));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertEquals(AiQueryPlanStatus.CLARIFY, resolved.getStatus());
        assertTrue(resolved.getTasks().isEmpty());
    }

    @Test
    void unknownCategoryShouldNotReachRetrieval() {
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "RECOMMEND", "推荐书店", Collections.<String>emptyList(),
                Collections.<String>emptyList(), "书店", null, null,
                Collections.<String>emptyList(), Collections.<String>emptyList()));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertEquals(AiQueryPlanStatus.CLARIFY, resolved.getStatus());
        assertTrue(resolved.getClarification().contains("分类"));
    }

    @Test
    void shopDataFailureShouldBeReportedAsServiceFailureNotUnknownShop() {
        when(shopService.list()).thenThrow(new IllegalStateException("database unavailable"));
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "RECOMMEND", "推荐餐厅", Collections.<String>emptyList(),
                Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(), Collections.<String>emptyList()));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertEquals(AiQueryPlanStatus.FAILED, resolved.getStatus());
        assertTrue(resolved.getClarification().contains("暂时不可用"));
    }

    @Test
    void factOnlyTaskShouldBeClarifiedByResolverInsteadOfQueryingAllStores() {
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "FACT_LOOKUP", "有什么优惠券？", Collections.<String>emptyList(),
                Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(), Collections.singletonList("ACTIVE_VOUCHER")));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertEquals(AiQueryPlanStatus.CLARIFY, resolved.getStatus());
        assertTrue(resolved.getClarification().contains("具体店铺"));
    }

    @Test
    void includeAndExcludeSameShopShouldBeClarified() {
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "FACT_LOOKUP", "炉鱼的优惠",
                Collections.singletonList("炉鱼（拱墅万达广场店）"),
                Collections.singletonList("炉鱼（拱墅万达广场店）"), null, null, null,
                Collections.<String>emptyList(), Collections.singletonList("ACTIVE_VOUCHER")));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertEquals(AiQueryPlanStatus.CLARIFY, resolved.getStatus());
        assertTrue(resolved.getClarification().contains("同时指定"));
    }

    @Test
    void moreThanFiveExplicitShopsShouldAskForClarification() {
        when(shopService.list()).thenReturn(Arrays.asList(
                shop(1L, "店铺一", "城东"), shop(2L, "店铺二", "城东"),
                shop(3L, "店铺三", "城东"), shop(4L, "店铺四", "城东"),
                shop(5L, "店铺五", "城东"), shop(6L, "店铺六", "城东")));
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "COMPARE", "比较六家店",
                Arrays.asList("店铺一", "店铺二", "店铺三", "店铺四", "店铺五", "店铺六"),
                Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(), Collections.singletonList("SHOP_DETAIL")));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertEquals(AiQueryPlanStatus.CLARIFY, resolved.getStatus());
        assertTrue(resolved.getClarification().contains("最多处理5家"));
    }

    @Test
    void multiNameStringShouldAskForClarificationInsteadOfDroppingShops() {
        when(shopService.list()).thenReturn(Collections.singletonList(
                shop(4L, "麦当劳", "望京")));
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "FACT_LOOKUP", "麦当劳和肯德基营业时间",
                Collections.singletonList("麦当劳和肯德基"), Collections.<String>emptyList(),
                null, null, null, Collections.<String>emptyList(),
                Collections.singletonList("SHOP_DETAIL")));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertEquals(AiQueryPlanStatus.CLARIFY, resolved.getStatus());
        assertTrue(resolved.getClarification().contains("一家店"));
    }

    @Test
    void fullBranchNameWithWhitelistedDescriptorShouldResolve() {
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "FACT_LOOKUP", "炉鱼营业时间",
                Collections.singletonList("炉鱼（拱墅万达广场店）这家店"),
                Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(), Collections.singletonList("SHOP_DETAIL")));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertTrue(resolved.isReady());
        assertEquals(Collections.singletonList(7L), resolved.candidateShopIds());
    }

    @Test
    void unlistedSuffixAfterBranchNameShouldNotResolveByReverseContainment() {
        AiQueryPlan plan = readyTask(new AiQueryTask(
                "FACT_LOOKUP", "炉鱼营业时间",
                Collections.singletonList("炉鱼（拱墅万达广场店）附近的"),
                Collections.<String>emptyList(), null, null, null,
                Collections.<String>emptyList(), Collections.singletonList("SHOP_DETAIL")));

        AiResolvedQueryPlan resolved = resolver.resolve(null, plan);

        assertEquals(AiQueryPlanStatus.CLARIFY, resolved.getStatus());
        assertTrue(resolved.getTasks().isEmpty());
    }

    private AiQueryPlan readyTask(AiQueryTask task) {
        return new AiQueryPlan(AiQueryPlanStatus.READY,
                Collections.singletonList(task), null, false, true, 0L,
                task.getQuery().length(), task.getQuery().length(), null);
    }

    private Shop shop(Long id, String name, String area) {
        return new Shop().setId(id).setName(name).setArea(area).setTypeId(1L);
    }
}
