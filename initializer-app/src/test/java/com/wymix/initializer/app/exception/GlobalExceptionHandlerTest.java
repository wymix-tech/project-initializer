package com.wymix.initializer.app.exception;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.api.error.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GlobalExceptionHandler} 单元测试。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessException_mapsTo422() {
        ResponseEntity<ErrorResponse> resp = handler.handleBusiness(
                new BusinessException(ErrorCode.INVALID_METADATA, "group 非法"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody().code()).isEqualTo(1001);
    }

    @Test
    void internalErrorCode_mapsTo500() {
        ResponseEntity<ErrorResponse> resp = handler.handleBusiness(
                new BusinessException(ErrorCode.INTERNAL_ERROR, null));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void validationException_mapsTo422WithFieldDetail() {
        MethodArgumentNotValidException e = mock(MethodArgumentNotValidException.class);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "req");
        bindingResult.addError(new FieldError("req", "group", "不能为空"));
        when(e.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ErrorResponse> resp = handler.handleValidation(e);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody().message()).contains("group");
    }

    @Test
    void validationException_withoutFieldErrors_fallsBackToDefaultMessage() {
        MethodArgumentNotValidException e = mock(MethodArgumentNotValidException.class);
        when(e.getBindingResult()).thenReturn(new BeanPropertyBindingResult(new Object(), "req"));

        ResponseEntity<ErrorResponse> resp = handler.handleValidation(e);
        assertThat(resp.getBody().message()).isEqualTo(ErrorCode.INVALID_METADATA.getMessage());
    }

    @Test
    void unknownException_mapsTo500WithoutStackDetail() {
        ResponseEntity<ErrorResponse> resp = handler.handleUnknown(new RuntimeException("boom"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().message()).doesNotContain("boom");
    }
}
