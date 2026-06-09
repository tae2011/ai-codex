package com.example.springaidemo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Markdown 导入响应。
 */
@Data
@AllArgsConstructor
public class RagImportResponse {

    /**
     * 成功导入的文件数量。
     */
    private int importedFiles;

    /**
     * 新增切片数量。
     */
    private int importedChunks;

    /**
     * 当前知识库总切片数。
     */
    private int totalChunks;

    /**
     * 导入的文件名列表。
     */
    private List<String> sourceNames;
}
