package com.example.springaidemo.exception;

import com.example.springaidemo.dto.ApiResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理请求体字段校验失败异常。ss
     *
     * @param ex 字段校验异常
     * @return 标准错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseDto> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        log.warn("request validation failed", ex);
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining(", "));
        return buildError(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 处理参数约束校验失败异常。
     *
     * @param ex 参数约束异常
     * @return 标准错误响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponseDto> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("constraint validation failed", ex);
        String message = ex.getConstraintViolations().stream()
                .map(this::formatConstraintViolation)
                .collect(Collectors.joining(", "));
        return buildError(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 处理客户端请求参数错误。
     *
     * @param ex 请求参数异常
     * @return 标准错误响应
     */
    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponseDto> handleBadRequest(Exception ex) {
        log.warn("bad request", ex);
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * 处理静态资源不存在异常，避免 favicon 请求被误判为服务故障。
     *
     * @param ex 静态资源不存在异常
     * @return 标准错误响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponseDto> handleNoResourceFound(NoResourceFoundException ex) {
        if (ex.getResourcePath() != null && ex.getResourcePath().endsWith("favicon.ico")) {
            log.debug("favicon not found");
        } else {
            log.warn("static resource not found: {}", ex.getResourcePath());
        }
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * 处理资源不存在异常。
     *
     * @param ex 资源不存在异常
     * @return 标准错误响应
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponseDto> handleNotFound(NoSuchElementException ex) {
        log.warn("resource not found", ex);
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * 处理未单独声明的服务端异常。
     *
     * @param ex 未知异常
     * @return 标准错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseDto> handleUnexpected(Exception ex) {
        log.error("unexpected server error", ex);
        String message = ex.getMessage() != null ? ex.getMessage() : "Internal Server Error";
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    /**
     * 构造统一错误响应对象。
     *
     * @param status HTTP 状态码
     * @param message 错误消息
     * @return 标准错误响应
     */
    private ResponseEntity<ApiResponseDto> buildError(HttpStatus status, String message) {
        ApiResponseDto body = new ApiResponseDto(message, "ERROR", UUID.randomUUID().toString());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 格式化字段校验错误。
     *
     * @param fieldError 字段错误
     * @return 可读错误文本
     */
    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }

    /**
     * 格式化约束校验错误。
     *
     * @param violation 约束错误
     * @return 可读错误文本
     */
    private String formatConstraintViolation(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }
}
