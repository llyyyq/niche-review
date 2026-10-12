package com.hmdp.service;

import com.hmdp.ai.AiTraceContext;
import com.hmdp.ai.AiResolvedQueryPlan;
import com.hmdp.ai.AiTaskRetrievalResult;

import java.util.List;

public interface IShopKnowledgeService {

    int rebuildShopKnowledge();

    void syncShopKnowledge(Long shopId);

    /**
     * Production retrieval result grouped by the validated business task.
     */
    List<AiTaskRetrievalResult> searchRelevantTaskResults(AiTraceContext traceContext,
                                                          AiResolvedQueryPlan plan);

}
