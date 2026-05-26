package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * DeepSeek LLM 服务实现（OpenAI 兼容 API）
 *
 * @author example
 * @date 2026/05/14
 */
@Service("executorLlm")
public class DeepSeekLlmServiceImpl extends BaseLlmServiceImpl {

    @Autowired
    public DeepSeekLlmServiceImpl(AgentConfigProperties configProperties, ObjectMapper objectMapper) {
        super(
                configProperties.getLlm().getExecutor().getApiUrl(),
                configProperties.getLlm().getExecutor().getApiKey(),
                configProperties.getLlm().getExecutor().getModel(),
                objectMapper
        );
    }
}
