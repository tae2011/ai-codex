package com.example.springaidemo.controller;

import com.example.springaidemo.dto.ConversationDetailDto;
import com.example.springaidemo.dto.ConversationSummaryDto;
import com.example.springaidemo.service.ConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供会话历史相关接口。
 */
@Slf4j
@CrossOrigin(origins = "*")
@RestController
public class ConversationController {

    private final ConversationService conversationService;

    /**
     * 注入会话服务。
     *
     * @param conversationService 会话服务
     */
    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * 获取所有会话摘要。
     *
     * @return 会话摘要列表
     */
    @GetMapping("/api/conversations")
    public List<ConversationSummaryDto> listConversations() {
        log.info("list conversations");
        return conversationService.list();
    }

    /**
     * 获取指定会话详情。
     *
     * @param conversationId 会话 ID
     * @return 会话详情
     */
    @GetMapping("/api/conversations/{conversationId}")
    public ConversationDetailDto getConversation(@PathVariable String conversationId) {
        log.info("get conversation detail, conversationId={}", conversationId);
        return conversationService.get(conversationId);
    }

    /**
     * 创建空白会话。
     *
     * @param platform 平台
     * @param modelType 模型类型
     * @return 新会话详情
     */
    @PostMapping("/api/conversations")
    public ConversationDetailDto createConversation(@RequestParam(required = false) String platform,
                                                    @RequestParam(required = false) String modelType) {
        log.info("create empty conversation, platform={}, modelType={}", platform, modelType);
        return conversationService.createEmpty(platform, modelType);
    }

    /**
     * 删除指定会话。
     *
     * @param conversationId 会话 ID
     */
    @DeleteMapping("/api/conversations/{conversationId}")
    public void deleteConversation(@PathVariable String conversationId) {
        log.info("delete conversation, conversationId={}", conversationId);
        conversationService.delete(conversationId);
    }
}
