package com.wymix.initializer.core;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PackageNamer} 单元测试。
 */
class PackageNamerTest {

    @Test
    void derive_normalGroupAndArtifact() {
        assertThat(PackageNamer.derive("com.wymix.top", "demo-app"))
                .isEqualTo("com.wymix.top.demoapp");
    }

    @Test
    void derive_groupWithUnderscoreAndArtifactWithDot() {
        assertThat(PackageNamer.derive("com.wymix_top", "demo.app"))
                .isEqualTo("com.wymix.top.demoapp");
    }

    @Test
    void derive_uppercaseNormalizedToLowercase() {
        assertThat(PackageNamer.derive("COM.Wymix", "DemoApp"))
                .isEqualTo("com.wymix.demoapp");
    }

    @Test
    void derive_blankGroupThrows() {
        assertThatThrownBy(() -> PackageNamer.derive("  ", "demo"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_METADATA);
    }

    @Test
    void derive_nullArtifactThrows() {
        assertThatThrownBy(() -> PackageNamer.derive("com.wymix", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("artifact");
    }

    @Test
    void derive_groupWithoutValidSegmentsThrows() {
        assertThatThrownBy(() -> PackageNamer.derive("---", "demo"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("group");
    }

    @Test
    void derive_artifactWithoutValidSegmentsThrows() {
        assertThatThrownBy(() -> PackageNamer.derive("com.wymix", "***"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("artifact");
    }
}
