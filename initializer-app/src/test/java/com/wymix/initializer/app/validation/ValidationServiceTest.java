package com.wymix.initializer.app.validation;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.app.validation.ValidationService.ValidationResult;
import com.wymix.initializer.core.validation.ValidationRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ValidationService} 单元测试：基于真实 validation.yml 规则。
 */
class ValidationServiceTest {

    private static ValidationService service;

    @BeforeAll
    static void setUp() {
        try (InputStream in = getClasspathYaml()) {
            service = new ValidationService(ValidationRules.load(in));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static InputStream getClasspathYaml() {
        return ValidationServiceTest.class.getClassLoader().getResourceAsStream("catalog/validation.yml");
    }

    @Test
    void validate_shouldPassForValidInput() {
        ValidationResult result = service.validate("com.example", "demo-app", "com.example.demo");
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validate_shouldCollectAllErrorsInOrder() {
        ValidationResult result = service.validate("Example", "Demo_App", "demo");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(3);
    }

    @Test
    void validate_shouldTreatBlankAsError() {
        ValidationResult result = service.validate("", "", "");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(3);
    }

    @Test
    void validate_shouldPassWhenOnlyPackageBlankButPartial() {
        ValidationResult result = service.validate("com.example", "demo-app", "bad Package");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(1);
    }

    @Test
    void validateResult_shouldNormalizeNullErrors() {
        ValidationResult result = new ValidationResult(true, null);
        assertThat(result.errors()).isEmpty();
        assertThat(ValidationResult.ok().valid()).isTrue();
    }

    @Test
    void derivePackage_shouldDelegateToPackageNamer() {
        assertThat(service.derivePackage("com.wymix.top", "demo-app")).isEqualTo("com.wymix.top.demoapp");
    }

    @Test
    void derivePackage_shouldThrowForBlankGroup() {
        assertThatThrownBy(() -> service.derivePackage(" ", "demo"))
                .isInstanceOf(BusinessException.class);
    }
}
