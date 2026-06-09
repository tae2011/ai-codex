package com.example.springaidemo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
public class ChatCompletionChunkResponse {

    private final String id;

    private final String object = "chat.completion.chunk";

    private final long created;

    private final String model;

    private final String conversationId;

    private final List<Choice> choices;

    /**
     * 创建新的流式响应片段。
     *
     * @param model 本次响应使用的模型
     * @param content 增量回复内容
     * @param includeRole 是否在片段中包含 assistant 角色
     */
    public ChatCompletionChunkResponse(String model, String content, boolean includeRole) {
        this.id = "chatcmpl-" + UUID.randomUUID();
        this.created = Instant.now().getEpochSecond();
        this.model = model;
        this.conversationId = null;
        this.choices = List.of(new Choice(0, new Delta(includeRole ? "assistant" : null, content), null));
    }

    /**
     * 使用指定 ID 和创建时间创建流式响应片段。
     *
     * @param id 响应片段 ID
     * @param created 创建时间戳
     * @param model 本次响应使用的模型
     * @param choices 片段候选结果
     */
    public ChatCompletionChunkResponse(String id, long created, String model, String conversationId, List<Choice> choices) {
        this.id = id;
        this.created = created;
        this.model = model;
        this.conversationId = conversationId;
        this.choices = choices;
    }

    /**
     * 创建包含增量文本的流式响应片段。
     *
     * @param id 响应片段 ID
     * @param created 创建时间戳
     * @param model 本次响应使用的模型
     * @param conversationId 当前对话 ID
     * @param content 增量回复内容
     * @param includeRole 是否在首个片段中包含 assistant 角色
     * @return 流式响应片段
     */
    public static ChatCompletionChunkResponse content(String id,
                                                      long created,
                                                      String model,
                                                      String conversationId,
                                                      String content,
                                                      boolean includeRole) {
        return new ChatCompletionChunkResponse(
                id,
                created,
                model,
                conversationId,
                List.of(new Choice(0, new Delta(includeRole ? "assistant" : null, content), null))
        );
    }

    /**
     * 创建标记流式响应结束的片段。
     *
     * @param id 响应片段 ID
     * @param created 创建时间戳
     * @param model 本次响应使用的模型
     * @return 结束片段
     */
    public static ChatCompletionChunkResponse done(String id, long created, String model, String conversationId) {
        return new ChatCompletionChunkResponse(
                id,
                created,
                model,
                conversationId,
                List.of(new Choice(0, new Delta(null, null), "stop"))
        );
    }

    @Data
    public static class Choice {

        private final int index;

        private final Delta delta;

        @JsonProperty("finish_reason")
        private final String finishReason;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Delta {

        private final String role;

        private final String content;
    }
}
