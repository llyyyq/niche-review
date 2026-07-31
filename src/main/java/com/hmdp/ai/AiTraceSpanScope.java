package com.hmdp.ai;

import com.hmdp.service.IAiTraceService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AiTraceSpanScope implements AutoCloseable {

    private final IAiTraceService traceService;
    private final AiTraceContext context;
    private final Map<String, Object> initialAttributes;
    private final Map<String, String> previousMdc;
    private final long startedNanos;
    private final AtomicBoolean finished = new AtomicBoolean(false);

    public AiTraceSpanScope(IAiTraceService traceService, AiTraceContext context) {
        this(traceService, context, Collections.<String, Object>emptyMap());
    }

    public AiTraceSpanScope(IAiTraceService traceService, AiTraceContext context,
                            Map<String, Object> initialAttributes) {
        this.traceService = traceService;
        this.context = context;
        this.initialAttributes = initialAttributes == null
                ? Collections.<String, Object>emptyMap()
                : new LinkedHashMap<>(initialAttributes);
        this.startedNanos = System.nanoTime();
        this.previousMdc = AiTraceMdc.capture();
        AiTraceMdc.set(context);
    }

    public AiTraceContext getContext() {
        return context;
    }

    public void success() {
        success(Collections.<String, Object>emptyMap());
    }

    public void success(Map<String, Object> attributes) {
        if (finished.compareAndSet(false, true)) {
            try {
                traceService.completeSpan(
                        context, true, mergedAttributes(attributes), null, elapsedMillis());
            } finally {
                AiTraceMdc.restore(previousMdc);
            }
        }
    }

    public void failure(Throwable error) {
        if (finished.compareAndSet(false, true)) {
            try {
                traceService.completeSpan(
                        context, false, initialAttributes, error, elapsedMillis());
            } finally {
                AiTraceMdc.restore(previousMdc);
            }
        }
    }

    private long elapsedMillis() {
        return Math.max(0L,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    private Map<String, Object> mergedAttributes(Map<String, Object> completedAttributes) {
        if (initialAttributes.isEmpty()) {
            return completedAttributes == null
                    ? Collections.<String, Object>emptyMap()
                    : completedAttributes;
        }
        Map<String, Object> merged = new LinkedHashMap<>(initialAttributes);
        if (completedAttributes != null) {
            merged.putAll(completedAttributes);
        }
        return merged;
    }

    @Override
    public void close() {
        success();
    }
}
