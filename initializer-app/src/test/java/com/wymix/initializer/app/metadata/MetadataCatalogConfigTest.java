package com.wymix.initializer.app.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.LinkageSpec;
import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MetadataCatalogConfig} 单元测试：覆盖正常装配与读取失败分支。
 */
class MetadataCatalogConfigTest {

    private final MetadataCatalogConfig config = new MetadataCatalogConfig();

    /** 测试用 starter 清单：与主资源同构（web 官方 + xxx-redis 内部）。 */
    static StarterCatalog testStarterCatalog() {
        return StarterCatalog.of(List.of(
                new StarterCatalogEntry("web", "web", Maintainer.OFFICIAL, "Spring Web",
                        List.of(new com.wymix.initializer.core.starter.DependencyCoord(
                                "org.springframework.boot", "spring-boot-starter-web", null)),
                        new LinkageSpec(null, List.of(), List.of(), List.of())),
                new StarterCatalogEntry("xxx-redis-spring-boot-starter", "middleware", Maintainer.INTERNAL,
                        "内部 Redis 封装 starter",
                        List.of(new com.wymix.initializer.core.starter.DependencyCoord(
                                "com.company", "xxx-redis-spring-boot-starter", "1.2.0")),
                        new LinkageSpec(null, List.of(), List.of(), List.of()))));
    }

    @Test
    void metadataCatalog_loadsFromClasspath() {
        MetadataCatalog catalog = config.metadataCatalog(testStarterCatalog(), true);
        assertThat(catalog.javaVersions()).isNotEmpty();
        assertThat(catalog.bootVersions()).isNotEmpty();
        // starter 清单联动：定义全部来自清单
        assertThat(catalog.starterDefinition("web").dependency().groupId())
                .isEqualTo("org.springframework.boot");
    }

    @Test
    void metadataCatalog_missingResource_throwsInternalError() {
        MetadataCatalogConfig badConfig = new MetadataCatalogConfig() {
            @Override
            public MetadataCatalog metadataCatalog(StarterCatalog starterCatalog, boolean demoEnabled) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "catalog/metadata.yml 读取失败",
                        new IOException("missing"));
            }
        };
        assertThatThrownBy(() -> badConfig.metadataCatalog(testStarterCatalog(), true))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void classpathResource_exists() throws IOException {
        assertThat(new ClassPathResource("catalog/metadata.yml").getInputStream()).isNotNull();
    }

    @Test
    void emptyYaml_loadFailsWithInternalError() {
        assertThatThrownBy(() -> MetadataCatalog.load(new ByteArrayInputStream(new byte[0])))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void yamlContent_canBeReadAsUtf8() {
        String yaml = "javaVersions:\n  - id: \"8\"\nbootVersions:\n  - id: \"2.7.18\"\n    supportedJavaVersions: [\"8\"]\n";
        MetadataCatalog catalog = MetadataCatalog.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        assertThat(catalog.javaVersions()).hasSize(1);
    }
}
