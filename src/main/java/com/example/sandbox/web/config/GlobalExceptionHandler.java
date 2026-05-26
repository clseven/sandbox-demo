package com.example.sandbox.web.config;

import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.exception.SkillNotFoundException;
import com.example.sandbox.web.exception.UnauthorizedException;
import com.example.sandbox.web.model.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 *
 * @author example
 * @date 2026/05/14
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleUnauthorized(UnauthorizedException e) {
        return ApiResponse.error(401, e.getMessage());
    }

    @ExceptionHandler(SessionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleSessionNotFound(SessionNotFoundException e) {
        log.warn("Session not found: {}", e.getSessionId());
        return ApiResponse.notFound(e.getMessage());
    }

    @ExceptionHandler(SkillNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleSkillNotFound(SkillNotFoundException e) {
        log.warn("Skill not found: {}", e.getSkillId());
        return ApiResponse.notFound(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneric(Exception e) {
        log.error("Unexpected error", e);
        return ApiResponse.error(500, "Internal server error");
    }
}