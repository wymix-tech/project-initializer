package com.wymix.initializer.app.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.validation.ValidationService;
import com.wymix.initializer.core.generator.GenerateRequest;
import com.wymix.initializer.core.generator.ProjectGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GeneratorService 单元测试：前置校验失败 1001；成功透传生成引擎产物。
 */
class GeneratorServiceTest {

    private GeneratorService service;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/catalog/metadata.yml")) {
            var catalog = com.wymix.initializer.core.metadata.MetadataCatalog.load(in);
            var starterCatalog = com.wymix.initializer.core.starter.StarterCatalog.of(java.util.List.of());
            ProjectGenerator generator = new ProjectGenerator(catalog,
                    new com.wymix.initializer.core.generator.TemplateRenderer(), starterCatalog);
            service = new GeneratorService(generator, new ValidationService(
                    com.wymix.initializer.core.validation.ValidationRules.load(
                            getClass().getResourceAsStream("/catalog/validation.yml"))));
        }
    }

    private static GenerateRequest request() {
        return new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "17", "3.2.5", List.of("web"));
    }

    @Test
    void validRequestShouldStreamZip() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.generate(request(), out);
        assertThat(out.toByteArray()).isNotEmpty();
        assertThat(service.zipFileName(request())).isEqualTo("demo-app.zip");
    }

    @Test
    void invalidGroupShouldThrow1001() {
        GenerateRequest bad = new GenerateRequest("Bad", "demo-app", "com.example.demo",
                "17", "3.2.5", List.of("web"));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertThatThrownBy(() -> service.generate(bad, out))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_METADATA));
        // 校验失败时输出流不应被写入（无半成品）
        assertThat(out.size()).isZero();
    }
}
