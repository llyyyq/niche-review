package com.hmdp.service;

import com.hmdp.ai.ShopKnowledge;
import com.hmdp.ai.AiTraceContext;

import java.util.List;

public interface IShopKnowledgeService {

    int rebuildShopKnowledge();

    void syncShopKnowledge(Long shopId);

    List<ShopKnowledge> searchRelevantShops(String question);

    List<ShopKnowledge> searchRelevantShops(List<String> questions);

    List<ShopKnowledge> searchRelevantShops(AiTraceContext traceContext, List<String> questions);

    /**
     * Used by the local evaluation runner to compare pure vector retrieval with
     * the production hybrid retrieval path without changing shared properties.
     */
    List<ShopKnowledge> searchRelevantShops(String question, boolean keywordFallbackEnabled);

    List<ShopKnowledge> searchRelevantShops(List<String> questions, boolean keywordFallbackEnabled);
}
