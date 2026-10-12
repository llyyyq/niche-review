package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.ai.AiChatModelClient;
import com.hmdp.ai.AiStreamObserver;
import com.hmdp.ai.AiTokenEstimator;
import com.hmdp.config.AiChatProperties;
import com.hmdp.config.AiMemoryProperties;
import com.hmdp.entity.AiConversation;
import com.hmdp.entity.AiMessage;
import com.hmdp.mapper.AiConversationMapper;
import com.hmdp.service.IAiMessageService;
import com.hmdp.service.IAiRequestLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiConversationMemoryServiceImplTest {

    @Mock
    private AiConversationMapper aiConversationMapper;

    @Mock
    private IAiMessageService aiMessageService;

    @Mock
    private AiChatModelClient aiChatModelClient;

    @Mock
    private IAiRequestLogService aiRequestLogService;

    private AiChatProperties chatProperties;
    private AiConversationMemoryServiceImpl service;

    @BeforeEach
    void setUp() {
        chatProperties = new AiChatProperties();
        service = new AiConversationMemoryServiceImpl();
        ReflectionTestUtils.setField(service, "aiConversationMapper", aiConversationMapper);
        ReflectionTestUtils.setField(service, "aiMessageService", aiMessageService);
        ReflectionTestUtils.setField(service, "aiChatModelClient", aiChatModelClient);
        ReflectionTestUtils.setField(service, "aiChatProperties", chatProperties);
        ReflectionTestUtils.setField(service, "aiMemoryProperties", new AiMemoryProperties());
        ReflectionTestUtils.setField(service, "aiTokenEstimator", new AiTokenEstimator());
        ReflectionTestUtils.setField(service, "aiRequestLogService", aiRequestLogService);
    }

    @Test
    void mockProviderSkipsSummaryWithoutModelCallOrWrite() {
        service.summarizeIfNeeded(null, 1L, 2L);

        verify(aiConversationMapper, never()).selectById(any());
        verify(aiMessageService, never()).list(any());
        verify(aiConversationMapper, never()).update(any(), any());
    }

    @Test
    void summaryAboveTriggerWritesSummaryUpToLastOlderMessage() throws Exception {
        chatProperties.setProvider("openai-compatible");
        when(aiConversationMapper.selectById(1L)).thenReturn(conversation(null));
        when(aiMessageService.list(any())).thenReturn(completedMessages(13));
        stubSummaryModel("用户偏好火锅。");
        when(aiConversationMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        service.summarizeIfNeeded(null, 1L, 2L);

        verify(aiConversationMapper).update(isNull(), any(UpdateWrapper.class));
        verify(aiRequestLogService).save(any());
    }

    @Test
    void summaryAtOrBelowTriggerDoesNotCallModel() throws Exception {
        chatProperties.setProvider("openai-compatible");
        when(aiConversationMapper.selectById(1L)).thenReturn(conversation(null));
        when(aiMessageService.list(any())).thenReturn(completedMessages(12));

        service.summarizeIfNeeded(null, 1L, 2L);

        verify(aiChatModelClient, never()).stream(anyList(), any());
        verify(aiConversationMapper, never()).update(any(), any());
    }

    @Test
    void supersededWatermarkLeavesNewerSummaryAndDoesNotFail() throws Exception {
        chatProperties.setProvider("openai-compatible");
        when(aiConversationMapper.selectById(1L)).thenReturn(conversation(5L));
        when(aiMessageService.list(any())).thenReturn(completedMessages(13));
        stubSummaryModel("用户偏好火锅。");
        when(aiConversationMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        service.summarizeIfNeeded(null, 1L, 2L);

        verify(aiConversationMapper).update(isNull(), any(UpdateWrapper.class));
        verify(aiRequestLogService).save(any());
    }

    private void stubSummaryModel(String summary) throws Exception {
        doAnswer(invocation -> {
            AiStreamObserver observer = invocation.getArgument(1);
            observer.onDelta(summary);
            return null;
        }).when(aiChatModelClient).stream(anyList(), any());
    }

    private static AiConversation conversation(Long summaryUpToMessageId) {
        AiConversation conversation = new AiConversation();
        conversation.setId(1L);
        conversation.setUserId(2L);
        conversation.setSummaryUpToMessageId(summaryUpToMessageId);
        return conversation;
    }

    private static List<AiMessage> completedMessages(int count) {
        List<AiMessage> messages = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            AiMessage message = new AiMessage();
            message.setId(id);
            message.setRole(id % 2 == 1 ? AiMessage.ROLE_USER : AiMessage.ROLE_ASSISTANT);
            message.setContent("message " + id);
            message.setStatus(AiMessage.STATUS_COMPLETED);
            messages.add(message);
        }
        return messages;
    }
}
