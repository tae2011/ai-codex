package com.example.springaidemo.dto;

import lombok.Data;

/**
 * 知识库检索请求。
 */
@Data
public class RagSearchRequest {

    /**
     * 用户查询文本。
     */
    private String query;

    /**
     * 可选的返回条数。
     */
    private Integer topK;
}
