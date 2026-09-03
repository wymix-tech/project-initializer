package com.wymix.initializer.app.exception;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.api.error.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：把异常统一映射为 {@link ErrorResponse}。
 *
 * <p>映射关系：
 * <ul>
 *   <li>{@link BusinessException} —— 9xxx 映射 500，其余映射 422</li>
 *   <li>{@link MethodArgumentNotValidException} —— 422</li>
 *   <li>未知异常 —— 500 + INTERNAL_ERROR</li>
 * </ul>
 *
 * <p>线程安全性：无状态单例，线程安全。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常统一处理。
     *
     * @param e 业务异常
     * @return 4xx/5xx 错误响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, msg={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity.status(mapStatus(e.getErrorCode()))
                .body(ErrorResponse.of(e.getErrorCode(), e.getMessage()));
    }

    /**
     * Jakarta Validation 校验失败处理。
     *
     * @param e 校验异常
     * @return 422 错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse(ErrorCode.INVALID_METADATA.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of(ErrorCode.INVALID_METADATA, detail));
    }

    /**
     * 兜底异常处理，避免堆栈泄露给前端。
     *
     * @param e 未知异常
     * @return 500 错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        log.error("系统内部错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage()));
    }

    /**
     * 错误码 → HTTP 状态映射：9xxx 系统错误映射 500，其余（校验/兼容性/生成）映射 422。
     *
     * @param code 错误码
     * @return 对应 HTTP 状态
     */
    static HttpStatus mapStatus(ErrorCode code) {
        if (code.getCode() >= 9000) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
