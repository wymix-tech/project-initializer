package com.wymix.initializer.core.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BuildTool} 单元测试：解析归一化、默认值、非法取值 1004。
 */
class BuildToolTest {

    @Test
    void parseShouldNormalizeCaseAndBlank() {
        assertThat(BuildTool.parse("gradle")).isEqualTo(BuildTool.GRADLE);
        assertThat(BuildTool.parse("GRADLE")).isEqualTo(BuildTool.GRADLE);
        assertThat(BuildTool.parse(" Gradle ")).isEqualTo(BuildTool.GRADLE);
        assertThat(BuildTool.parse("maven")).isEqualTo(BuildTool.MAVEN);
        assertThat(BuildTool.parse("MAVEN")).isEqualTo(BuildTool.MAVEN);
    }

    @Test
    void parseNullBlankShouldFallbackToMaven() {
        assertThat(BuildTool.parse(null)).isEqualTo(BuildTool.MAVEN);
        assertThat(BuildTool.parse("")).isEqualTo(BuildTool.MAVEN);
        assertThat(BuildTool.parse("   ")).isEqualTo(BuildTool.MAVEN);
    }

    @Test
    void parseInvalidShouldThrow1004() {
        assertThatThrownBy(() -> BuildTool.parse("ant"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_BUILD_TOOL));
    }

    @Test
    void defaultToolShouldBeMaven() {
        assertThat(BuildTool.defaultTool()).isEqualTo(BuildTool.MAVEN);
    }
}
