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
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙盒操作服务实现
 *
 * <p>沙箱所有权为用户级别：一个用户永久持有一个沙箱，所有会话共享。</p>
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class SandboxServiceImpl implements SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxServiceImpl.class);

    private static final Duration SANDBOX_TIMEOUT = Duration.ofHours(24);
    private static final Duration RENEW_INTERVAL = Duration.ofMinutes(30);

    /** sandboxId → SandboxAgent */
    private final Map<String, SandboxAgent> sandboxAgents = new ConcurrentHashMap<>();
    /** sessionId → sandboxId */
    private final Map<String, String> sessionSandboxMap = new ConcurrentHashMap<>();
    /** sessionId → isAio */
    private final Map<String, Boolean> sessionTypeMap = new ConcurrentHashMap<>();
    /** userId → sandboxId（用户级永久沙箱） */
    private final Map<Long, String> userSandboxMap = new ConcurrentHashMap<>();
    /** sandboxId → userId（反向查找） */
    private final Map<String, Long> sandboxUserMap = new ConcurrentHashMap<>();
    /** 创建锁 */
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

    @PreDestroy
    public void shutdown() {
        log.info("应用关闭，销毁 {} 个用户沙箱...", userSandboxMap.size());
        List<Long> userIds = new ArrayList<>(userSandboxMap.keySet());
        for (Long userId : userIds) {
            destroyUserSandbox(userId);
        }
    }

    @Scheduled(fixedRate = 20 * 60 * 1000)
    public void renewAllSandboxes() {
        for (var entry : userSandboxMap.entrySet()) {
            try {
                String sandboxId = entry.getValue();
                SandboxAgent agent = sandboxAgents.get(sandboxId);
                if (agent != null && agent.isHealthy()) {
                    agent.renew(RENEW_INTERVAL);
                    log.debug("续期沙箱 {}（用户 {}）", sandboxId, entry.getKey());
                } else {
                    log.warn("沙箱 {} 不健康，移除用户 {} 映射", sandboxId, entry.getKey());
                    userSandboxMap.remove(entry.getKey());
                    sandboxUserMap.remove(sandboxId);
                }
            } catch (Exception e) {
                log.warn("续期沙箱失败，用户 {}", entry.getKey(), e);
            }
        }
    }

    // ==================== 沙箱创建（用户级） ====================

    @Override
    public void createSandbox(String sessionId) {
        Long userId = getUserIdForSession(sessionId);
        if (userId == null) {
            log.warn("无法解析会话 {} 对应的用户，跳过沙箱创建", sessionId);
            return;
        }

        String existingSandboxId = userSandboxMap.get(userId);
        if (existingSandboxId != null) {
            SandboxAgent existingAgent = sandboxAgents.get(existingSandboxId);
            if (existingAgent != null && existingAgent.isHealthy()) {
                linkSessionToSandbox(sessionId, existingSandboxId, existingAgent.getAioEndpoint());
                log.info("用户 {} 已有沙箱 {}，关联会话 {}", userId, existingSandboxId, sessionId);
                return;
            }
            log.warn("用户 {} 沙箱 {} 不健康，将重建", userId, existingSandboxId);
            userSandboxMap.remove(userId);
            sandboxUserMap.remove(existingSandboxId);
        }

        Object lock = creationLocks.computeIfAbsent("user:" + userId, k -> new Object());
        synchronized (lock) {
            try {
                existingSandboxId = userSandboxMap.get(userId);
                if (existingSandboxId != null && sandboxAgents.containsKey(existingSandboxId)) {
                    linkSessionToSandbox(sessionId, existingSandboxId,
                            sandboxAgents.get(existingSandboxId).getAioEndpoint());
                    return;
                }

                SandboxAgent.Builder builder = SandboxAgent.builder()
                        .image(config.getSandbox().getImage())
                        .timeout(SANDBOX_TIMEOUT)
                        .readyTimeout(Duration.parse(config.getSandbox().getReadyTimeout()));

                if (isCurrentImageAio()) {
                    builder.entrypoint("/opt/gem/run.sh");
                }

                SandboxAgent agent = builder.build();
                sandboxAgents.put(agent.getSandboxId(), agent);
                userSandboxMap.put(userId, agent.getSandboxId());
                sandboxUserMap.put(agent.getSandboxId(), userId);

                String endpoint = isCurrentImageAio() ? agent.getAioEndpoint() : null;
                linkSessionToSandbox(sessionId, agent.getSandboxId(), endpoint);

                log.info("为用户 {} 创建永久沙箱 {}（会话 {}）", userId, agent.getSandboxId(), sessionId);

                if (isCurrentImageAio()) {
                    aioSandboxStore.register(sessionId, agent.getSandboxId(), endpoint);
                    initAioContext(sessionId, agent);
                }

                fileSyncService.syncUploadFiles(sessionId);
                syncAllEnabledSkills(sessionId);

            } catch (Exception e) {
                log.error("沙箱创建失败，用户 {} 会话 {}", userId, sessionId, e);
                throw new RuntimeException("沙箱创建失败：" + e.getMessage(), e);
            } finally {
                creationLocks.remove("user:" + userId);
            }
        }
    }

    private void linkSessionToSandbox(String sessionId, String sandboxId, String aioEndpoint) {
        sessionSandboxMap.put(sessionId, sandboxId);
        sessionTypeMap.put(sessionId, isCurrentImageAio());
        persistSandboxInfo(sessionId, sandboxId, aioEndpoint);
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
            }
        } catch (Exception e) {
            log.warn("持久化沙箱信息失败: sessionId={}", sessionId, e);
        }
    }

    // ==================== 沙箱销毁 ====================

    @Override
    public void removeSandbox(String sessionId) {
        sessionSandboxMap.remove(sessionId);
        sessionTypeMap.remove(sessionId);
        if (isCurrentImageAio()) {
            aioSandboxStore.remove(sessionId);
        }
        clearSandboxRecord(sessionId);
    }

    private void destroyUserSandbox(Long userId) {
        String sandboxId = userSandboxMap.remove(userId);
        if (sandboxId == null) {
            return;
        }
        sandboxUserMap.remove(sandboxId);
        SandboxAgent agent = sandboxAgents.remove(sandboxId);
        if (agent != null) {
            try {
                agent.close();
                log.info("已销毁用户 {} 的沙箱 {}", userId, sandboxId);
            } catch (Exception e) {
                log.error("关闭沙箱失败: {}", e.getMessage());
            }
        }
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

    // ==================== 查询 ====================

    @Override
    public boolean hasSandbox(String sessionId) {
        String sandboxId = sessionSandboxMap.get(sessionId);
        if (sandboxId != null && sandboxAgents.containsKey(sandboxId)) {
            return true;
        }

        Long userId = getUserIdForSession(sessionId);
        if (userId != null) {
            sandboxId = userSandboxMap.get(userId);
            if (sandboxId != null && sandboxAgents.containsKey(sandboxId)) {
                sessionSandboxMap.put(sessionId, sandboxId);
                return true;
            }
        }

        if (isCurrentImageAio() && aioSandboxStore.hasSandbox(sessionId)) {
            return true;
        }

        return false;
    }

    public SandboxAgent getSandbox(String sessionId) {
        // 先查会话映射，再查用户映射
        String sandboxId = sessionSandboxMap.get(sessionId);
        if (sandboxId == null) {
            Long userId = getUserIdForSession(sessionId);
            if (userId != null) {
                sandboxId = userSandboxMap.get(userId);
                if (sandboxId != null) {
                    sessionSandboxMap.put(sessionId, sandboxId);
                }
            }
        }

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
    public boolean isAioSandbox(String sessionId) {
        Boolean sessionType = sessionTypeMap.get(sessionId);
        if (sessionType != null) {
            return sessionType;
        }
        return isCurrentImageAio();
    }

    // ==================== 命令/文件操作 ====================

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
            return ExecutionResult.error(e.getMessage(), Duration.between(start, Instant.now()));
        }
    }

    @Override
    public ExecutionResult readFile(String sessionId, String path) {
        SandboxAgent agent = getSandbox(sessionId);
        Instant start = Instant.now();

        try {
            String content = agent.readFile(path);
            return ExecutionResult.success(content, Duration.between(start, Instant.now()));
        } catch (Exception e) {
            return ExecutionResult.error(e.getMessage(), Duration.between(start, Instant.now()));
        }
    }

    @Override
    public ExecutionResult writeFile(String sessionId, String path, String content) {
        SandboxAgent agent = getSandbox(sessionId);
        Instant start = Instant.now();

        try {
            agent.writeFile(path, content);
            return ExecutionResult.success("File written: " + path, Duration.between(start, Instant.now()));
        } catch (Exception e) {
            return ExecutionResult.error(e.getMessage(), Duration.between(start, Instant.now()));
        }
    }

    // ==================== AIO ====================

    public AioSandboxClient getAioClient(String sessionId) {
        if (aioSandboxStore.hasSandbox(sessionId)) {
            return aioSandboxStore.getClient(sessionId);
        }

        SandboxAgent agent = getSandbox(sessionId);
        String endpoint = agent.getAioEndpoint();
        return new AioSandboxClient("http://" + endpoint);
    }

    public String getAioEndpoint(String sessionId) {
        if (aioSandboxStore.hasSandbox(sessionId)) {
            return aioSandboxStore.getEndpoint(sessionId);
        }

        SandboxAgent agent = getSandbox(sessionId);
        return agent.getAioEndpoint();
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
            AioSandboxClient client = new AioSandboxClient("http://" + endpoint);

            log.info("等待 AIO 服务就绪，会话: {}", sessionId);
            if (!client.waitForReady()) {
                log.warn("AIO 服务未就绪，会话: {}", sessionId);
                return;
            }

            SandboxClient.SandboxContext context = client.getContext();
            if (context != null) {
                log.info("AIO 沙箱就绪 - 会话: {}, workspace: {}", sessionId, context.getWorkspace());
            }
        } catch (Exception e) {
            log.warn("获取 AIO 环境信息失败（不影响使用），会话: {}, 原因: {}", sessionId, e.getMessage());
        }
    }

    // ==================== 技能同步 ====================

    private void syncAllEnabledSkills(String sessionId) {
        Set<String> enabledSkillIds = getEnabledSkillIds(sessionId);
        for (String skillId : enabledSkillIds) {
            try {
                Skill skill = skillService.getSkill(skillId);
                fileSyncService.syncSkill(sessionId, skill.getLocalPath(), skill.getId());
            } catch (Exception e) {
                log.warn("同步技能 {} 失败: {}", skillId, e.getMessage());
            }
        }
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

    private Long getUserIdForSession(String sessionId) {
        try {
            var session = sessionRepository.findById(sessionId);
            return session.map(ConversationSessionEntity::getUserId).orElse(null);
        } catch (Exception e) {
            log.warn("获取会话 {} 对应用户失败: {}", sessionId, e.getMessage());
            return null;
        }
    }
}
