package com.example.springaidemo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * 会话列表摘要。
 */
@Data
@AllArgsConstructor
public class ConversationSummaryDto {

    /**
     * 会话 ID。
     */
    private String id;

    /**
     * 会话标题。
     */
    private String title;

    /**
     * 当前会话使用的平台。
     */
    private String platform;

    /**
     * 当前会话使用的模型类型。
     */
    private String modelType;

    /**
     * 创建时间。
     */
    private Instant createdAt;

    /**
     * 最后更新时间。
     */
    private Instant updatedAt;
}
