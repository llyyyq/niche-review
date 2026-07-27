package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.ai.EmbeddingModelClient;
import com.hmdp.ai.QdrantKnowledgeClient;
import com.hmdp.ai.ShopKnowledge;
import com.hmdp.config.AiEmbeddingProperties;
import com.hmdp.config.AiKnowledgeProperties;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopKnowledgeService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ShopKnowledgeServiceImpl implements IShopKnowledgeService {

    private static final int EMBEDDING_BATCH_SIZE = 10;
    private static final int MAX_BLOGS_PER_SHOP = 3;
    private static final int MAX_BLOG_CONTENT_LENGTH = 240;
    private static final Pattern BUDGET_PATTERN = Pattern.compile(
            "(?:\\u4eba\\u5747|\\u9884\\u7b97)\\s*"
                    + "(?:\\u4e0d\\u8d85\\u8fc7|\\u4e0d\\u9ad8\\u4e8e|\\u4ee5\\u5185|"
                    + "\\u4f4e\\u4e8e|\\u5c11\\u4e8e|\\u7ea6|\\u5927\\u7ea6)?\\s*(\\d{1,5})"
    );

    @Resource
    private IShopService shopService;

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IBlogService blogService;

    @Resource
    private EmbeddingModelClient embeddingModelClient;

    @Resource
    private QdrantKnowledgeClient qdrantKnowledgeClient;

    @Resource
    private AiEmbeddingProperties embeddingProperties;

    @Resource
    private AiKnowledgeProperties knowledgeProperties;

    @Override
    public int rebuildShopKnowledge() {
        List<Shop> shops = shopService.list();
        Map<Long, String> typeNames = shopTypeNames();
        Map<Long, List<Voucher>> vouchersByShop = enabledVouchersByShop();
        Map<Long, List<Blog>> blogsByShop = blogsByShop();
        try {
            qdrantKnowledgeClient.recreateCollection(
                    knowledgeProperties.getShopCollection(),
                    embeddingProperties.getDimension()
            );
            for (int start = 0; start < shops.size(); start += EMBEDDING_BATCH_SIZE) {
                int end = Math.min(start + EMBEDDING_BATCH_SIZE, shops.size());
                writeBatch(shops.subList(start, end), typeNames, vouchersByShop, blogsByShop);
            }
            log.info("Shop knowledge rebuild completed, shopCount={}, enabledVoucherCount={}, blogCount={}",
                    shops.size(), countValues(vouchersByShop), countValues(blogsByShop));
            return shops.size();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to rebuild shop knowledge", e);
        }
    }

    @Override
    public void syncShopKnowledge(Long shopId) {
        if (shopId == null) {
            return;
        }
        try {
            Shop shop = shopService.getById(shopId);
            if (shop == null) {
                qdrantKnowledgeClient.deletePoint(knowledgeProperties.getShopCollection(), shopId);
                log.info("Shop knowledge point deleted, shopId={}", shopId);
                return;
            }
            qdrantKnowledgeClient.ensureCollection(
                    knowledgeProperties.getShopCollection(), embeddingProperties.getDimension());
            Map<Long, String> typeNames = shopTypeNames();
            Map<Long, List<Voucher>> vouchersByShop = enabledVouchersByShop();
            Map<Long, List<Blog>> blogsByShop = blogsByShop();
            writeBatch(Collections.singletonList(shop), typeNames, vouchersByShop, blogsByShop);
            log.info("Shop knowledge point synchronized, shopId={}", shopId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to synchronize shop knowledge, shopId=" + shopId, e);
        }
    }

    @Override
    public List<ShopKnowledge> searchRelevantShops(String question) {
        return searchRelevantShops(question, Boolean.TRUE.equals(knowledgeProperties.getKeywordFallbackEnabled()));
    }

    @Override
    public List<ShopKnowledge> searchRelevantShops(String question, boolean keywordFallbackEnabled) {
        if (StrUtil.isBlank(question)) {
            return Collections.emptyList();
        }
        try {
            List<List<Float>> vectors = embeddingModelClient.embed(Collections.singletonList(question));
            List<QdrantKnowledgeClient.QdrantSearchResult> results = qdrantKnowledgeClient.search(
                    knowledgeProperties.getShopCollection(), vectors.get(0), knowledgeProperties.getRetrieveLimit());
            List<ShopKnowledge> vectorShops = new ArrayList<>(results.size());
            for (QdrantKnowledgeClient.QdrantSearchResult result : results) {
                Map<String, Object> payload = result.getPayload();
                Object content = payload.get("content");
                vectorShops.add(new ShopKnowledge(result.getId(), content == null ? "" : String.valueOf(content),
                        result.getScore(), payload));
            }
            if (!keywordFallbackEnabled) {
                return vectorShops;
            }

            RetrievalContext context = buildRetrievalContext(question);
            if (context.explicitUnknownShop) {
                log.info("RAG retrieval rejected an unknown explicit shop query, questionLength={}",
                        question.length());
                return Collections.emptyList();
            }

            List<ShopKnowledge> constrainedVectorShops = applyStructuredConstraints(vectorShops, context);
            List<ShopKnowledge> keywordShops = keywordFallback(context);
            if (!keywordShops.isEmpty()) {
                List<ShopKnowledge> merged = mergeKnowledge(keywordShops, constrainedVectorShops);
                log.info("Hybrid retrieval merged keyword and vector results, questionLength={}, "
                                + "vectorResultCount={}, keywordResultCount={}, mergedResultCount={}",
                        question.length(), constrainedVectorShops.size(), keywordShops.size(), merged.size());
                return merged;
            }

            return isReliableVectorResult(constrainedVectorShops)
                    ? constrainedVectorShops
                    : Collections.emptyList();
        } catch (Exception e) {
            // Retrieval is an enhancement. A temporary vector/embedding failure must not stop the chat service.
            log.warn("Vector retrieval skipped, attempting keyword fallback: {}", e.getMessage());
            return keywordFallback(question, keywordFallbackEnabled);
        }
    }

    private boolean isReliableVectorResult(List<ShopKnowledge> shops) {
        if (shops.isEmpty()) {
            return false;
        }
        Double minScore = knowledgeProperties.getVectorMinScore();
        if (minScore == null) {
            return true;
        }
        Double topScore = shops.get(0).getScore();
        return topScore != null && topScore >= minScore;
    }

    private List<ShopKnowledge> keywordFallback(String question, boolean keywordFallbackEnabled) {
        if (!keywordFallbackEnabled || StrUtil.isBlank(question)) {
            return Collections.emptyList();
        }
        RetrievalContext context = buildRetrievalContext(question);
        if (context.explicitUnknownShop) {
            return Collections.emptyList();
        }
        return keywordFallback(context);
    }

    private List<ShopKnowledge> keywordFallback(RetrievalContext context) {
        Map<Long, String> typeNames = shopTypeNames();
        Map<Long, List<Voucher>> vouchersByShop = enabledVouchersByShop();
        Map<Long, List<Blog>> blogsByShop = blogsByShop();
        List<Shop> shops = new ArrayList<>(context.shops);
        shops.sort((left, right) -> Integer.compare(
                keywordScore(right, typeNames.get(right.getTypeId()), context),
                keywordScore(left, typeNames.get(left.getTypeId()), context)
        ));
        int limit = Math.max(1, knowledgeProperties.getKeywordFallbackLimit());
        List<ShopKnowledge> matches = new ArrayList<>();
        for (Shop shop : shops) {
            String typeName = typeNames.get(shop.getTypeId());
            int score = keywordScore(shop, typeName, context);
            if (score <= 0) {
                continue;
            }
            String content = toKnowledgeText(shop, typeName, vouchersByShop.get(shop.getId()), blogsByShop.get(shop.getId()));
            Map<String, Object> payload = toPayload(shop, typeName, content);
            payload.put("retrievalSource", "keyword-fallback");
            payload.put("keywordScore", score);
            matches.add(new ShopKnowledge(shop.getId(), content, 0D, payload));
            if (matches.size() >= limit) {
                break;
            }
        }
        return matches;
    }

    private int keywordScore(Shop shop, String typeName, RetrievalContext context) {
        if (!context.matchedShopIds.isEmpty() && !context.matchedShopIds.contains(shop.getId())) {
            return 0;
        }
        if (context.maxAveragePrice != null
                && (shop.getAvgPrice() == null || shop.getAvgPrice() > context.maxAveragePrice)) {
            return 0;
        }
        int score = 0;
        score += shopNameMatchScore(context.normalizedQuestion, shop.getName());
        score += matchScore(context.normalizedQuestion, typeName, 8);
        score += matchScore(context.normalizedQuestion, shop.getArea(), 5);
        score += matchScore(context.normalizedQuestion, shop.getAddress(), 3);
        if (context.foodIntent && isFoodShop(shop, typeName)) {
            score += 8;
        }
        if (context.ktvIntent && isKtvShop(shop, typeName)) {
            score += 8;
        }
        if (context.maxAveragePrice != null) {
            score += 2;
        }
        return score;
    }

    private int shopNameMatchScore(String normalizedQuestion, String shopName) {
        if (StrUtil.isBlank(shopName)) {
            return 0;
        }
        int explicitMatchScore = explicitShopNameMatchScore(normalizedQuestion, shopName);
        if (explicitMatchScore > 0) {
            return explicitMatchScore;
        }
        int overlapLength = longestCommonSubstringLength(normalizedQuestion, normalize(shopName));
        if (overlapLength >= 4) {
            return 8;
        }
        if (overlapLength == 3) {
            return 6;
        }
        return overlapLength == 2 ? 4 : 0;
    }

    private int explicitShopNameMatchScore(String normalizedQuestion, String shopName) {
        String normalizedName = normalize(shopName);
        if (normalizedQuestion.contains(normalizedName)) {
            return 12;
        }
        String coreName = normalize(stripShopBranch(shopName));
        if (coreName.length() >= 3 && normalizedQuestion.contains(coreName)) {
            return 11;
        }
        String prefix = leadingAlias(coreName);
        return prefix.length() >= 3 && normalizedQuestion.contains(prefix) ? 9 : 0;
    }

    private int matchScore(String normalizedQuestion, String value, int weight) {
        if (StrUtil.isBlank(value)) {
            return 0;
        }
        String normalizedValue = normalize(value);
        if (normalizedQuestion.contains(normalizedValue)) {
            return weight;
        }
        if (normalizedValue.length() >= 2 && normalizedValue.contains(normalizedQuestion)) {
            return 1;
        }
        int overlapLength = longestCommonSubstringLength(normalizedQuestion, normalizedValue);
        if (overlapLength >= 4) {
            return Math.max(2, weight - 1);
        }
        if (overlapLength == 3) {
            return Math.max(2, weight / 2);
        }
        return overlapLength == 2 ? Math.max(1, weight / 3) : 0;
    }

    /**
     * Chinese queries do not contain spaces reliably. A shared contiguous phrase
     * is a lightweight language-agnostic signal for exact business terms.
     */
    private int longestCommonSubstringLength(String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)) {
            return 0;
        }
        int[] previous = new int[right.length() + 1];
        int longest = 0;
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            int[] current = new int[right.length() + 1];
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                if (left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1)) {
                    current[rightIndex] = previous[rightIndex - 1] + 1;
                    longest = Math.max(longest, current[rightIndex]);
                }
            }
            previous = current;
        }
        return longest;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[\\s\\p{P}\\p{S}]", "");
    }

    private RetrievalContext buildRetrievalContext(String question) {
        List<Shop> shops = shopService.list();
        String normalizedQuestion = normalize(question);
        Set<Long> matchedShopIds = new LinkedHashSet<>();
        for (Shop shop : shops) {
            if (explicitShopNameMatchScore(normalizedQuestion, shop.getName()) > 0) {
                matchedShopIds.add(shop.getId());
            }
        }
        Long maxAveragePrice = parseMaxAveragePrice(question);
        boolean foodIntent = containsAny(normalizedQuestion,
                "\u7f8e\u98df", "\u9910\u5385", "\u9910\u9986", "\u5403\u996d",
                "\u805a\u9910", "\u706b\u9505", "\u5bff\u53f8", "\u70e4\u8089",
                "\u6dae\u9505", "\u9910\u996e", "\u591c\u5bb5", "\u6cd5\u9910");
        boolean ktvIntent = containsAny(normalizedQuestion,
                "ktv", "\u5531\u6b4c", "\u5531k", "\u6b4c\u5385");
        boolean explicitUnknownShop = matchedShopIds.isEmpty()
                && looksLikeExplicitUnknownShop(normalizedQuestion);
        return new RetrievalContext(shops, normalizedQuestion, matchedShopIds,
                maxAveragePrice, foodIntent, ktvIntent, explicitUnknownShop);
    }

    private List<ShopKnowledge> applyStructuredConstraints(List<ShopKnowledge> vectorShops,
                                                            RetrievalContext context) {
        if (vectorShops.isEmpty()) {
            return vectorShops;
        }
        List<ShopKnowledge> filtered = new ArrayList<>();
        for (ShopKnowledge knowledge : vectorShops) {
            if (!context.matchedShopIds.isEmpty()
                    && !context.matchedShopIds.contains(knowledge.getShopId())) {
                continue;
            }
            if (context.maxAveragePrice != null) {
                Object avgPrice = knowledge.getPayload().get("avgPrice");
                if (!(avgPrice instanceof Number)
                        || ((Number) avgPrice).longValue() > context.maxAveragePrice) {
                    continue;
                }
            }
            filtered.add(knowledge);
        }
        return filtered;
    }

    private Long parseMaxAveragePrice(String question) {
        Matcher matcher = BUDGET_PATTERN.matcher(question);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }

    private boolean looksLikeExplicitUnknownShop(String normalizedQuestion) {
        boolean hasEntity = containsAny(normalizedQuestion,
                "\u9910\u5385", "\u8336\u9910\u5385", "\u5496\u5561\u9986", "ktv",
                "\u6cd5\u9910", "\u706b\u9505\u5e97");
        if (!hasEntity) {
            return false;
        }
        if (containsAny(normalizedQuestion,
                "\u54ea\u5bb6\u5e97", "\u54ea\u4e9b\u5e97", "\u4ec0\u4e48\u5e97",
                "\u6709\u4ec0\u4e48\u9910\u5385", "\u54ea\u4e9bktv",
                "\u6709\u4ec0\u4e48ktv", "\u6709\u4ec0\u4e48\u706b\u9505",
                "\u63a8\u8350")) {
            return false;
        }
        boolean factIntent = containsAny(normalizedQuestion,
                "\u4f18\u60e0", "\u4ee3\u91d1\u5238", "\u8425\u4e1a", "\u51e0\u70b9",
                "\u5173\u95e8", "\u5728\u54ea\u91cc", "\u5730\u5740", "\u63a2\u5e97",
                "\u516c\u5f00\u7b14\u8bb0", "\u8bc4\u5206", "\u4eba\u5747\u591a\u5c11",
                "\u80fd\u7528", "\u6709\u6548\u671f");
        boolean locationAssertion = normalizedQuestion.contains("\u5728")
                && normalizedQuestion.endsWith("\u5417");
        return factIntent || locationAssertion;
    }

    private boolean isFoodShop(Shop shop, String typeName) {
        return containsAny(normalize(typeName), "\u7f8e\u98df", "\u9910\u996e")
                || containsAny(normalize(shop.getName()),
                "\u9910\u5385", "\u706b\u9505", "\u5bff\u53f8", "\u6dae\u9505",
                "\u8336\u9910\u5385", "\u70e4\u8089");
    }

    private boolean isKtvShop(Shop shop, String typeName) {
        return containsAny(normalize(typeName), "ktv", "\u5a31\u4e50")
                || normalize(shop.getName()).contains("ktv");
    }

    private String stripShopBranch(String shopName) {
        return shopName == null ? "" : shopName.replaceFirst("[\\(\\uff08].*$", "");
    }

    private String leadingAlias(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        return value.substring(0, Math.min(3, value.length()));
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<ShopKnowledge> mergeKnowledge(List<ShopKnowledge> first, List<ShopKnowledge> second) {
        Map<Long, ShopKnowledge> merged = new java.util.LinkedHashMap<>();
        for (ShopKnowledge shop : first) {
            merged.put(shop.getShopId(), shop);
        }
        for (ShopKnowledge shop : second) {
            merged.putIfAbsent(shop.getShopId(), shop);
        }
        int limit = Math.max(1, knowledgeProperties.getRetrieveLimit());
        return merged.values().stream().limit(limit).collect(Collectors.toList());
    }

    private void writeBatch(List<Shop> shops, Map<Long, String> typeNames,
                            Map<Long, List<Voucher>> vouchersByShop, Map<Long, List<Blog>> blogsByShop) throws Exception {
        List<String> documents = new ArrayList<>(shops.size());
        for (Shop shop : shops) {
            documents.add(toKnowledgeText(shop, typeNames.get(shop.getTypeId()),
                    vouchersByShop.get(shop.getId()), blogsByShop.get(shop.getId())));
        }
        List<List<Float>> vectors = embeddingModelClient.embed(documents);
        List<QdrantKnowledgeClient.QdrantPoint> points = new ArrayList<>(shops.size());
        for (int i = 0; i < shops.size(); i++) {
            Shop shop = shops.get(i);
            points.add(new QdrantKnowledgeClient.QdrantPoint(shop.getId(), vectors.get(i),
                    toPayload(shop, typeNames.get(shop.getTypeId()), documents.get(i))));
        }
        qdrantKnowledgeClient.upsert(knowledgeProperties.getShopCollection(), points);
    }

    private Map<Long, String> shopTypeNames() {
        Map<Long, String> names = new HashMap<>();
        for (ShopType type : shopTypeService.list()) {
            names.put(type.getId(), type.getName());
        }
        return names;
    }

    private Map<Long, List<Voucher>> enabledVouchersByShop() {
        LocalDateTime now = LocalDateTime.now();
        return voucherService.listEnabledVouchersForKnowledge().stream()
                .filter(voucher -> voucher.getShopId() != null)
                .filter(voucher -> isAvailableNow(voucher, now))
                .collect(Collectors.groupingBy(Voucher::getShopId));
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

    private Map<Long, List<Blog>> blogsByShop() {
        return blogService.list().stream()
                .filter(blog -> blog.getShopId() != null)
                .collect(Collectors.groupingBy(Blog::getShopId));
    }

    private String toKnowledgeText(Shop shop, String typeName, List<Voucher> vouchers, List<Blog> blogs) {
        StringBuilder text = new StringBuilder();
        appendLine(text, "Store ID", shop.getId());
        appendLine(text, "Store name", shop.getName());
        appendLine(text, "Category", typeName);
        appendLine(text, "Business area", shop.getArea());
        appendLine(text, "Address", shop.getAddress());
        appendLine(text, "Average spend", shop.getAvgPrice() == null ? null : shop.getAvgPrice() + " CNY");
        appendLine(text, "Rating", shop.getScore() == null ? null : shop.getScore() / 10.0 + "/5");
        appendLine(text, "Sales", shop.getSold());
        appendLine(text, "Review count", shop.getComments());
        appendLine(text, "Opening hours", shop.getOpenHours());
        appendVoucherSection(text, vouchers);
        appendBlogSection(text, blogs);
        return text.toString();
    }

    private void appendVoucherSection(StringBuilder text, List<Voucher> vouchers) {
        if (vouchers == null || vouchers.isEmpty()) {
            return;
        }
        text.append("Available vouchers:\n");
        for (Voucher voucher : vouchers) {
            text.append("- ").append(voucher.getTitle());
            if (voucher.getPayValue() != null || voucher.getActualValue() != null) {
                text.append(" (pay ").append(valueOrUnknown(voucher.getPayValue()))
                        .append(" CNY, value ").append(valueOrUnknown(voucher.getActualValue())).append(" CNY)");
            }
            if (StrUtil.isNotBlank(voucher.getRules())) {
                text.append(", rules: ").append(voucher.getRules().replaceAll("\\s+", " "));
            }
            if (voucher.getEndTime() != null) {
                text.append(", valid until: ").append(voucher.getEndTime());
            }
            text.append('\n');
        }
    }

    private void appendBlogSection(StringBuilder text, List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return;
        }
        text.append("Popular public reviews:\n");
        blogs.stream()
                .sorted(Comparator.comparing(Blog::getLiked, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_BLOGS_PER_SHOP)
                .forEach(blog -> {
                    text.append("- ");
                    if (StrUtil.isNotBlank(blog.getTitle())) {
                        text.append(blog.getTitle()).append(": ");
                    }
                    text.append(truncate(blog.getContent(), MAX_BLOG_CONTENT_LENGTH));
                    if (blog.getLiked() != null) {
                        text.append(" (likes: ").append(blog.getLiked()).append(')');
                    }
                    text.append('\n');
                });
    }

    private void appendLine(StringBuilder text, String label, Object value) {
        if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
            text.append(label).append(": ").append(value).append('\n');
        }
    }

    private Object valueOrUnknown(Object value) {
        return value == null ? "unknown" : value;
    }

    private String truncate(String value, int limit) {
        if (StrUtil.isBlank(value)) {
            return "No text content";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private int countValues(Map<Long, ? extends List<?>> groupedValues) {
        int count = 0;
        for (List<?> values : groupedValues.values()) {
            count += values.size();
        }
        return count;
    }

    private Map<String, Object> toPayload(Shop shop, String typeName, String content) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("shopId", shop.getId());
        payload.put("name", shop.getName());
        payload.put("typeId", shop.getTypeId());
        payload.put("typeName", typeName);
        payload.put("area", shop.getArea());
        payload.put("address", shop.getAddress());
        payload.put("avgPrice", shop.getAvgPrice());
        payload.put("score", shop.getScore());
        payload.put("openHours", shop.getOpenHours());
        payload.put("content", content);
        return payload;
    }

    private static class RetrievalContext {

        private final List<Shop> shops;
        private final String normalizedQuestion;
        private final Set<Long> matchedShopIds;
        private final Long maxAveragePrice;
        private final boolean foodIntent;
        private final boolean ktvIntent;
        private final boolean explicitUnknownShop;

        private RetrievalContext(List<Shop> shops, String normalizedQuestion, Set<Long> matchedShopIds,
                                 Long maxAveragePrice, boolean foodIntent, boolean ktvIntent,
                                 boolean explicitUnknownShop) {
            this.shops = shops;
            this.normalizedQuestion = normalizedQuestion;
            this.matchedShopIds = matchedShopIds;
            this.maxAveragePrice = maxAveragePrice;
            this.foodIntent = foodIntent;
            this.ktvIntent = ktvIntent;
            this.explicitUnknownShop = explicitUnknownShop;
        }
    }
}
