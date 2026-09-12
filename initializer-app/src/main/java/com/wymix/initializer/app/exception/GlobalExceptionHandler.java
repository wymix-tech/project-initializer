package com.wymix.initializer.app.exception;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.api.error.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器：把异常统一映射为 {@link ErrorResponse}。
 *
 * <p>映射关系：
 * <ul>
 *   <li>{@link BusinessException} —— 9xxx 映射 500，其余映射 422</li>
 *   <li>{@link MethodArgumentNotValidException} —— 422</li>
 *   <li>请求参数/报文类异常（缺参、类型不匹配、报文不可读、约束违反）—— 400/422 + INVALID_REQUEST</li>
 *   <li>未知异常 —— 500 + INTERNAL_ERROR</li>
 * </ul>
 *
 * <p>请求参数类异常必须显式声明：否则会落到 {@link #handleUnknown(Exception)} 被映射为
 * 500 INTERNAL_ERROR，把客户端错误误报成服务端故障（例如路径变量传非数字）。
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
     * 缺少必需请求参数处理。
     *
     * @param e 缺参异常
     * @return 400 错误响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST, "缺少必需参数: " + e.getParameterName()));
    }

    /**
     * 缺少 multipart 分段处理（如导入归档未携带 {@code file} 字段）。
     *
     * <p>必须显式声明：否则会落到兜底处理被映射为 500 INTERNAL_ERROR，
     * 把客户端漏传文件误报成服务端故障。
     *
     * @param e 缺分段异常
     * @return 400 错误响应
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST, "缺少必需的文件字段: " + e.getRequestPartName()));
    }

    /**
     * 请求报文不可读处理（JSON 语法错误、字段类型与 DTO 不匹配等）。
     *
     * @param e 报文读取异常
     * @return 400 错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求报文不可读: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST, "请求报文格式非法，无法解析"));
    }

    /**
     * 路径变量/请求参数类型不匹配处理（如 rollback 路径传入非数字 releaseId）。
     *
     * @param e 类型不匹配异常
     * @return 400 错误响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST,
                        "参数 " + e.getName() + " 取值非法: " + e.getValue()));
    }

    /**
     * {@code @Validated} 参数约束违反处理。
     *
     * @param e 约束违反异常
     * @return 422 错误响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST, e.getMessage()));
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
