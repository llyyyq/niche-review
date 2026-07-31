package com.hmdp.ai;

import com.hmdp.config.AiChatConfig;
import com.hmdp.service.IAiTraceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AiTraceCoreTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatedIdsUseExpectedHexFormatsAndRemainUnique() {
        Set<String> traceIds = new HashSet<>();
        Set<String> spanIds = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            String requestId = AiTraceIds.requestId();
            String traceId = AiTraceIds.traceId();
            String spanId = AiTraceIds.spanId();
            String toolCallId = AiTraceIds.toolCallId();

            assertTrue(requestId.matches("[0-9a-f]{32}"));
            assertTrue(traceId.matches("[0-9a-f]{32}"));
            assertTrue(spanId.matches("[0-9a-f]{16}"));
            assertTrue(toolCallId.matches("[0-9a-f]{32}"));
            assertTrue(traceIds.add(traceId));
            assertTrue(spanIds.add(spanId));
        }
    }

    @Test
    void childSpanKeepsRequestAndTraceAndPointsToCurrentParent() {
        AiTraceContext root = context("request-a", "trace-a", "root-span", "root-span", null);
        AiTraceContext child = root.child("child-span");
        AiTraceContext grandChild = child.child("grandchild-span");

        assertEquals(root.getRequestId(), child.getRequestId());
        assertEquals(root.getTraceId(), child.getTraceId());
        assertEquals(root.getRootSpanId(), child.getRootSpanId());
        assertEquals(root.getCurrentSpanId(), child.getParentSpanId());
        assertEquals(child.getCurrentSpanId(), grandChild.getParentSpanId());
        assertNotEquals(child.getCurrentSpanId(), grandChild.getCurrentSpanId());
    }

    @Test
    void concurrentRequestsInSameConversationUseDifferentCorrelationIds() {
        AiTraceContext first = context(
                AiTraceIds.requestId(), AiTraceIds.traceId(), AiTraceIds.spanId(), "span-a", null);
        AiTraceContext second = context(
                AiTraceIds.requestId(), AiTraceIds.traceId(), AiTraceIds.spanId(), "span-b", null);

        assertEquals(first.getConversationId(), second.getConversationId());
        assertNotEquals(first.getRequestId(), second.getRequestId());
        assertNotEquals(first.getTraceId(), second.getTraceId());
    }

    @Test
    void spanScopeCompletesOnlyOnceAndRestoresPreviousMdc() {
        IAiTraceService traceService = mock(IAiTraceService.class);
        AiTraceContext root = context("request-a", "trace-a", "root-span", "root-span", null);
        AiTraceContext child = root.child("child-span");
        MDC.put("existing", "kept");
        AiTraceMdc.set(root);

        AiTraceSpanScope scope = new AiTraceSpanScope(traceService, child);
        assertEquals("request-a", MDC.get(AiTraceMdc.REQUEST_ID));
        assertEquals("trace-a", MDC.get(AiTraceMdc.TRACE_ID));
        assertEquals("child-span", MDC.get(AiTraceMdc.SPAN_ID));

        scope.success(Collections.<String, Object>singletonMap("resultCount", 3));
        scope.failure(new IllegalStateException("must be ignored"));
        scope.close();

        verify(traceService, times(1))
                .completeSpan(eq(child), eq(true), any(), eq(null), anyLong());
        assertEquals("kept", MDC.get("existing"));
        assertEquals("root-span", MDC.get(AiTraceMdc.SPAN_ID));
    }

    @Test
    void failedSpanKeepsDiagnosticAttributesFromSpanStart() {
        IAiTraceService traceService = mock(IAiTraceService.class);
        AiTraceContext child = context(
                "request-c", "trace-c", "root-c", "tool-span", "agent-span");
        AiTraceSpanScope scope = new AiTraceSpanScope(
                traceService,
                child,
                Collections.<String, Object>singletonMap("toolCallId", "tool-call-c")
        );

        scope.failure(new IllegalStateException("tool failed"));

        verify(traceService).completeSpan(
                eq(child),
                eq(false),
                argThat(attributes -> "tool-call-c".equals(attributes.get("toolCallId"))),
                any(IllegalStateException.class),
                anyLong()
        );
    }

    @Test
    void mdcRunRestoresEmptyContextAfterTask() {
        AiTraceContext context = context("request-b", "trace-b", "root-b", "root-b", null);

        AiTraceMdc.run(context, () -> {
            assertEquals("request-b", MDC.get(AiTraceMdc.REQUEST_ID));
            assertEquals("trace-b", MDC.get(AiTraceMdc.TRACE_ID));
            assertEquals("root-b", MDC.get(AiTraceMdc.SPAN_ID));
        });

        assertNull(MDC.get(AiTraceMdc.REQUEST_ID));
        assertNull(MDC.get(AiTraceMdc.TRACE_ID));
        assertNull(MDC.get(AiTraceMdc.SPAN_ID));
        assertFalse(context.child("child").getParentSpanId().isEmpty());
    }

    @Test
    void taskDecoratorPropagatesAndClearsMdcAcrossReusedThreads() throws Exception {
        AiChatConfig config = new AiChatConfig();
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.aiKnowledgeExecutor();
        AtomicReference<String> firstTrace = new AtomicReference<>();
        AtomicReference<String> secondTrace = new AtomicReference<>("not-run");
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        try {
            MDC.put(AiTraceMdc.TRACE_ID, "trace-from-caller");
            executor.execute(() -> {
                firstTrace.set(MDC.get(AiTraceMdc.TRACE_ID));
                firstDone.countDown();
            });
            assertTrue(firstDone.await(3, TimeUnit.SECONDS));

            MDC.clear();
            executor.execute(() -> {
                secondTrace.set(MDC.get(AiTraceMdc.TRACE_ID));
                secondDone.countDown();
            });
            assertTrue(secondDone.await(3, TimeUnit.SECONDS));

            assertEquals("trace-from-caller", firstTrace.get());
            assertNull(secondTrace.get());
        } finally {
            executor.shutdown();
        }
    }

    private AiTraceContext context(String requestId, String traceId, String rootSpanId,
                                   String currentSpanId, String parentSpanId) {
        return new AiTraceContext(requestId, traceId, rootSpanId, currentSpanId, parentSpanId,
                1L, 2L, 3L, 4L);
    }
}
