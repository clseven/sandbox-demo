package com.example.sandbox.web.controller;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.model.request.ChatRequest;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.model.response.SessionResponse;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.ConversationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 会话及对话 API
 *
 * @author example
 * @date 2026/05/14
 */
@RestController
@RequestMapping("/api/sessions")
public class AgentController {

    @Autowired
    private AgentService agentService;

    @Autowired
    private ConversationService conversationService;

    /**
     * 创建会话
     */
    @PostMapping
    public ApiResponse<SessionResponse> createSession() {
        ConversationSession session = agentService.createSession();
        return ApiResponse.success(toSessionResponse(session));
    }

    /**
     * 关闭会话
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> closeSession(@PathVariable String id) {
        agentService.closeSession(id);
        return ApiResponse.success();
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/{id}")
    public ApiResponse<SessionResponse> getSession(@PathVariable String id) {
        ConversationSession session = agentService.getSession(id);
        return ApiResponse.success(toSessionResponse(session));
    }

    /**
     * 发送消息
     */
    @PostMapping("/{id}/chat")
    public ApiResponse<ChatMessage> chat(@PathVariable String id, @RequestBody ChatRequest request) {
        ChatMessage response = agentService.chat(id, request.getMessage());
        return ApiResponse.success(response);
    }

    /**
     * 获取历史消息
     */
    @GetMapping("/{id}/history")
    public ApiResponse<List<ChatMessage>> getHistory(@PathVariable String id) {
        List<ChatMessage> history = conversationService.getHistory(id);
        return ApiResponse.success(history);
    }

    /**
     * 获取启用的技能
     */
    @GetMapping("/{id}/skills")
    public ApiResponse<Set<String>> getEnabledSkills(@PathVariable String id) {
        Set<String> skills = conversationService.getEnabledSkillIds(id);
        return ApiResponse.success(skills);
    }

    /**
     * 启用技能
     */
    @PostMapping("/{id}/skills/{skillId}/enable")
    public ApiResponse<Void> enableSkill(@PathVariable String id, @PathVariable String skillId) {
        conversationService.enableSkill(id, skillId);
        return ApiResponse.success();
    }

    /**
     * 禁用技能
     */
    @PostMapping("/{id}/skills/{skillId}/disable")
    public ApiResponse<Void> disableSkill(@PathVariable String id, @PathVariable String skillId) {
        conversationService.disableSkill(id, skillId);
        return ApiResponse.success();
    }

    private SessionResponse toSessionResponse(ConversationSession session) {
        SessionResponse response = new SessionResponse();
        response.setSessionId(session.getSessionId());
        response.setSandboxId(session.getSandboxId());
        response.setEnabledSkillIds(session.getEnabledSkillIds());
        response.setCreatedAt(session.getCreatedAt());
        response.setUpdatedAt(session.getUpdatedAt());
        return response;
    }
}
