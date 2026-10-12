package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.ai.AiToolExecution;
import com.hmdp.ai.AiToolQueryScope;
import com.hmdp.ai.AiTraceContext;
import com.hmdp.ai.AiTraceIds;
import com.hmdp.ai.AiTraceSpanScope;
import com.hmdp.ai.ShopKnowledge;
import com.hmdp.config.AiKnowledgeProperties;
import com.hmdp.dto.Result;
import com.hmdp.entity.AiToolLog;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IAiReadOnlyToolService;
import com.hmdp.service.IAiToolLogService;
import com.hmdp.service.IAiTraceService;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiReadOnlyToolServiceImpl implements IAiReadOnlyToolService {

    private static final int MAX_BLOG_RESULTS = 6;
    private static final String NO_CANDIDATE_REASON =
            "Not queried: this task has no candidate stores. Do not infer that no matching data exists.";
    private static final String NO_TYPE_REASON =
            "Not queried: no store category was available for the nearby search. "
                    + "Do not infer that no nearby store exists.";
    private static final List<String> SUPPORTED_TOOLS = java.util.Arrays.asList(
            "shopDetail", "voucherQuery", "blogSearch", "nearbyShopSearch"
    );

    @Resource
    private IShopService shopService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IBlogService blogService;

    @Resource
    private IAiToolLogService aiToolLogService;

    @Resource
    private IAiTraceService aiTraceService;

    @Resource
    private AiKnowledgeProperties knowledgeProperties;

    @Override
    public List<AiToolExecution> executeTools(AiTraceContext traceContext,
                                               Long conversationId, Long userId, List<String> toolNames,
                                               Double x, Double y, AiToolQueryScope scope,
                                               List<ShopKnowledge> retrievedShops) {
        if (toolNames == null || toolNames.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> shopIds;
        RuntimeException candidateResolutionError = null;
        try {
            shopIds = candidateShopIds(scope, retrievedShops);
        } catch (RuntimeException e) {
            shopIds = Collections.emptyList();
            candidateResolutionError = e;
        }
        final List<Long> resolvedShopIds = shopIds;
        List<AiToolExecution> executions = new ArrayList<>();
        for (String toolName : new LinkedHashSet<>(toolNames)) {
            if (!SUPPORTED_TOOLS.contains(toolName)) {
                continue;
            }
            if (candidateResolutionError != null && requiresShopCandidates(toolName)) {
                final RuntimeException failure = candidateResolutionError;
                addIfPresent(executions, invoke(traceContext, conversationId, userId, toolName,
                        "candidateResolutionFailed=true", () -> {
                            throw failure;
                        }));
                continue;
            }
            if (requiresShopCandidates(toolName) && resolvedShopIds.isEmpty()) {
                executions.add(AiToolExecution.skipped(toolName, NO_CANDIDATE_REASON));
                continue;
            }
            if ("shopDetail".equals(toolName)) {
                addIfPresent(executions, invoke(traceContext, conversationId, userId, toolName,
                        "shopIds=" + resolvedShopIds, () -> queryShopDetails(resolvedShopIds)));
            }
            if ("voucherQuery".equals(toolName)) {
                addIfPresent(executions, invoke(traceContext, conversationId, userId, toolName,
                        "shopIds=" + resolvedShopIds, () -> queryCurrentVouchers(resolvedShopIds)));
            }
            if ("blogSearch".equals(toolName)) {
                addIfPresent(executions, invoke(traceContext, conversationId, userId, toolName,
                        "shopIds=" + resolvedShopIds, () -> queryPopularBlogs(resolvedShopIds)));
            }
            if ("nearbyShopSearch".equals(toolName) && hasValidLocation(x, y)) {
                Integer typeId = firstCandidateTypeId(
                        scope == null ? Collections.<Long>emptyList() : scope.getTypeIds(), retrievedShops);
                if (typeId != null) {
                    addIfPresent(executions, invoke(traceContext, conversationId, userId, toolName,
                            "typeId=" + typeId + ",locationProvided=true",
                            () -> queryNearbyShops(typeId, x, y, scope)));
                } else {
                    executions.add(AiToolExecution.skipped(toolName, NO_TYPE_REASON));
                }
            }
        }
        return executions;
    }

    private List<Long> candidateShopIds(AiToolQueryScope scope, List<ShopKnowledge> retrievedShops) {
        Set<Long> ids = new LinkedHashSet<>();
        AiToolQueryScope safeScope = scope == null
                ? new AiToolQueryScope(Collections.<Long>emptyList(), Collections.<Long>emptyList(),
                Collections.<Long>emptyList(), Collections.<String>emptyList(), null) : scope;
        if (!safeScope.getShopIds().isEmpty()) {
            List<Shop> resolved = shopService.listByIds(safeScope.getShopIds());
            if (resolved != null) {
                for (Shop shop : resolved) {
                    if (shop != null && matchesScope(shop, safeScope)) {
                        ids.add(shop.getId());
                    }
                    if (ids.size() >= taskCandidateLimit()) {
                        break;
                    }
                }
            }
            // A resolved shop name is a hard condition. Never append a semantic
            // candidate when that named store failed the same task constraints.
            return new ArrayList<>(ids);
        }
        if (retrievedShops == null || retrievedShops.isEmpty()) {
            return new ArrayList<>(ids);
        }
        for (ShopKnowledge shop : retrievedShops) {
            if (shop != null && shop.getShopId() != null && matchesScope(shop, safeScope)) {
                ids.add(shop.getShopId());
            }
            if (ids.size() >= taskCandidateLimit()) {
                break;
            }
        }
        return new ArrayList<>(ids);
    }

    private boolean requiresShopCandidates(String toolName) {
        return "shopDetail".equals(toolName) || "voucherQuery".equals(toolName)
                || "blogSearch".equals(toolName);
    }

    private boolean matchesScope(Shop shop, AiToolQueryScope scope) {
        if (scope.getExcludedShopIds().contains(shop.getId())) {
            return false;
        }
        if (!scope.getTypeIds().isEmpty()
                && (shop.getTypeId() == null || !scope.getTypeIds().contains(shop.getTypeId()))) {
            return false;
        }
        if (!scope.getAreas().isEmpty()
                && (shop.getArea() == null || !scope.getAreas().contains(shop.getArea()))) {
            return false;
        }
        return scope.getMaxAveragePrice() == null
                || (shop.getAvgPrice() != null && shop.getAvgPrice() <= scope.getMaxAveragePrice());
    }

    private boolean matchesScope(ShopKnowledge knowledge, AiToolQueryScope scope) {
        if (knowledge == null || knowledge.getShopId() == null
                || scope.getExcludedShopIds().contains(knowledge.getShopId())) {
            return false;
        }
        Map<String, Object> payload = knowledge.getPayload();
        if (!scope.getTypeIds().isEmpty()
                && (payload == null || !scope.getTypeIds().contains(asLong(payload.get("typeId"))))) {
            return false;
        }
        if (!scope.getAreas().isEmpty()
                && (payload == null || !scope.getAreas().contains(String.valueOf(payload.get("area"))))) {
            return false;
        }
        if (scope.getMaxAveragePrice() != null) {
            Long averagePrice = payload == null ? null : asLong(payload.get("avgPrice"));
            if (averagePrice == null || averagePrice > scope.getMaxAveragePrice()) {
                return false;
            }
        }
        return true;
    }

    private Long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String queryShopDetails(List<Long> shopIds) {
        Map<Long, Shop> shopsById = new HashMap<>();
        List<Shop> loadedShops = shopService.listByIds(shopIds);
        if (loadedShops == null) {
            loadedShops = Collections.emptyList();
        }
        for (Shop shop : loadedShops) {
            shopsById.put(shop.getId(), shop);
        }
        StringBuilder result = new StringBuilder("Live store details:\n");
        for (Long shopId : shopIds) {
            Shop shop = shopsById.get(shopId);
            if (shop != null) {
                result.append("- ").append(formatShop(shop)).append('\n');
            }
        }
        return result.length() == "Live store details:\n".length()
                ? "Live store details: no current store was found for the resolved candidates."
                : result.toString();
    }

    private String queryCurrentVouchers(List<Long> shopIds) {
        Set<Long> shopIdSet = new LinkedHashSet<>(shopIds);
        LocalDateTime now = LocalDateTime.now();
        List<Voucher> enabledVouchers = voucherService.listEnabledVouchersForKnowledge(shopIds);
        if (enabledVouchers == null) {
            enabledVouchers = Collections.emptyList();
        }
        List<Voucher> vouchers = enabledVouchers.stream()
                .filter(voucher -> shopIdSet.contains(voucher.getShopId()))
                .filter(voucher -> isAvailableNow(voucher, now))
                .collect(Collectors.toList());
        if (vouchers.isEmpty()) {
            return "Live voucher query: no currently available voucher was found for the candidate stores.";
        }
        StringBuilder result = new StringBuilder("Live voucher query:\n");
        for (Voucher voucher : vouchers) {
            result.append("- shopId=").append(voucher.getShopId())
                    .append(", title=").append(voucher.getTitle());
            if (voucher.getPayValue() != null || voucher.getActualValue() != null) {
                result.append(", pay=").append(formatMoneyInYuan(voucher.getPayValue()))
                        .append(" CNY, value=").append(formatMoneyInYuan(voucher.getActualValue())).append(" CNY");
            }
            if (StrUtil.isNotBlank(voucher.getRules())) {
                result.append(", rules=").append(voucher.getRules().replaceAll("\\s+", " "));
            }
            if (voucher.getEndTime() != null) {
                result.append(", validUntil=").append(voucher.getEndTime());
            }
            result.append('\n');
        }
        return result.toString();
    }

    private String queryPopularBlogs(List<Long> shopIds) {
        List<Blog> blogs = blogService.query()
                .in("shop_id", shopIds)
                .orderByDesc("liked")
                .last("LIMIT " + MAX_BLOG_RESULTS)
                .list();
        if (blogs == null) {
            blogs = Collections.emptyList();
        }
        if (blogs.isEmpty()) {
            return "Live blog query: no public blog was found for the candidate stores.";
        }
        StringBuilder result = new StringBuilder("Live public blog query:\n");
        for (Blog blog : blogs) {
            result.append("- shopId=").append(blog.getShopId());
            if (StrUtil.isNotBlank(blog.getTitle())) {
                result.append(", title=").append(blog.getTitle());
            }
            result.append(", summary=").append(truncate(blog.getContent(), 240));
            if (blog.getLiked() != null) {
                result.append(", likes=").append(blog.getLiked());
            }
            result.append('\n');
        }
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private String queryNearbyShops(Integer typeId, Double x, Double y, AiToolQueryScope scope) {
        Result result = shopService.queryShopByType(typeId, 1, x, y);
        if (result == null || !(result.getData() instanceof List)) {
            return "Live nearby-store query: no indexed store was found within the current 50 km search range. The browser location was received successfully.";
        }
        List<?> values = (List<?>) result.getData();
        if (values.isEmpty()) {
            return "Live nearby-store query: no indexed store was found within the current 50 km search range. The browser location was received successfully.";
        }
        StringBuilder text = new StringBuilder("Live nearby-store query:\n");
        int count = 0;
        for (Object value : values) {
            if (!(value instanceof Shop)) {
                continue;
            }
            Shop shop = (Shop) value;
            if (scope != null && !matchesScope(shop, scope)) {
                continue;
            }
            if (scope != null && !scope.getShopIds().isEmpty()
                    && !scope.getShopIds().contains(shop.getId())) {
                continue;
            }
            text.append("- ").append(formatShop(shop)).append('\n');
            count++;
            if (count >= taskCandidateLimit()) {
                break;
            }
        }
        return count == 0
                ? "Live nearby-store query: no indexed store was found within the current 50 km search range. The browser location was received successfully."
                : text.toString();
    }

    private Integer firstCandidateTypeId(List<Long> explicitTypeIds, List<ShopKnowledge> retrievedShops) {
        if (explicitTypeIds != null) {
            for (Long typeId : explicitTypeIds) {
                if (typeId != null) {
                    return typeId.intValue();
                }
            }
        }
        if (retrievedShops == null || retrievedShops.isEmpty()) {
            return null;
        }
        Map<String, Object> payload = retrievedShops.get(0).getPayload();
        Object value = payload == null ? null : payload.get("typeId");
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int taskCandidateLimit() {
        Integer configured = knowledgeProperties == null ? null : knowledgeProperties.getTaskCandidateLimit();
        return configured == null || configured <= 0 ? 5 : configured;
    }

    private boolean hasValidLocation(Double x, Double y) {
        return x != null && y != null
                && x >= -180D && x <= 180D
                && y >= -90D && y <= 90D;
    }

    private AiToolExecution invoke(AiTraceContext traceContext,
                                   Long conversationId, Long userId, String toolName,
                                   String requestContent, ToolSupplier supplier) {
        String toolCallId = AiTraceIds.toolCallId();
        Map<String, Object> spanAttributes = new HashMap<>();
        spanAttributes.put("toolName", toolName);
        spanAttributes.put("toolCallId", toolCallId);
        if (traceContext != null && traceContext.getTaskIndex() > 0) {
            spanAttributes.put("taskIndex", traceContext.getTaskIndex());
        }
        AiTraceSpanScope toolSpan = traceContext == null || aiTraceService == null
                ? null : aiTraceService.startSpan(traceContext, "TOOL_CALL", spanAttributes);
        AiTraceContext toolContext = toolSpan == null ? traceContext : toolSpan.getContext();
        long start = System.currentTimeMillis();
        try {
            String resultContent = supplier.get();
            if (toolSpan != null) {
                Map<String, Object> successAttributes = new HashMap<>(spanAttributes);
                successAttributes.put("resultChars", resultContent == null ? 0 : resultContent.length());
                toolSpan.success(successAttributes);
            }
            saveLog(toolContext, toolCallId, conversationId, userId, toolName, requestContent, resultContent, 1,
                    System.currentTimeMillis() - start);
            return new AiToolExecution(toolName, resultContent);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String error = "Tool execution failed: " + e.getClass().getSimpleName();
            log.warn("AI read-only tool failed, toolName={}, conversationId={}", toolName, conversationId, e);
            if (toolSpan != null) {
                toolSpan.failure(e);
            }
            saveLog(toolContext, toolCallId, conversationId, userId, toolName, requestContent, error, 0, duration);
            // Keep the failure in the evidence stream. Returning no execution makes
            // the final model unable to distinguish "no live data was requested"
            // from "live data was requested but unavailable", so it may fall back
            // to stale vector knowledge for a time-sensitive fact.
            return new AiToolExecution(toolName,
                    "Live business data is temporarily unavailable for this request. "
                            + "Do not infer time-sensitive facts from the unavailable tool result.",
                    false);
        }
    }

    private void saveLog(AiTraceContext traceContext, String toolCallId,
                         Long conversationId, Long userId, String toolName, String requestContent,
                         String resultContent, int success, long durationMs) {
        try {
            AiToolLog toolLog = new AiToolLog();
            toolLog.setConversationId(conversationId);
            toolLog.setUserId(userId);
            if (traceContext != null && traceContext.isValid()) {
                toolLog.setAssistantMessageId(traceContext.getAssistantMessageId());
                toolLog.setRequestId(traceContext.getRequestId());
                toolLog.setTraceId(traceContext.getTraceId());
                toolLog.setSpanId(traceContext.getCurrentSpanId());
                toolLog.setParentSpanId(traceContext.getParentSpanId());
            }
            toolLog.setToolCallId(toolCallId);
            toolLog.setToolName(toolName);
            toolLog.setRequestContent(requestContent);
            toolLog.setResultContent(resultContent);
            toolLog.setSuccess(success);
            toolLog.setDurationMs(durationMs);
            aiToolLogService.save(toolLog);
        } catch (Exception e) {
            log.error("Failed to save AI tool log, toolName={}, conversationId={}", toolName, conversationId, e);
        }
    }

    private void addIfPresent(List<AiToolExecution> executions, AiToolExecution execution) {
        if (execution != null) {
            executions.add(execution);
        }
    }

    private boolean isAvailableNow(Voucher voucher, LocalDateTime now) {
        if (voucher.getBeginTime() != null && voucher.getBeginTime().isAfter(now)) {
            return false;
        }
        if (voucher.getEndTime() != null && voucher.getEndTime().isBefore(now)) {
            return false;
        }
        return voucher.getStock() == null || voucher.getStock() > 0;
    }

    private String formatShop(Shop shop) {
        StringBuilder text = new StringBuilder();
        text.append("name=").append(shop.getName());
        appendIfPresent(text, "rating", shop.getScore() == null ? null : shop.getScore() / 10.0 + "/5");
        appendIfPresent(text, "averageSpend", shop.getAvgPrice() == null ? null : shop.getAvgPrice() + " CNY");
        appendIfPresent(text, "address", shop.getAddress());
        appendIfPresent(text, "openingHours", shop.getOpenHours());
        appendIfPresent(text, "distance", shop.getDistance() == null ? null : String.format("%.0f m", shop.getDistance()));
        return text.toString();
    }

    private void appendIfPresent(StringBuilder text, String label, Object value) {
        if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
            text.append(", ").append(label).append('=').append(value);
        }
    }

    /**
     * The voucher table stores monetary values in cents, while AI tool output is displayed in yuan.
     */
    private String formatMoneyInYuan(Long amountInCents) {
        if (amountInCents == null) {
            return "unknown";
        }
        return BigDecimal.valueOf(amountInCents, 2).toPlainString();
    }

    private String truncate(String value, int limit) {
        if (StrUtil.isBlank(value)) {
            return "No text content";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    @FunctionalInterface
    private interface ToolSupplier {
        String get() throws Exception;
    }
}
