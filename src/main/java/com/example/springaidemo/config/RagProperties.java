package com.example.springaidemo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 相关配置。
 */
@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /**
     * 是否启用 RAG。
     */
    private boolean enabled = true;

    /**
     * 向量模型名称。
     */
    private String embeddingModel = "text-embedding-v4";

    /**
     * 单次检索返回的最大片段数量。
     */
    private int topK = 4;

    /**
     * 最小相似度阈值。
     */
    private double minScore = 0.45;

    /**
     * 单个切片的最大字符数。
     */
    private int chunkSize = 800;

    /**
     * 相邻切片重叠字符数。
     */
    private int chunkOverlap = 120;

    /**
     * 本地向量库存储文件。
     */
    private String storeFile = "data/rag-store.json";
}
