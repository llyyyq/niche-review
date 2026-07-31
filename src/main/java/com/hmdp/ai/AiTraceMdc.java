package com.hmdp.ai;

import org.slf4j.MDC;

import java.util.Map;

public final class AiTraceMdc {

    public static final String REQUEST_ID = "requestId";
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";

    private AiTraceMdc() {
    }

    public static Map<String, String> capture() {
        return MDC.getCopyOfContextMap();
    }

    public static void restore(Map<String, String> context) {
        MDC.clear();
        if (context != null && !context.isEmpty()) {
            MDC.setContextMap(context);
        }
    }

    public static void set(AiTraceContext context) {
        if (context == null || !context.isValid()) {
            return;
        }
        MDC.put(REQUEST_ID, context.getRequestId());
        MDC.put(TRACE_ID, context.getTraceId());
        MDC.put(SPAN_ID, context.getCurrentSpanId());
    }

    public static void clear() {
        MDC.remove(REQUEST_ID);
        MDC.remove(TRACE_ID);
        MDC.remove(SPAN_ID);
    }

    public static void run(AiTraceContext context, Runnable action) {
        Map<String, String> previous = capture();
        try {
            set(context);
            action.run();
        } finally {
            restore(previous);
        }
    }
}
