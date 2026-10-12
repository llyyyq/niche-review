package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.ai.AiRequestCatalog;
import com.hmdp.ai.EmbeddingModelClient;
import com.hmdp.ai.AiTraceContext;
import com.hmdp.ai.AiTraceSpanScope;
import com.hmdp.ai.QdrantKnowledgeClient;
import com.hmdp.ai.ShopKnowledge;
import com.hmdp.ai.AiResolvedQueryPlan;
import com.hmdp.ai.AiResolvedQueryTask;
import com.hmdp.ai.AiTaskRetrievalResult;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ShopKnowledgeServiceImpl implements IShopKnowledgeService {

    private static final int EMBEDDING_BATCH_SIZE = 10;
    private static final int MAX_BLOG_CONTENT_LENGTH = 800;

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
        if (shops == null) {
            shops = Collections.emptyList();
        }
        Map<Long, String> typeNames = shopTypeNames();
        Map<Long, Shop> shopsById = shops.stream()
                .collect(Collectors.toMap(Shop::getId, shop -> shop));
        List<Blog> allBlogs = blogService.list();
        if (allBlogs == null) {
            allBlogs = Collections.emptyList();
        }
        List<Blog> blogs = allBlogs.stream()
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
            if (blogs == null) {
                blogs = Collections.emptyList();
            }
            writeBlogBatch(blogs, Collections.singletonMap(shopId, shop), typeNames);
            log.info("Shop knowledge point synchronized, shopId={}", shopId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to synchronize shop knowledge, shopId=" + shopId, e);
        }
    }

    @Override
    public List<AiTaskRetrievalResult> searchRelevantTaskResults(AiTraceContext traceContext,
                                                                   AiResolvedQueryPlan plan) {
        if (plan == null || !plan.isReady()) {
            return Collections.emptyList();
        }
        return searchResolvedTaskResults(traceContext, plan.getTasks());
    }

    /** Production path: hard conditions are resolved by MySQL; vectors only rank semantic preferences. */
    private List<AiTaskRetrievalResult> searchResolvedTaskResults(AiTraceContext traceContext,
                                                                   List<AiResolvedQueryTask> tasks) {
        List<AiResolvedQueryTask> validTasks = new ArrayList<>();
        List<String> queries = new ArrayList<>();
        if (tasks != null) {
            for (AiResolvedQueryTask task : tasks) {
                if (task != null && StrUtil.isNotBlank(task.getSemanticQuery())) {
                    validTasks.add(task);
                    queries.add(task.getSemanticQuery().trim());
                }
            }
        }
        if (validTasks.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> retrievalAttributes = new LinkedHashMap<>();
        retrievalAttributes.put("queryCount", validTasks.size());
        retrievalAttributes.put("structuredPlan", true);
        AiTraceSpanScope retrievalSpan = startSpan(traceContext, "RETRIEVAL", retrievalAttributes);
        AiTraceContext retrievalContext = contextOf(retrievalSpan, traceContext);
        try {
            List<AiResolvedQueryTask> semanticTasks = new ArrayList<>();
            List<String> semanticQueries = new ArrayList<>();
            for (int index = 0; index < validTasks.size(); index++) {
                AiResolvedQueryTask task = validTasks.get(index);
                if (needsSemanticRetrieval(task)) {
                    semanticTasks.add(task);
                    semanticQueries.add(queries.get(index));
                }
            }
            Map<AiResolvedQueryTask, List<Float>> vectorsByTask = new HashMap<>();
            boolean embeddingDegraded = false;
            if (!semanticTasks.isEmpty()) {
                AiTraceSpanScope embeddingSpan = startSpan(retrievalContext, "EMBEDDING",
                        Collections.<String, Object>singletonMap("queryCount", semanticTasks.size()));
                try {
                    List<List<Float>> vectors = embeddingModelClient.embed(semanticQueries);
                    if (vectors == null || vectors.size() != semanticTasks.size()) {
                        throw new IllegalStateException("Embedding count does not match semantic task count");
                    }
                    for (int index = 0; index < semanticTasks.size(); index++) {
                        vectorsByTask.put(semanticTasks.get(index), vectors.get(index));
                    }
                    success(embeddingSpan, Collections.<String, Object>singletonMap("vectorCount", vectors.size()));
                } catch (Exception e) {
                    failure(embeddingSpan, e);
                    embeddingDegraded = true;
                    log.warn("Embedding unavailable; semantic tasks will use structured candidates: {}",
                            e.getMessage());
                }
            }
            List<AiTaskRetrievalResult> taskResults = new ArrayList<>(validTasks.size());
            for (int index = 0; index < validTasks.size(); index++) {
                AiResolvedQueryTask task = validTasks.get(index);
                List<ShopKnowledge> structuredCandidates = searchStructuredCandidates(
                        retrievalContext, task, index + 1);
                boolean semanticRequested = needsSemanticRetrieval(task);
                boolean semanticDegraded = semanticRequested && embeddingDegraded;
                List<ShopKnowledge> candidates = structuredCandidates;
                if (semanticRequested && !embeddingDegraded) {
                    try {
                        candidates = searchSemanticCandidates(
                                retrievalContext, task, vectorsByTask.get(task), index + 1);
                    } catch (Exception semanticError) {
                        semanticDegraded = true;
                        log.warn("Semantic retrieval degraded to structured candidates, taskIndex={}, reason={}",
                                index + 1, semanticError.getMessage());
                    }
                }
                taskResults.add(new AiTaskRetrievalResult(index + 1, task, candidates,
                        semanticRequested, semanticDegraded));
            }
            List<ShopKnowledge> merged = mergeTaskResultsWithTrace(retrievalContext, taskResults);
            success(retrievalSpan, retrievalResultAttributes(
                    merged, taskResults.stream().anyMatch(AiTaskRetrievalResult::isSemanticDegraded),
                    validTasks.size()));
            return taskResults;
        } catch (Exception e) {
            failure(retrievalSpan, e);
            throw e instanceof RuntimeException
                    ? (RuntimeException) e : new IllegalStateException("Structured retrieval failed", e);
        }
    }

    private boolean needsSemanticRetrieval(AiResolvedQueryTask task) {
        return task != null && task.getSource() != null
                && !task.getSource().getSemanticPreferences().isEmpty();
    }

    private List<ShopKnowledge> searchStructuredCandidates(AiTraceContext traceContext,
                                                            AiResolvedQueryTask task,
                                                            int taskIndex) {
        RetrievalContext context = buildRetrievalContext(task);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("taskIndex", taskIndex);
        attributes.put("candidateLimit", taskCandidateLimit());
        AiTraceSpanScope structuredSpan = startSpan(traceContext, "STRUCTURED_RETRIEVAL", attributes);
        try {
            List<ShopKnowledge> result = deterministicStructuredCandidates(context, taskCandidateLimit());
            Map<String, Object> resultAttributes = new LinkedHashMap<>();
            resultAttributes.put("resultCount", result.size());
            resultAttributes.put("shopIds", result.stream().map(ShopKnowledge::getShopId)
                    .collect(Collectors.toList()));
            success(structuredSpan, resultAttributes);
            return result;
        } catch (RuntimeException e) {
            failure(structuredSpan, e);
            throw e;
        }
    }

    private List<ShopKnowledge> searchSingleQuery(AiTraceContext traceContext,
                                                   String question, List<Float> vector,
                                                   RetrievalContext context,
                                                   boolean enforceHardConstraints,
                                                   int resultLimit,
                                                   int taskIndex) throws Exception {
        Map<String, Object> vectorAttributes = new LinkedHashMap<>();
        if (taskIndex > 0) {
            vectorAttributes.put("taskIndex", taskIndex);
        }
        AiTraceSpanScope vectorSpan = startSpan(traceContext, "QDRANT_SEARCH", vectorAttributes);
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
        List<ShopKnowledge> constrained = enforceHardConstraints
                ? applyStructuredConstraints(vectorShops, context) : vectorShops;
        return isReliableVectorResult(constrained)
                ? limitKnowledge(constrained, resultLimit) : Collections.emptyList();
    }

    private List<ShopKnowledge> searchSemanticCandidates(AiTraceContext traceContext,
                                                          AiResolvedQueryTask task,
                                                          List<Float> vector,
                                                          int taskIndex) throws Exception {
        return searchSingleQuery(traceContext, task.getSemanticQuery(), vector,
                buildRetrievalContext(task), true, taskCandidateLimit(), taskIndex);
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
        if (profileResults != null) {
            for (QdrantKnowledgeClient.QdrantSearchResult result : profileResults) {
                if (result != null) {
                    candidates.add(toShopKnowledge(result));
                }
            }
        }
        if (blogResults != null) {
            for (QdrantKnowledgeClient.QdrantSearchResult result : blogResults) {
                if (result != null) {
                    candidates.add(toShopKnowledge(result));
                }
            }
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
        Map<String, Object> payload = result.getPayload() == null
                ? Collections.<String, Object>emptyMap() : result.getPayload();
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
        return Math.max(Math.max(1, candidateLimit), taskCandidateLimit());
    }

    private QdrantKnowledgeClient.QdrantFilter toQdrantFilter(RetrievalContext context) {
        return new QdrantKnowledgeClient.QdrantFilter()
                .requireShopIds(context.matchedShopIds)
                .excludeShopIds(context.excludedShopIds)
                .requireTypeIds(context.matchedTypeIds)
                .requireAreas(context.matchedAreas)
                .maxAveragePrice(context.maxAveragePrice);
    }

    private Map<String, Object> retrievalResultAttributes(List<ShopKnowledge> shops, boolean semanticDegraded) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("resultCount", shops == null ? 0 : shops.size());
        attributes.put("semanticDegraded", semanticDegraded);
        if (shops != null && !shops.isEmpty()) {
            attributes.put("shopIds", shops.stream()
                    .map(ShopKnowledge::getShopId)
                    .collect(Collectors.toList()));
        }
        return attributes;
    }

    private Map<String, Object> retrievalResultAttributes(List<ShopKnowledge> shops,
                                                          boolean semanticDegraded,
                                                          int queryCount) {
        Map<String, Object> attributes = retrievalResultAttributes(shops, semanticDegraded);
        attributes.put("queryCount", queryCount);
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
        if (shops == null || shops.isEmpty()) {
            return false;
        }
        Double minScore = knowledgeProperties.getVectorMinScore();
        if (minScore == null) {
            return true;
        }
        Double topScore = shops.get(0).getScore();
        return topScore != null && topScore >= minScore;
    }

    private List<ShopKnowledge> deterministicStructuredCandidates(RetrievalContext context, int limit) {
        Map<Long, String> typeNames = shopTypeNames();
        List<Shop> eligible = new ArrayList<>();
        for (Shop shop : context.shops) {
            if (matchesStructuredConditions(shop, context)) {
                eligible.add(shop);
            }
        }

        final Map<Long, Integer> explicitOrder = new LinkedHashMap<>();
        int order = 0;
        for (Long shopId : context.matchedShopIds) {
            explicitOrder.put(shopId, order++);
        }
        eligible.sort((left, right) -> {
            if (!explicitOrder.isEmpty()) {
                return Integer.compare(explicitOrder.get(left.getId()), explicitOrder.get(right.getId()));
            }
            int compared = compareNullableDesc(left.getScore(), right.getScore());
            if (compared == 0) compared = compareNullableDesc(left.getComments(), right.getComments());
            if (compared == 0) compared = compareNullableDesc(left.getSold(), right.getSold());
            return compared == 0 ? Long.compare(left.getId(), right.getId()) : compared;
        });

        List<ShopKnowledge> result = new ArrayList<>();
        for (Shop shop : eligible) {
            String typeName = typeNames.get(shop.getTypeId());
            String content = toShopProfileText(shop, typeName);
            Map<String, Object> payload = toPayload(shop, typeName, content);
            payload.put("retrievalSource", "structured-mysql");
            result.add(new ShopKnowledge(shop.getId(), content, 0D, payload));
            if (result.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return result;
    }

    private int compareNullableDesc(Integer left, Integer right) {
        int safeLeft = left == null ? Integer.MIN_VALUE : left;
        int safeRight = right == null ? Integer.MIN_VALUE : right;
        return Integer.compare(safeRight, safeLeft);
    }

    private RetrievalContext buildRetrievalContext(AiResolvedQueryTask task) {
        List<Shop> shops = AiRequestCatalog.shops(shopService::list);
        if (shops == null) {
            shops = Collections.emptyList();
        }
        return new RetrievalContext(
                shops,
                new LinkedHashSet<>(task.getShopIds()),
                new LinkedHashSet<>(task.getExcludedShopIds()),
                new LinkedHashSet<>(task.getTypeIds()),
                new LinkedHashSet<>(task.getAreas()),
                task.getMaxAveragePrice() == null ? null : task.getMaxAveragePrice().longValue()
        );
    }

    private List<ShopKnowledge> applyStructuredConstraints(List<ShopKnowledge> vectorShops,
                                                            RetrievalContext context) {
        if (vectorShops.isEmpty()) {
            return vectorShops;
        }
        Map<Long, Shop> currentShops = context.shops.stream()
                .filter(shop -> shop != null && shop.getId() != null)
                .collect(Collectors.toMap(Shop::getId, shop -> shop, (left, right) -> left));
        List<ShopKnowledge> filtered = new ArrayList<>();
        for (ShopKnowledge knowledge : vectorShops) {
            Shop current = currentShops.get(knowledge.getShopId());
            if (matchesStructuredConditions(current, context)) {
                filtered.add(knowledge);
            }
        }
        return filtered;
    }

    private boolean matchesStructuredConditions(Shop shop, RetrievalContext context) {
        if (shop == null || shop.getId() == null || context.excludedShopIds.contains(shop.getId())) {
            return false;
        }
        if (!context.matchedShopIds.isEmpty() && !context.matchedShopIds.contains(shop.getId())) {
            return false;
        }
        if (!context.matchedTypeIds.isEmpty() && !context.matchedTypeIds.contains(shop.getTypeId())) {
            return false;
        }
        if (!context.matchedAreas.isEmpty() && !context.matchedAreas.contains(shop.getArea())) {
            return false;
        }
        return context.maxAveragePrice == null
                || shop.getAvgPrice() != null && shop.getAvgPrice() <= context.maxAveragePrice;
    }

    private List<AiTaskRetrievalResult> emptyTaskResults(List<AiResolvedQueryTask> tasks) {
        List<AiTaskRetrievalResult> result = new ArrayList<>();
        for (int index = 0; tasks != null && index < tasks.size(); index++) {
            result.add(new AiTaskRetrievalResult(index + 1, tasks.get(index),
                    Collections.<ShopKnowledge>emptyList()));
        }
        return result;
    }

    private List<ShopKnowledge> flattenTaskResults(List<AiTaskRetrievalResult> taskResults, int limit) {
        if (taskResults == null || taskResults.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, ShopKnowledge> merged = new LinkedHashMap<>();
        for (AiTaskRetrievalResult taskResult : taskResults) {
            if (taskResult == null) {
                continue;
            }
            for (ShopKnowledge knowledge : taskResult.getCandidates()) {
                if (knowledge != null && knowledge.getShopId() != null) {
                    merged.putIfAbsent(knowledge.getShopId(), knowledge);
                }
            }
        }
        List<ShopKnowledge> result = new ArrayList<>(merged.values());
        if (limit < 1 || limit == Integer.MAX_VALUE || result.size() <= limit) {
            return result;
        }
        return new ArrayList<>(result.subList(0, limit));
    }

    private List<ShopKnowledge> mergeTaskResultsWithTrace(AiTraceContext traceContext,
                                                           List<AiTaskRetrievalResult> taskResults) {
        List<ShopKnowledge> merged = flattenTaskResults(taskResults, Integer.MAX_VALUE);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("groupCount", taskResults == null ? 0 : taskResults.size());
        attributes.put("taskCount", taskResults == null ? 0 : taskResults.size());
        attributes.put("resultCount", merged.size());
        if (!merged.isEmpty()) {
            attributes.put("shopIds", merged.stream()
                    .map(ShopKnowledge::getShopId).collect(Collectors.toList()));
        }
        AiTraceSpanScope mergeSpan = startSpan(traceContext, "RESULT_MERGE", attributes);
        try {
            success(mergeSpan, attributes);
            return merged;
        } catch (RuntimeException e) {
            failure(mergeSpan, e);
            throw e;
        }
    }

    private List<ShopKnowledge> limitKnowledge(List<ShopKnowledge> candidates, int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        return candidates.stream().limit(Math.max(1, limit)).collect(Collectors.toList());
    }

    private int taskCandidateLimit() {
        Integer configured = knowledgeProperties.getTaskCandidateLimit();
        return configured == null || configured < 1 ? 5 : configured;
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
        List<ShopType> types = AiRequestCatalog.types(shopTypeService::list);
        if (types == null) {
            return names;
        }
        for (ShopType type : types) {
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
        private final Set<Long> matchedShopIds;
        private final Set<Long> excludedShopIds;
        private final Set<Long> matchedTypeIds;
        private final Set<String> matchedAreas;
        private final Long maxAveragePrice;

        private RetrievalContext(List<Shop> shops, Set<Long> matchedShopIds,
                                 Set<Long> excludedShopIds, Set<Long> matchedTypeIds,
                                 Set<String> matchedAreas, Long maxAveragePrice) {
            this.shops = shops;
            this.matchedShopIds = matchedShopIds;
            this.excludedShopIds = excludedShopIds;
            this.matchedTypeIds = matchedTypeIds;
            this.matchedAreas = matchedAreas;
            this.maxAveragePrice = maxAveragePrice;
        }
    }
}
