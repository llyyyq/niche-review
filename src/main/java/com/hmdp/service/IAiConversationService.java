package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.AiConversationCreateRequest;
import com.hmdp.dto.AiConversationUpdateRequest;
import com.hmdp.dto.AiMessageSendRequest;
import com.hmdp.dto.Result;
import com.hmdp.entity.AiConversation;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface IAiConversationService extends IService<AiConversation> {

    Result createConversation(AiConversationCreateRequest request);

    Result queryMyConversations(Long current);

    Result queryMessages(Long conversationId);

    Result updateConversationTitle(Long conversationId, AiConversationUpdateRequest request);

    Result deleteConversation(Long conversationId);

    Result saveUserMessage(Long conversationId, AiMessageSendRequest request);

    SseEmitter chat(Long conversationId, AiMessageSendRequest request);
}
