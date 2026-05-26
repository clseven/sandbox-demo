package com.example.sandbox.web.service.impl;

import com.example.sandbox.agent.SandboxAgent;
import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.model.entity.ExecutionResult;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.service.SandboxClient;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.SkillService;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import jakarta.annotation.PostConstruct;
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

    private final Map<String, SandboxAgent> sandboxAgents = new ConcurrentHashMap<>();
    private final Map<String, String> sessionSandboxMap = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sessionTypeMap = new ConcurrentHashMap<>();
    private final Map<String, Object> creationLocks = new ConcurrentHashMap<>();

    private final SkillService skillService;
    private final ConversationSessionRepository sessionRepository;
    private final AgentConfigProperties config;

    @Autowired
    private AioSandboxStore aioSandboxStore;

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

    @PostConstruct
    public void cleanupStaleRecords() {
        if (!isCurrentImageAio()) {
            log.info("当前为非 AIO 镜像，清理残留沙箱记录...");
            try {
                var sessions = sessionRepository.findAllWithSandbox();
                for (var session : sessions) {
                    if (session.getAioEndpoint() == null || session.getAioEndpoint().isBlank()) {
                        session.setSandboxId(null);
                        sessionRepository.save(session);
                    }
                }
                log.info("非 AIO 沙箱记录清理完成");
            } catch (Exception e) {
                log.warn("清理残留沙箱记录失败: {}", e.getMessage());
            }
        }
    }

    public void registerSandbox(String sessionId, SandboxAgent agent) {
        sandboxAgents.put(agent.getSandboxId(), agent);
        sessionSandboxMap.put(sessionId, agent.getSandboxId());
        boolean isAio = isCurrentImageAio();
        sessionTypeMap.put(sessionId, isAio);
        log.info("Registered sandbox {} for session {} (type: {})", agent.getSandboxId(), sessionId,
                isAio ? "AIO" : "COMMON");

        if (isAio) {
            String endpoint = agent.getAioEndpoint();
            aioSandboxStore.register(sessionId, agent.getSandboxId(), endpoint);
            persistSandboxInfo(sessionId, agent.getSandboxId(), endpoint);
        } else {
            persistSandboxInfo(sessionId, agent.getSandboxId(), null);
        }
    }

    @Transactional
    public void persistSandboxInfo(String sessionId, String sandboxId, String aioEndpoint) {
        try {
            var session = sessionRepository.findById(sessionId);
            if (session.isPresent()) {
                ConversationSessionEntity entity = session.get();
                entity.setSandboxId(sandboxId);
                entity.setAioEndpoint(aioEndpoint);
                sessionRepository.save(entity);
                log.info("持久化沙箱信息: sessionId={}, sandboxId={}, aioEndpoint={}", sessionId, sandboxId, aioEndpoint);
            } else {
                log.warn("会话 {} 不存在，无法持久化沙箱信息", sessionId);
            }
        } catch (Exception e) {
            log.error("持久化沙箱信息失败: sessionId={}", sessionId, e);
        }
    }

    @Override
    public void createSandbox(String sessionId) {
        if (hasSandbox(sessionId)) {
            String sandboxId = sessionSandboxMap.get(sessionId);
            SandboxAgent existingAgent = sandboxAgents.get(sandboxId);
            if (existingAgent != null && existingAgent.isHealthy()) {
                log.info("沙箱 {} 对会话 {} 状态健康，跳过创建", sandboxId, sessionId);
                return;
            }
            log.warn("沙箱 {} 对会话 {} 不健康，清理中", sandboxId, sessionId);
            removeSandbox(sessionId);
        }

        Object lock = creationLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            try {
                if (hasSandbox(sessionId)) {
                    return;
                }

                SandboxAgent.Builder builder = SandboxAgent.builder()
                        .image(config.getSandbox().getImage())
                        .timeout(Duration.parse(config.getSandbox().getTimeout()))
                        .readyTimeout(Duration.parse(config.getSandbox().getReadyTimeout()));

                if (isCurrentImageAio()) {
                    builder.entrypoint("/opt/gem/run.sh");
                }

                SandboxAgent agent = builder.build();
                registerSandbox(sessionId, agent);
                log.info("创建沙箱 {} 对会话 {}", agent.getSandboxId(), sessionId);

                if (isCurrentImageAio()) {
                    initAioContext(sessionId, agent);
                }

                fileSyncService.syncUploadFiles(sessionId);
                syncAllEnabledSkills(sessionId);

            } catch (Exception e) {
                log.error("沙箱创建失败，会话 {}", sessionId, e);
                throw new RuntimeException("沙箱创建失败：" + e.getMessage(), e);
            } finally {
                creationLocks.remove(sessionId);
            }
        }
    }

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

    private void syncSkillToSandbox(String sessionId, Skill skill) {
        fileSyncService.syncSkill(sessionId, skill.getLocalPath(), skill.getId());
        log.info("技能 {} 同步完成", skill.getId());
    }

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
        if (isAioSandbox(sessionId) && aioSandboxStore.hasSandbox(sessionId)) {
            return true;
        }

        String sandboxId = sessionSandboxMap.get(sessionId);
        if (sandboxId == null) {
            return false;
        }
        SandboxAgent agent = sandboxAgents.get(sandboxId);
        if (agent == null) {
            log.warn("Sandbox agent {} lost, cleaning up session {}", sandboxId, sessionId);
            sessionSandboxMap.remove(sessionId);
            return false;
        }
        return true;
    }

    public SandboxAgent getSandbox(String sessionId) {
        String sandboxId = sessionSandboxMap.get(sessionId);
        if (sandboxId == null) {
            throw new SessionNotFoundException("No sandbox for session: " + sessionId);
        }
        SandboxAgent agent = sandboxAgents.get(sandboxId);
        if (agent == null) {
            sessionSandboxMap.remove(sessionId);
            throw new SessionNotFoundException("Sandbox agent lost for session: " + sessionId);
        }
        if (!agent.isHealthy()) {
            log.warn("Sandbox {} for session {} is unhealthy", sandboxId, sessionId);
            removeSandbox(sessionId);
            throw new SessionNotFoundException("Sandbox unhealthy for session: " + sessionId);
        }
        return agent;
    }

    @Override
    public void removeSandbox(String sessionId) {
        String sandboxId = sessionSandboxMap.remove(sessionId);
        sessionTypeMap.remove(sessionId);

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

        if (isCurrentImageAio()) {
            aioSandboxStore.remove(sessionId);
        }
        clearSandboxRecord(sessionId);
    }

    @Transactional
    public void clearSandboxRecord(String sessionId) {
        try {
            var session = sessionRepository.findById(sessionId);
            if (session.isPresent()) {
                ConversationSessionEntity entity = session.get();
                entity.setSandboxId(null);
                entity.setAioEndpoint(null);
                sessionRepository.save(entity);
            }
        } catch (Exception e) {
            log.warn("清除沙箱记录失败: sessionId={}", sessionId, e);
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
        Boolean sessionType = sessionTypeMap.get(sessionId);
        if (sessionType != null) {
            return sessionType;
        }
        return isCurrentImageAio();
    }

    // ==================== AIO Sandbox ====================

    public AioSandboxClient getAioClient(String sessionId) {
        if (aioSandboxStore.hasSandbox(sessionId)) {
            return aioSandboxStore.getClient(sessionId);
        }

        SandboxAgent agent = getSandbox(sessionId);
        String endpoint = agent.getAioEndpoint();
        log.info("AIO endpoint for session {}: {}", sessionId, endpoint);
        return new AioSandboxClient("http://" + endpoint);
    }

    private boolean isCurrentImageAio() {
        String image = config.getSandbox().getImage();
        if (image == null) {
            return false;
        }
        return image.contains("agent-infra/sandbox") || image.contains("all-in-one-sandbox");
    }

    private void initAioContext(String sessionId, SandboxAgent agent) {
        try {
            String endpoint = agent.getAioEndpoint();
            log.info("AIO endpoint for session {}: http://{}", sessionId, endpoint);
            AioSandboxClient client = new AioSandboxClient("http://" + endpoint);

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
