package com.example.sandbox.web.controller;

import com.example.sandbox.web.model.entity.ExecutionResult;
import com.example.sandbox.web.model.request.ExecuteRequest;
import com.example.sandbox.web.model.request.FileWriteRequest;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.service.SandboxService;
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

    private final SandboxService sandboxService;

    public SandboxController(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

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
}
