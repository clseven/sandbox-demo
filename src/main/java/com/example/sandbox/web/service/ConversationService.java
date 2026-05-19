package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.ChatMessage;

import java.util.List;
import java.util.Set;

/**
 * 对话记忆服务接口
 *
 * @author example
 * @date 2026/05/14
 */
public interface ConversationService {

    /**
     * 添加用户消息
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     */
    void addUserMessage(String sessionId, String content);

    /**
     * 添加助手消息
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     */
    void addAssistantMessage(String sessionId, String content);

    /**
     * 获取消息历史
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    List<ChatMessage> getHistory(String sessionId);

    /**
     * 清空消息历史
     *
     * @param sessionId 会话 ID
     */
    void clearHistory(String sessionId);

    /**
     * 构建发送给 LLM 的 prompt
     *
     * @param sessionId 会话 ID
     * @return prompt 字符串
     */
    String buildPrompt(String sessionId);

    /**
     * 启用技能
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     */
    void enableSkill(String sessionId, String skillId);

    /**
     * 禁用技能
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     */
    void disableSkill(String sessionId, String skillId);

    /**
     * 获取启用的技能 ID
     *
     * @param sessionId 会话 ID
     * @return 技能 ID 集合
     */
    Set<String> getEnabledSkillIds(String sessionId);
}
