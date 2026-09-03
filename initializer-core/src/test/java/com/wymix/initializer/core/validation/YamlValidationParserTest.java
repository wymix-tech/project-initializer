package com.wymix.initializer.core.validation;

import com.wymix.initializer.api.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link YamlValidationParser} 单元测试：结构映射与错误分支。
 */
class YamlValidationParserTest {

    private YamlValidationParser.ParsedRules parse(String yaml) {
        return YamlValidationParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parse_shouldMapRules() {
        YamlValidationParser.ParsedRules parsed = parse("""
                rules:
                  group:
                    pattern: "^g$"
                  artifact:
                    pattern: "^a$"
                  package:
                    pattern: "^p$"
                """);
        assertThat(parsed.rules()).hasSize(3);
        assertThat(parsed.rules().get(0).field()).isEqualTo("group");
        assertThat(parsed.rules().get(0).pattern()).isEqualTo("^g$");
    }

    @Test
    void parse_shouldThrowOnBrokenYaml() {
        assertThatThrownBy(() -> parse("rules: [unclosed"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    void parse_shouldThrowOnEmptyContent() {
        assertThatThrownBy(() -> parse(""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内容为空");
    }

    @Test
    void parse_shouldThrowWhenRulesNotMap() {
        assertThatThrownBy(() -> parse("rules: 42"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rules");
    }

    @Test
    void parse_shouldThrowWhenFieldNotMap() {
        assertThatThrownBy(() -> parse("""
                rules:
                  group: 42
                  artifact:
                    pattern: "a"
                  package:
                    pattern: "p"
                """))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rules.group");
    }

    @Test
    void parse_shouldThrowWhenPatternBlank() {
        assertThatThrownBy(() -> parse("""
                rules:
                  group:
                    pattern: "  "
                  artifact:
                    pattern: "a"
                  package:
                    pattern: "p"
                """))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rules.group.pattern");
    }

    @Test
    void parse_shouldUseFallbackMessages() {
        YamlValidationParser.ParsedRules parsed = parse("""
                rules:
                  group:
                    pattern: "^g$"
                    tooShortMessage: ""
                    defaultMessage: ""
                  artifact:
                    pattern: "^a$"
                  package:
                    pattern: "^p$"
                """);
        assertThat(parsed.rules().get(0).emptyMessage()).isEqualTo("group 不能为空");
        assertThat(parsed.rules().get(0).defaultMessage()).isEqualTo("group 不符合命名规范");
    }
}
