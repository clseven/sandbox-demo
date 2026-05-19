package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.ToolDefinition;

import java.util.Map;

/**
 * 工具接口
 *
 * @author example
 * @date 2026/05/15
 */
public interface Tool {

    /**
     * 获取工具定义
     *
     * @return 工具定义
     */
    ToolDefinition getDefinition();

    /**
     * 执行工具
     *
     * @param sessionId 会话 ID
     * @param arguments 工具参数
     * @return 执行结果
     */
    String execute(String sessionId, Map<String, Object> arguments);
}
