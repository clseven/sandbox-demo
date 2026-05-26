package com.example.sandbox.web.controller;

import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * 文件上传下载 API
 *
 * @author example
 * @date 2026/05/20
 */
@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private AgentService agentService;

    @PostMapping("/upload")
    public ApiResponse<String> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sessionId") String sessionId) {
        if (file.isEmpty()) {
            return ApiResponse.error(400, "文件不能为空");
        }
        agentService.getSession(sessionId);

        try {
            String path = fileStorageService.store(sessionId, file.getOriginalFilename(), file.getInputStream());
            return ApiResponse.success(path);
        } catch (Exception e) {
            log.error("文件上传失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "文件上传失败");
        }
    }

    @GetMapping("/download/{sessionId}/{filename}")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable String sessionId,
            @PathVariable String filename) {
        try {
            agentService.getSession(sessionId);
            InputStream inputStream = fileStorageService.getFile(sessionId, filename);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new InputStreamResource(inputStream));
        } catch (Exception e) {
            log.error("文件下载失败: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{sessionId}/{filename}")
    public ApiResponse<Void> delete(
            @PathVariable String sessionId,
            @PathVariable String filename) {
        try {
            agentService.getSession(sessionId);
            fileStorageService.delete(sessionId, filename);
            return ApiResponse.success();
        } catch (Exception e) {
            log.error("文件删除失败: {}", e.getMessage());
            return ApiResponse.error(500, "文件删除失败");
        }
    }

    /**
     * 获取存储路径信息（供调试用）
     */
    @GetMapping("/path/{sessionId}")
    public ApiResponse<StoragePathInfo> getStoragePath(@PathVariable String sessionId) {
        return ApiResponse.success(new StoragePathInfo(
                fileStorageService.getStoragePath(sessionId),
                fileStorageService.getMountPath()
        ));
    }

    public record StoragePathInfo(String hostPath, String containerPath) {}
}
