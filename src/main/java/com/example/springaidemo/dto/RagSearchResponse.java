package com.example.springaidemo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 知识库检索响应。
 */
@Data
@AllArgsConstructor
public class RagSearchResponse {

    /**
     * 查询文本。
     */
    private String query;

    /**
     * 命中的切片。
     */
    private List<RagChunkDto> chunks;
}
