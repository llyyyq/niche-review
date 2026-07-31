package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.ai.AiTraceContext;
import com.hmdp.ai.AiTraceSpanScope;
import com.hmdp.entity.AiTrace;

import java.util.Map;

public interface IAiTraceService extends IService<AiTrace> {

    AiTraceContext startChatTrace(Long conversationId, Long userId,
                                  Long userMessageId, Long assistantMessageId);

    AiTraceContext startLinkedTrace(String traceType, String linkedTraceId,
                                    Long conversationId, Long userId, Long assistantMessageId);

    AiTraceSpanScope startSpan(AiTraceContext parent, String stageName);

    AiTraceSpanScope startSpan(AiTraceContext parent, String stageName, Map<String, Object> attributes);

    void completeSpan(AiTraceContext spanContext, boolean success,
                      Map<String, Object> attributes, Throwable error, long durationMs);

    void updateCurrentStage(AiTraceContext context, String stageName);

    void markFirstToken(AiTraceContext context);

    void completeTrace(AiTraceContext context, String outcome);

    void failTrace(AiTraceContext context, String errorStage, Throwable error);

    void cancelTrace(AiTraceContext context, String errorStage, Throwable error);
}
