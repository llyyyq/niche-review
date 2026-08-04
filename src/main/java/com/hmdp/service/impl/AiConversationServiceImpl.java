package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.AiChatModelClient;
import com.hmdp.ai.AiCompletionOptions;
import com.hmdp.ai.AiAgentRunner;
import com.hmdp.ai.AiPromptMessage;
import com.hmdp.ai.AiPromptBuildResult;
import com.hmdp.ai.AiQueryPreprocessor;
import com.hmdp.ai.AiRagEvaluationRequest;
import com.hmdp.ai.AiRagEvaluationResult;
import com.hmdp.ai.AiRetrievalQueryPlan;
import com.hmdp.ai.AiTokenEstimator;
import com.hmdp.ai.AiToolExecution;
import com.hmdp.ai.AiTraceContext;
import com.hmdp.ai.AiTraceMdc;
import com.hmdp.ai.AiTraceSpanScope;
import com.hmdp.ai.ShopKnowledge;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.AiConversationCreateRequest;
import com.hmdp.dto.AiConversationUpdateRequest;
import com.hmdp.dto.AiMessageSendRequest;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.AiConversation;
import com.hmdp.entity.AiMessage;
import com.hmdp.entity.AiRequestLog;
import com.hmdp.entity.AiToolLog;
import com.hmdp.mapper.AiConversationMapper;
import com.hmdp.config.AiMemoryProperties;
import com.hmdp.service.IAiConversationService;
import com.hmdp.service.IAiConversationMemoryService;
import com.hmdp.service.IAiRagEvaluationService;
import com.hmdp.service.IAiMessageService;
import com.hmdp.service.IAiReadOnlyToolService;
import com.hmdp.service.IAiRequestLogService;
import com.hmdp.service.IAiToolLogService;
import com.hmdp.service.IAiTraceService;
import com.hmdp.service.IShopKnowledgeService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import com.hmdp.config.AiChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class AiConversationServiceImpl extends ServiceImpl<AiConversationMapper, AiConversation>
        implements IAiConversationService, IAiRagEvaluationService {

    private static final int MAX_TITLE_LENGTH = 128;
    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final int AUTO_TITLE_LENGTH = 24;
    private static final String DEFAULT_CONVERSATION_TITLE = "\u65b0\u4f1a\u8bdd";

    @Resource
    private IAiMessageService aiMessageService;

    @Resource
    private AiChatModelClient aiChatModelClient;

    @Resource
    private AiChatProperties aiChatProperties;

    @Resource
    private IShopKnowledgeService shopKnowledgeService;

    @Resource
    private IAiReadOnlyToolService aiReadOnlyToolService;

    @Resource
    private AiAgentRunner aiAgentRunner;

    @Resource
    private AiQueryPreprocessor aiQueryPreprocessor;

    @Resource
    private IAiConversationMemoryService aiConversationMemoryService;

    @Resource
    private IAiRequestLogService aiRequestLogService;

    @Resource
    private IAiToolLogService aiToolLogService;

    @Resource
    private AiMemoryProperties aiMemoryProperties;

    @Resource
    private AiTokenEstimator aiTokenEstimator;

    @Resource
    private IAiTraceService aiTraceService;

    @Resource(name = "aiChatExecutor")
    private Executor aiChatExecutor;

    @Override
    public Result createConversation(AiConversationCreateRequest request) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }

        String title = request == null ? null : StrUtil.trim(request.getTitle());
        if (StrUtil.isBlank(title)) {
            title = DEFAULT_CONVERSATION_TITLE;
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            return Result.fail("会话标题不能超过128个字符");
        }

        LocalDateTime now = LocalDateTime.now();
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setTitle(title);
        conversation.setLastMessageAt(now);
        save(conversation);
        return Result.ok(conversation);
    }

    @Override
    public Result queryMyConversations(Long current) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }

        long pageNumber = current == null || current < 1 ? 1 : current;
        QueryWrapper<AiConversation> wrapper = new QueryWrapper<AiConversation>()
                .eq("user_id", userId)
                .orderByDesc("last_message_at")
                .orderByDesc("id");
        Page<AiConversation> page = page(
                new Page<>(pageNumber, SystemConstants.MAX_PAGE_SIZE),
                wrapper
        );
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    public Result queryMessages(Long conversationId) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        if (findOwnedConversation(conversationId, userId) == null) {
            return Result.fail("会话不存在或无访问权限");
        }

        List<AiMessage> messages = aiMessageService.query()
                .eq("conversation_id", conversationId)
                .eq("user_id", userId)
                .orderByAsc("id")
                .list();
        return Result.ok(messages);
    }

    @Override
    public Result updateConversationTitle(Long conversationId, AiConversationUpdateRequest request) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        if (findOwnedConversation(conversationId, userId) == null) {
            return Result.fail("会话不存在或无访问权限");
        }
        String title = request == null ? null : StrUtil.trim(request.getTitle());
        if (StrUtil.isBlank(title)) {
            return Result.fail("会话标题不能为空");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            return Result.fail("会话标题不能超过128个字符");
        }
        boolean updated = update()
                .eq("id", conversationId)
                .eq("user_id", userId)
                .set("title", title)
                .update();
        return updated ? Result.ok() : Result.fail("会话标题修改失败");
    }

    @Override
    @Transactional
    public Result deleteConversation(Long conversationId) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        if (findOwnedConversation(conversationId, userId) == null) {
            return Result.fail("会话不存在或无访问权限");
        }
        aiMessageService.remove(new QueryWrapper<AiMessage>()
                .eq("conversation_id", conversationId).eq("user_id", userId));
        aiRequestLogService.remove(new QueryWrapper<AiRequestLog>()
                .eq("conversation_id", conversationId).eq("user_id", userId));
        aiToolLogService.remove(new QueryWrapper<AiToolLog>()
                .eq("conversation_id", conversationId).eq("user_id", userId));
        boolean removed = remove(new QueryWrapper<AiConversation>()
                .eq("id", conversationId).eq("user_id", userId));
        return removed ? Result.ok() : Result.fail("删除会话失败");
    }

    @Override
    @Transactional
    public Result saveUserMessage(Long conversationId, AiMessageSendRequest request) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        if (findOwnedConversation(conversationId, userId) == null) {
            return Result.fail("会话不存在或无访问权限");
        }

        String content = request == null ? null : StrUtil.trim(request.getContent());
        if (StrUtil.isBlank(content)) {
            return Result.fail("消息内容不能为空");
        }
        if (content.length() > MAX_MESSAGE_LENGTH) {
            return Result.fail("消息内容不能超过4000个字符");
        }

        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(AiMessage.ROLE_USER);
        message.setContent(content);
        message.setStatus(AiMessage.STATUS_COMPLETED);
        if (!aiMessageService.save(message)) {
            throw new IllegalStateException("Failed to save AI user message");
        }

        AiConversation conversation = findOwnedConversation(conversationId, userId);
        if (!touchConversationAfterUserMessage(conversation, content)) {
            throw new IllegalStateException("Failed to update AI conversation activity time");
        }
        return Result.ok(message);
    }

    @Override
    @Transactional
    public SseEmitter chat(Long conversationId, AiMessageSendRequest request) {
        Long userId = currentUserId();
        if (userId == null) {
            throw new IllegalStateException("User must be logged in before AI chat");
        }
        AiConversation conversation = findOwnedConversation(conversationId, userId);
        if (conversation == null) {
            throw new IllegalArgumentException("AI conversation does not exist or is not owned by current user");
        }

        String content = validMessageContent(request);
        AiMessage userMessage = new AiMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setUserId(userId);
        userMessage.setRole(AiMessage.ROLE_USER);
        userMessage.setContent(content);
        userMessage.setStatus(AiMessage.STATUS_COMPLETED);
        if (!aiMessageService.save(userMessage)) {
            throw new IllegalStateException("Failed to save AI user message");
        }

        AiMessage assistantMessage = new AiMessage();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setUserId(userId);
        assistantMessage.setRole(AiMessage.ROLE_ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setStatus(AiMessage.STATUS_GENERATING);
        if (!aiMessageService.save(assistantMessage)) {
            throw new IllegalStateException("Failed to create AI assistant message");
        }

        if (!touchConversationAfterUserMessage(conversation, content)) {
            throw new IllegalStateException("Failed to update AI conversation activity time");
        }

        AiTraceContext traceContext = aiTraceService.startChatTrace(
                conversationId, userId, userMessage.getId(), assistantMessage.getId());
        SseEmitter emitter = new SseEmitter(aiChatProperties.getStreamTimeoutMs());
        emitter.onTimeout(() -> aiTraceService.cancelTrace(
                traceContext, "SSE_STREAM", new IllegalStateException("SSE stream timed out")));
        emitter.onError(error -> aiTraceService.cancelTrace(traceContext, "SSE_STREAM", error));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                aiChatExecutor.execute(() -> AiTraceMdc.run(traceContext, () ->
                        generateAndStream(
                                traceContext,
                                conversationId,
                                userId,
                                assistantMessage.getId(),
                                conversation,
                                userMessage.getId(),
                                content,
                                request == null ? null : request.getX(),
                                request == null ? null : request.getY(),
                                emitter
                        )));
            }
        });
        return emitter;
    }

    /**
     * Offline evidence entry point. It shares production query preprocessing, retrieval,
     * tool routing and model streaming, but intentionally does not create user messages
     * or SSE output.
     */
    @Override
    public AiRagEvaluationResult evaluate(AiRagEvaluationRequest request) {
        if (request == null || StrUtil.isBlank(request.getQuestion())) {
            throw new IllegalArgumentException("Evaluation question must not be blank");
        }
        String question = request.getQuestion().trim();
        AiTraceContext traceContext = aiTraceService.startLinkedTrace(
                "RAG_EVALUATION", null, null, null, null);
        long startedAt = System.currentTimeMillis();
        AtomicLong firstTokenMs = new AtomicLong(-1L);
        StringBuilder answer = new StringBuilder();
        AiPromptBuildResult promptBuildResult = null;
        AiTraceContext requestLogContext = traceContext;
        String failureStage = "QUERY_PREPROCESS";
        try {
            promptBuildResult = buildEvaluationPrompt(traceContext, question, request.getHistory());
            if (StrUtil.isNotBlank(promptBuildResult.getDirectResponse())) {
                firstTokenMs.set(System.currentTimeMillis() - startedAt);
                aiTraceService.markFirstToken(traceContext);
                answer.append(promptBuildResult.getDirectResponse());
            } else {
                failureStage = "FINAL_MODEL";
                Map<String, Object> modelAttributes = new LinkedHashMap<>();
                modelAttributes.put("provider", aiChatProperties.getProvider());
                modelAttributes.put("model", aiChatProperties.getModel());
                modelAttributes.put("inputTokens", promptBuildResult.getInputTokens());
                AiTraceSpanScope modelSpan = aiTraceService.startSpan(traceContext, "FINAL_MODEL", modelAttributes);
                requestLogContext = modelSpan.getContext();
                try {
                    aiChatModelClient.stream(promptBuildResult.getMessages(), delta -> {
                        if (firstTokenMs.compareAndSet(-1L, System.currentTimeMillis() - startedAt)) {
                            aiTraceService.markFirstToken(traceContext);
                        }
                        answer.append(delta);
                    });
                    Map<String, Object> completed = new LinkedHashMap<>(modelAttributes);
                    completed.put("outputTokens", aiTokenEstimator.estimateText(answer.toString()));
                    modelSpan.success(completed);
                } catch (Exception e) {
                    modelSpan.failure(e);
                    throw e;
                }
            }
            int outputTokens = aiTokenEstimator.estimateText(answer.toString());
            long totalMs = System.currentTimeMillis() - startedAt;
            saveRequestLog(requestLogContext, null, null, null, "rag_answer_evaluation", promptBuildResult,
                    firstTokenMs.get(), totalMs, outputTokens, 1, null);
            aiTraceService.completeTrace(traceContext, promptBuildResult.getOutcome());
            return evaluationResult(traceContext, promptBuildResult, answer.toString(),
                    firstTokenMs.get(), totalMs, null);
        } catch (Exception e) {
            log.error("RAG answer evaluation failed, traceId={}", traceContext.getTraceId(), e);
            if (promptBuildResult == null) {
                promptBuildResult = new AiPromptBuildResult(
                        Collections.<AiPromptMessage>emptyList(), 0L, 0L, 0);
            }
            long totalMs = System.currentTimeMillis() - startedAt;
            saveRequestLog(requestLogContext, null, null, null, "rag_answer_evaluation", promptBuildResult,
                    firstTokenMs.get(), totalMs, aiTokenEstimator.estimateText(answer.toString()), 0, e.getMessage());
            aiTraceService.failTrace(traceContext, failureStage, e);
            return evaluationResult(traceContext, promptBuildResult, answer.toString(),
                    firstTokenMs.get(), totalMs, limitText(e.getMessage(), 512));
        }
    }

    private AiRagEvaluationResult evaluationResult(AiTraceContext traceContext,
                                                    AiPromptBuildResult promptBuildResult,
                                                    String answer, long firstTokenMs,
                                                    long totalMs, String errorMessage) {
        AiRetrievalQueryPlan plan = promptBuildResult.getQueryPlan();
        return new AiRagEvaluationResult(
                traceContext.getRequestId(), traceContext.getTraceId(),
                plan == null ? "UNKNOWN" : plan.getMode().name(),
                plan == null ? Collections.<String>emptyList() : plan.getQueries(),
                plan != null && plan.isModelCalled(),
                plan != null && plan.isValidModelOutput(),
                promptBuildResult.getOutcome(),
                answer, promptBuildResult.getRetrievedShops(), promptBuildResult.getToolExecutions(),
                promptBuildResult.getRetrievalMs(), promptBuildResult.getToolMs(),
                firstTokenMs, totalMs, errorMessage);
    }

    private void generateAndStream(AiTraceContext traceContext,
                                   Long conversationId,
                                   Long userId,
                                   Long assistantMessageId,
                                   AiConversation conversation,
                                   Long currentMessageId,
                                   String currentQuestion,
                                   Double x,
                                   Double y,
                                   SseEmitter emitter) {
        StringBuilder response = new StringBuilder();
        AtomicLong firstTokenMs = new AtomicLong(-1L);
        long modelStartedAt = System.currentTimeMillis();
        AiPromptBuildResult promptBuildResult = null;
        AiTraceSpanScope streamSpan = aiTraceService.startSpan(traceContext, "SSE_STREAM");
        AiTraceContext requestLogContext = traceContext;
        String failureStage = "CHAT";
        try {
            emitter.send(SseEmitter.event().name("message_start")
                    .data(messageStartData(traceContext, conversationId, assistantMessageId)));
            failureStage = "QUERY_PREPROCESS";
            promptBuildResult = buildPrompt(traceContext, conversation, assistantMessageId,
                    currentMessageId, currentQuestion, x, y);
            if (StrUtil.isNotBlank(promptBuildResult.getDirectResponse())) {
                if (firstTokenMs.compareAndSet(-1L, System.currentTimeMillis() - modelStartedAt)) {
                    aiTraceService.markFirstToken(traceContext);
                }
                response.append(promptBuildResult.getDirectResponse());
                emitter.send(SseEmitter.event().name("delta")
                        .data(Collections.singletonMap("content", promptBuildResult.getDirectResponse())));
            } else {
                failureStage = "FINAL_MODEL";
                Map<String, Object> modelAttributes = new LinkedHashMap<>();
                modelAttributes.put("provider", aiChatProperties.getProvider());
                modelAttributes.put("model", aiChatProperties.getModel());
                modelAttributes.put("inputTokens", promptBuildResult.getInputTokens());
                AiTraceSpanScope modelSpan = aiTraceService.startSpan(traceContext, "FINAL_MODEL", modelAttributes);
                requestLogContext = modelSpan.getContext();
                try {
                    aiChatModelClient.stream(promptBuildResult.getMessages(), delta -> {
                        if (firstTokenMs.compareAndSet(-1L, System.currentTimeMillis() - modelStartedAt)) {
                            aiTraceService.markFirstToken(traceContext);
                        }
                        response.append(delta);
                        emitter.send(SseEmitter.event().name("delta")
                                .data(Collections.singletonMap("content", delta)));
                    });
                    Map<String, Object> completedAttributes = new LinkedHashMap<>(modelAttributes);
                    completedAttributes.put("outputTokens", aiTokenEstimator.estimateText(response.toString()));
                    modelSpan.success(completedAttributes);
                } catch (Exception e) {
                    modelSpan.failure(e);
                    throw e;
                }
            }
            int outputTokens = aiTokenEstimator.estimateText(response.toString());
            failureStage = "MESSAGE_PERSIST";
            AiTraceSpanScope persistSpan = aiTraceService.startSpan(traceContext, "MESSAGE_PERSIST");
            try {
                updateAssistantMessage(assistantMessageId, response.toString(), AiMessage.STATUS_COMPLETED,
                        promptBuildResult.getInputTokens(), outputTokens);
                persistSpan.success();
            } catch (RuntimeException e) {
                persistSpan.failure(e);
                throw e;
            }
            saveRequestLog(requestLogContext, conversationId, userId, assistantMessageId, "chat", promptBuildResult,
                    firstTokenMs.get(), System.currentTimeMillis() - modelStartedAt, outputTokens, 1, null);
            failureStage = "SSE_STREAM";
            emitter.send(SseEmitter.event().name("message_end")
                    .data(messageEndData(traceContext, assistantMessageId, "stop")));
            streamSpan.success(Collections.<String, Object>singletonMap("event", "message_end"));
            aiTraceService.completeTrace(traceContext, promptBuildResult.getOutcome());
            emitter.complete();
            aiChatExecutor.execute(() -> {
                AiTraceContext summaryTrace = aiTraceService.startLinkedTrace(
                        "SUMMARY", traceContext.getTraceId(), conversationId, userId, assistantMessageId);
                AiTraceMdc.run(summaryTrace, () ->
                        aiConversationMemoryService.summarizeIfNeeded(summaryTrace, conversationId, userId));
            });
        } catch (Exception e) {
            streamSpan.failure(e);
            log.error("AI chat generation failed, assistantMessageId={}, traceId={}",
                    assistantMessageId, traceContext.getTraceId(), e);
            if (promptBuildResult == null) {
                promptBuildResult = new AiPromptBuildResult(Collections.<AiPromptMessage>emptyList(), 0L, 0L, 0);
            }
            int outputTokens = aiTokenEstimator.estimateText(response.toString());
            try {
                updateAssistantMessage(assistantMessageId, response.toString(), AiMessage.STATUS_FAILED,
                        promptBuildResult.getInputTokens(), outputTokens);
            } catch (RuntimeException persistError) {
                log.error("Failed to persist failed AI message, messageId={}", assistantMessageId, persistError);
            }
            saveRequestLog(requestLogContext, conversationId, userId, assistantMessageId, "chat", promptBuildResult,
                    firstTokenMs.get(), System.currentTimeMillis() - modelStartedAt, outputTokens, 0, e.getMessage());
            aiTraceService.failTrace(traceContext, failureStage, e);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(errorData(traceContext, "AI_GENERATION_FAILED", "AI回复生成失败")));
            } catch (Exception ignored) {
                // The browser can close the stream before the error event is sent.
            }
            emitter.completeWithError(e);
        }
    }

    private Map<String, Object> messageStartData(AiTraceContext traceContext,
                                                 Long conversationId, Long messageId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", traceContext.getRequestId());
        data.put("traceId", traceContext.getTraceId());
        data.put("conversationId", conversationId);
        data.put("messageId", messageId);
        return data;
    }

    private Map<String, Object> messageEndData(AiTraceContext traceContext,
                                               Long messageId, String finishReason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", traceContext.getRequestId());
        data.put("traceId", traceContext.getTraceId());
        data.put("messageId", messageId);
        data.put("finishReason", finishReason);
        return data;
    }

    private Map<String, Object> errorData(AiTraceContext traceContext, String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", traceContext.getRequestId());
        data.put("traceId", traceContext.getTraceId());
        data.put("code", code);
        data.put("message", message);
        return data;
    }

    private AiPromptBuildResult buildPrompt(AiConversation conversation, Long assistantMessageId,
                                            Long currentMessageId, String currentQuestion,
                                            Double x, Double y) {
        return buildPrompt(null, conversation, assistantMessageId,
                currentMessageId, currentQuestion, x, y);
    }

    private AiPromptBuildResult buildPrompt(AiTraceContext traceContext,
                                            AiConversation conversation, Long assistantMessageId,
                                            Long currentMessageId, String currentQuestion,
                                            Double x, Double y) {
        long pageSize = Math.max(1, aiChatProperties.getContextMessageLimit());
        QueryWrapper<AiMessage> wrapper = new QueryWrapper<AiMessage>()
                .eq("conversation_id", conversation.getId())
                .eq("user_id", conversation.getUserId())
                .eq("status", AiMessage.STATUS_COMPLETED)
                .orderByDesc("id");
        Page<AiMessage> page = aiMessageService.page(
                new Page<>(1, pageSize),
                wrapper
        );
        List<AiPromptMessage> recentMessages = buildRecentMessageContext(page.getRecords(), currentMessageId);
        return assemblePrompt(traceContext, conversation.getId(), conversation.getUserId(),
                conversation.getSummary(), assistantMessageId, recentMessages, currentQuestion, x, y);
    }

    private AiPromptBuildResult buildEvaluationPrompt(AiTraceContext traceContext, String currentQuestion,
                                                       List<AiPromptMessage> history) {
        return assemblePrompt(traceContext, null, null, null, null,
                history == null ? Collections.<AiPromptMessage>emptyList() : history,
                currentQuestion, null, null);
    }

    private AiPromptBuildResult assemblePrompt(AiTraceContext traceContext, Long conversationId,
                                                Long userId, String summary, Long assistantMessageId,
                                                List<AiPromptMessage> recentMessages,
                                                String currentQuestion, Double x, Double y) {
        long retrievalStartedAt = System.currentTimeMillis();
        AiRetrievalQueryPlan queryPlan = traceContext == null
                ? aiQueryPreprocessor.preprocess(
                        conversationId, userId, assistantMessageId, currentQuestion,
                        summary, recentMessages)
                : aiQueryPreprocessor.preprocess(
                        traceContext, conversationId, userId, assistantMessageId, currentQuestion,
                        summary, recentMessages);
        if (queryPlan.requiresClarification()) {
            return new AiPromptBuildResult(
                    Collections.<AiPromptMessage>emptyList(),
                    System.currentTimeMillis() - retrievalStartedAt,
                    0L,
                    0,
                    queryPlan.getClarification(),
                    "CLARIFIED",
                    queryPlan,
                    Collections.<ShopKnowledge>emptyList(),
                    Collections.<AiToolExecution>emptyList()
            );
        }
        List<ShopKnowledge> retrievedShops = traceContext == null
                ? shopKnowledgeService.searchRelevantShops(queryPlan.getQueries())
                : shopKnowledgeService.searchRelevantShops(traceContext, queryPlan.getQueries());
        long retrievalMs = System.currentTimeMillis() - retrievalStartedAt;
        long toolStartedAt = System.currentTimeMillis();
        boolean businessEvidenceRequired = requiresBusinessEvidence(currentQuestion);
        List<AiToolExecution> toolExecutions;
        if (businessEvidenceRequired && retrievedShops.isEmpty()) {
            toolExecutions = Collections.emptyList();
        } else {
            toolExecutions = traceContext == null
                    ? aiAgentRunner.run(conversationId, userId, currentQuestion, x, y, retrievedShops)
                    : aiAgentRunner.run(traceContext, conversationId, userId,
                            currentQuestion, x, y, retrievedShops);
        }
        long toolMs = System.currentTimeMillis() - toolStartedAt;
        List<AiPromptMessage> promptMessages = new ArrayList<>(recentMessages.size() + 6);
        promptMessages.add(new AiPromptMessage("system", aiChatProperties.getSystemPrompt()));
        promptMessages.add(new AiPromptMessage("system", buildResponseStylePrompt()));
        if (hasValidLocation(x, y)) {
            promptMessages.add(new AiPromptMessage("system", buildClientLocationContext(x, y)));
        }
        if (StrUtil.isNotBlank(summary)) {
            promptMessages.add(new AiPromptMessage("system", "Conversation memory:\n"
                    + limitText(summary, aiMemoryProperties.getMaxSummaryChars())));
        }
        promptMessages.addAll(recentMessages);
        if (!retrievedShops.isEmpty()) {
            promptMessages.add(new AiPromptMessage("system", limitText(buildShopKnowledgeContext(retrievedShops),
                    aiMemoryProperties.getMaxKnowledgeChars())));
        }
        if (!toolExecutions.isEmpty()) {
            promptMessages.add(new AiPromptMessage("system", limitText(buildToolContext(toolExecutions),
                    aiMemoryProperties.getMaxToolResultChars())));
        }
        promptMessages.add(new AiPromptMessage("user", currentQuestion));
        String directResponse = businessEvidenceRequired
                && retrievedShops.isEmpty()
                && toolExecutions.isEmpty()
                ? buildNoEvidenceResponse()
                : null;
        int inputTokens = directResponse == null ? aiTokenEstimator.estimateMessages(promptMessages) : 0;
        return new AiPromptBuildResult(
                promptMessages, retrievalMs, toolMs, inputTokens, directResponse,
                directResponse == null ? "ANSWERED" : "NO_EVIDENCE",
                queryPlan, retrievedShops, toolExecutions);
    }

    private boolean requiresBusinessEvidence(String question) {
        if (StrUtil.isBlank(question)) {
            return false;
        }
        String normalized = question.toLowerCase();
        return containsAny(normalized,
                "\u5e97", "\u9910\u5385", "\u9910\u9986", "ktv", "\u4f18\u60e0",
                "\u4ee3\u91d1\u5238", "\u8425\u4e1a", "\u5730\u5740", "\u4eba\u5747",
                "\u8bc4\u5206", "\u63a2\u5e97", "\u9644\u8fd1", "\u5546\u5708",
                "\u706b\u9505", "\u5496\u5561\u9986", "\u7f8e\u98df", "\u805a\u9910");
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String buildNoEvidenceResponse() {
        return "\u5f53\u524d\u5c0f\u4f17\u70b9\u8bc4\u7684\u8d44\u6599\u4e2d\u6ca1\u6709"
                + "\u627e\u5230\u4e0e\u4f60\u95ee\u9898\u76f8\u5339\u914d\u7684\u5e97\u94fa"
                + "\u6216\u4f18\u60e0\u4fe1\u606f\u3002\u8bf7\u63d0\u4f9b\u66f4\u51c6\u786e"
                + "\u7684\u5e97\u540d\u3001\u5546\u5708\u6216\u5206\u7c7b\uff0c\u6211\u518d"
                + "\u5e2e\u4f60\u67e5\u8be2\u3002";
    }

    private String buildResponseStylePrompt() {
        return "Respond in the user's language. You are a practical local-life assistant. "
                + "When recommending stores, provide at most three numbered choices. "
                + "For each choice, show the store name and only the relevant known facts such as category, rating, average spend, address, opening hours, vouchers, or public-review highlights. "
                + "Evidence priority is: live business-tool result first, then retrieved knowledge. "
                + "Voucher availability, price, stock, validity, latest public blogs, distance, and current opening status "
                + "must be stated only when the live result explicitly supports the same store. "
                + "Never combine voucher values, calculate a discount plan, or move a fact from one store to another. "
                + "Do not expose retrieval scores, internal document labels, database field names, or raw knowledge text. "
                + "Do not invent stores, discounts, exact distances, availability, or facts missing from the supplied context. "
                + "When evidence covers only part of a multi-part question, answer the supported part and explicitly say which part is unavailable. "
                + "If the supplied information is insufficient, state that clearly and suggest a narrower query.";
    }

    private String buildToolContext(List<AiToolExecution> toolExecutions) {
        StringBuilder context = new StringBuilder();
        context.append("The following are live read-only business-tool results. They are more current than the vector knowledge. ")
                .append("Use them for time-sensitive facts such as voucher availability, stock-related availability, latest public blogs, and distance. ")
                .append("Do not mention internal tool names or raw field names to the user.\n");
        for (AiToolExecution execution : toolExecutions) {
            context.append("[Live result] ").append(execution.getResultContent()).append('\n');
        }
        return context.toString();
    }

    private List<AiPromptMessage> buildRecentMessageContext(List<AiMessage> records, Long currentMessageId) {
        int remainingChars = safeLimit(aiMemoryProperties.getMaxRecentMessageChars());
        List<AiPromptMessage> selected = new ArrayList<>();
        for (AiMessage message : records) {
            if (message.getId().equals(currentMessageId) || remainingChars <= 0) {
                continue;
            }
            String content = limitText(message.getContent(), remainingChars);
            selected.add(new AiPromptMessage(toModelRole(message.getRole()), content));
            remainingChars -= content.length();
        }
        Collections.reverse(selected);
        return selected;
    }

    private boolean hasValidLocation(Double x, Double y) {
        return x != null && y != null && x >= -180 && x <= 180 && y >= -90 && y <= 90;
    }

    private String buildClientLocationContext(Double x, Double y) {
        return "The user has granted browser location permission for this request. "
                + "Never say that you cannot obtain the user's current location. "
                + "Do not reveal, infer, or repeat the user's precise coordinates. "
                + "This application does not reverse-geocode coordinates into a street or district. "
                + "For nearby-store questions, use the live nearby-store tool result when available. "
                + "If that result is empty, say that no indexed store was found within the current search range, not that location is unavailable.";
    }

    private String buildShopKnowledgeContext(List<ShopKnowledge> shops) {
        StringBuilder context = new StringBuilder();
        context.append("以下是从小众点评真实店铺知识库检索到的资料。")
                .append("涉及店铺名称、地址、人均、评分、营业时间等事实时，只能依据这些资料回答；")
                .append("资料不足时明确说明，不要编造店铺或优惠券。\n");
        for (int i = 0; i < shops.size(); i++) {
            context.append("[店铺资料").append(i + 1).append("] ")
                    .append(shops.get(i).getContent()).append('\n');
        }
        return context.toString();
    }

    private void updateAssistantMessage(Long messageId, String content, int status, int inputTokens, int outputTokens) {
        boolean updated = aiMessageService.update()
                .eq("id", messageId)
                .set("content", content)
                .set("status", status)
                .set("input_tokens", inputTokens)
                .set("output_tokens", outputTokens)
                .update();
        if (!updated) {
            log.error("Failed to update AI assistant message, messageId={}", messageId);
            throw new IllegalStateException("Failed to update AI assistant message");
        }
    }

    private void saveRequestLog(AiTraceContext traceContext,
                                Long conversationId, Long userId, Long assistantMessageId, String requestType,
                                AiPromptBuildResult promptBuildResult, long firstTokenMs, long totalMs,
                                int outputTokens, int success, String errorMessage) {
        try {
            AiRequestLog requestLog = new AiRequestLog();
            requestLog.setConversationId(conversationId);
            requestLog.setUserId(userId);
            requestLog.setAssistantMessageId(assistantMessageId);
            if (traceContext != null && traceContext.isValid()) {
                requestLog.setRequestId(traceContext.getRequestId());
                requestLog.setTraceId(traceContext.getTraceId());
                requestLog.setSpanId(traceContext.getCurrentSpanId());
                requestLog.setParentSpanId(traceContext.getParentSpanId());
            }
            requestLog.setRequestType(requestType);
            requestLog.setProvider(aiChatProperties.getProvider());
            requestLog.setModel(aiChatProperties.getModel());
            requestLog.setRetrievalMs(promptBuildResult.getRetrievalMs());
            requestLog.setToolMs(promptBuildResult.getToolMs());
            requestLog.setFirstTokenMs(firstTokenMs < 0 ? null : firstTokenMs);
            requestLog.setTotalMs(totalMs);
            requestLog.setInputTokens(promptBuildResult.getInputTokens());
            requestLog.setOutputTokens(outputTokens);
            requestLog.setSuccess(success);
            requestLog.setErrorMessage(limitText(errorMessage, 512));
            aiRequestLogService.save(requestLog);
        } catch (Exception e) {
            log.error("Failed to save AI request log, conversationId={}", conversationId, e);
        }
    }

    private String limitText(String text, Integer limit) {
        if (text == null) {
            return "";
        }
        int safeLimit = safeLimit(limit);
        return text.length() <= safeLimit ? text : text.substring(0, safeLimit) + "...";
    }

    private int safeLimit(Integer limit) {
        return limit == null || limit < 1 ? 1 : limit;
    }

    private boolean touchConversationAfterUserMessage(AiConversation conversation, String content) {
        if (conversation == null) {
            return false;
        }
        com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<AiConversation> updateChain = lambdaUpdate()
                .eq(AiConversation::getId, conversation.getId())
                .eq(AiConversation::getUserId, conversation.getUserId())
                .set(AiConversation::getLastMessageAt, LocalDateTime.now());
        if (StrUtil.isBlank(conversation.getTitle()) || DEFAULT_CONVERSATION_TITLE.equals(conversation.getTitle())) {
            updateChain.set(AiConversation::getTitle, buildAutoTitle(content));
        }
        return updateChain.update();
    }

    private String buildAutoTitle(String content) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= AUTO_TITLE_LENGTH
                ? normalized
                : normalized.substring(0, AUTO_TITLE_LENGTH) + "...";
    }

    private String validMessageContent(AiMessageSendRequest request) {
        String content = request == null ? null : StrUtil.trim(request.getContent());
        if (StrUtil.isBlank(content)) {
            throw new IllegalArgumentException("AI message content must not be blank");
        }
        if (content.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("AI message content is too long");
        }
        return content;
    }

    private String toModelRole(Integer role) {
        if (AiMessage.ROLE_ASSISTANT == role) {
            return "assistant";
        }
        if (AiMessage.ROLE_SYSTEM == role) {
            return "system";
        }
        return "user";
    }

    private AiConversation findOwnedConversation(Long conversationId, Long userId) {
        if (conversationId == null) {
            return null;
        }
        return query()
                .eq("id", conversationId)
                .eq("user_id", userId)
                .one();
    }

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }

    private static class AiError {
        private final String code;
        private final String message;

        private AiError(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }
}
