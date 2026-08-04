package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.ai.AiToolExecution;
import com.hmdp.ai.AiTraceContext;
import com.hmdp.ai.AiTraceIds;
import com.hmdp.ai.AiTraceSpanScope;
import com.hmdp.ai.ShopKnowledge;
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

    private static final int MAX_CANDIDATE_SHOPS = 3;
    private static final int MAX_BLOG_RESULTS = 6;
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

    @Override
    public List<AiToolExecution> executeRelevantTools(Long conversationId, Long userId, String question,
                                                       Double x, Double y, List<ShopKnowledge> retrievedShops) {
        return executeRelevantTools(null, conversationId, userId, question, x, y, retrievedShops);
    }

    @Override
    public List<AiToolExecution> executeRelevantTools(AiTraceContext traceContext,
                                                       Long conversationId, Long userId, String question,
                                                       Double x, Double y, List<ShopKnowledge> retrievedShops) {
        List<String> toolNames = new ArrayList<>();
        toolNames.add("shopDetail");
        if (containsVoucherIntent(question)) {
            toolNames.add("voucherQuery");
        }
        if (containsBlogIntent(question)) {
            toolNames.add("blogSearch");
        }
        if (x != null && y != null && containsNearbyIntent(question)) {
            toolNames.add("nearbyShopSearch");
        }
        return executeTools(traceContext, conversationId, userId, toolNames, x, y, retrievedShops);
    }

    @Override
    public boolean shouldUseDirectToolRouting(String question, Double x, Double y) {
        if (StrUtil.isBlank(question)) {
            return false;
        }
        return containsVoucherIntent(question)
                || containsBlogIntent(question)
                || (x != null && y != null && containsNearbyIntent(question))
                || containsExplicitShopIntent(question)
                || containsShopDetailIntent(question);
    }

    @Override
    public List<AiToolExecution> executeTools(Long conversationId, Long userId, List<String> toolNames,
                                              Double x, Double y, List<ShopKnowledge> retrievedShops) {
        return executeTools(null, conversationId, userId, toolNames, x, y, retrievedShops);
    }

    @Override
    public List<AiToolExecution> executeTools(AiTraceContext traceContext,
                                              Long conversationId, Long userId, List<String> toolNames,
                                              Double x, Double y, List<ShopKnowledge> retrievedShops) {
        if (toolNames == null || toolNames.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> shopIds = candidateShopIds(retrievedShops);
        List<AiToolExecution> executions = new ArrayList<>();
        for (String toolName : new LinkedHashSet<>(toolNames)) {
            if (!SUPPORTED_TOOLS.contains(toolName)) {
                continue;
            }
            if ("shopDetail".equals(toolName) && !shopIds.isEmpty()) {
                addIfPresent(executions, invoke(traceContext, conversationId, userId, toolName,
                        "shopIds=" + shopIds, () -> queryShopDetails(shopIds)));
            }
            if ("voucherQuery".equals(toolName) && !shopIds.isEmpty()) {
                addIfPresent(executions, invoke(traceContext, conversationId, userId, toolName,
                        "shopIds=" + shopIds, () -> queryCurrentVouchers(shopIds)));
            }
            if ("blogSearch".equals(toolName) && !shopIds.isEmpty()) {
                addIfPresent(executions, invoke(traceContext, conversationId, userId, toolName,
                        "shopIds=" + shopIds, () -> queryPopularBlogs(shopIds)));
            }
            if ("nearbyShopSearch".equals(toolName) && x != null && y != null) {
                Integer typeId = firstCandidateTypeId(retrievedShops);
                if (typeId != null) {
                    addIfPresent(executions, invoke(traceContext, conversationId, userId, toolName,
                            "typeId=" + typeId + ",locationProvided=true",
                            () -> queryNearbyShops(typeId, x, y)));
                }
            }
        }
        return executions;
    }

    @Override
    public List<String> supportedToolNames() {
        return SUPPORTED_TOOLS;
    }

    private List<Long> candidateShopIds(List<ShopKnowledge> retrievedShops) {
        if (retrievedShops == null || retrievedShops.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (ShopKnowledge shop : retrievedShops) {
            if (shop.getShopId() != null) {
                ids.add(shop.getShopId());
            }
            if (ids.size() == MAX_CANDIDATE_SHOPS) {
                break;
            }
        }
        return new ArrayList<>(ids);
    }

    private String queryShopDetails(List<Long> shopIds) {
        Map<Long, Shop> shopsById = new HashMap<>();
        for (Shop shop : shopService.listByIds(shopIds)) {
            shopsById.put(shop.getId(), shop);
        }
        StringBuilder result = new StringBuilder("Live store details:\n");
        for (Long shopId : shopIds) {
            Shop shop = shopsById.get(shopId);
            if (shop != null) {
                result.append("- ").append(formatShop(shop)).append('\n');
            }
        }
        return result.toString();
    }

    private String queryCurrentVouchers(List<Long> shopIds) {
        Set<Long> shopIdSet = new LinkedHashSet<>(shopIds);
        LocalDateTime now = LocalDateTime.now();
        List<Voucher> vouchers = voucherService.listEnabledVouchersForKnowledge().stream()
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
    private String queryNearbyShops(Integer typeId, Double x, Double y) {
        Result result = shopService.queryShopByType(typeId, 1, x, y);
        if (!(result.getData() instanceof List)) {
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
            text.append("- ").append(formatShop((Shop) value)).append('\n');
            count++;
            if (count == MAX_CANDIDATE_SHOPS) {
                break;
            }
        }
        return count == 0
                ? "Live nearby-store query: no indexed store was found within the current 50 km search range. The browser location was received successfully."
                : text.toString();
    }

    private Integer firstCandidateTypeId(List<ShopKnowledge> retrievedShops) {
        if (retrievedShops == null || retrievedShops.isEmpty()) {
            return null;
        }
        Object value = retrievedShops.get(0).getPayload().get("typeId");
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private AiToolExecution invoke(AiTraceContext traceContext,
                                   Long conversationId, Long userId, String toolName,
                                   String requestContent, ToolSupplier supplier) {
        String toolCallId = AiTraceIds.toolCallId();
        Map<String, Object> spanAttributes = new HashMap<>();
        spanAttributes.put("toolName", toolName);
        spanAttributes.put("toolCallId", toolCallId);
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
            return null;
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

    private boolean containsVoucherIntent(String question) {
        return containsAny(question, "coupon", "voucher", "discount", "deal", "\u4f18\u60e0", "\u4ee3\u91d1", "\u5238", "\u62a2", "\u79d2\u6740");
    }

    private boolean containsBlogIntent(String question) {
        return containsAny(question, "blog", "review", "note", "\u7b14\u8bb0", "\u535a\u5ba2", "\u63a2\u5e97", "\u8bc4\u4ef7", "\u53e3\u7891", "\u8bc4\u8bba");
    }

    private boolean containsNearbyIntent(String question) {
        return containsAny(question, "nearby", "near me", "distance", "\u9644\u8fd1", "\u8ddd\u79bb", "\u5468\u8fb9", "\u8fd1");
    }

    private boolean containsExplicitShopIntent(String question) {
        return question.matches("(?s).*?(?:shop|store|\u5e97\u94fa|\u5546\u5bb6|\u9910\u5385|\u5e97)\\s*#?\\d+.*");
    }

    private boolean containsShopDetailIntent(String question) {
        return containsAny(question, "shop detail", "opening hours", "address", "average spend", "rating",
                "\u5e97\u94fa\u8be6\u60c5", "\u8425\u4e1a\u65f6\u95f4", "\u8425\u4e1a\u5230",
                "\u51e0\u70b9\u5173\u95e8", "\u4ec0\u4e48\u65f6\u5019\u5173\u95e8", "\u5f00\u5230\u51e0\u70b9",
                "\u6253\u70ca", "\u5468\u672b\u8425\u4e1a", "\u5468\u672b\u5f00\u95e8", "\u4eca\u5929\u8425\u4e1a",
                "\u662f\u5426\u8425\u4e1a", "\u8425\u4e1a\u5417", "\u5f00\u7740\u5417", "\u5730\u5740", "\u4eba\u5747", "\u8bc4\u5206");
    }

    private boolean containsAny(String value, String... keywords) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        String lowercase = value.toLowerCase();
        for (String keyword : keywords) {
            if (lowercase.contains(keyword)) {
                return true;
            }
        }
        return false;
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
