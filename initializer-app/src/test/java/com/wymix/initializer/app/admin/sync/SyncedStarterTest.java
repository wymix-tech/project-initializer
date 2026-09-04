package com.wymix.initializer.app.admin.sync;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.sync.SyncedStarter;
import com.wymix.initializer.core.starter.YamlStarterParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SyncedStarter} 单元测试（M3.3）：字段校验、清单条目转换与 yml 往返（回滚通道无损）。
 */
class SyncedStarterTest {

    @Test
    void constructor_missingId_throws4005() {
        assertThatThrownBy(() -> new SyncedStarter(null, "web", "d", "g", "a", "1.0"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED));
    }

    @Test
    void constructor_missingCategoryGroupIdArtifactIdVersion_throws4005() {
        assertThatThrownBy(() -> new SyncedStarter("x", " ", "d", "g", "a", "1.0"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new SyncedStarter("x", "web", "d", " ", "a", "1.0"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new SyncedStarter("x", "web", "d", "g", " ", "1.0"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new SyncedStarter("x", "web", "d", "g", "a", " "))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void blankDescription_fallsBackToId() {
        SyncedStarter starter = new SyncedStarter("xxx-oss", "middleware", null, "g", "a", "1.0");
        assertThat(starter.description()).isEqualTo("xxx-oss");
    }

    @Test
    void toEntry_mapsToInternalEntryWithEmptyLinkages() {
        SyncedStarter starter = new SyncedStarter("xxx-oss", "middleware", "内部 OSS",
                "com.company", "xxx-oss", "1.0.0");
        var entry = starter.toEntry();
        assertThat(entry.id()).isEqualTo("xxx-oss");
        assertThat(entry.maintainer()).isEqualTo(Maintainer.INTERNAL);
        assertThat(entry.dependencies()).hasSize(1);
        assertThat(entry.dependencies().get(0).version()).isEqualTo("1.0.0");
        assertThat(entry.linkages().isEmpty()).isTrue();
    }

    @Test
    void toYaml_roundTripsThroughStarterParser() {
        // 回滚通道语义：toYaml 产物必须能被 YamlStarterParser 无损重载
        SyncedStarter starter = new SyncedStarter("xxx-oss", "middleware", "内部 OSS",
                "com.company", "xxx-oss-starter", "1.0.0");
        var reparsed = YamlStarterParser.parse(
                new ByteArrayInputStream(starter.toYaml().getBytes(StandardCharsets.UTF_8)));
        assertThat(reparsed.id()).isEqualTo("xxx-oss");
        assertThat(reparsed.maintainer()).isEqualTo(Maintainer.INTERNAL);
        assertThat(reparsed.dependencies().get(0).artifactId()).isEqualTo("xxx-oss-starter");
        assertThat(reparsed.dependencies().get(0).version()).isEqualTo("1.0.0");
    }
}
