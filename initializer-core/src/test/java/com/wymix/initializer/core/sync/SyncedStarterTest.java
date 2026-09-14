package com.wymix.initializer.core.sync;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import com.wymix.initializer.core.starter.YamlStarterParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SyncedStarter} 单元测试：字段必填校验、描述回退、坐标归一化、
 * 与 starter yml 无损往返（同步层草稿与回滚通道依赖该往返）。
 */
class SyncedStarterTest {

    private static SyncedStarter sample() {
        return new SyncedStarter("xxx-oss-spring-boot-starter", "middleware", "OSS 对象存储",
                "com.company", "xxx-oss", "1.2.0");
    }

    @Test
    void toEntry_shouldNormalizeMaintainerAndBlankLinkages() {
        StarterCatalogEntry entry = sample().toEntry();
        assertThat(entry.id()).isEqualTo("xxx-oss-spring-boot-starter");
        assertThat(entry.maintainer()).isEqualTo(Maintainer.INTERNAL);
        assertThat(entry.linkages().isEmpty()).isTrue();
        assertThat(entry.dependencies()).hasSize(1);
        assertThat(entry.dependencies().get(0).groupId()).isEqualTo("com.company");
        assertThat(entry.dependencies().get(0).artifactId()).isEqualTo("xxx-oss");
        assertThat(entry.dependencies().get(0).version()).isEqualTo("1.2.0");
    }

    @Test
    void blankDescription_shouldFallBackToId() {
        SyncedStarter noDescription = new SyncedStarter("x-starter", "web", "  ",
                "com.company", "x", "1.0.0");
        assertThat(noDescription.description()).isEqualTo("x-starter");
        assertThat(noDescription.toEntry().description()).isEqualTo("x-starter");
    }

    @Test
    void whitespaceFields_shouldBeTrimmedInEntryAndYaml() {
        SyncedStarter padded = new SyncedStarter("  x-starter  ", " web ", " 描述 ",
                " com.company ", " x ", " 1.0.0 ");
        StarterCatalogEntry entry = padded.toEntry();
        assertThat(entry.id()).isEqualTo("  x-starter  ");
        assertThat(entry.category()).isEqualTo("web");
        assertThat(entry.description()).isEqualTo("描述");
        assertThat(entry.dependencies().get(0).groupId()).isEqualTo("com.company");
        assertThat(padded.toYaml()).contains("category: web");
    }

    @Test
    void toYaml_shouldRoundTripThroughStarterParser() {
        SyncedStarter starter = sample();
        String yaml = starter.toYaml();
        StarterCatalogEntry reparsed = YamlStarterParser.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        assertThat(reparsed.id()).isEqualTo(starter.id());
        assertThat(reparsed.category()).isEqualTo(starter.category());
        assertThat(reparsed.maintainer()).isEqualTo(Maintainer.INTERNAL);
        assertThat(reparsed.description()).isEqualTo(starter.description());
        assertThat(reparsed.dependencies()).hasSize(1);
    }

    @Test
    void toYaml_shouldUseBlockFlowStyle() {
        // 块风格是回滚通道可读性要求（同步草稿与发布历史都按此文本存储）
        assertThat(sample().toYaml()).contains("\n").contains("linkages: {}");
    }

    @Test
    void missingId_shouldThrow9005() {
        assertThatThrownBy(() -> new SyncedStarter(null, "web", "d", "g", "a", "1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED));
        assertThatThrownBy(() -> new SyncedStarter("  ", "web", "d", "g", "a", "1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void missingCategory_shouldThrow9005() {
        assertThatThrownBy(() -> new SyncedStarter("x", null, "d", "g", "a", "1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED));
    }

    @Test
    void missingGroupId_shouldThrow9005() {
        assertThatThrownBy(() -> new SyncedStarter("x", "web", "d", null, "a", "1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED));
    }

    @Test
    void missingArtifactId_shouldThrow9005() {
        assertThatThrownBy(() -> new SyncedStarter("x", "web", "d", "g", " ", "1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED));
    }

    @Test
    void missingVersion_shouldThrow9005() {
        assertThatThrownBy(() -> new SyncedStarter("x", "web", "d", "g", "a", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED));
        assertThatThrownBy(() -> new SyncedStarter("x", "web", "d", "g", "a", "  "))
                .isInstanceOf(BusinessException.class);
    }
}
