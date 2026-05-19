package com.example.sandbox.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 配置属性
 *
 * @author example
 * @date 2026/05/14
 */
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentConfigProperties {

    private Sandbox sandbox = new Sandbox();
    private Skill skill = new Skill();
    private Llm llm = new Llm();

    public Sandbox getSandbox() {
        return sandbox;
    }

    public void setSandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public Skill getSkill() {
        return skill;
    }

    public void setSkill(Skill skill) {
        this.skill = skill;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public static class Sandbox {
        private String domain = "localhost:8080";
        private String image = "sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/code-interpreter:v1.0.2";
        private String timeout = "PT30M";
        private String readyTimeout = "PT120S";

        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
        public void setImage(String image) { this.image = image; }
        public String getImage() { return image; }
        public String getTimeout() { return timeout; }
        public void setTimeout(String timeout) { this.timeout = timeout; }
        public String getReadyTimeout() { return readyTimeout; }
        public void setReadyTimeout(String readyTimeout) { this.readyTimeout = readyTimeout; }
    }

    public static class Skill {
        private String directory = ".claude/skills";

        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
    }

    public static class Llm {
        private String apiUrl = "";
        private String apiKey = "";
        private String model = "glm-4";

        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }
}