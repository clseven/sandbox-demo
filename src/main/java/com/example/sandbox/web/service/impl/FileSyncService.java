package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.service.FileStorageService;
import com.example.sandbox.web.service.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件同步服务
 *
 * <p>将本地存储的文件同步到沙盒中。
 * 统一处理 skill 文件和用户上传文件的同步逻辑。</p>
 *
 * @author example
 * @date 2026/05/20
 */
@Service
public class FileSyncService {

    private static final Logger log = LoggerFactory.getLogger(FileSyncService.class);

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private SandboxService sandboxService;

    /**
     * 同步用户上传的文件到沙盒
     *
     * @param sessionId 会话 ID
     */
    public void syncUploadFiles(String sessionId) {
        syncFromLocal(
                Path.of(fileStorageService.getStoragePath(sessionId)),
                fileStorageService.getMountPath(),
                sessionId
        );
    }

    /**
     * 同步 skill 文件到沙盒
     *
     * @param sessionId 会话 ID
     * @param skillPath skill 本地路径
     * @param skillId skill ID
     */
    public void syncSkill(String sessionId, Path skillPath, String skillId) {
        String containerBase = "/mounted-skills/" + skillId;
        syncFromLocal(skillPath, containerBase, sessionId);
    }

    /**
     * 从本地目录同步文件到沙盒
     *
     * @param sourceDir 本地源目录
     * @param targetContainerPath 沙盒内目标路径前缀
     * @param sessionId 会话 ID
     */
    private void syncFromLocal(Path sourceDir, String targetContainerPath, String sessionId) {
        if (!Files.exists(sourceDir)) {
            log.warn("同步源目录不存在: {}", sourceDir);
            return;
        }

        try (var stream = Files.walk(sourceDir)) {
            var files = stream.filter(Files::isRegularFile).toList();
            if (files.isEmpty()) {
                log.info("同步目录为空: {}", sourceDir);
                return;
            }

            for (Path file : files) {
                try {
                    String relativePath = sourceDir.relativize(file).toString().replace("\\", "/");
                    String containerPath = targetContainerPath + "/" + relativePath;
                    String content = Files.readString(file);

                    sandboxService.writeFile(sessionId, containerPath, content);
                    log.debug("已同步: {} -> {}", file.getFileName(), containerPath);
                } catch (IOException e) {
                    log.warn("同步文件失败: {} - {}", file, e.getMessage());
                }
            }
            log.info("同步完成，共 {} 个文件", files.size());
        } catch (IOException e) {
            log.error("遍历同步目录失败: {}", sourceDir, e);
        }
    }
}