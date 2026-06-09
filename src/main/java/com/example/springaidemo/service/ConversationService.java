package com.example.springaidemo.service;

import com.example.springaidemo.dto.ChatMessageDto;
import com.example.springaidemo.dto.ConversationDetailDto;
import com.example.springaidemo.dto.ConversationSummaryDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 负责维护内存中的会话历史。
 */
@Slf4j
@Service
public class ConversationService {

    private static final int TITLE_MAX_LENGTH = 24;

    private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();

    /**
     * 获取会话列表。
     *
     * @return 会话摘要列表
     */
    public List<ConversationSummaryDto> list() {
        return conversations.values().stream()
                .sorted(Comparator.comparing(ConversationState::getUpdatedAt).reversed())
                .map(this::toSummary)
                .toList();
    }

    /**
     * 获取指定会话详情。
     *
     * @param conversationId 会话 ID
     * @return 会话详情
     */
    public ConversationDetailDto get(String conversationId) {
        ConversationState state = getRequiredConversation(conversationId);
        return toDetail(state);
    }

    /**
     * 创建一个空会话。
     *
     * @param platform 当前平台
     * @param modelType 当前模型类型
     * @return 新会话详情
     */
    public ConversationDetailDto createEmpty(String platform, String modelType) {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        ConversationState state = new ConversationState(id, "新对话", platform, modelType, now, now);
        conversations.put(id, state);
        log.info("conversation created, conversationId={}, platform={}, modelType={}", id, platform, modelType);
        return toDetail(state);
    }

    /**
     * 根据请求中的新消息准备会话上下文。
     *
     * @param conversationId 会话 ID，可为空
     * @param platform 平台
     * @param modelType 模型类型
     * @param incomingMessages 本次新消息
     * @return 可供模型调用和后续持久化使用的会话上下文
     */
    public ConversationContext prepareConversation(String conversationId,
                                                   String platform,
                                                   String modelType,
                                                   List<ChatMessageDto> incomingMessages) {
        if (incomingMessages == null || incomingMessages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }

        ConversationState state;
        if (conversationId == null || conversationId.isBlank()) {
            Instant now = Instant.now();
            String id = UUID.randomUUID().toString();
            String title = buildTitle(incomingMessages);
            state = new ConversationState(id, title, platform, modelType, now, now);
            conversations.put(id, state);
            log.info("conversation auto-created, conversationId={}, title={}", id, title);
        } else {
            state = getRequiredConversation(conversationId);
        }

        synchronized (state) {
            state.setPlatform(platform);
            state.setModelType(modelType);
            List<ChatMessageDto> history = copyMessages(state.getMessages());
            List<ChatMessageDto> incomingCopy = copyMessages(incomingMessages);
            state.getMessages().addAll(incomingCopy);
            state.setUpdatedAt(Instant.now());
            return new ConversationContext(
                    state.getId(),
                    state.getTitle(),
                    state.getPlatform(),
                    state.getModelType(),
                    history,
                    incomingCopy
            );
        }
    }

    /**
     * 将模型回复补入指定会话。
     *
     * @param conversationId 会话 ID
     * @param content 模型回复
     * @param platform 平台
     * @param modelType 模型类型
     */
    public void appendAssistantMessage(String conversationId, String content, String platform, String modelType) {
        if (content == null || content.isBlank()) {
            return;
        }
        ConversationState state = getRequiredConversation(conversationId);
        synchronized (state) {
            state.setPlatform(platform);
            state.setModelType(modelType);
            state.getMessages().add(new ChatMessageDto("assistant", content));
            state.setUpdatedAt(Instant.now());
        }
        log.info("assistant message appended, conversationId={}, length={}", conversationId, content.length());
    }

    /**
     * 删除指定会话。
     *
     * @param conversationId 会话 ID
     */
    public void delete(String conversationId) {
        conversations.remove(conversationId);
        log.info("conversation deleted, conversationId={}", conversationId);
    }

    /**
     * 将内部状态转换为摘要 DTO。
     *
     * @param state 内部会话状态
     * @return 会话摘要
     */
    private ConversationSummaryDto toSummary(ConversationState state) {
        return new ConversationSummaryDto(
                state.getId(),
                state.getTitle(),
                state.getPlatform(),
                state.getModelType(),
                state.getCreatedAt(),
                state.getUpdatedAt()
        );
    }

    /**
     * 将内部状态转换为详情 DTO。
     *
     * @param state 内部会话状态
     * @return 会话详情
     */
    private ConversationDetailDto toDetail(ConversationState state) {
        synchronized (state) {
            return new ConversationDetailDto(
                    state.getId(),
                    state.getTitle(),
                    state.getPlatform(),
                    state.getModelType(),
                    state.getCreatedAt(),
                    state.getUpdatedAt(),
                    copyMessages(state.getMessages())
            );
        }
    }

    /**
     * 读取必定存在的会话。
     *
     * @param conversationId 会话 ID
     * @return 会话状态
     */
    private ConversationState getRequiredConversation(String conversationId) {
        ConversationState state = conversations.get(conversationId);
        if (state == null) {
            throw new IllegalArgumentException("会话不存在: " + conversationId);
        }
        return state;
    }

    /**
     * 生成默认会话标题。
     *
     * @param messages 首轮消息
     * @return 标题
     */
    private String buildTitle(List<ChatMessageDto> messages) {
        String content = messages.stream()
                .filter(message -> message != null && "user".equalsIgnoreCase(message.getRole()))
                .map(ChatMessageDto::getContent)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse("新对话")
                .replaceAll("\\s+", " ")
                .trim();
        if (content.length() <= TITLE_MAX_LENGTH) {
            return content;
        }
        return content.substring(0, TITLE_MAX_LENGTH) + "...";
    }

    /**
     * 复制消息列表，避免共享可变对象。
     *
     * @param source 源消息列表
     * @return 副本
     */
    private List<ChatMessageDto> copyMessages(List<ChatMessageDto> source) {
        List<ChatMessageDto> copy = new ArrayList<>();
        for (ChatMessageDto message : source) {
            copy.add(new ChatMessageDto(message.getRole(), message.getContent()));
        }
        return copy;
    }

    /**
     * 会话上下文。
     */
    public record ConversationContext(String conversationId,
                                      String title,
                                      String platform,
                                      String modelType,
                                      List<ChatMessageDto> historyMessages,
                                      List<ChatMessageDto> incomingMessages) {
    }

    /**
     * 内部会话状态。
     */
    private static final class ConversationState {

        private final String id;

        private final String title;

        private String platform;

        private String modelType;

        private final Instant createdAt;

        private Instant updatedAt;

        private final List<ChatMessageDto> messages = new ArrayList<>();

        private ConversationState(String id,
                                  String title,
                                  String platform,
                                  String modelType,
                                  Instant createdAt,
                                  Instant updatedAt) {
            this.id = id;
            this.title = title;
            this.platform = platform;
            this.modelType = modelType;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        private String getId() {
            return id;
        }

        private String getTitle() {
            return title;
        }

        private String getPlatform() {
            return platform;
        }

        private void setPlatform(String platform) {
            this.platform = platform;
        }

        private String getModelType() {
            return modelType;
        }

        private void setModelType(String modelType) {
            this.modelType = modelType;
        }

        private Instant getCreatedAt() {
            return createdAt;
        }

        private Instant getUpdatedAt() {
            return updatedAt;
        }

        private void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }

        private List<ChatMessageDto> getMessages() {
            return messages;
        }
    }
}
