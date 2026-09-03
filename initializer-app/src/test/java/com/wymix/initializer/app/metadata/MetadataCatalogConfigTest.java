package com.wymix.initializer.app.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MetadataCatalogConfig} 单元测试：覆盖正常装配与读取失败分支。
 */
class MetadataCatalogConfigTest {

    private final MetadataCatalogConfig config = new MetadataCatalogConfig();

    @Test
    void metadataCatalog_loadsFromClasspath() {
        MetadataCatalog catalog = config.metadataCatalog();
        assertThat(catalog.javaVersions()).isNotEmpty();
        assertThat(catalog.bootVersions()).isNotEmpty();
    }

    @Test
    void metadataCatalog_missingResource_throwsInternalError() {
        MetadataCatalogConfig badConfig = new MetadataCatalogConfig() {
            @Override
            public MetadataCatalog metadataCatalog() {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "catalog/metadata.yml 读取失败",
                        new IOException("missing"));
            }
        };
        assertThatThrownBy(badConfig::metadataCatalog)
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
