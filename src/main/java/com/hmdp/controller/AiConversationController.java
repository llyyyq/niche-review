package com.hmdp.controller;

import com.hmdp.dto.AiConversationCreateRequest;
import com.hmdp.dto.AiConversationUpdateRequest;
import com.hmdp.dto.AiMessageSendRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IAiConversationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;

@RestController
@RequestMapping("/ai/conversations")
public class AiConversationController {

    @Resource
    private IAiConversationService aiConversationService;

    @PostMapping
    public Result createConversation(@RequestBody(required = false) AiConversationCreateRequest request) {
        return aiConversationService.createConversation(request);
    }

    @GetMapping
    public Result queryMyConversations(@RequestParam(defaultValue = "1") Long current) {
        return aiConversationService.queryMyConversations(current);
    }

    @GetMapping("/{conversationId}/messages")
    public Result queryMessages(@PathVariable Long conversationId) {
        return aiConversationService.queryMessages(conversationId);
    }

    @PatchMapping("/{conversationId}")
    public Result updateConversationTitle(@PathVariable Long conversationId,
                                          @RequestBody AiConversationUpdateRequest request) {
        return aiConversationService.updateConversationTitle(conversationId, request);
    }

    @DeleteMapping("/{conversationId}")
    public Result deleteConversation(@PathVariable Long conversationId) {
        return aiConversationService.deleteConversation(conversationId);
    }

    @PostMapping("/{conversationId}/messages")
    public Result saveUserMessage(@PathVariable Long conversationId,
                                  @RequestBody AiMessageSendRequest request) {
        return aiConversationService.saveUserMessage(conversationId, request);
    }

    @PostMapping(value = "/{conversationId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable Long conversationId,
                           @RequestBody AiMessageSendRequest request) {
        return aiConversationService.chat(conversationId, request);
    }
}
