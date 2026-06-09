package com.example.springaidemo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 知识库切片结果。
 */
@Data
@AllArgsConstructor
public class RagChunkDto {

    /**
     * 切片 ID。
     */
    private String id;

    /**
     * 来源文件名。
     */
    private String sourceName;

    /**
     * 切片标题。
     */
    private String title;

    /**
     * 切片内容。
     */
    private String content;

    /**
     * 相似度分数。
     */
    private double score;
}
