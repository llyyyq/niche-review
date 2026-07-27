package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.AiChatModelClient;
import com.hmdp.ai.AiPromptMessage;
import com.hmdp.ai.AiTokenEstimator;
import com.hmdp.config.AiChatProperties;
import com.hmdp.config.AiMemoryProperties;
import com.hmdp.entity.AiConversation;
import com.hmdp.entity.AiMessage;
import com.hmdp.entity.AiRequestLog;
import com.hmdp.mapper.AiConversationMapper;
import com.hmdp.service.IAiConversationMemoryService;
import com.hmdp.service.IAiMessageService;
import com.hmdp.service.IAiRequestLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class AiConversationMemoryServiceImpl implements IAiConversationMemoryService {

    @Resource
    private AiConversationMapper aiConversationMapper;

    @Resource
    private IAiMessageService aiMessageService;

    @Resource
    private AiChatModelClient aiChatModelClient;

    @Resource
    private AiChatProperties aiChatProperties;

    @Resource
    private AiMemoryProperties aiMemoryProperties;

    @Resource
    private AiTokenEstimator aiTokenEstimator;

    @Resource
    private IAiRequestLogService aiRequestLogService;

    @Override
    public void summarizeIfNeeded(Long conversationId, Long userId) {
        long startedAt = System.currentTimeMillis();
        AtomicLong firstTokenMs = new AtomicLong(-1L);
        int inputTokens = 0;
        StringBuilder generatedSummary = new StringBuilder();
        try {
            AiConversation conversation = aiConversationMapper.selectById(conversationId);
            if (conversation == null || !userId.equals(conversation.getUserId())) {
                return;
            }
            List<AiMessage> unsummarized = findUnsummarizedMessages(conversationId, userId,
                    conversation.getSummaryUpToMessageId());
            int triggerCount = Math.max(1, aiMemoryProperties.getSummaryTriggerMessageCount());
            int recentLimit = Math.max(1, aiChatProperties.getContextMessageLimit());
            if (unsummarized.size() <= triggerCount || unsummarized.size() <= recentLimit) {
                return;
            }

            int summaryEndExclusive = unsummarized.size() - recentLimit;
            List<AiMessage> messagesToSummarize = unsummarized.subList(0, summaryEndExclusive);
            List<AiPromptMessage> summaryPrompt = buildSummaryPrompt(conversation.getSummary(), messagesToSummarize);
            inputTokens = aiTokenEstimator.estimateMessages(summaryPrompt);
            final long modelStartedAt = System.currentTimeMillis();
            aiChatModelClient.stream(summaryPrompt, delta -> {
                if (firstTokenMs.compareAndSet(-1L, System.currentTimeMillis() - modelStartedAt)) {
                    // Capture the first-token timing only once.
                }
                generatedSummary.append(delta);
            });

            String summary = limitText(generatedSummary.toString(), aiMemoryProperties.getMaxSummaryChars());
            if (StrUtil.isBlank(summary)) {
                throw new IllegalStateException("AI summary model returned an empty response");
            }
            AiMessage lastSummarized = messagesToSummarize.get(messagesToSummarize.size() - 1);
            conversation.setSummary(summary);
            conversation.setSummaryUpToMessageId(lastSummarized.getId());
            aiConversationMapper.updateById(conversation);
            saveRequestLog(conversationId, userId, "summary", null, 0L, 0L,
                    firstTokenMs.get(), System.currentTimeMillis() - modelStartedAt,
                    inputTokens, aiTokenEstimator.estimateText(summary), 1, null);
            log.info("AI conversation summary updated, conversationId={}, summaryUpToMessageId={}",
                    conversationId, lastSummarized.getId());
        } catch (Exception e) {
            log.warn("AI conversation summary skipped, conversationId={}", conversationId, e);
            saveRequestLog(conversationId, userId, "summary", null, 0L, 0L,
                    firstTokenMs.get(), System.currentTimeMillis() - startedAt,
                    inputTokens, aiTokenEstimator.estimateText(generatedSummary.toString()), 0, e.getMessage());
        }
    }

    private List<AiMessage> findUnsummarizedMessages(Long conversationId, Long userId, Long summaryUpToMessageId) {
        QueryWrapper<AiMessage> wrapper = new QueryWrapper<AiMessage>()
                .eq("conversation_id", conversationId)
                .eq("user_id", userId)
                .eq("status", AiMessage.STATUS_COMPLETED)
                .orderByAsc("id");
        if (summaryUpToMessageId != null) {
            wrapper.gt("id", summaryUpToMessageId);
        }
        return aiMessageService.list(wrapper);
    }

    private List<AiPromptMessage> buildSummaryPrompt(String existingSummary, List<AiMessage> messages) {
        List<AiPromptMessage> prompt = new ArrayList<>(2);
        prompt.add(new AiPromptMessage("system", "Create a compact factual conversation memory. Preserve user preferences, budget, location constraints, selected stores, confirmed facts, and unresolved questions. Do not invent facts. Do not include conversational filler."));
        StringBuilder source = new StringBuilder();
        if (StrUtil.isNotBlank(existingSummary)) {
            source.append("Existing memory:\n").append(existingSummary).append("\n\n");
        }
        source.append("New messages to merge:\n");
        for (AiMessage message : messages) {
            source.append(message.getRole() == AiMessage.ROLE_USER ? "User: " : "Assistant: ")
                    .append(message.getContent()).append('\n');
        }
        prompt.add(new AiPromptMessage("user", limitText(source.toString(), aiMemoryProperties.getMaxSummarySourceChars())));
        return prompt;
    }

    private String limitText(String text, Integer limit) {
        if (text == null) {
            return "";
        }
        int safeLimit = Math.max(1, limit == null ? 1 : limit);
        return text.length() <= safeLimit ? text : text.substring(0, safeLimit) + "...";
    }

    private void saveRequestLog(Long conversationId, Long userId, String requestType, Long assistantMessageId,
                                long retrievalMs, long toolMs, long firstTokenMs, long totalMs,
                                int inputTokens, int outputTokens, int success, String errorMessage) {
        try {
            AiRequestLog requestLog = new AiRequestLog();
            requestLog.setConversationId(conversationId);
            requestLog.setUserId(userId);
            requestLog.setAssistantMessageId(assistantMessageId);
            requestLog.setRequestType(requestType);
            requestLog.setProvider(aiChatProperties.getProvider());
            requestLog.setModel(aiChatProperties.getModel());
            requestLog.setRetrievalMs(retrievalMs);
            requestLog.setToolMs(toolMs);
            requestLog.setFirstTokenMs(firstTokenMs < 0 ? null : firstTokenMs);
            requestLog.setTotalMs(totalMs);
            requestLog.setInputTokens(inputTokens);
            requestLog.setOutputTokens(outputTokens);
            requestLog.setSuccess(success);
            requestLog.setErrorMessage(limitText(errorMessage, 512));
            aiRequestLogService.save(requestLog);
        } catch (Exception logError) {
            log.error("Failed to save AI request log, conversationId={}", conversationId, logError);
        }
    }
}
