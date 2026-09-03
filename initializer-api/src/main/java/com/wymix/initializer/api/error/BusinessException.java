package com.wymix.initializer.api.error;

/**
 * 平台统一业务异常。
 *
 * <p>所有业务失败（校验失败、版本不兼容、生成失败等）均抛出本异常，
 * 携带 {@link ErrorCode} 与可读的明细信息，由 HTTP 层统一映射为错误响应。
 *
 * <p>线程安全性：实例不共享、无可变状态，线程安全。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * @param errorCode 错误码（必填，禁止 null）
     * @param detail    覆盖默认文案的明细信息，为 null 时使用错误码默认文案
     */
    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail != null ? detail : errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * @param errorCode 错误码（必填，禁止 null）
     * @param detail    明细信息
     * @param cause     根因
     */
    public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail != null ? detail : errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    /** @return 本异常对应的统一错误码，永不为 null */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
