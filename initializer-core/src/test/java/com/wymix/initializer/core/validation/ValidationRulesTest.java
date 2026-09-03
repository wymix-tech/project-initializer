package com.wymix.initializer.core.validation;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ValidationRules} 单元测试：装载期校验 + 三字段校验行为。
 */
class ValidationRulesTest {

    private static final String FULL_YAML = """
            rules:
              group:
                pattern: "^[a-z][a-z0-9_]*(\\\\.[a-z][a-z0-9_]*)+$"
                tooShortMessage: "groupId 至少需要两段，如 com.example"
                defaultMessage: "groupId 不符合 Maven 规范"
              artifact:
                pattern: "^[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*$"
                tooShortMessage: "artifactId 不能为空"
                defaultMessage: "artifactId 不符合 Maven 规范"
              package:
                pattern: "^[a-z][a-z0-9_]*(\\\\.[a-z][a-z0-9_]*)+$"
                tooShortMessage: "package 至少需要两段"
                defaultMessage: "package 不符合 Java 规范"
            """;

    private static final String MINIMAL_YAML = """
            rules:
              group:
                pattern: "^[a-z]+$"
              artifact:
                pattern: "^[a-z]+$"
              package:
                pattern: "^[a-z]+$"
            """;

    private ValidationRules load(String yaml) {
        return ValidationRules.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void load_shouldBuildRulesFromFullYaml() {
        ValidationRules rules = load(FULL_YAML);
        assertThat(rules.validateGroup("com.example")).isNull();
        assertThat(rules.validateArtifact("demo-app")).isNull();
        assertThat(rules.validatePackage("com.example.demo")).isNull();
    }

    @Test
    void load_shouldUseDefaultMessagesWhenMessagesMissing() {
        ValidationRules rules = load(MINIMAL_YAML);
        assertThat(rules.validateGroup("com.example"))
                .isEqualTo("group 不符合命名规范");
        assertThat(rules.validateGroup(null)).isEqualTo("group 不能为空");
    }

    @Test
    void load_shouldFailWhenYamlEmpty() {
        assertThatThrownBy(() -> load(""))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void load_shouldFailWhenRulesMissing() {
        assertThatThrownBy(() -> load("foo: bar"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rules");
    }

    @Test
    void load_shouldFailWhenRequiredFieldMissing() {
        String yaml = """
                rules:
                  group:
                    pattern: "^[a-z]+$"
                  artifact:
                    pattern: "^[a-z]+$"
                """;
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rules.package");
    }

    @Test
    void load_shouldFailWhenPatternMissing() {
        String yaml = """
                rules:
                  group:
                    pattern: "^[a-z]+$"
                  artifact: {}
                  package:
                    pattern: "^[a-z]+$"
                """;
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rules.artifact.pattern");
    }

    @Test
    void load_shouldFailWhenPatternInvalidRegex() {
        String yaml = """
                rules:
                  group:
                    pattern: "^[a-z("
                  artifact:
                    pattern: "^[a-z]+$"
                  package:
                    pattern: "^[a-z]+$"
                """;
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正则非法");
    }

    @Test
    void validate_shouldThrowForUnknownField() {
        ValidationRules rules = load(MINIMAL_YAML);
        assertThatThrownBy(() -> rules.validate("unknown", "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未配置的校验字段");
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.example", "com.wymix.top", "com.example.app_core"})
    void validateGroup_shouldPassForValidGroup(String value) {
        assertThat(load(FULL_YAML).validateGroup(value)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"example", "Com.example", "com.example.", "com..example", "com.ex ample", "com.示例"})
    void validateGroup_shouldFailForInvalidGroup(String value) {
        assertThat(load(FULL_YAML).validateGroup(value)).isNotNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void validateGroup_shouldFailForBlank(String value) {
        assertThat(load(FULL_YAML).validateGroup(value))
                .isEqualTo("groupId 至少需要两段，如 com.example");
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo", "demo-app", "demo-app-core", "demo2"})
    void validateArtifact_shouldPassForValidArtifact(String value) {
        assertThat(load(FULL_YAML).validateArtifact(value)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Demo", "demo_app", "-demo", "demo-", "demo--app", "demo app"})
    void validateArtifact_shouldFailForInvalidArtifact(String value) {
        assertThat(load(FULL_YAML).validateArtifact(value)).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({
            "com.example.demo, true",
            "com.example, true",
            "com.example.demo_app.core, true",
            "no..segments, false",
            "com.Example.demo, false",
            "com, false",
            "com..example, false"
    })
    void validatePackage_shouldMatchPattern(String value, boolean valid) {
        String error = load(FULL_YAML).validatePackage(value);
        assertThat(error == null).isEqualTo(valid);
    }

    @Test
    void validatePackage_shouldFailForBlank() {
        assertThat(load(FULL_YAML).validatePackage("")).isEqualTo("package 至少需要两段");
    }

    @Test
    void validateArtifact_shouldFailForBlank() {
        assertThat(load(FULL_YAML).validateArtifact("")).isEqualTo("artifactId 不能为空");
    }
}
