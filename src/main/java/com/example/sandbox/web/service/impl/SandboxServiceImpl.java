package com.example.sandbox.web.service.impl;

import com.example.sandbox.agent.SandboxAgent;
import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.model.entity.ExecutionResult;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.service.SandboxClient;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.SkillService;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙盒操作服务实现
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class SandboxServiceImpl implements SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxServiceImpl.class);

    /**
     * 沙盒实例存储（sandboxId -> SandboxAgent）
     */
    private final Map<String, SandboxAgent> sandboxAgents = new ConcurrentHashMap<>();

    /**
     * 会话到沙盒 ID 的映射
     */
    private final Map<String, String> sessionSandboxMap = new ConcurrentHashMap<>();

    private final SkillService skillService;
    private final ConversationSessionRepository sessionRepository;
    private final AgentConfigProperties config;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private FileSyncService fileSyncService;

    public SandboxServiceImpl(SkillService skillService,
                             ConversationSessionRepository sessionRepository,
                             AgentConfigProperties config) {
        this.skillService = skillService;
        this.sessionRepository = sessionRepository;
        this.config = config;
    }

    /**
     * 注册沙盒实例到会话
     */
    public void registerSandbox(String sessionId, SandboxAgent agent) {
        sandboxAgents.put(agent.getSandboxId(), agent);
        sessionSandboxMap.put(sessionId, agent.getSandboxId());
        log.info("Registered sandbox {} for session {}", agent.getSandboxId(), sessionId);
    }

    @Override
    public void createSandbox(String sessionId) {
        // 先检查现有沙箱是否健康
        if (hasSandbox(sessionId)) {
            String sandboxId = sessionSandboxMap.get(sessionId);
            SandboxAgent existingAgent = sandboxAgents.get(sandboxId);
            if (existingAgent != null && existingAgent.isHealthy()) {
                log.info("沙箱 {} 对会话 {} 状态健康，跳过创建", sandboxId, sessionId);
                return;
            }
            // 不健康，清理旧记录
            log.warn("沙箱 {} 对会话 {} 不健康，清理中", sandboxId, sessionId);
            removeSandbox(sessionId);
        }

        try {
            // 构建沙箱
            SandboxAgent.Builder builder = SandboxAgent.builder()
                    .image(config.getSandbox().getImage())
                    .timeout(Duration.parse(config.getSandbox().getTimeout()))
                    .readyTimeout(Duration.parse(config.getSandbox().getReadyTimeout()));

            // AIO 镜像需要使用 /opt/gem/run.sh 启动服务
            if (isAioImage(config.getSandbox().getImage())) {
                builder.entrypoint("/opt/gem/run.sh");
            }

            SandboxAgent agent = builder.build();
            registerSandbox(sessionId, agent);
            log.info("创建沙箱 {} 对会话 {}", agent.getSandboxId(), sessionId);

            // 如果是 AIO 镜像，获取环境信息
            if (isAioImage(config.getSandbox().getImage())) {
                initAioContext(sessionId, agent);
            }

            // 同步用户上传的文件到沙盒
            fileSyncService.syncUploadFiles(sessionId);

            // 同步所有已启用的技能
            syncAllEnabledSkills(sessionId);

        } catch (Exception e) {
            log.error("沙箱创建失败，会话 {}", sessionId, e);
            throw new RuntimeException("沙箱创建失败：" + e.getMessage(), e);
        }
    }

    /**
     * 同步所有已启用的技能到沙箱
     */
    private void syncAllEnabledSkills(String sessionId) {
        Set<String> enabledSkillIds = getEnabledSkillIds(sessionId);
        for (String skillId : enabledSkillIds) {
            try {
                Skill skill = skillService.getSkill(skillId);
                syncSkillToSandbox(sessionId, skill);
            } catch (Exception e) {
                log.warn("同步技能 {} 失败: {}", skillId, e.getMessage());
            }
        }
        log.info("会话 {} 已同步 {} 个技能", sessionId, enabledSkillIds.size());
    }

    /**
     * 同步单个技能到沙箱
     */
    private void syncSkillToSandbox(String sessionId, Skill skill) {
        fileSyncService.syncSkill(sessionId, skill.getLocalPath(), skill.getId());
        log.info("技能 {} 同步完成", skill.getId());
    }

    /**
     * 获取会话启用的技能 ID 列表（public 以便 Spring 事务代理）
     */
    @Transactional(readOnly = true)
    public Set<String> getEnabledSkillIds(String sessionId) {
        try {
            var session = sessionRepository.findById(sessionId);
            if (session.isPresent()) {
                return session.get().getEnabledSkillIds();
            }
        } catch (Exception e) {
            log.warn("获取会话 {} 启用技能失败: {}", sessionId, e.getMessage());
        }
        return Set.of();
    }

    @Override
    public boolean hasSandbox(String sessionId) {
        String sandboxId = sessionSandboxMap.get(sessionId);
        if (sandboxId == null) {
            return false;
        }
        SandboxAgent agent = sandboxAgents.get(sandboxId);
        // 记录存在但 agent 丢失，清理脏数据
        if (agent == null) {
            log.warn("Sandbox agent {} lost, cleaning up session {}", sandboxId, sessionId);
            sessionSandboxMap.remove(sessionId);
            return false;
        }
        return true;
    }

    /**
     * 获取会话关联的沙盒（带健康检查）
     */
    public SandboxAgent getSandbox(String sessionId) {
        String sandboxId = sessionSandboxMap.get(sessionId);
        if (sandboxId == null) {
            throw new SessionNotFoundException("No sandbox for session: " + sessionId);
        }
        SandboxAgent agent = sandboxAgents.get(sandboxId);
        if (agent == null) {
            // 脏数据，清理
            sessionSandboxMap.remove(sessionId);
            throw new SessionNotFoundException("Sandbox agent lost for session: " + sessionId);
        }
        if (!agent.isHealthy()) {
            // 沙箱不健康，清理并抛异常
            log.warn("Sandbox {} for session {} is unhealthy", sandboxId, sessionId);
            removeSandbox(sessionId);
            throw new SessionNotFoundException("Sandbox unhealthy for session: " + sessionId);
        }
        return agent;
    }

    /**
     * 移除会话的沙盒
     */
    public void removeSandbox(String sessionId) {
        String sandboxId = sessionSandboxMap.remove(sessionId);
        if (sandboxId != null) {
            SandboxAgent agent = sandboxAgents.remove(sandboxId);
            if (agent != null) {
                try {
                    agent.close();
                    log.info("Closed sandbox {} for session {}", sandboxId, sessionId);
                } catch (Exception e) {
                    log.error("Failed to close sandbox for session: {}", sessionId, e);
                }
            }
        }
    }

    @Override
    public ExecutionResult executeCommand(String sessionId, String command) {
        SandboxAgent agent = getSandbox(sessionId);
        Instant start = Instant.now();

        try {
            SandboxAgent.CommandResult result = agent.executeCommand(command);
            Duration duration = Duration.between(start, Instant.now());

            if (result.isSuccess()) {
                return ExecutionResult.success(result.getStdout(), duration);
            } else {
                return ExecutionResult.error("Exit code: " + result.getExitCode(), duration);
            }
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            return ExecutionResult.error(e.getMessage(), duration);
        }
    }

    @Override
    public ExecutionResult readFile(String sessionId, String path) {
        SandboxAgent agent = getSandbox(sessionId);
        Instant start = Instant.now();

        try {
            String content = agent.readFile(path);
            Duration duration = Duration.between(start, Instant.now());
            return ExecutionResult.success(content, duration);
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            return ExecutionResult.error(e.getMessage(), duration);
        }
    }

    @Override
    public ExecutionResult writeFile(String sessionId, String path, String content) {
        SandboxAgent agent = getSandbox(sessionId);
        Instant start = Instant.now();

        try {
            agent.writeFile(path, content);
            Duration duration = Duration.between(start, Instant.now());
            return ExecutionResult.success("File written: " + path, duration);
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            return ExecutionResult.error(e.getMessage(), duration);
        }
    }

    @Override
    public boolean isAioSandbox(String sessionId) {
        return isAioImage(config.getSandbox().getImage());
    }

    // ==================== AIO Sandbox ====================

    /**
     * 获取会话的 AIO Sandbox 客户端
     *
     * <p>用于调用 AIO 镜像特有的能力，如浏览器截图。</p>
     *
     * @param sessionId 会话 ID
     * @return AIO Sandbox 客户端
     */
    public AioSandboxClient getAioClient(String sessionId) {
        SandboxAgent agent = getSandbox(sessionId);
        String endpoint = agent.getAioEndpoint();
        log.info("AIO endpoint for session {}: {}", sessionId, endpoint);
        return new AioSandboxClient("http://" + endpoint);
    }

    /**
     * 判断是否为 AIO 镜像
     */
    private boolean isAioImage(String image) {
        if (image == null) return false;
        // 匹配原版 AIO 镜像或国内镜像
        return image.contains("agent-infra/sandbox") || image.contains("all-in-one-sandbox");
    }

    /**
     * 沙箱创建后，自动获取 AIO 环境信息
     */
    private void initAioContext(String sessionId, SandboxAgent agent) {
        try {
            String endpoint = agent.getAioEndpoint();
            log.info("AIO endpoint for session {}: http://{}", sessionId, endpoint);
            AioSandboxClient client = new AioSandboxClient("http://" + endpoint);

            // 等待 AIO 服务就绪
            log.info("等待 AIO 服务就绪，会话: {}", sessionId);
            boolean ready = client.waitForReady();
            if (!ready) {
                log.warn("AIO 服务未就绪，会话: {}", sessionId);
                return;
            }

            SandboxClient.SandboxContext context = client.getContext();

            if (context != null) {
                log.info("AIO 沙箱环境就绪 - 会话: {}, homeDir: {}, workspace: {}",
                        sessionId, context.getHomeDir(), context.getWorkspace());
            } else {
                log.warn("AIO 沙箱环境信息为空，会话: {}", sessionId);
            }
        } catch (Exception e) {
            log.warn("获取 AIO 沙箱环境信息失败（不影响沙箱使用），会话: {}, 原因: {}",
                    sessionId, e.getMessage());
        }
    }
}