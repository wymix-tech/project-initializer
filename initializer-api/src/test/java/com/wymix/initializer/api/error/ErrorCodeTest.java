package com.wymix.initializer.api.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ErrorCode} 与 {@link BusinessException} 单元测试。
 */
class ErrorCodeTest {

    @Test
    void errorCode_hasUniqueCodes() {
        long distinct = java.util.Arrays.stream(ErrorCode.values())
                .mapToInt(ErrorCode::getCode).distinct().count();
        assertThat(distinct).isEqualTo(ErrorCode.values().length);
    }

    @Test
    void businessException_usesDetailWhenPresent() {
        BusinessException e = new BusinessException(ErrorCode.INVALID_METADATA, "group 非法");
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_METADATA);
        assertThat(e.getMessage()).isEqualTo("group 非法");
    }

    @Test
    void businessException_fallsBackToDefaultMessage() {
        BusinessException e = new BusinessException(ErrorCode.INTERNAL_ERROR, null);
        assertThat(e.getMessage()).isEqualTo(ErrorCode.INTERNAL_ERROR.getMessage());
    }

    @Test
    void businessException_withCauseKeepsRootCause() {
        RuntimeException cause = new RuntimeException("boom");
        BusinessException e = new BusinessException(ErrorCode.ZIP_PACK_FAILED, "打包失败", cause);
        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    void errorResponse_carriesCodeAndMessage() {
        ErrorResponse resp = ErrorResponse.of(ErrorCode.INCOMPATIBLE_VERSION, "Java 8 + Boot 3.x");
        assertThat(resp.code()).isEqualTo(2001);
        assertThat(resp.message()).isEqualTo("Java 8 + Boot 3.x");
        assertThat(resp.timestamp()).isPositive();
    }
}
