package com.wymix.initializer.core.metadata;

import com.wymix.initializer.api.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link YamlMetadataParser} 单元测试：覆盖默认值、official 缺省、空集合等分支。
 */
class YamlMetadataParserTest {

    private YamlMetadataParser.ParsedMetadata parse(String yaml) {
        return YamlMetadataParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void officialDefaultsTrueWhenAbsent() {
        YamlMetadataParser.ParsedMetadata p = parse("""
                javaVersions:
                  - id: "8"
                bootVersions:
                  - id: "2.7.18"
                    supportedJavaVersions: ["8"]
                """);
        assertThat(p.bootVersions().get(0).official()).isTrue();
        assertThat(p.bootVersions().get(0).label()).isEqualTo("Spring Boot 2.7.18");
        assertThat(p.javaVersions().get(0).label()).isEqualTo("Java 8");
        assertThat(p.javaVersions().get(0).recommendedBootVersion()).isNull();
    }

    @Test
    void officialFalseAndDeprecatedParsed() {
        YamlMetadataParser.ParsedMetadata p = parse("""
                javaVersions:
                  - id: "8"
                    deprecated: true
                bootVersions:
                  - id: "2.7.18"
                    official: false
                    supportedJavaVersions: ["8"]
                """);
        assertThat(p.javaVersions().get(0).deprecated()).isTrue();
        assertThat(p.bootVersions().get(0).official()).isFalse();
    }

    @Test
    void nullSupportedJavaAndStartersDefaultToEmpty() {
        YamlMetadataParser.ParsedMetadata p = parse("""
                javaVersions:
                  - id: "8"
                bootVersions:
                  - id: "2.7.18"
                    supportedJavaVersions: ["8"]
                    starters: []
                """);
        assertThat(p.bootVersions().get(0).starters()).isEmpty();
    }

    @Test
    void blankStringFieldTreatedAsMissing() {
        assertThatThrownBy(() -> parse("""
                javaVersions:
                  - id: " "
                bootVersions:
                  - id: "2.7.18"
                    supportedJavaVersions: [" "]
                """)).isInstanceOf(BusinessException.class);
    }

    @Test
    void nonListStartersThrows() {
        assertThatThrownBy(() -> parse("""
                javaVersions:
                  - id: "8"
                bootVersions:
                  - id: "2.7.18"
                    supportedJavaVersions: ["8"]
                    starters: "web"
                """)).isInstanceOf(BusinessException.class);
    }

    @Test
    void nullInputStreamThrows() {
        assertThatThrownBy(() -> YamlMetadataParser.parse((java.io.InputStream) null))
                .isInstanceOf(BusinessException.class);
    }
}
