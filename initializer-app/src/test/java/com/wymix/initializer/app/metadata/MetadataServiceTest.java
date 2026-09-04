package com.wymix.initializer.app.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MetadataService} 单元测试：基于 classpath 真实 metadata.yml。
 */
class MetadataServiceTest {

    private MetadataService service;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("catalog/metadata.yml")) {
            service = new MetadataService(
                    new com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder(),
                    com.wymix.initializer.core.metadata.MetadataCatalog.load(in),
                    com.wymix.initializer.core.starter.StarterCatalog.of(List.of()));
        }
    }

    @Test
    void listJavaVersions_returnsConfiguredVersions() {
        List<MetadataService.JavaVersionDto> versions = service.listJavaVersions();
        assertThat(versions).extracting(MetadataService.JavaVersionDto::id)
                .containsExactly("8", "11", "17", "21");
        assertThat(versions).allSatisfy(v -> {
            assertThat(v.label()).isNotBlank();
            assertThat(v.recommendedBootVersion()).isNotNull();
        });
    }

    @Test
    void listCompatibleBootVersions_java8_only2x() {
        List<MetadataService.BootVersionDto> result = service.listCompatibleBootVersions("8");
        assertThat(result).extracting(MetadataService.BootVersionDto::id).containsExactly("2.7.18");
        assertThat(result).allSatisfy(v -> assertThat(v.official()).isTrue());
    }

    @Test
    void listCompatibleBootVersions_java17_only3x() {
        List<MetadataService.BootVersionDto> result = service.listCompatibleBootVersions("17");
        assertThat(result).extracting(MetadataService.BootVersionDto::id).containsExactly("3.2.5");
    }

    @Test
    void listCompatibleBootVersions_unknownJava_throws2002() {
        assertThatThrownBy(() -> service.listCompatibleBootVersions("99"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
    }

    @Test
    void listStarters_boot325_containsWeb() {
        List<MetadataService.StarterDto> starters = service.listStarters("3.2.5");
        assertThat(starters).extracting(MetadataService.StarterDto::id).contains("web");
        assertThat(starters).allSatisfy(s -> assertThat(s.label()).isNotBlank());
    }

    @Test
    void listStarters_unknownBoot_throws2002() {
        assertThatThrownBy(() -> service.listStarters("9.9.9"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
    }

    @Test
    void listCompatibleBootVersions_emptyResult_throws2001() {
        // 构造 Java 版本存在但无兼容 Boot 的目录
        String yaml = """
                javaVersions:
                  - id: "25"
                  - id: "17"
                bootVersions:
                  - id: "3.2.5"
                    supportedJavaVersions: ["17"]
                """;
        MetadataService svc = new MetadataService(
                new com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder(),
                com.wymix.initializer.core.metadata.MetadataCatalog.load(
                        new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                com.wymix.initializer.core.starter.StarterCatalog.of(List.of()));
        assertThatThrownBy(() -> svc.listCompatibleBootVersions("25"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INCOMPATIBLE_VERSION));
    }

    @Test
    void starterLabel_comesFromConfiguredStartersSection() {
        // 展示名来自 metadata.yml starters 节（配置驱动），而非代码硬编码
        assertThat(service.listStarters("3.2.5"))
                .extracting(MetadataService.StarterDto::label)
                .contains("Spring Web", "Spring Data JPA");
    }

    @Test
    void starterLabel_danglingStarterReferenceFailsAtLoad() {
        // bootVersions.starters 引用未登记的 starter → 装载即失败
        String yaml = """
                javaVersions:
                  - id: "8"
                bootVersions:
                  - id: "2.7.18"
                    supportedJavaVersions: ["8"]
                    starters: ["custom-starter"]
                """;
        assertThatThrownBy(() -> com.wymix.initializer.core.metadata.MetadataCatalog.load(
                        new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isInstanceOf(BusinessException.class);
    }
}
