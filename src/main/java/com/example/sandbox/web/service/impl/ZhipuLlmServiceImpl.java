package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 智谱 AI LLM 服务实现（GLM 系列模型）
 *
 * @author example
 * @date 2026/05/14
 */
@Service("plannerLlm")
public class ZhipuLlmServiceImpl extends BaseLlmServiceImpl {

    @Autowired
    public ZhipuLlmServiceImpl(AgentConfigProperties configProperties, ObjectMapper objectMapper) {
        super(
                configProperties.getLlm().getPlanner().getApiUrl(),
                configProperties.getLlm().getPlanner().getApiKey(),
                configProperties.getLlm().getPlanner().getModel(),
                objectMapper
        );
    }
}
