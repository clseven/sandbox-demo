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
    private Storage storage = new Storage();

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

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
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
        private String model = "glm-4.7";

        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class Storage {
        private String type = "local";
        private Local local = new Local();
        private Oss oss = new Oss();

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Local getLocal() { return local; }
        public void setLocal(Local local) { this.local = local; }
        public Oss getOss() { return oss; }
        public void setOss(Oss oss) { this.oss = oss; }

        public static class Local {
            private String basePath = "./uploads";

            public String getBasePath() { return basePath; }
            public void setBasePath(String basePath) { this.basePath = basePath; }
        }

        public static class Oss {
            private String endpoint = "";
            private String bucket = "";
            private String accessKey = "";
            private String secretKey = "";

            public String getEndpoint() { return endpoint; }
            public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
            public String getBucket() { return bucket; }
            public void setBucket(String bucket) { this.bucket = bucket; }
            public String getAccessKey() { return accessKey; }
            public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
            public String getSecretKey() { return secretKey; }
            public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        }
    }
}