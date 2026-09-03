package com.wymix.initializer.api.error;

/**
 * 统一错误响应体，HTTP 层全局异常处理器输出。
 *
 * <p>结构：{@code { code, message, timestamp }}，前端依据 code 做差异化提示。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record ErrorResponse(int code, String message, long timestamp) {

    /**
     * @param errorCode 错误码枚举
     * @param message   明细文案
     * @return 错误响应体实例
     */
    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.getCode(), message, System.currentTimeMillis());
    }
}
