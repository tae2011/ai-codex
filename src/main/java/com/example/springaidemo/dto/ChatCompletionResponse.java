package com.example.springaidemo.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ChatCompletionResponse {

    private String id = "chatcmpl-" + UUID.randomUUID();

    private String object = "chat.completion";

    private long created = Instant.now().getEpochSecond();

    private String model;

    private String conversationId;

    private List<Choice> choices;

    /**
     * 创建非流式聊天补全响应。
     *
     * @param model 本次响应使用的模型
     * @param content 模型回复内容
     */
    public ChatCompletionResponse(String model, String content, String conversationId) {
        this.model = model;
        this.conversationId = conversationId;
        this.choices = List.of(new Choice(0, new ChatMessageDto("assistant", content), "stop"));
    }

    @Data
    public static class Choice {

        private final int index;

        private final ChatMessageDto message;

        @JsonProperty("finish_reason")
        private final String finishReason;
    }
}
