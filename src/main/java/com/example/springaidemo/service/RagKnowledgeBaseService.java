package com.example.springaidemo.service;

import com.example.springaidemo.config.AiProviderProperties;
import com.example.springaidemo.config.RagProperties;
import com.example.springaidemo.dto.RagChunkDto;
import com.example.springaidemo.dto.RagImportResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 负责 Markdown 导入、向量化和知识库检索。
 */
@Slf4j
@Service
public class RagKnowledgeBaseService {

    private static final int EMBEDDING_BATCH_SIZE = 10;

    private final RagProperties ragProperties;

    private final AiProviderProperties aiProviderProperties;

    private final WebClient.Builder webClientBuilder;

    private final ObjectMapper objectMapper;

    private final List<StoredChunk> storedChunks = new CopyOnWriteArrayList<>();

    /**
     * 注入依赖。
     *
     * @param ragProperties RAG 配置
     * @param aiProviderProperties AI 平台配置
     * @param webClientBuilder WebClient 构造器
     * @param objectMapper JSON 工具
     */
    public RagKnowledgeBaseService(RagProperties ragProperties,
                                   AiProviderProperties aiProviderProperties,
                                   WebClient.Builder webClientBuilder,
                                   ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.aiProviderProperties = aiProviderProperties;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动时加载本地向量库文件。
     */
    @PostConstruct
    public void loadStore() {
        Path storePath = resolveStorePath();
        if (!Files.exists(storePath)) {
            return;
        }
        try {
            List<StoredChunk> data = objectMapper.readValue(storePath.toFile(), new TypeReference<>() {
            });
            storedChunks.clear();
            storedChunks.addAll(data);
            log.info("rag store loaded, path={}, chunks={}", storePath, storedChunks.size());
        } catch (IOException ex) {
            throw new IllegalStateException("加载本地向量库失败: " + storePath, ex);
        }
    }

    /**
     * 导入 Markdown 文件并完成向量化。
     *
     * @param files 上传文件
     * @return 导入结果
     */
    public synchronized RagImportResponse importMarkdownFiles(MultipartFile[] files) {
        ensureEnabled();
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("请至少上传一个 Markdown 文件");
        }

        List<MarkdownChunk> chunksToImport = new ArrayList<>();
        Set<String> sourceNames = new LinkedHashSet<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String filename = file.getOriginalFilename() == null ? "unknown.md" : file.getOriginalFilename();
            if (!filename.toLowerCase(Locale.ROOT).endsWith(".md")) {
                throw new IllegalArgumentException("仅支持导入 .md 文件: " + filename);
            }
            sourceNames.add(filename);
            chunksToImport.addAll(splitMarkdown(readFileContent(file), filename));
        }

        if (chunksToImport.isEmpty()) {
            throw new IllegalArgumentException("未从 Markdown 文件中解析出可向量化内容");
        }

        List<List<Double>> embeddings = embedTexts(
                chunksToImport.stream().map(MarkdownChunk::getContent).toList()
        );
        for (int i = 0; i < chunksToImport.size(); i++) {
            MarkdownChunk chunk = chunksToImport.get(i);
            storedChunks.add(new StoredChunk(
                    UUID.randomUUID().toString(),
                    chunk.getSourceName(),
                    chunk.getTitle(),
                    chunk.getContent(),
                    embeddings.get(i),
                    Instant.now()
            ));
        }
        persistStore();
        log.info("markdown imported into rag store, files={}, chunks={}, totalChunks={}",
                sourceNames.size(), chunksToImport.size(), storedChunks.size());
        return new RagImportResponse(sourceNames.size(), chunksToImport.size(), storedChunks.size(), new ArrayList<>(sourceNames));
    }

    /**
     * 执行相似度检索。
     *
     * @param query 用户查询
     * @param topK 返回条数
     * @return 命中的切片
     */
    public List<RagChunkDto> search(String query, Integer topK) {
        ensureEnabled();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (storedChunks.isEmpty()) {
            return List.of();
        }

        int limit = topK == null || topK <= 0 ? ragProperties.getTopK() : topK;
        List<Double> queryVector = embedSingleText(query);
        return storedChunks.stream()
                .map(chunk -> new RagChunkDto(
                        chunk.getId(),
                        chunk.getSourceName(),
                        chunk.getTitle(),
                        chunk.getContent(),
                        cosineSimilarity(queryVector, chunk.getEmbedding())
                ))
                .filter(chunk -> chunk.getScore() >= ragProperties.getMinScore())
                .sorted(Comparator.comparingDouble(RagChunkDto::getScore).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * 获取知识库切片数量。
     *
     * @return 切片数量
     */
    public int count() {
        return storedChunks.size();
    }

    /**
     * 清空知识库。
     */
    public synchronized void clear() {
        storedChunks.clear();
        persistStore();
        log.info("rag store cleared");
    }

    /**
     * 将检索结果格式化成系统上下文。
     *
     * @param query 查询文本
     * @return 注入提示词的上下文文本
     */
    public String buildContextPrompt(String query) {
        List<RagChunkDto> hits = search(query, ragProperties.getTopK());
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("以下内容来自本地知识库检索，请优先参考；若与用户问题无关，可以忽略。").append("\n\n");
        for (int i = 0; i < hits.size(); i++) {
            RagChunkDto hit = hits.get(i);
            builder.append("资料 ").append(i + 1)
                    .append(" [").append(hit.getSourceName()).append("]");
            if (hit.getTitle() != null && !hit.getTitle().isBlank()) {
                builder.append(" - ").append(hit.getTitle());
            }
            builder.append("\n")
                    .append(hit.getContent())
                    .append("\n\n");
        }
        log.info("rag search completed, queryLength={}, hits={}", query.length(), hits.size());
        return builder.toString().trim();
    }

    /**
     * 读取上传文件内容。
     *
     * @param file 上传文件
     * @return 文本内容
     */
    private String readFileContent(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("读取 Markdown 文件失败: " + file.getOriginalFilename(), ex);
        }
    }

    /**
     * 将 Markdown 文本切成适合向量化的片段。
     *
     * @param markdown Markdown 文本
     * @param sourceName 来源文件名
     * @return 切片列表
     */
    private List<MarkdownChunk> splitMarkdown(String markdown, String sourceName) {
        String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n").trim();
        if (normalized.isBlank()) {
            return List.of();
        }

        List<MarkdownChunk> chunks = new ArrayList<>();
        String currentTitle = sourceName;
        StringBuilder sectionBuilder = new StringBuilder();
        for (String line : normalized.split("\n")) {
            if (line.startsWith("#")) {
                flushSection(chunks, sectionBuilder.toString(), sourceName, currentTitle);
                currentTitle = line.replaceFirst("^#+\\s*", "").trim();
                sectionBuilder.setLength(0);
                continue;
            }
            sectionBuilder.append(line).append("\n");
        }
        flushSection(chunks, sectionBuilder.toString(), sourceName, currentTitle);
        return chunks;
    }

    /**
     * 将单个章节继续切分成固定长度片段。
     *
     * @param chunks 输出结果集合
     * @param sectionText 章节正文
     * @param sourceName 来源文件名
     * @param title 章节标题
     */
    private void flushSection(List<MarkdownChunk> chunks, String sectionText, String sourceName, String title) {
        String cleaned = sectionText == null ? "" : sectionText.replaceAll("\\n{3,}", "\n\n").trim();
        if (cleaned.isBlank()) {
            return;
        }
        int chunkSize = Math.max(ragProperties.getChunkSize(), 200);
        int overlap = Math.max(0, Math.min(ragProperties.getChunkOverlap(), chunkSize / 2));
        int start = 0;
        while (start < cleaned.length()) {
            int end = Math.min(cleaned.length(), start + chunkSize);
            if (end < cleaned.length()) {
                int paragraphBoundary = cleaned.lastIndexOf("\n\n", end);
                if (paragraphBoundary > start + 200) {
                    end = paragraphBoundary;
                }
            }
            String content = cleaned.substring(start, end).trim();
            if (!content.isBlank()) {
                chunks.add(new MarkdownChunk(sourceName, title, content));
            }
            if (end >= cleaned.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
    }

    /**
     * 批量生成文本向量。
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    private List<List<Double>> embedTexts(List<String> texts) {
        List<List<Double>> vectors = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(texts.size(), start + EMBEDDING_BATCH_SIZE);
            vectors.addAll(requestEmbeddings(texts.subList(start, end)));
        }
        return vectors;
    }

    /**
     * 生成单条文本向量。
     *
     * @param text 文本
     * @return 向量
     */
    private List<Double> embedSingleText(String text) {
        return requestEmbeddings(List.of(text)).get(0);
    }

    /**
     * 调用阿里云嵌入模型生成向量。
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    private List<List<Double>> requestEmbeddings(List<String> texts) {
        AiProviderProperties.Provider provider = aiProviderProperties.getProviders().get("aliyun");
        if (provider == null || provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            throw new IllegalStateException("RAG 向量化需要可用的阿里云 API Key");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", ragProperties.getEmbeddingModel());
        ArrayNode inputArray = objectMapper.createArrayNode();
        texts.forEach(inputArray::add);
        body.set("input", inputArray);
        body.put("encoding_format", "float");

        log.info("request embeddings, model={}, batchSize={}", ragProperties.getEmbeddingModel(), texts.size());
        JsonNode response = webClientBuilder
                .baseUrl(provider.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + provider.getApiKey())
                .build()
                .post()
                .uri("/v1/embeddings")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (response == null || !response.has("data")) {
            throw new IllegalStateException("嵌入模型返回为空");
        }
        List<List<Double>> vectors = new ArrayList<>();
        for (JsonNode item : response.path("data")) {
            ArrayNode embeddingArray = (ArrayNode) item.path("embedding");
            List<Double> vector = new ArrayList<>(embeddingArray.size());
            embeddingArray.forEach(node -> vector.add(node.asDouble()));
            vectors.add(vector);
        }
        return vectors;
    }

    /**
     * 计算余弦相似度。
     *
     * @param left 左向量
     * @param right 右向量
     * @return 相似度
     */
    private double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0D;
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i);
            double b = right.get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0D || rightNorm == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /**
     * 将当前向量库写入本地文件。
     */
    private void persistStore() {
        try {
            Path storePath = resolveStorePath();
            Files.createDirectories(storePath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), storedChunks);
        } catch (IOException ex) {
            throw new IllegalStateException("写入本地向量库失败", ex);
        }
    }

    /**
     * 解析本地向量库存储路径。
     *
     * @return 绝对路径
     */
    private Path resolveStorePath() {
        return Path.of(ragProperties.getStoreFile()).toAbsolutePath().normalize();
    }

    /**
     * 校验 RAG 是否启用。
     */
    private void ensureEnabled() {
        if (!ragProperties.isEnabled()) {
            throw new IllegalStateException("RAG 功能当前未启用");
        }
    }

    /**
     * Markdown 切片。
     */
    @Data
    private static final class MarkdownChunk {

        private final String sourceName;

        private final String title;

        private final String content;
    }

    /**
     * 本地存储的向量切片。
     */
    @Data
    private static final class StoredChunk {

        private String id;

        private String sourceName;

        private String title;

        private String content;

        private List<Double> embedding;

        private Instant createdAt;

        private StoredChunk() {
        }

        private StoredChunk(String id,
                            String sourceName,
                            String title,
                            String content,
                            List<Double> embedding,
                            Instant createdAt) {
            this.id = id;
            this.sourceName = sourceName;
            this.title = title;
            this.content = content;
            this.embedding = embedding;
            this.createdAt = createdAt;
        }
    }
}
