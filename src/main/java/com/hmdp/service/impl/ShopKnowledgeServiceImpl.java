package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.ai.EmbeddingModelClient;
import com.hmdp.ai.AiTraceContext;
import com.hmdp.ai.AiTraceSpanScope;
import com.hmdp.ai.QdrantKnowledgeClient;
import com.hmdp.ai.ShopKnowledge;
import com.hmdp.config.AiEmbeddingProperties;
import com.hmdp.config.AiKnowledgeProperties;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopKnowledgeService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IAiTraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ShopKnowledgeServiceImpl implements IShopKnowledgeService {

    private static final int EMBEDDING_BATCH_SIZE = 10;
    private static final int MAX_BLOG_CONTENT_LENGTH = 800;
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
    private IBlogService blogService;

    @Resource
    private EmbeddingModelClient embeddingModelClient;

    @Resource
    private QdrantKnowledgeClient qdrantKnowledgeClient;

    @Resource
    private AiEmbeddingProperties embeddingProperties;

    @Resource
    private AiKnowledgeProperties knowledgeProperties;

    @Resource
    private IAiTraceService aiTraceService;

    @Override
    public int rebuildShopKnowledge() {
        List<Shop> shops = shopService.list();
        Map<Long, String> typeNames = shopTypeNames();
        Map<Long, Shop> shopsById = shops.stream()
                .collect(Collectors.toMap(Shop::getId, shop -> shop));
        List<Blog> blogs = blogService.list().stream()
                .filter(blog -> blog.getShopId() != null && shopsById.containsKey(blog.getShopId()))
                .collect(Collectors.toList());
        try {
            qdrantKnowledgeClient.recreateCollection(
                    knowledgeProperties.getShopCollection(),
                    embeddingProperties.getDimension()
            );
            qdrantKnowledgeClient.recreateCollection(
                    knowledgeProperties.getBlogCollection(),
                    embeddingProperties.getDimension()
            );
            for (int start = 0; start < shops.size(); start += EMBEDDING_BATCH_SIZE) {
                int end = Math.min(start + EMBEDDING_BATCH_SIZE, shops.size());
                writeShopProfileBatch(shops.subList(start, end), typeNames);
            }
            for (int start = 0; start < blogs.size(); start += EMBEDDING_BATCH_SIZE) {
                int end = Math.min(start + EMBEDDING_BATCH_SIZE, blogs.size());
                writeBlogBatch(blogs.subList(start, end), shopsById, typeNames);
            }
            log.info("Shop knowledge rebuild completed, shopCount={}, blogCount={}", shops.size(), blogs.size());
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
                qdrantKnowledgeClient.deleteByShopId(knowledgeProperties.getBlogCollection(), shopId);
                log.info("Shop knowledge point deleted, shopId={}", shopId);
                return;
            }
            qdrantKnowledgeClient.ensureCollection(
                    knowledgeProperties.getShopCollection(), embeddingProperties.getDimension());
            qdrantKnowledgeClient.ensureCollection(
                    knowledgeProperties.getBlogCollection(), embeddingProperties.getDimension());
            Map<Long, String> typeNames = shopTypeNames();
            writeShopProfileBatch(Collections.singletonList(shop), typeNames);
            qdrantKnowledgeClient.deleteByShopId(knowledgeProperties.getBlogCollection(), shopId);
            List<Blog> blogs = blogService.query().eq("shop_id", shopId).list();
            writeBlogBatch(blogs, Collections.singletonMap(shopId, shop), typeNames);
            log.info("Shop knowledge point synchronized, shopId={}", shopId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to synchronize shop knowledge, shopId=" + shopId, e);
        }
    }

    @Override
    public List<ShopKnowledge> searchRelevantShops(String question) {
        return searchRelevantShops(Collections.singletonList(question));
    }

    @Override
    public List<ShopKnowledge> searchRelevantShops(List<String> questions) {
        return searchRelevantShops(null, questions,
                Boolean.TRUE.equals(knowledgeProperties.getKeywordFallbackEnabled()));
    }

    @Override
    public List<ShopKnowledge> searchRelevantShops(AiTraceContext traceContext, List<String> questions) {
        return searchRelevantShops(traceContext, questions,
                Boolean.TRUE.equals(knowledgeProperties.getKeywordFallbackEnabled()));
    }

    @Override
    public List<ShopKnowledge> searchRelevantShops(String question, boolean keywordFallbackEnabled) {
        return searchRelevantShops(Collections.singletonList(question), keywordFallbackEnabled);
    }

    @Override
    public List<ShopKnowledge> searchRelevantShops(List<String> questions, boolean keywordFallbackEnabled) {
        return searchRelevantShops(null, questions, keywordFallbackEnabled);
    }

    private List<ShopKnowledge> searchRelevantShops(AiTraceContext traceContext, List<String> questions,
                                                     boolean keywordFallbackEnabled) {
        List<String> validQuestions = validQuestions(questions);
        if (validQuestions.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> retrievalAttributes = new LinkedHashMap<>();
        retrievalAttributes.put("queryCount", validQuestions.size());
        retrievalAttributes.put("keywordFallbackEnabled", keywordFallbackEnabled);
        AiTraceSpanScope retrievalSpan = startSpan(traceContext, "RETRIEVAL", retrievalAttributes);
        AiTraceContext retrievalContext = contextOf(retrievalSpan, traceContext);
        try {
            AiTraceSpanScope embeddingSpan = startSpan(retrievalContext, "EMBEDDING",
                    Collections.<String, Object>singletonMap("queryCount", validQuestions.size()));
            List<List<Float>> vectors;
            try {
                vectors = embeddingModelClient.embed(validQuestions);
                success(embeddingSpan, Collections.<String, Object>singletonMap("vectorCount", vectors.size()));
            } catch (Exception e) {
                failure(embeddingSpan, e);
                throw e;
            }
            if (vectors.size() != validQuestions.size()) {
                throw new IllegalStateException("Embedding count does not match query count");
            }
            List<List<ShopKnowledge>> resultGroups = new ArrayList<>(validQuestions.size());
            for (int index = 0; index < validQuestions.size(); index++) {
                resultGroups.add(searchSingleQuery(
                        retrievalContext,
                        validQuestions.get(index),
                        vectors.get(index),
                        keywordFallbackEnabled
                ));
            }
            List<ShopKnowledge> merged = mergeWithTrace(retrievalContext, resultGroups);
            success(retrievalSpan, retrievalResultAttributes(
                    merged, false, validQuestions.size(), keywordFallbackEnabled));
            return merged;
        } catch (Exception e) {
            // Retrieval is an enhancement. A temporary vector/embedding failure must not stop the chat service.
            log.warn("Vector retrieval skipped, attempting keyword fallback: {}", e.getMessage());
            List<List<ShopKnowledge>> fallbackGroups = new ArrayList<>(validQuestions.size());
            for (String question : validQuestions) {
                AiTraceSpanScope keywordSpan = startSpan(retrievalContext, "KEYWORD_SEARCH",
                        Collections.<String, Object>singletonMap("reason", "vectorFailure"));
                try {
                    List<ShopKnowledge> fallback = keywordFallback(question, keywordFallbackEnabled);
                    fallbackGroups.add(fallback);
                    success(keywordSpan, Collections.<String, Object>singletonMap("resultCount", fallback.size()));
                } catch (RuntimeException fallbackError) {
                    failure(keywordSpan, fallbackError);
                    throw fallbackError;
                }
            }
            List<ShopKnowledge> merged = mergeWithTrace(retrievalContext, fallbackGroups);
            success(retrievalSpan, retrievalResultAttributes(
                    merged, true, validQuestions.size(), keywordFallbackEnabled));
            return merged;
        }
    }

    private List<ShopKnowledge> searchSingleQuery(AiTraceContext traceContext,
                                                   String question, List<Float> vector,
                                                   boolean keywordFallbackEnabled) throws Exception {
        RetrievalContext context = buildRetrievalContext(question);
        AiTraceSpanScope vectorSpan = startSpan(traceContext, "QDRANT_SEARCH");
        List<ShopKnowledge> vectorShops;
        try {
            int candidateLimit = vectorCandidateLimit();
            QdrantKnowledgeClient.QdrantFilter filter = toQdrantFilter(context);
            List<QdrantKnowledgeClient.QdrantSearchResult> profileResults = qdrantKnowledgeClient.search(
                    knowledgeProperties.getShopCollection(), vector, candidateLimit, filter);
            List<QdrantKnowledgeClient.QdrantSearchResult> blogResults = searchBlogKnowledge(vector, candidateLimit, filter);
            vectorShops = mergeVectorCandidates(profileResults, blogResults);
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("profileResultCount", profileResults.size());
            attributes.put("blogResultCount", blogResults.size());
            attributes.put("candidateShopCount", vectorShops.size());
            success(vectorSpan, attributes);
        } catch (Exception e) {
            failure(vectorSpan, e);
            throw e;
        }
        if (!keywordFallbackEnabled) {
            return limitKnowledge(vectorShops);
        }

        if (context.explicitUnknownShop) {
            log.info("RAG retrieval rejected an unknown explicit shop query, questionLength={}",
                    question.length());
            return Collections.emptyList();
        }

        List<ShopKnowledge> constrainedVectorShops = applyStructuredConstraints(vectorShops, context);
        AiTraceSpanScope keywordSpan = startSpan(traceContext, "KEYWORD_SEARCH");
        List<ShopKnowledge> keywordShops;
        try {
            keywordShops = keywordFallback(context);
            success(keywordSpan, Collections.<String, Object>singletonMap("resultCount", keywordShops.size()));
        } catch (RuntimeException e) {
            failure(keywordSpan, e);
            throw e;
        }
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
    }

    private List<QdrantKnowledgeClient.QdrantSearchResult> searchBlogKnowledge(List<Float> vector, int limit,
                                                                                 QdrantKnowledgeClient.QdrantFilter filter) {
        try {
            return qdrantKnowledgeClient.search(knowledgeProperties.getBlogCollection(), vector, limit, filter);
        } catch (Exception e) {
            // Deploying the code before the first rebuild must not take the stable profile retrieval offline.
            log.warn("Blog knowledge collection is unavailable; continuing with shop profiles only: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ShopKnowledge> mergeVectorCandidates(
            List<QdrantKnowledgeClient.QdrantSearchResult> profileResults,
            List<QdrantKnowledgeClient.QdrantSearchResult> blogResults) {
        List<ShopKnowledge> candidates = new ArrayList<>();
        for (QdrantKnowledgeClient.QdrantSearchResult result : profileResults) {
            candidates.add(toShopKnowledge(result));
        }
        for (QdrantKnowledgeClient.QdrantSearchResult result : blogResults) {
            candidates.add(toShopKnowledge(result));
        }
        candidates.sort((left, right) -> Double.compare(
                right.getScore() == null ? 0D : right.getScore(),
                left.getScore() == null ? 0D : left.getScore()));
        Map<Long, ShopKnowledge> deduplicated = new LinkedHashMap<>();
        for (ShopKnowledge candidate : candidates) {
            deduplicated.putIfAbsent(candidate.getShopId(), candidate);
        }
        return new ArrayList<>(deduplicated.values());
    }

    private ShopKnowledge toShopKnowledge(QdrantKnowledgeClient.QdrantSearchResult result) {
        Map<String, Object> payload = result.getPayload();
        Object payloadShopId = payload.get("shopId");
        Long shopId = payloadShopId instanceof Number
                ? ((Number) payloadShopId).longValue() : result.getId();
        Object content = payload.get("content");
        return new ShopKnowledge(shopId, content == null ? "" : String.valueOf(content),
                result.getScore(), payload);
    }

    private int vectorCandidateLimit() {
        Integer configured = knowledgeProperties.getVectorCandidateLimit();
        int candidateLimit = configured == null ? 10 : configured;
        int retrieveLimit = knowledgeProperties.getRetrieveLimit() == null ? 3 : knowledgeProperties.getRetrieveLimit();
        return Math.max(Math.max(1, candidateLimit), retrieveLimit);
    }

    private QdrantKnowledgeClient.QdrantFilter toQdrantFilter(RetrievalContext context) {
        return new QdrantKnowledgeClient.QdrantFilter()
                .requireShopIds(context.matchedShopIds)
                .excludeShopIds(context.excludedShopIds)
                .requireTypeIds(context.matchedTypeIds)
                .requireAreas(context.matchedAreas)
                .maxAveragePrice(context.maxAveragePrice);
    }

    private List<String> validQuestions(List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String question : questions) {
            if (StrUtil.isNotBlank(question)) {
                unique.add(question.trim());
            }
        }
        return new ArrayList<>(unique);
    }

    private List<ShopKnowledge> mergeRoundRobin(List<List<ShopKnowledge>> groups) {
        if (groups == null || groups.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, ShopKnowledge> merged = new LinkedHashMap<>();
        int limit = Math.max(1, knowledgeProperties.getRetrieveLimit());
        int maxSize = 0;
        for (List<ShopKnowledge> group : groups) {
            maxSize = Math.max(maxSize, group == null ? 0 : group.size());
        }
        for (int rank = 0; rank < maxSize && merged.size() < limit; rank++) {
            for (List<ShopKnowledge> group : groups) {
                if (group == null || rank >= group.size()) {
                    continue;
                }
                ShopKnowledge knowledge = group.get(rank);
                merged.putIfAbsent(knowledge.getShopId(), knowledge);
                if (merged.size() >= limit) {
                    break;
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<ShopKnowledge> mergeWithTrace(AiTraceContext traceContext,
                                                List<List<ShopKnowledge>> groups) {
        AiTraceSpanScope mergeSpan = startSpan(traceContext, "RESULT_MERGE",
                Collections.<String, Object>singletonMap("groupCount", groups.size()));
        try {
            List<ShopKnowledge> merged = mergeRoundRobin(groups);
            success(mergeSpan, retrievalResultAttributes(merged, false));
            return merged;
        } catch (RuntimeException e) {
            failure(mergeSpan, e);
            throw e;
        }
    }

    private Map<String, Object> retrievalResultAttributes(List<ShopKnowledge> shops, boolean degraded) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("resultCount", shops == null ? 0 : shops.size());
        attributes.put("degradedToKeyword", degraded);
        if (shops != null && !shops.isEmpty()) {
            attributes.put("shopIds", shops.stream()
                    .map(ShopKnowledge::getShopId)
                    .collect(Collectors.toList()));
        }
        return attributes;
    }

    private Map<String, Object> retrievalResultAttributes(List<ShopKnowledge> shops,
                                                          boolean degraded,
                                                          int queryCount,
                                                          boolean keywordFallbackEnabled) {
        Map<String, Object> attributes = retrievalResultAttributes(shops, degraded);
        attributes.put("queryCount", queryCount);
        attributes.put("keywordFallbackEnabled", keywordFallbackEnabled);
        return attributes;
    }

    private AiTraceSpanScope startSpan(AiTraceContext context, String stageName) {
        return startSpan(context, stageName, Collections.<String, Object>emptyMap());
    }

    private AiTraceSpanScope startSpan(AiTraceContext context, String stageName,
                                       Map<String, Object> attributes) {
        return context == null || aiTraceService == null
                ? null : aiTraceService.startSpan(context, stageName, attributes);
    }

    private AiTraceContext contextOf(AiTraceSpanScope scope, AiTraceContext fallback) {
        return scope == null ? fallback : scope.getContext();
    }

    private void success(AiTraceSpanScope scope, Map<String, Object> attributes) {
        if (scope != null) {
            scope.success(attributes);
        }
    }

    private void failure(AiTraceSpanScope scope, Throwable error) {
        if (scope != null) {
            scope.failure(error);
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
            String content = toShopProfileText(shop, typeName);
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
        if (context.excludedShopIds.contains(shop.getId())) {
            return 0;
        }
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
        List<ShopType> shopTypes = shopTypeService.list();
        String normalizedQuestion = normalize(question);
        Set<Long> matchedShopIds = new LinkedHashSet<>();
        Set<Long> excludedShopIds = new LinkedHashSet<>();
        for (Shop shop : shops) {
            if (explicitShopNameMatchScore(normalizedQuestion, shop.getName()) > 0) {
                if (isExcludedShop(normalizedQuestion, shop.getName())) {
                    excludedShopIds.add(shop.getId());
                } else {
                    matchedShopIds.add(shop.getId());
                }
            }
        }
        Long maxAveragePrice = parseMaxAveragePrice(question);
        boolean foodIntent = containsAny(normalizedQuestion,
                "\u7f8e\u98df", "\u9910\u5385", "\u9910\u9986", "\u5403\u996d",
                "\u805a\u9910", "\u706b\u9505", "\u5bff\u53f8", "\u70e4\u8089",
                "\u6dae\u9505", "\u9910\u996e", "\u591c\u5bb5", "\u6cd5\u9910");
        boolean ktvIntent = containsAny(normalizedQuestion,
                "ktv", "\u5531\u6b4c", "\u5531k", "\u6b4c\u5385");
        Set<Long> matchedTypeIds = matchedTypeIds(normalizedQuestion, shopTypes, foodIntent, ktvIntent);
        Set<String> matchedAreas = matchedAreas(normalizedQuestion, shops);
        boolean explicitUnknownShop = matchedShopIds.isEmpty()
                && looksLikeExplicitUnknownShop(normalizedQuestion);
        return new RetrievalContext(shops, normalizedQuestion, matchedShopIds, excludedShopIds,
                matchedTypeIds, matchedAreas, maxAveragePrice, foodIntent, ktvIntent, explicitUnknownShop);
    }

    private Set<Long> matchedTypeIds(String normalizedQuestion, List<ShopType> shopTypes,
                                     boolean foodIntent, boolean ktvIntent) {
        Set<Long> matched = new LinkedHashSet<>();
        for (ShopType type : shopTypes) {
            String typeName = normalize(type.getName());
            if (typeName.length() >= 2 && normalizedQuestion.contains(typeName)) {
                matched.add(type.getId());
                continue;
            }
            if (ktvIntent && typeName.contains("ktv")) {
                matched.add(type.getId());
            } else if (foodIntent && (typeName.contains("\u7f8e\u98df") || typeName.contains("\u9910\u996e"))) {
                matched.add(type.getId());
            }
        }
        return matched;
    }

    private Set<String> matchedAreas(String normalizedQuestion, List<Shop> shops) {
        Set<String> matched = new LinkedHashSet<>();
        for (Shop shop : shops) {
            String area = shop.getArea();
            String normalizedArea = normalize(area);
            if (normalizedArea.length() >= 2 && normalizedQuestion.contains(normalizedArea)) {
                matched.add(area);
            }
        }
        return matched;
    }

    private boolean isExcludedShop(String normalizedQuestion, String shopName) {
        String normalizedName = normalize(shopName);
        String coreName = normalize(stripShopBranch(shopName));
        for (String marker : Arrays.asList(
                "\u6392\u9664", "\u4e0d\u8981", "\u4e0d\u8003\u8651",
                "\u9664\u4e86", "\u6362\u6389", "\u907f\u5f00"
        )) {
            if (normalizedQuestion.contains(marker + normalizedName)
                    || (coreName.length() >= 3 && normalizedQuestion.contains(marker + coreName))) {
                return true;
            }
        }
        return false;
    }

    private List<ShopKnowledge> applyStructuredConstraints(List<ShopKnowledge> vectorShops,
                                                            RetrievalContext context) {
        if (vectorShops.isEmpty()) {
            return vectorShops;
        }
        List<ShopKnowledge> filtered = new ArrayList<>();
        for (ShopKnowledge knowledge : vectorShops) {
            if (context.excludedShopIds.contains(knowledge.getShopId())) {
                continue;
            }
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
        return limitKnowledge(new ArrayList<>(merged.values()));
    }

    private List<ShopKnowledge> limitKnowledge(List<ShopKnowledge> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = Math.max(1, knowledgeProperties.getRetrieveLimit());
        return candidates.stream().limit(limit).collect(Collectors.toList());
    }

    private void writeShopProfileBatch(List<Shop> shops, Map<Long, String> typeNames) throws Exception {
        List<String> documents = new ArrayList<>(shops.size());
        for (Shop shop : shops) {
            documents.add(toShopProfileText(shop, typeNames.get(shop.getTypeId())));
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

    private void writeBlogBatch(List<Blog> blogs, Map<Long, Shop> shopsById,
                                Map<Long, String> typeNames) throws Exception {
        if (blogs == null || blogs.isEmpty()) {
            return;
        }
        List<Blog> validBlogs = new ArrayList<>();
        List<String> documents = new ArrayList<>();
        for (Blog blog : blogs) {
            Shop shop = shopsById.get(blog.getShopId());
            if (shop == null || blog.getId() == null) {
                continue;
            }
            validBlogs.add(blog);
            documents.add(toBlogKnowledgeText(shop, typeNames.get(shop.getTypeId()), blog));
        }
        if (validBlogs.isEmpty()) {
            return;
        }
        List<List<Float>> vectors = embeddingModelClient.embed(documents);
        List<QdrantKnowledgeClient.QdrantPoint> points = new ArrayList<>(validBlogs.size());
        for (int index = 0; index < validBlogs.size(); index++) {
            Blog blog = validBlogs.get(index);
            Shop shop = shopsById.get(blog.getShopId());
            String typeName = typeNames.get(shop.getTypeId());
            Map<String, Object> payload = toPayload(shop, typeName, documents.get(index));
            payload.put("documentType", "public_blog");
            payload.put("blogId", blog.getId());
            payload.put("blogTitle", blog.getTitle());
            payload.put("blogUpdatedAt", blog.getUpdateTime() == null ? null : blog.getUpdateTime().toString());
            points.add(new QdrantKnowledgeClient.QdrantPoint(blog.getId(), vectors.get(index), payload));
        }
        qdrantKnowledgeClient.upsert(knowledgeProperties.getBlogCollection(), points);
    }

    private Map<Long, String> shopTypeNames() {
        Map<Long, String> names = new HashMap<>();
        for (ShopType type : shopTypeService.list()) {
            names.put(type.getId(), type.getName());
        }
        return names;
    }

    private String toShopProfileText(Shop shop, String typeName) {
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
        return text.toString();
    }

    private String toBlogKnowledgeText(Shop shop, String typeName, Blog blog) {
        StringBuilder text = new StringBuilder();
        appendLine(text, "Store ID", shop.getId());
        appendLine(text, "Store name", shop.getName());
        appendLine(text, "Category", typeName);
        appendLine(text, "Business area", shop.getArea());
        appendLine(text, "Average spend", shop.getAvgPrice() == null ? null : shop.getAvgPrice() + " CNY");
        appendLine(text, "Public blog ID", blog.getId());
        appendLine(text, "Public blog title", blog.getTitle());
        appendLine(text, "Public blog content", truncate(blog.getContent(), MAX_BLOG_CONTENT_LENGTH));
        return text.toString();
    }

    private void appendLine(StringBuilder text, String label, Object value) {
        if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
            text.append(label).append(": ").append(value).append('\n');
        }
    }

    private String truncate(String value, int limit) {
        if (StrUtil.isBlank(value)) {
            return "No text content";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
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
        payload.put("documentType", "shop_profile");
        payload.put("content", content);
        return payload;
    }

    private static class RetrievalContext {

        private final List<Shop> shops;
        private final String normalizedQuestion;
        private final Set<Long> matchedShopIds;
        private final Set<Long> excludedShopIds;
        private final Set<Long> matchedTypeIds;
        private final Set<String> matchedAreas;
        private final Long maxAveragePrice;
        private final boolean foodIntent;
        private final boolean ktvIntent;
        private final boolean explicitUnknownShop;

        private RetrievalContext(List<Shop> shops, String normalizedQuestion, Set<Long> matchedShopIds,
                                 Set<Long> excludedShopIds, Set<Long> matchedTypeIds,
                                 Set<String> matchedAreas, Long maxAveragePrice,
                                 boolean foodIntent, boolean ktvIntent,
                                 boolean explicitUnknownShop) {
            this.shops = shops;
            this.normalizedQuestion = normalizedQuestion;
            this.matchedShopIds = matchedShopIds;
            this.excludedShopIds = excludedShopIds;
            this.matchedTypeIds = matchedTypeIds;
            this.matchedAreas = matchedAreas;
            this.maxAveragePrice = maxAveragePrice;
            this.foodIntent = foodIntent;
            this.ktvIntent = ktvIntent;
            this.explicitUnknownShop = explicitUnknownShop;
        }
    }
}
