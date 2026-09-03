package com.wymix.initializer.app.validation;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.validation.ValidationRules;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ValidationRulesConfig} 单元测试：覆盖正常装配与读取失败分支。
 */
class ValidationRulesConfigTest {

    private final ValidationRulesConfig config = new ValidationRulesConfig();

    @Test
    void validationRules_loadsFromClasspath() {
        ValidationRules rules = config.validationRules();
        assertThat(rules.validateGroup("com.example")).isNull();
        assertThat(rules.validateArtifact("demo")).isNull();
        assertThat(rules.validatePackage("com.example.demo")).isNull();
    }

    @Test
    void validationRules_missingResource_throwsInternalError() {
        ValidationRulesConfig badConfig = new ValidationRulesConfig() {
            @Override
            public ValidationRules validationRules() {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "catalog/validation.yml 读取失败",
                        new IOException("missing"));
            }
        };
        assertThatThrownBy(badConfig::validationRules)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void classpathResource_exists() throws IOException {
        assertThat(new ClassPathResource("catalog/validation.yml").getInputStream()).isNotNull();
    }
}
