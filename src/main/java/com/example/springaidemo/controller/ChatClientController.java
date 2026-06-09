package com.example.springaidemo.controller;

import com.example.springaidemo.dto.ChatCompletionRequest;
import com.example.springaidemo.dto.ChatCompletionResponse;
import com.example.springaidemo.service.AiChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@Slf4j
@CrossOrigin(origins = "*")
@RestController
public class ChatClientController {

    private final AiChatService aiChatService;

    /**
     * 注入聊天服务。
     *
     * @param aiChatService 聊天服务
     */
    public ChatClientController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    /**
     * 处理非流式聊天补全请求。
     *
     * @param request ChatGPT/OpenAI 风格的聊天请求体
     * @return ChatGPT/OpenAI 风格的聊天响应
     */
    @PostMapping("/v1/chat/completions")
    public ChatCompletionResponse completions(@RequestBody ChatCompletionRequest request) {
        log.info("receive chat completion request, conversationId={}, platform={}, modelType={}, model={}, messages={}",
                request.getConversationId(), request.getPlatform(), request.getModelType(), request.getModel(),
                request.getMessages() == null ? 0 : request.getMessages().size());
        return aiChatService.complete(request);
    }

    /**
     * 处理流式聊天补全请求，并以 SSE 格式返回增量内容。
     *
     * @param request ChatGPT/OpenAI 风格的聊天请求体
     * @return SSE 事件流
     */
    @PostMapping(value = "/v1/chat/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamCompletions(@RequestBody ChatCompletionRequest request) {
        log.info("receive stream chat request, conversationId={}, platform={}, modelType={}, model={}, messages={}",
                request.getConversationId(), request.getPlatform(), request.getModelType(), request.getModel(),
                request.getMessages() == null ? 0 : request.getMessages().size());
        return aiChatService.stream(request);
    }
}
