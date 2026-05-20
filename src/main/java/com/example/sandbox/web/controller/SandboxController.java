package com.example.sandbox.web.controller;

import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.model.entity.ExecutionResult;
import com.example.sandbox.web.model.request.ExecuteRequest;
import com.example.sandbox.web.model.request.FileWriteRequest;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.impl.SandboxServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

/**
 * 沙盒操作 API
 *
 * @author example
 * @date 2026/05/14
 */
@RestController
@RequestMapping("/api/sessions")
public class SandboxController {

    @Autowired
    private SandboxService sandboxService;

    @Autowired
    private SandboxServiceImpl sandboxServiceImpl;

    /**
     * 执行命令
     */
    @PostMapping("/{id}/execute")
    public ApiResponse<ExecutionResult> executeCommand(
            @PathVariable String id,
            @RequestBody ExecuteRequest request) {
        ExecutionResult result = sandboxService.executeCommand(id, request.getCommand());
        return ApiResponse.success(result);
    }

    /**
     * 读取文件
     */
    @PostMapping("/{id}/files/read")
    public ApiResponse<ExecutionResult> readFile(
            @PathVariable String id,
            @RequestBody FileWriteRequest request) {
        ExecutionResult result = sandboxService.readFile(id, request.getPath());
        return ApiResponse.success(result);
    }

    /**
     * 写入文件
     */
    @PostMapping("/{id}/files/write")
    public ApiResponse<ExecutionResult> writeFile(
            @PathVariable String id,
            @RequestBody FileWriteRequest request) {
        ExecutionResult result = sandboxService.writeFile(id, request.getPath(), request.getContent());
        return ApiResponse.success(result);
    }

    /**
     * 下载 AIO 沙箱中的文件
     */
    @GetMapping("/{id}/aio/download")
    public void downloadAioFile(@PathVariable String id, @RequestParam String path, HttpServletResponse response) {
        try {
            AioSandboxClient client = sandboxServiceImpl.getAioClient(id);
            byte[] fileContent = client.downloadFile(path);

            String filename = path.substring(path.lastIndexOf('/') + 1);
            response.setContentType(MediaType.IMAGE_PNG_VALUE);
            response.setHeader("Content-Disposition", "inline; filename=\"" + filename + "\"");
            response.getOutputStream().write(fileContent);
        } catch (Exception e) {
            response.setStatus(500);
        }
    }
}
