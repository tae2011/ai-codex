package com.example.springaidemo.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class ChatCompletionRequest {

    /**
     * 会话 ID。
     */
    private String conversationId;

    private String platform;

    private String modelType;

    private String model;

    private Double temperature;

    private Boolean stream = false;

    private List<ChatMessageDto> messages = new ArrayList<>();
}
