package com.wymix.initializer.app.controller;

import com.wymix.initializer.app.exception.GlobalExceptionHandler;
import com.wymix.initializer.app.validation.ValidationService;
import com.wymix.initializer.core.validation.ValidationRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ValidateController} MockMvc 切片测试：真实 service + 真实 validation.yml，验证 HTTP 契约与错误映射。
 */
class ValidateControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("catalog/validation.yml")) {
            ValidationService service = new ValidationService(ValidationRules.load(in));
            mockMvc = MockMvcBuilders.standaloneSetup(new ValidateController(service))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();
        }
    }

    @Test
    void validate_shouldReturnValidResult() throws Exception {
        mockMvc.perform(post("/api/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group\":\"com.example\",\"artifact\":\"demo-app\",\"packageName\":\"com.example.demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void validate_shouldReturnErrorsWith200() throws Exception {
        mockMvc.perform(post("/api/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group\":\"Example\",\"artifact\":\"demo-app\",\"packageName\":\"com.example.demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors.length()").value(1));
    }

    @Test
    void validate_shouldCollectAllErrors() throws Exception {
        mockMvc.perform(post("/api/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group\":\"Example\",\"artifact\":\"Demo\",\"packageName\":\"demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors.length()").value(3));
    }

    @Test
    void validate_shouldReturn400ForMalformedJson() throws Exception {
        // 报文不可读属客户端错误：应映射 400 + 1002，而非 500 + 9001
        mockMvc.perform(post("/api/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void derivePackage_shouldReturnDerivedName() throws Exception {
        mockMvc.perform(get("/api/derive-package")
                        .param("group", "com.example")
                        .param("artifact", "demo-app"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageName").value("com.example.demoapp"));
    }

    @Test
    void derivePackage_shouldReturn422ForBlankGroup() throws Exception {
        mockMvc.perform(get("/api/derive-package")
                        .param("group", "  ")
                        .param("artifact", "demo"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void derivePackage_shouldReturn400WhenParamMissing() throws Exception {
        mockMvc.perform(get("/api/derive-package")
                        .param("artifact", "demo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));
    }
}
