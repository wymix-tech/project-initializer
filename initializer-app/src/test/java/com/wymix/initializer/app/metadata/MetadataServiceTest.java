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
    void listStarters_withKeyword_filtersByIdOrLabel() {
        // 测试 classpath 无 starter 清单 yml：data-redis 命中 ID，官方兜底
        assertThat(service.listStarters("3.2.5", "REDIS", null))
                .extracting(MetadataService.StarterDto::id)
                .containsExactly("data-redis");
    }

    @Test
    void listStarters_withCategory_noRegisteredEntries_returnsEmpty() {
        // 分类信息来自 starter 清单（本测试 classpath 未提供清单 yml）→ 无条目可命中
        assertThat(service.listStarters("3.2.5", null, "web")).isEmpty();
    }

    @Test
    void listStarters_unknownCategory_returnsEmptyList() {
        assertThat(service.listStarters("3.2.5", null, "nope")).isEmpty();
    }

    @Test
    void listStarters_noFilters_unregisteredStarterDefaultsOfficial() {
        List<MetadataService.StarterDto> result = service.listStarters("3.2.5");
        assertThat(result).hasSize(4);
        assertThat(result).allSatisfy(s -> {
            assertThat(s.maintainer()).isEqualTo("official");
            assertThat(s.category()).isNull();
        });
        // 向后兼容入口（两参重载）行为一致
        assertThat(service.listStarters("3.2.5")).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(result);
    }

    @Test
    void listStarters_snapshotCatalog_supportsSearchAndCategory() {
        // 快照携带 starter 清单时，搜索/分类基于快照生效（热刷新语义）
        com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder holder =
                new com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder();
        holder.seed(new com.wymix.initializer.app.admin.refresh.CatalogSnapshot(
                snapshotMetadata(), snapshotStarters()));
        MetadataService svc = new MetadataService(holder, snapshotMetadata(),
                com.wymix.initializer.core.starter.StarterCatalog.of(List.of()));
        assertThat(svc.listStarters("3.2.5", null, "middleware"))
                .extracting(MetadataService.StarterDto::id)
                .containsExactly("xxx-redis-spring-boot-starter");
        assertThat(svc.listStarters("3.2.5", "redis", "middleware"))
                .extracting(MetadataService.StarterDto::id)
                .containsExactly("xxx-redis-spring-boot-starter");
        // 关键字命中描述、ID/展示名不含 → 命中（名称+描述 contains 语义）
        assertThat(svc.listStarters("3.2.5", "内部 Redis 封装", null)).hasSize(1);
        // 基线版本透出（验收一般项①）：清单声明了 version 的 starter 透出，未声明的为 null
        assertThat(svc.listStarters("3.2.5", "xxx-redis", null).get(0).version()).isEqualTo("1.2.0");
        assertThat(svc.listStarters("3.2.5", "spring web", null).get(0).version()).isNull();
    }

    /** 快照用元数据目录（含 xxx-redis 展示名）。 */
    private com.wymix.initializer.core.metadata.MetadataCatalog snapshotMetadata() {
        StringBuilder yaml = new StringBuilder()
                .append("javaVersions:\n")
                .append("  - id: \"17\"\n")
                .append("    label: \"Java 17\"\n")
                .append("    deprecated: false\n")
                .append("    recommendedBootVersion: \"3.2.5\"\n")
                .append("bootVersions:\n")
                .append("  - id: \"3.2.5\"\n")
                .append("    label: \"Spring Boot 3.2.5\"\n")
                .append("    official: true\n")
                .append("    supportedJavaVersions: [\"17\"]\n")
                .append("    starters: [\"web\", \"xxx-redis-spring-boot-starter\"]\n")
                .append("starters:\n")
                .append("  web:\n")
                .append("    label: \"Spring Web\"\n")
                .append("    dependency:\n")
                .append("      groupId: org.springframework.boot\n")
                .append("      artifactId: spring-boot-starter-web\n")
                .append("  xxx-redis-spring-boot-starter:\n")
                .append("    label: \"内部 Redis 封装 starter\"\n")
                .append("    dependency:\n")
                .append("      groupId: com.company\n")
                .append("      artifactId: xxx-redis-spring-boot-starter\n");
        return com.wymix.initializer.core.metadata.MetadataCatalog.load(
                new java.io.ByteArrayInputStream(yaml.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** 快照用 starter 清单（web/middleware 分类 + internal 维护方）。 */
    private com.wymix.initializer.core.starter.StarterCatalog snapshotStarters() {
        return com.wymix.initializer.core.starter.StarterCatalog.of(List.of(
                new com.wymix.initializer.core.starter.StarterCatalogEntry("web", "web",
                        com.wymix.initializer.core.starter.Maintainer.OFFICIAL, "Spring MVC + 内嵌 Tomcat",
                        List.of(new com.wymix.initializer.core.starter.DependencyCoord(
                                "org.springframework.boot", "spring-boot-starter-web", null)),
                        new com.wymix.initializer.core.starter.LinkageSpec(null, List.of(), List.of(), List.of())),
                new com.wymix.initializer.core.starter.StarterCatalogEntry("xxx-redis-spring-boot-starter",
                        "middleware",
                        com.wymix.initializer.core.starter.Maintainer.INTERNAL, "内部 Redis 封装 starter",
                        List.of(new com.wymix.initializer.core.starter.DependencyCoord(
                                "com.company", "xxx-redis-spring-boot-starter", "1.2.0")),
                        new com.wymix.initializer.core.starter.LinkageSpec(null, List.of(), List.of(), List.of()))));
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
