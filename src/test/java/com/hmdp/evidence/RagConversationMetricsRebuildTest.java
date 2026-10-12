package com.hmdp.evidence;

import com.hmdp.HmDianPingApplication;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

/**
 * Rebuilds evaluation metrics from existing E2E conversations and their persisted traces.
 * It never sends a user question or calls an AI provider.
 */
@SpringBootTest(classes = HmDianPingApplication.class)
@EnabledIfSystemProperty(named = "rag.metrics.rebuild", matches = "true")
class RagConversationMetricsRebuildTest {

    @Resource
    private RagConversationEvaluationRunner evaluationRunner;

    @Test
    void rebuildMetricsFromPersistedTraces() throws Exception {
        evaluationRunner.rebuildExistingMetrics();
    }
}
