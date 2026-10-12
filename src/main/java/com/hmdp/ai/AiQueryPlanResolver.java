package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import com.hmdp.config.AiKnowledgeProperties;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IAiTraceService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves model-provided names into database values. The model never supplies
 * shop IDs, type IDs, or Qdrant filter objects.
 */
@Slf4j
@Component
public class AiQueryPlanResolver {

    private static final String AMBIGUOUS_SHOP =
            "\u4f60\u63d0\u5230\u7684\u5e97\u540d\u5b58\u5728\u591a\u4e2a\u53ef\u80fd\u5339\u914d\uff0c\u8bf7\u8865\u5145\u5546\u5708\u6216\u5206\u5e97\u4fe1\u606f\u3002";
    private static final String UNKNOWN_SHOP =
            "\u5f53\u524d\u5e97\u94fa\u8d44\u6599\u4e2d\u6ca1\u6709\u627e\u5230\u4f60\u63d0\u5230\u7684\u5e97\uff0c\u8bf7\u68c0\u67e5\u5e97\u540d\u6216\u8865\u5145\u5546\u5708\u3002";
    private static final String UNKNOWN_CATEGORY =
            "\u5f53\u524d\u8d44\u6599\u4e2d\u6ca1\u6709\u5339\u914d\u7684\u5e97\u94fa\u5206\u7c7b\uff0c\u8bf7\u6362\u4e00\u79cd\u5206\u7c7b\u6216\u8bf4\u660e\u5177\u4f53\u5e97\u540d\u3002";
    private static final String UNKNOWN_AREA =
            "\u5f53\u524d\u8d44\u6599\u4e2d\u6ca1\u6709\u5339\u914d\u7684\u5546\u5708\uff0c\u8bf7\u68c0\u67e5\u5546\u5708\u540d\u79f0\u3002";
    private static final String MULTIPLE_SHOPS = "一次只能指定一家店，请分开说明每家店名。";
    private static final String DATA_UNAVAILABLE =
            "\u5f53\u524d\u5e97\u94fa\u6570\u636e\u670d\u52a1\u6682\u65f6\u4e0d\u53ef\u7528\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";

    @Resource
    private IShopService shopService;
    @Resource
    private IShopTypeService shopTypeService;
    @Resource
    private IAiTraceService aiTraceService;

    @Resource
    private AiKnowledgeProperties knowledgeProperties;

    public AiResolvedQueryPlan resolve(AiTraceContext traceContext, AiQueryPlan plan) {
        if (plan == null) {
            return new AiResolvedQueryPlan(AiQueryPlanStatus.FAILED,
                    Collections.<AiResolvedQueryTask>emptyList(), UNKNOWN_SHOP, null);
        }
        if (!plan.isReady()) {
            return new AiResolvedQueryPlan(plan.getStatus(),
                    Collections.<AiResolvedQueryTask>emptyList(), plan.getClarification(), plan);
        }

        AiTraceSpanScope span = traceContext == null || aiTraceService == null
                ? null : aiTraceService.startSpan(traceContext, "QUERY_PLAN_RESOLVE");
        try {
            List<Shop> shops = safeShops();
            List<ShopType> types = safeTypes();
            List<AiResolvedQueryTask> resolved = new ArrayList<>();
            for (AiQueryTask task : plan.getTasks()) {
                if (task == null) {
                    throw new PlanResolutionException(UNKNOWN_SHOP);
                }
                resolved.add(resolveTask(task, shops, types));
            }
            if (span != null) {
                Map<String, Object> attrs = new java.util.LinkedHashMap<>();
                attrs.put("taskCount", resolved.size());
                attrs.put("resolvedShopCount", candidateShopCount(resolved));
                span.success(attrs);
            }
            return new AiResolvedQueryPlan(AiQueryPlanStatus.READY, resolved, null, plan);
        } catch (PlanResolutionException e) {
            if (span != null) {
                span.failure(e);
            }
            return new AiResolvedQueryPlan(AiQueryPlanStatus.CLARIFY,
                    Collections.<AiResolvedQueryTask>emptyList(), e.getMessage(), plan);
        } catch (PlanDataUnavailableException e) {
            if (span != null) {
                span.failure(e);
            }
            log.warn("Query plan resolution is unavailable because the shop data service failed", e);
            return new AiResolvedQueryPlan(AiQueryPlanStatus.FAILED,
                    Collections.<AiResolvedQueryTask>emptyList(), DATA_UNAVAILABLE, plan);
        } catch (Exception e) {
            if (span != null) {
                span.failure(e);
            }
            log.warn("Query plan resolution failed", e);
            return new AiResolvedQueryPlan(AiQueryPlanStatus.FAILED,
                    Collections.<AiResolvedQueryTask>emptyList(), UNKNOWN_SHOP, plan);
        }
    }

    private AiResolvedQueryTask resolveTask(AiQueryTask task, List<Shop> shops,
                                             List<ShopType> types) {
        if (isUnboundedTask(task)) {
            throw new PlanResolutionException(
                    "请补充具体店铺、类别、商圈、预算或偏好，我再帮你查询。" );
        }
        if (task.getShopNames().size() > taskCandidateLimit()) {
            throw new PlanResolutionException(
                    "一次最多处理" + taskCandidateLimit() + "家明确店铺，请缩小查询范围。" );
        }
        // Resolve the area first so an explicit district can disambiguate a
        // brand name with multiple branches.
        List<String> areas = resolveArea(task.getArea(), shops);
        List<Long> shopIds = resolveShopNames(task.getShopNames(), shops, areas);
        List<Long> excludedShopIds = resolveShopNames(task.getExcludedShopNames(), shops, areas);
        for (Long shopId : shopIds) {
            if (excludedShopIds.contains(shopId)) {
                throw new PlanResolutionException(
                        "同一问题同时指定了要查询和排除的店铺，请明确保留哪一家。" );
            }
        }
        List<Long> typeIds = resolveCategory(task.getCategory(), types);
        return new AiResolvedQueryTask(task, shopIds, excludedShopIds, typeIds, areas);
    }

    private boolean isUnboundedTask(AiQueryTask task) {
        return task.getShopNames().isEmpty()
                && task.getExcludedShopNames().isEmpty()
                && StrUtil.isBlank(task.getCategory())
                && StrUtil.isBlank(task.getArea())
                && task.getMaxAveragePrice() == null
                && task.getSemanticPreferences().isEmpty();
    }

    private int taskCandidateLimit() {
        if (knowledgeProperties == null || knowledgeProperties.getTaskCandidateLimit() == null
                || knowledgeProperties.getTaskCandidateLimit() < 1) {
            return 5;
        }
        return knowledgeProperties.getTaskCandidateLimit();
    }

    private List<Long> resolveShopNames(List<String> names, List<Shop> shops,
                                        List<String> requestedAreas) {
        List<Long> ids = new ArrayList<>();
        for (String name : names) {
            List<Shop> matches = findShops(name, shops);
            if (!requestedAreas.isEmpty()) {
                matches = filterByAreas(matches, requestedAreas);
            }
            if (matches.isEmpty()) {
                throw new PlanResolutionException(UNKNOWN_SHOP);
            }
            if (matches.size() > 1) {
                throw new PlanResolutionException(AMBIGUOUS_SHOP);
            }
            ids.add(matches.get(0).getId());
        }
        return ids;
    }

    private List<Shop> filterByAreas(List<Shop> shops, List<String> requestedAreas) {
        if (shops.isEmpty() || requestedAreas.isEmpty()) {
            return shops;
        }
        Set<String> normalizedAreas = new LinkedHashSet<>();
        for (String area : requestedAreas) {
            normalizedAreas.add(normalize(area));
        }
        List<Shop> filtered = new ArrayList<>();
        for (Shop shop : shops) {
            if (shop != null && normalizedAreas.contains(normalize(shop.getArea()))) {
                filtered.add(shop);
            }
        }
        return filtered;
    }

    private List<Shop> findShops(String name, List<Shop> shops) {
        String expected = normalizeStore(name);
        if (StrUtil.isBlank(expected)) {
            return Collections.emptyList();
        }
        List<Shop> exact = new ArrayList<>();
        for (Shop shop : shops) {
            if (expected.equals(normalizeStore(shop.getName()))) {
                exact.add(shop);
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        List<Shop> core = new ArrayList<>();
        for (Shop shop : shops) {
            if (expected.equals(normalizeStore(stripBranch(shop.getName())))) {
                core.add(shop);
            }
        }
        if (!core.isEmpty()) {
            return core;
        }
        if (containsShopConnector(name)) {
            // A loose match on a string naming several shops would keep one shop and silently drop the rest.
            throw new PlanResolutionException(MULTIPLE_SHOPS);
        }
        List<Shop> contained = new ArrayList<>();
        for (Shop shop : shops) {
            String actual = normalizeStore(shop.getName());
            if (actual.contains(expected)) {
                contained.add(shop);
                continue;
            }
            String actualCore = normalizeStore(stripBranch(shop.getName()));
            if (hasRecognizedShopDescriptorSuffix(expected, actual)
                    || hasRecognizedShopDescriptorSuffix(expected, actualCore)) {
                contained.add(shop);
            }
        }
        return contained;
    }

    /**
     * Accept a brand/core name followed by a conversational or category
     * descriptor while keeping the database shop name as the source of truth.
     */
    private boolean hasRecognizedShopDescriptorSuffix(String expected, String actualCore) {
        if (StrUtil.isBlank(actualCore) || !expected.startsWith(actualCore)
                || expected.length() <= actualCore.length()) {
            return false;
        }
        String suffix = expected.substring(actualCore.length());
        return SHOP_DESCRIPTOR_SUFFIXES.contains(suffix);
    }

    private static final List<String> SHOP_CONNECTORS = java.util.Arrays.asList(
            "和", "及", "与", "以及", "、", ",", "，", "&", "/", "+");

    private boolean containsShopConnector(String name) {
        for (String connector : SHOP_CONNECTORS) {
            if (name.contains(connector)) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> SHOP_DESCRIPTOR_SUFFIXES = new LinkedHashSet<>(
            java.util.Arrays.asList(
                    "这家", "这家店", "这家门店", "这家餐厅", "这间店", "该店", "该门店", "该餐厅",
                    "店", "门店", "店铺", "餐厅", "西餐厅", "饭店", "酒吧", "火锅", "寿司", "ktv"));

    private List<Long> resolveCategory(String category, List<ShopType> types) {
        if (StrUtil.isBlank(category)) {
            return Collections.emptyList();
        }
        List<Long> ids = AiCategoryCatalog.from(types).resolve(category);
        if (ids.isEmpty()) {
            throw new PlanResolutionException(UNKNOWN_CATEGORY);
        }
        return ids;
    }

    private List<String> resolveArea(String area, List<Shop> shops) {
        if (StrUtil.isBlank(area)) {
            return Collections.emptyList();
        }
        String expected = normalize(area);
        Set<String> values = new LinkedHashSet<>();
        for (Shop shop : shops) {
            if (expected.equals(normalize(shop.getArea()))) {
                values.add(shop.getArea());
            }
        }
        if (values.isEmpty()) {
            throw new PlanResolutionException(UNKNOWN_AREA);
        }
        return new ArrayList<>(values);
    }

    private int candidateShopCount(List<AiResolvedQueryTask> tasks) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AiResolvedQueryTask task : tasks) {
            ids.addAll(task.getShopIds());
        }
        return ids.size();
    }

    private List<Shop> safeShops() {
        try {
            if (shopService == null) {
                throw new IllegalStateException("shop service is not configured");
            }
            List<Shop> shops = AiRequestCatalog.shops(shopService::list);
            if (shops == null) {
                throw new IllegalStateException("shop data is unavailable");
            }
            return shops;
        } catch (Exception e) {
            throw new PlanDataUnavailableException(e);
        }
    }

    private List<ShopType> safeTypes() {
        try {
            if (shopTypeService == null) {
                throw new IllegalStateException("shop type service is not configured");
            }
            List<ShopType> types = AiRequestCatalog.types(shopTypeService::list);
            if (types == null) {
                throw new IllegalStateException("shop type data is unavailable");
            }
            return types;
        } catch (Exception e) {
            throw new PlanDataUnavailableException(e);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase();
    }

    private String normalizeStore(String value) {
        return normalize(value).replace('\uff08', '(').replace('\uff09', ')')
                .replaceAll("[\\p{P}\\p{S}]", "");
    }

    private String stripBranch(String value) {
        return value == null ? "" : value.replaceFirst("[\\(\\uff08].*$", "");
    }

    private static final class PlanResolutionException extends RuntimeException {
        private PlanResolutionException(String message) {
            super(message);
        }
    }

    private static final class PlanDataUnavailableException extends RuntimeException {
        private PlanDataUnavailableException(Throwable cause) {
            super("Shop data service unavailable", cause);
        }
    }
}
