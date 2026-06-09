package com.example.springaidemo.service;

import com.example.springaidemo.config.AiProviderProperties;
import com.example.springaidemo.dto.ChatCompletionChunkResponse;
import com.example.springaidemo.dto.ChatCompletionRequest;
import com.example.springaidemo.dto.ChatCompletionResponse;
import com.example.springaidemo.dto.ChatMessageDto;
import com.example.springaidemo.dto.ToolCallResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 负责模型选择、消息路由、流式输出和日志记录。
 */
@Slf4j
@Service
public class AiChatService {

    private final AiProviderProperties properties;

    private final ObjectMapper objectMapper;

    private final WebClient.Builder webClientBuilder;

    private final ConversationService conversationService;

    private final RagKnowledgeBaseService ragKnowledgeBaseService;

    private final ToolRoutingService toolRoutingService;

    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    /**
     * 注入平台配置、序列化工具、会话服务和知识库服务。
     *
     * @param properties 平台配置
     * @param objectMapper JSON 序列化工具
     * @param webClientBuilder WebClient 构造器
     * @param conversationService 会话服务
     * @param ragKnowledgeBaseService 知识库服务
     * @param toolRoutingService 工具路由服务
     */
    public AiChatService(AiProviderProperties properties,
                         ObjectMapper objectMapper,
                         WebClient.Builder webClientBuilder,
                         ConversationService conversationService,
                         RagKnowledgeBaseService ragKnowledgeBaseService,
                         ToolRoutingService toolRoutingService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
        this.conversationService = conversationService;
        this.ragKnowledgeBaseService = ragKnowledgeBaseService;
        this.toolRoutingService = toolRoutingService;
    }

    /**
     * 发起非流式聊天补全请求。
     *
     * @param request 聊天请求
     * @return 非流式聊天响应
     */
    public ChatCompletionResponse complete(ChatCompletionRequest request) {
        validateRequest(request);
        ModelSelection selection = resolveSelection(request);
        ConversationService.ConversationContext conversation = conversationService.prepareConversation(
                request.getConversationId(),
                selection.platform(),
                normalize(request.getModelType(), properties.getProviders().get(selection.platform()).getDefaultModelType()),
                request.getMessages()
        );
        List<ChatMessageDto> modelMessages = mergeConversationMessages(conversation);
        Instant requestAt = Instant.now();
        log.info("complete request resolved, conversationId={}, platform={}, model={}, temperature={}, messageCount={}",
                conversation.conversationId(), selection.platform(), selection.model(), selection.temperature(), modelMessages.size());
        ToolCallResult toolCallResult = toolRoutingService.tryHandle(conversation.incomingMessages());
        if (toolCallResult.isHandled()) {
            conversationService.appendAssistantMessage(
                    conversation.conversationId(),
                    toolCallResult.getContent(),
                    selection.platform(),
                    conversation.modelType()
            );
            log.info("tool call executed, conversationId={}, toolName={}, requestedAt={}",
                    conversation.conversationId(), toolCallResult.getToolName(), requestAt);
            return new ChatCompletionResponse(toolCallResult.getToolName(), toolCallResult.getContent(), conversation.conversationId());
        }

        String content;
        if (needsDashScopeDirectCall(selection)) {
            content = directComplete(modelMessages, selection, conversation.conversationId(), requestAt);
        } else {
            String responseText = chatModel(selection.platform())
                    .call(buildPrompt(modelMessages, selection))
                    .getResult()
                    .getOutput()
                    .getText();
            Instant responseAt = Instant.now();
            log.info("upstream response received, conversationId={}, platform={}, model={}, httpStatus={}, firstTextAt={}, lastTextAt={}, firstFrameLatencyMs={}, lastFrameLatencyMs={}",
                    conversation.conversationId(),
                    selection.platform(),
                    selection.model(),
                    200,
                    responseAt,
                    responseAt,
                    elapsedMillis(requestAt, responseAt),
                    elapsedMillis(requestAt, responseAt));
            content = responseText;
        }

        conversationService.appendAssistantMessage(
                conversation.conversationId(),
                content,
                selection.platform(),
                conversation.modelType()
        );
        log.info("complete request finished, conversationId={}, platform={}, model={}, responseLength={}, requestedAt={}",
                conversation.conversationId(), selection.platform(), selection.model(), content == null ? 0 : content.length(), requestAt);
        return new ChatCompletionResponse(selection.model(), content, conversation.conversationId());
    }

    /**
     * 发起流式聊天补全请求。
     *
     * @param request 聊天请求
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> stream(ChatCompletionRequest request) {
        validateRequest(request);
        ModelSelection selection = resolveSelection(request);
        ConversationService.ConversationContext conversation = conversationService.prepareConversation(
                request.getConversationId(),
                selection.platform(),
                normalize(request.getModelType(), properties.getProviders().get(selection.platform()).getDefaultModelType()),
                request.getMessages()
        );
        List<ChatMessageDto> modelMessages = mergeConversationMessages(conversation);
        String chunkId = "chatcmpl-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();
        Instant requestedAt = Instant.now();
        AtomicBoolean firstChunk = new AtomicBoolean(true);
        AtomicReference<Instant> firstTextAt = new AtomicReference<>();
        AtomicReference<Instant> lastTextAt = new AtomicReference<>();
        StringBuilder assistantText = new StringBuilder();

        log.info("stream request resolved, chunkId={}, conversationId={}, platform={}, model={}, temperature={}, messageCount={}",
                chunkId, conversation.conversationId(), selection.platform(), selection.model(), selection.temperature(), modelMessages.size());
        ToolCallResult toolCallResult = toolRoutingService.tryHandle(conversation.incomingMessages());
        if (toolCallResult.isHandled()) {
            conversationService.appendAssistantMessage(
                    conversation.conversationId(),
                    toolCallResult.getContent(),
                    selection.platform(),
                    conversation.modelType()
            );
            log.info("tool call executed in stream mode, chunkId={}, conversationId={}, toolName={}, requestedAt={}",
                    chunkId, conversation.conversationId(), toolCallResult.getToolName(), requestedAt);
            return Flux.just(
                    buildChunkEvent(ChatCompletionChunkResponse.content(
                            chunkId,
                            created,
                            toolCallResult.getToolName(),
                            conversation.conversationId(),
                            toolCallResult.getContent(),
                            true
                    )),
                    buildChunkEvent(ChatCompletionChunkResponse.done(
                            chunkId,
                            created,
                            toolCallResult.getToolName(),
                            conversation.conversationId()
                    )),
                    ServerSentEvent.builder("[DONE]").build()
            );
        }

        Flux<ServerSentEvent<String>> streamFlux;
        if (needsDashScopeDirectCall(selection)) {
            streamFlux = directStream(modelMessages, selection, conversation.conversationId(), chunkId, created, firstChunk, firstTextAt, lastTextAt, assistantText);
        } else {
            streamFlux = chatModel(selection.platform()).stream(buildPrompt(modelMessages, selection))
                    .handle((chatResponse, sink) -> {
                        if (chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
                            return;
                        }
                        String text = chatResponse.getResult().getOutput().getText();
                        if (text != null && !text.isBlank()) {
                            Instant now = Instant.now();
                            firstTextAt.compareAndSet(null, now);
                            lastTextAt.set(now);
                            assistantText.append(text);
                            sink.next(text);
                        }
                    })
                    .cast(String.class)
                    .map(text -> buildChunkEvent(ChatCompletionChunkResponse.content(
                            chunkId,
                            created,
                            selection.model(),
                            conversation.conversationId(),
                            text,
                            firstChunk.getAndSet(false)
                    )));
        }

        return streamFlux
                .doOnSubscribe(subscription -> log.info("stream started, chunkId={}, conversationId={}, platform={}, model={}",
                        chunkId, conversation.conversationId(), selection.platform(), selection.model()))
                .concatWith(Flux.just(
                        buildChunkEvent(ChatCompletionChunkResponse.done(chunkId, created, selection.model(), conversation.conversationId())),
                        ServerSentEvent.builder("[DONE]").build()
                ))
                .doOnComplete(() -> {
                    conversationService.appendAssistantMessage(
                            conversation.conversationId(),
                            assistantText.toString(),
                            selection.platform(),
                            conversation.modelType()
                    );
                    log.info("stream completed, chunkId={}, conversationId={}, platform={}, model={}, requestedAt={}, firstTextAt={}, lastTextAt={}, responseLength={}",
                            chunkId,
                            conversation.conversationId(),
                            selection.platform(),
                            selection.model(),
                            requestedAt,
                            firstTextAt.get(),
                            lastTextAt.get(),
                            assistantText.length());
                    log.info("stream latency summary, chunkId={}, conversationId={}, firstFrameLatencyMs={}, lastFrameLatencyMs={}",
                            chunkId,
                            conversation.conversationId(),
                            elapsedMillis(requestedAt, firstTextAt.get()),
                            elapsedMillis(requestedAt, lastTextAt.get()));
                })
                .onErrorResume(ex -> Flux.just(
                        buildChunkEvent(buildErrorBody(ex)),
                        ServerSentEvent.builder("[DONE]").build()
                ));
    }

    /**
     * 判断是否需要走阿里云兼容接口直连。
     *
     * @param selection 模型选择结果
     * @return 是否直连
     */
    private boolean needsDashScopeDirectCall(ModelSelection selection) {
        return "aliyun".equals(selection.platform())
                && ("glm-5.1".equals(selection.model())
                || "deepseek-v4-flash".equals(selection.model())
                || "deepseek-v4-pro".equals(selection.model()));
    }

    /**
     * 通过 DashScope 兼容接口发起非流式请求。
     *
     * @param messages 发送给模型的消息
     * @param selection 模型选择结果
     * @param conversationId 会话 ID
     * @return 模型文本
     */
    private String directComplete(List<ChatMessageDto> messages,
                                  ModelSelection selection,
                                  String conversationId,
                                  Instant requestedAt) {
        log.info("use direct completion, conversationId={}, platform={}, model={}",
                conversationId, selection.platform(), selection.model());
        ObjectNode body = buildDashScopeBody(messages, selection, false);
        AtomicReference<HttpStatusCode> statusHolder = new AtomicReference<>();
        JsonNode response = dashScopeWebClient(selection.platform())
                .post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .exchangeToMono(clientResponse -> {
                    statusHolder.set(clientResponse.statusCode());
                    log.info("upstream http status received, conversationId={}, platform={}, model={}, httpStatus={}",
                            conversationId, selection.platform(), selection.model(), clientResponse.statusCode().value());
                    if (clientResponse.statusCode().isError()) {
                        return clientResponse.createException().flatMap(Mono::error);
                    }
                    return clientResponse.bodyToMono(JsonNode.class);
                })
                .block();
        String content = response == null
                ? ""
                : response.path("choices").path(0).path("message").path("content").asText("");
        Instant responseAt = Instant.now();
        log.info("direct completion finished, conversationId={}, platform={}, model={}, httpStatus={}, firstTextAt={}, lastTextAt={}, firstFrameLatencyMs={}, lastFrameLatencyMs={}, responseLength={}",
                conversationId,
                selection.platform(),
                selection.model(),
                statusHolder.get() == null ? "unknown" : statusHolder.get().value(),
                responseAt,
                responseAt,
                elapsedMillis(requestedAt, responseAt),
                elapsedMillis(requestedAt, responseAt),
                content.length());
        return content;
    }

    /**
     * 通过 DashScope 兼容接口发起流式请求。
     *
     * @param messages 发送给模型的消息
     * @param selection 模型选择结果
     * @param conversationId 会话 ID
     * @param chunkId 流式响应 ID
     * @param created 创建时间戳
     * @param firstChunk 首包标记
     * @param firstTextAt 首次收到文字时间
     * @param lastTextAt 最后收到文字时间
     * @param assistantText 助手累计输出
     * @return SSE 事件流
     */
    private Flux<ServerSentEvent<String>> directStream(List<ChatMessageDto> messages,
                                                       ModelSelection selection,
                                                       String conversationId,
                                                       String chunkId,
                                                       long created,
                                                       AtomicBoolean firstChunk,
                                                       AtomicReference<Instant> firstTextAt,
                                                       AtomicReference<Instant> lastTextAt,
                                                       StringBuilder assistantText) {
        log.info("use direct stream, chunkId={}, conversationId={}, platform={}, model={}",
                chunkId, conversationId, selection.platform(), selection.model());
        ObjectNode body = buildDashScopeBody(messages, selection, true);
        return dashScopeWebClient(selection.platform())
                .post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .exchangeToFlux(clientResponse -> {
                    log.info("upstream http status received, chunkId={}, conversationId={}, platform={}, model={}, httpStatus={}",
                            chunkId, conversationId, selection.platform(), selection.model(), clientResponse.statusCode().value());
                    if (clientResponse.statusCode().isError()) {
                        return clientResponse.createException().flatMapMany(Flux::error);
                    }
                    return clientResponse.bodyToFlux(String.class);
                })
                .flatMap(data -> toChunkEvents(
                        data,
                        selection.model(),
                        conversationId,
                        chunkId,
                        created,
                        firstChunk,
                        firstTextAt,
                        lastTextAt,
                        assistantText
                ));
    }

    /**
     * 组装 DashScope 兼容接口请求体。
     *
     * @param messages 模型消息
     * @param selection 模型选择结果
     * @param stream 是否流式
     * @return 请求体
     */
    private ObjectNode buildDashScopeBody(List<ChatMessageDto> messages, ModelSelection selection, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode messageArray = objectMapper.createArrayNode();
        for (ChatMessageDto message : messages) {
            Message springMessage = toSpringMessage(message);
            ObjectNode item = objectMapper.createObjectNode();
            item.put("role", message.getRole() == null ? "user" : message.getRole().toLowerCase(Locale.ROOT));
            item.put("content", springMessage.getText());
            messageArray.add(item);
        }

        body.put("model", selection.model());
        body.set("messages", messageArray);
        body.put("stream", stream);
        body.put("temperature", selection.temperature());
        if ("glm-5.1".equals(selection.model())) {
            body.put("enable_thinking", true);
        }
        if ("deepseek-v4-flash".equals(selection.model()) || "deepseek-v4-pro".equals(selection.model())) {
            body.put("reasoning_effort", "high");
        }
        return body;
    }

    /**
     * 将上游流式数据转换为页面可消费的 SSE 片段。
     *
     * @param data 上游原始片段
     * @param model 模型名
     * @param conversationId 会话 ID
     * @param chunkId 响应 ID
     * @param created 创建时间戳
     * @param firstChunk 首包标记
     * @param firstTextAt 首次收到文字时间
     * @param lastTextAt 最后收到文字时间
     * @param assistantText 助手累计输出
     * @return SSE 事件流
     */
    private Flux<ServerSentEvent<String>> toChunkEvents(String data,
                                                        String model,
                                                        String conversationId,
                                                        String chunkId,
                                                        long created,
                                                        AtomicBoolean firstChunk,
                                                        AtomicReference<Instant> firstTextAt,
                                                        AtomicReference<Instant> lastTextAt,
                                                        StringBuilder assistantText) {
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
            return Flux.empty();
        }
        try {
            JsonNode json = objectMapper.readTree(stripSsePrefix(data));
            String content = json.path("choices").path(0).path("delta").path("content").asText("");
            if (content.isBlank()) {
                content = json.path("choices").path(0).path("message").path("content").asText("");
            }
            if (content.isBlank()) {
                return Flux.empty();
            }
            Instant now = Instant.now();
            firstTextAt.compareAndSet(null, now);
            lastTextAt.set(now);
            assistantText.append(content);
            return Flux.just(buildChunkEvent(ChatCompletionChunkResponse.content(
                    chunkId,
                    created,
                    model,
                    conversationId,
                    content,
                    firstChunk.getAndSet(false)
            )));
        } catch (JsonProcessingException e) {
            log.warn("skip unparsable stream chunk, model={}, data={}", model, data);
            return Flux.empty();
        }
    }

    /**
     * 去掉 SSE 数据前缀。
     *
     * @param data 原始流式片段
     * @return 纯 JSON 字符串
     */
    private String stripSsePrefix(String data) {
        String trimmed = data.trim();
        return trimmed.startsWith("data:") ? trimmed.substring(5).trim() : trimmed;
    }

    /**
     * 创建指定平台的 WebClient。
     *
     * @param platform 平台名
     * @return WebClient
     */
    private WebClient dashScopeWebClient(String platform) {
        AiProviderProperties.Provider provider = properties.getProviders().get(platform);
        return webClientBuilder
                .baseUrl(provider.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();
    }

    /**
     * 校验聊天请求。
     *
     * @param request 请求体
     */
    private void validateRequest(ChatCompletionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
    }

    /**
     * 根据平台、模型类型和显式模型名解析最终调用配置。
     *
     * @param request 聊天请求
     * @return 模型选择结果
     */
    private ModelSelection resolveSelection(ChatCompletionRequest request) {
        String platform = normalize(request.getPlatform(), properties.getDefaultPlatform());
        AiProviderProperties.Provider provider = properties.getProviders().get(platform);
        if (provider == null) {
            throw new IllegalArgumentException("不支持的平台: " + platform);
        }

        String modelType = normalize(request.getModelType(), provider.getDefaultModelType());
        String model = request.getModel();
        if (model == null || model.isBlank()) {
            model = provider.getModels().get(modelType);
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("平台 " + platform + " 不支持模型类型 " + modelType);
        }

        Double temperature = request.getTemperature() == null ? provider.getTemperature() : request.getTemperature();
        return new ModelSelection(platform, modelType, model, temperature == null ? 0.8 : temperature);
    }

    /**
     * 获取指定平台对应的聊天模型实例。
     *
     * @param platform 平台名
     * @return 聊天模型
     */
    private ChatModel chatModel(String platform) {
        return chatModelCache.computeIfAbsent(platform, this::buildChatModel);
    }

    /**
     * 创建 OpenAI 兼容聊天模型。
     *
     * @param platform 平台名
     * @return 聊天模型
     */
    private ChatModel buildChatModel(String platform) {
        AiProviderProperties.Provider provider = properties.getProviders().get(platform);
        if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            throw new IllegalStateException("平台 " + platform + " 的 api-key 未配置");
        }
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            throw new IllegalStateException("平台 " + platform + " 的 base-url 未配置");
        }
        log.info("create chat model, platform={}, baseUrl={}", platform, provider.getBaseUrl());

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().build())
                .build();
    }

    /**
     * 将消息转换为模型可调用的 Prompt。
     *
     * @param messages 消息列表
     * @param selection 模型选择结果
     * @return Prompt
     */
    private Prompt buildPrompt(List<ChatMessageDto> messages, ModelSelection selection) {
        List<Message> promptMessages = messages.stream()
                .map(this::toSpringMessage)
                .toList();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(selection.model())
                .temperature(selection.temperature())
                .build();
        return new Prompt(promptMessages, options);
    }

    /**
     * 合并历史消息和本轮新消息。
     *
     * @param conversation 会话上下文
     * @return 模型完整消息列表
     */
    private List<ChatMessageDto> mergeConversationMessages(ConversationService.ConversationContext conversation) {
        List<ChatMessageDto> merged = new ArrayList<>(conversation.historyMessages());
        merged.addAll(conversation.incomingMessages());
        String query = conversation.incomingMessages().stream()
                .filter(message -> message != null && "user".equalsIgnoreCase(message.getRole()))
                .map(ChatMessageDto::getContent)
                .filter(text -> text != null && !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        String ragContext = ragKnowledgeBaseService.buildContextPrompt(query);
        if (ragContext.isBlank()) {
            return merged;
        }
        List<ChatMessageDto> withContext = new ArrayList<>();
        withContext.add(new ChatMessageDto("system", ragContext));
        withContext.addAll(merged);
        log.info("rag context attached, conversationId={}, queryLength={}, contextLength={}",
                conversation.conversationId(), query.length(), ragContext.length());
        return withContext;
    }

    /**
     * 将 OpenAI 风格消息转为 Spring AI 消息对象。
     *
     * @param message OpenAI 风格消息
     * @return Spring AI 消息对象
     */
    private Message toSpringMessage(ChatMessageDto message) {
        if (message == null || message.getContent() == null || message.getContent().isBlank()) {
            throw new IllegalArgumentException("messages 中存在空内容");
        }
        String role = message.getRole() == null ? "user" : message.getRole().toLowerCase(Locale.ROOT);
        return switch (role) {
            case "system" -> new SystemMessage(message.getContent());
            case "assistant" -> new AssistantMessage(message.getContent());
            case "user" -> new UserMessage(message.getContent());
            default -> throw new IllegalArgumentException("不支持的 role: " + message.getRole());
        };
    }

    /**
     * 构造 SSE 数据事件。
     *
     * @param chunk 响应对象
     * @return SSE 事件
     */
    private ServerSentEvent<String> buildChunkEvent(Object chunk) {
        return ServerSentEvent.builder(toJson(chunk)).build();
    }

    /**
     * 构造流式错误响应体。
     *
     * @param ex 异常对象
     * @return 错误响应体
     */
    private Map<String, Object> buildErrorBody(Throwable ex) {
        log.error("stream request failed", ex);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", readableErrorMessage(ex));
        error.put("type", "ERROR");
        return Map.of("error", error);
    }

    /**
     * 将上游异常转换为更友好的错误信息。
     *
     * @param ex 异常对象
     * @return 可读错误信息
     */
    private String readableErrorMessage(Throwable ex) {
        if (ex instanceof WebClientResponseException.Forbidden) {
            return "上游返回 403 Forbidden：当前 API Key 可能没有该模型权限，或模型名称不可用。";
        }
        if (ex instanceof WebClientResponseException webClientException) {
            String body = webClientException.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                return "上游调用失败，HTTP 状态码：" + webClientException.getStatusCode().value() + "，响应：" + body;
            }
            return "上游调用失败，HTTP 状态码：" + webClientException.getStatusCode().value();
        }
        return ex.getMessage() == null ? "流式调用失败" : ex.getMessage();
    }

    /**
     * 将对象序列化为 JSON。
     *
     * @param node 待序列化对象
     * @return JSON 字符串
     */
    private String toJson(Object node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("构造流式响应失败", e);
        }
    }

    /**
     * 归一化字符串配置。
     *
     * @param value 请求值
     * @param defaultValue 默认值
     * @return 归一化后的值
     */
    private String normalize(String value, String defaultValue) {
        String resolved = value == null || value.isBlank() ? defaultValue : value;
        return resolved == null ? "" : resolved.toLowerCase(Locale.ROOT);
    }

    /**
     * 计算两个时间点之间的毫秒耗时。
     *
     * @param start 开始时间
     * @param end 结束时间
     * @return 毫秒耗时，任一时间为空时返回 -1
     */
    private long elapsedMillis(Instant start, Instant end) {
        if (start == null || end == null) {
            return -1;
        }
        return Duration.between(start, end).toMillis();
    }

    /**
     * 模型选择结果。
     */
    private record ModelSelection(String platform, String modelType, String model, double temperature) {
    }
}
