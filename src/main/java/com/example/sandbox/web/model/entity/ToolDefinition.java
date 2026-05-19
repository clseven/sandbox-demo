package com.example.sandbox.web.model.entity;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.Map;

/**
 * 工具定义
 *
 * @author example
 * @date 2026/05/15
 */
public class ToolDefinition {

    /**
     * 工具名称
     */
    private final String name;

    /**
     * 工具描述
     */
    private final String description;

    /**
     * 参数 JSON Schema
     */
    private final Map<String, Object> parameters;

    public ToolDefinition(String name, String description, Map<String, Object> parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    /**
     * 转换为 LLM API 需要的格式
     */
    public Map<String, Object> toApiFormat() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", parameters
                )
        );
    }
}
