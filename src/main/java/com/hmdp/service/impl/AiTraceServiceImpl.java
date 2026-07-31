package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.AiTraceContext;
import com.hmdp.ai.AiTraceIds;
import com.hmdp.ai.AiTraceSpanScope;
import com.hmdp.config.AiTraceProperties;
import com.hmdp.entity.AiRequestLog;
import com.hmdp.entity.AiToolLog;
import com.hmdp.entity.AiTrace;
import com.hmdp.entity.AiTraceSpan;
import com.hmdp.mapper.AiTraceMapper;
import com.hmdp.service.IAiRequestLogService;
import com.hmdp.service.IAiToolLogService;
import com.hmdp.service.IAiTraceService;
import com.hmdp.service.IAiTraceSpanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiTraceServiceImpl extends ServiceImpl<AiTraceMapper, AiTrace>
        implements IAiTraceService {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private static final Set<String> TRACE_ATTRIBUTE_ALLOWLIST =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "originalChars", "rewrittenChars", "mode", "queryCount",
                    "modelCalled", "validModelOutput", "vectorCount",
                    "keywordFallbackEnabled", "resultCount", "degradedToKeyword",
                    "shopIds", "groupCount", "reason", "provider", "model",
                    "inputTokens", "outputTokens", "toolCount", "finished",
                    "toolName", "toolCallId", "resultChars", "event"
            )));

    @Resource
    private IAiTraceSpanService aiTraceSpanService;

    @Resource
    private IAiRequestLogService aiRequestLogService;

    @Resource
    private IAiToolLogService aiToolLogService;

    @Resource
    private AiTraceProperties properties;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public AiTraceContext startChatTrace(Long conversationId, Long userId,
                                         Long userMessageId, Long assistantMessageId) {
        return startTrace("CHAT", null, conversationId, userId, userMessageId, assistantMessageId);
    }

    @Override
    public AiTraceContext startLinkedTrace(String traceType, String linkedTraceId,
                                           Long conversationId, Long userId, Long assistantMessageId) {
        return startTrace(traceType, linkedTraceId, conversationId, userId, null, assistantMessageId);
    }

    private AiTraceContext startTrace(String traceType, String linkedTraceId,
                                      Long conversationId, Long userId,
                                      Long userMessageId, Long assistantMessageId) {
        String requestId = AiTraceIds.requestId();
        String traceId = AiTraceIds.traceId();
        String rootSpanId = AiTraceIds.spanId();
        AiTraceContext context = new AiTraceContext(
                requestId, traceId, rootSpanId, rootSpanId, null,
                conversationId, userId, userMessageId, assistantMessageId
        );
        if (!enabled()) {
            return context;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            AiTrace trace = new AiTrace();
            trace.setRequestId(requestId);
            trace.setTraceId(traceId);
            trace.setRootSpanId(rootSpanId);
            trace.setTraceType(traceType);
            trace.setLinkedTraceId(linkedTraceId);
            trace.setConversationId(conversationId);
            trace.setUserId(userId);
            trace.setUserMessageId(userMessageId);
            trace.setAssistantMessageId(assistantMessageId);
            trace.setStatus(STATUS_RUNNING);
            trace.setCurrentStage(traceType);
            trace.setStartedAt(now);
            save(trace);

            AiTraceSpan rootSpan = new AiTraceSpan();
            rootSpan.setTraceId(traceId);
            rootSpan.setSpanId(rootSpanId);
            rootSpan.setStageName(traceType);
            rootSpan.setStatus(STATUS_RUNNING);
            rootSpan.setStartedAt(now);
            aiTraceSpanService.save(rootSpan);
        } catch (Exception e) {
            log.error("Failed to create AI trace, traceId={}", traceId, e);
        }
        return context;
    }

    @Override
    public AiTraceSpanScope startSpan(AiTraceContext parent, String stageName) {
        return startSpan(parent, stageName, Collections.<String, Object>emptyMap());
    }

    @Override
    public AiTraceSpanScope startSpan(AiTraceContext parent, String stageName,
                                      Map<String, Object> attributes) {
        if (parent == null || !parent.isValid()) {
            return new AiTraceSpanScope(this, parent, attributes);
        }
        AiTraceContext child = parent.child(AiTraceIds.spanId());
        if (enabled()) {
            try {
                AiTraceSpan span = new AiTraceSpan();
                span.setTraceId(child.getTraceId());
                span.setSpanId(child.getCurrentSpanId());
                span.setParentSpanId(parent.getCurrentSpanId());
                span.setStageName(stageName);
                span.setStatus(STATUS_RUNNING);
                span.setAttributesJson(toJson(attributes));
                span.setStartedAt(LocalDateTime.now());
                aiTraceSpanService.save(span);
                updateCurrentStage(child, stageName);
            } catch (Exception e) {
                log.error("Failed to start AI trace span, traceId={}, stage={}",
                        child.getTraceId(), stageName, e);
            }
        }
        return new AiTraceSpanScope(this, child, attributes);
    }

    @Override
    public void completeSpan(AiTraceContext spanContext, boolean success,
                             Map<String, Object> attributes, Throwable error, long durationMs) {
        if (!enabled() || spanContext == null || !spanContext.isValid()) {
            return;
        }
        try {
            aiTraceSpanService.update()
                    .eq("trace_id", spanContext.getTraceId())
                    .eq("span_id", spanContext.getCurrentSpanId())
                    .eq("status", STATUS_RUNNING)
                    .set("status", success ? STATUS_SUCCEEDED : STATUS_FAILED)
                    .set("attributes_json", toJson(attributes))
                    .set("error_message", error == null ? null : limitError(error.getMessage()))
                    .set("completed_at", LocalDateTime.now())
                    .set("duration_ms", Math.max(0L, durationMs))
                    .update();
        } catch (Exception e) {
            log.error("Failed to complete AI trace span, traceId={}, spanId={}",
                    spanContext.getTraceId(), spanContext.getCurrentSpanId(), e);
        }
    }

    @Override
    public void updateCurrentStage(AiTraceContext context, String stageName) {
        if (!enabled() || context == null || !context.isValid()) {
            return;
        }
        try {
            update()
                    .eq("trace_id", context.getTraceId())
                    .eq("status", STATUS_RUNNING)
                    .set("current_stage", stageName)
                    .update();
        } catch (Exception e) {
            log.error("Failed to update AI trace stage, traceId={}, stage={}",
                    context.getTraceId(), stageName, e);
        }
    }

    @Override
    public void markFirstToken(AiTraceContext context) {
        if (!enabled() || context == null || !context.isValid()) {
            return;
        }
        try {
            update()
                    .eq("trace_id", context.getTraceId())
                    .eq("status", STATUS_RUNNING)
                    .isNull("first_token_at")
                    .set("first_token_at", LocalDateTime.now())
                    .update();
        } catch (Exception e) {
            log.error("Failed to mark AI trace first token, traceId={}", context.getTraceId(), e);
        }
    }

    @Override
    public void completeTrace(AiTraceContext context, String outcome) {
        finishTrace(context, STATUS_SUCCEEDED, outcome, null, null);
    }

    @Override
    public void failTrace(AiTraceContext context, String errorStage, Throwable error) {
        finishTrace(context, STATUS_FAILED, null, errorStage, error);
    }

    @Override
    public void cancelTrace(AiTraceContext context, String errorStage, Throwable error) {
        finishTrace(context, STATUS_CANCELLED, null, errorStage, error);
    }

    private void finishTrace(AiTraceContext context, String status, String outcome,
                             String errorStage, Throwable error) {
        if (!enabled() || context == null || !context.isValid()) {
            return;
        }
        try {
            LocalDateTime completedAt = LocalDateTime.now();
            long totalMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - context.getTraceStartedNanos()));
            boolean updated = update()
                    .eq("trace_id", context.getTraceId())
                    .eq("status", STATUS_RUNNING)
                    .set("status", status)
                    .set("outcome", outcome)
                    .set("current_stage", errorStage == null ? "COMPLETED" : errorStage)
                    .set("error_stage", errorStage)
                    .set("error_message", error == null ? null : limitError(error.getMessage()))
                    .set("completed_at", completedAt)
                    .set("total_ms", totalMs)
                    .update();
            if (updated) {
                aiTraceSpanService.update()
                        .eq("trace_id", context.getTraceId())
                        .eq("span_id", context.getRootSpanId())
                        .eq("status", STATUS_RUNNING)
                        .set("status", status)
                        .set("error_message", error == null ? null : limitError(error.getMessage()))
                        .set("completed_at", completedAt)
                        .set("duration_ms", totalMs)
                        .update();
            }
        } catch (Exception e) {
            log.error("Failed to finish AI trace, traceId={}, status={}", context.getTraceId(), status, e);
        }
    }

    @Scheduled(cron = "${ai.trace.cleanup-cron:0 20 3 * * ?}")
    public void cleanupExpiredTraces() {
        if (!enabled() || properties.getRetentionDays() == null || properties.getRetentionDays() < 1) {
            return;
        }
        int batchSize = properties.getCleanupBatchSize() == null
                ? 500 : Math.max(1, properties.getCleanupBatchSize());
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getRetentionDays());
        try {
            List<AiTrace> expired = list(new QueryWrapper<AiTrace>()
                    .lt("started_at", cutoff)
                    .orderByAsc("id")
                    .last("LIMIT " + batchSize));
            if (expired.isEmpty()) {
                return;
            }
            List<String> traceIds = expired.stream().map(AiTrace::getTraceId).collect(Collectors.toList());
            aiTraceSpanService.remove(new QueryWrapper<AiTraceSpan>().in("trace_id", traceIds));
            aiRequestLogService.remove(new QueryWrapper<AiRequestLog>().in("trace_id", traceIds));
            aiToolLogService.remove(new QueryWrapper<AiToolLog>().in("trace_id", traceIds));
            removeByIds(expired.stream().map(AiTrace::getId).collect(Collectors.toList()));
            log.info("Expired AI traces deleted, count={}", traceIds.size());
        } catch (Exception e) {
            log.error("Failed to clean expired AI traces", e);
        }
    }

    private boolean enabled() {
        return Boolean.TRUE.equals(properties.getEnabled());
    }

    private String toJson(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || !TRACE_ATTRIBUTE_ALLOWLIST.contains(entry.getKey())) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                safe.put(entry.getKey(), value);
            } else {
                String text = String.valueOf(value);
                safe.put(entry.getKey(), text.length() <= 256 ? text : text.substring(0, 256));
            }
        }
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (Exception e) {
            return "{\"serializationError\":true}";
        }
    }

    private String limitError(String error) {
        if (error == null) {
            return null;
        }
        String sanitized = error.replaceAll("[\\r\\n]+", " ");
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512);
    }
}
