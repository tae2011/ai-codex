package com.example.springaidemo.controller;

import com.example.springaidemo.dto.RagChunkDto;
import com.example.springaidemo.dto.RagImportResponse;
import com.example.springaidemo.dto.RagSearchRequest;
import com.example.springaidemo.dto.RagSearchResponse;
import com.example.springaidemo.service.RagKnowledgeBaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 提供知识库导入和检索接口。
 */
@Slf4j
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagKnowledgeBaseService ragKnowledgeBaseService;

    /**
     * 注入知识库服务。
     *
     * @param ragKnowledgeBaseService 知识库服务
     */
    public RagController(RagKnowledgeBaseService ragKnowledgeBaseService) {
        this.ragKnowledgeBaseService = ragKnowledgeBaseService;
    }

    /**
     * 导入 Markdown 文件到本地向量库。
     *
     * @param files Markdown 文件数组
     * @return 导入结果
     */
    @PostMapping("/documents/import")
    public RagImportResponse importMarkdown(@RequestParam("files") MultipartFile[] files) {
        log.info("rag import request received, files={}", files == null ? 0 : files.length);
        return ragKnowledgeBaseService.importMarkdownFiles(files);
    }

    /**
     * 执行知识库检索。
     *
     * @param request 检索请求
     * @return 检索结果
     */
    @PostMapping("/search")
    public RagSearchResponse search(@RequestBody RagSearchRequest request) {
        List<RagChunkDto> chunks = ragKnowledgeBaseService.search(request.getQuery(), request.getTopK());
        return new RagSearchResponse(request.getQuery(), chunks);
    }

    /**
     * 查看当前知识库状态。
     *
     * @return 简要状态
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", true, "totalChunks", ragKnowledgeBaseService.count());
    }

    /**
     * 清空当前知识库。
     */
    @DeleteMapping("/documents")
    public void clear() {
        ragKnowledgeBaseService.clear();
    }
}
