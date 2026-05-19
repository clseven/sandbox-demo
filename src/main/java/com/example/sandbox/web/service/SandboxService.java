package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.ExecutionResult;

/**
 * 沙盒操作服务接口
 *
 * @author example
 * @date 2026/05/14
 */
public interface SandboxService {

    /**
     * 创建沙箱（按需创建）
     *
     * @param sessionId 会话 ID
     */
    void createSandbox(String sessionId);

    /**
     * 检查沙箱是否已创建
     *
     * @param sessionId 会话 ID
     * @return 是否已创建
     */
    boolean hasSandbox(String sessionId);

    /**
     * 执行命令
     *
     * @param sessionId 会话 ID
     * @param command   命令
     * @return 执行结果
     */
    ExecutionResult executeCommand(String sessionId, String command);

    /**
     * 读取文件
     *
     * @param sessionId 会话 ID
     * @param path      文件路径
     * @return 执行结果
     */
    ExecutionResult readFile(String sessionId, String path);

    /**
     * 写入文件
     *
     * @param sessionId 会话 ID
     * @param path      文件路径
     * @param content   文件内容
     * @return 执行结果
     */
    ExecutionResult writeFile(String sessionId, String path, String content);
}
