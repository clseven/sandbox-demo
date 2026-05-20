package com.example.sandbox.web.service.impl;

import com.example.sandbox.agent.SandboxAgent;
import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.service.SandboxClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 沙箱客户端工厂
 *
 * <p>根据会话类型返回对应的 SandboxClient 实现。</p>
 *
 * @author example
 * @date 2026/05/20
 */
@Component
public class SandboxClientFactory {

    @Autowired
    private SandboxServiceImpl sandboxService;

    /**
     * 获取会话对应的沙箱客户端
     *
     * @param sessionId 会话 ID
     * @return 沙箱客户端实现
     */
    public SandboxClient getClient(String sessionId) {
        if (sandboxService.isAioSandbox(sessionId)) {
            return getAioClient(sessionId);
        } else {
            return getOpensandboxClient(sessionId);
        }
    }

    /**
     * 获取 AIO 沙箱客户端
     */
    public AioSandboxClient getAioClient(String sessionId) {
        return sandboxService.getAioClient(sessionId);
    }

    /**
     * 获取 Opensandbox 沙箱客户端
     */
    public OpensandboxClient getOpensandboxClient(String sessionId) {
        OpensandboxClient client = new OpensandboxClient();
        client.setSessionId(sessionId);
        return client;
    }
}