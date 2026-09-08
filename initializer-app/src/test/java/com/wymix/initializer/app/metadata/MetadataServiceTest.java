package com.wymix.initializer.app.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.DependencyCoord;
import com.wymix.initializer.core.starter.LinkageSpec;
import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MetadataService} 单元测试：基于 classpath 真实 metadata.yml + 测试 starter 清单。
 *
 * <p>starter 元信息（展示名/坐标/必选/联动）全部由 starter 清单提供，服务层读取的是
 * 「metadata 与清单联动合并后」的目录。
 */
class MetadataServiceTest {

    private MetadataService service;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("catalog/metadata.yml")) {
            StarterCatalog starterCatalog = testStarterCatalog();
            MetadataCatalog catalog = MetadataCatalog.load(in).merge(starterCatalog);
            service = new MetadataService(
                    new com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder(),
                    catalog, starterCatalog);
        }
    }

    /** 测试用 starter 清单（与内置 metadata 的 boot starters 对齐）。 */
    private static StarterCatalog testStarterCatalog() {
        return StarterCatalog.of(List.of(
                entry("web", "web", "Spring MVC + 内嵌 Tomcat"),
                entry("data-jpa", "data", "Spring Data JPA 持久层"),
                entry("data-redis", "data", "Spring Data Redis 缓存"),
                entry("actuator", "tool", "Spring Boot Actuator 生产可观测端点")));
    }

    private static StarterCatalogEntry entry(String id, String category, String description) {
        return new StarterCatalogEntry(id, category, Maintainer.OFFICIAL, description,
                List.of(new DependencyCoord("org.springframework.boot", "spring-boot-starter-" + id, null)),
                new LinkageSpec(null, List.of(), List.of(), List.of()));
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
                MetadataCatalog.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                        .merge(StarterCatalog.of(List.of())),
                StarterCatalog.of(List.of()));
        assertThatThrownBy(() -> svc.listCompatibleBootVersions("25"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INCOMPATIBLE_VERSION));
    }

    @Test
    void listStarters_withKeyword_filtersByIdOrLabel() {
        assertThat(service.listStarters("3.2.5", "REDIS", null))
                .extracting(MetadataService.StarterDto::id)
                .containsExactly("data-redis");
    }

    @Test
    void listStarters_withCategory_filtersByCatalogCategory() {
        // 分类来自 starter 清单
        assertThat(service.listStarters("3.2.5", null, "data"))
                .extracting(MetadataService.StarterDto::id)
                .containsExactlyInAnyOrder("data-jpa", "data-redis");
    }

    @Test
    void listStarters_unknownCategory_returnsEmptyList() {
        assertThat(service.listStarters("3.2.5", null, "nope")).isEmpty();
    }

    @Test
    void listStarters_noFilters_returnsAllCatalogStarters() {
        List<MetadataService.StarterDto> result = service.listStarters("3.2.5");
        assertThat(result).hasSize(4);
        assertThat(result).allSatisfy(s -> {
            assertThat(s.maintainer()).isEqualTo("official");
            assertThat(s.category()).isNotNull();
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
        MetadataService svc = new MetadataService(holder, snapshotMetadata(), snapshotStarters());
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
        assertThat(svc.listStarters("3.2.5", "spring mvc", null).get(0).version()).isNull();
    }

    /** 快照用元数据目录：starter 元信息来自清单，经 merge 联动。 */
    private com.wymix.initializer.core.metadata.MetadataCatalog snapshotMetadata() {
        String yaml = """
                javaVersions:
                  - id: "17"
                    label: "Java 17"
                    deprecated: false
                    recommendedBootVersion: "3.2.5"
                bootVersions:
                  - id: "3.2.5"
                    label: "Spring Boot 3.2.5"
                    official: true
                    supportedJavaVersions: ["17"]
                """;
        return MetadataCatalog.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                .merge(snapshotStarters());
    }

    /** 快照用 starter 清单（web/middleware 分类 + internal 维护方）。 */
    private StarterCatalog snapshotStarters() {
        return StarterCatalog.of(List.of(
                new StarterCatalogEntry("web", "web",
                        Maintainer.OFFICIAL, "Spring MVC + 内嵌 Tomcat",
                        List.of(new DependencyCoord(
                                "org.springframework.boot", "spring-boot-starter-web", null)),
                        new LinkageSpec(null, List.of(), List.of(), List.of())),
                new StarterCatalogEntry("xxx-redis-spring-boot-starter",
                        "middleware",
                        Maintainer.INTERNAL, "内部 Redis 封装 starter",
                        List.of(new DependencyCoord(
                                "com.company", "xxx-redis-spring-boot-starter", "1.2.0")),
                        new LinkageSpec(null, List.of(), List.of(), List.of()))));
    }

    @Test
    void starterLabel_comesFromStarterCatalogDescription() {
        // 展示名来自 starter 清单的 description（metadata 不再维护 starters 节）
        assertThat(service.listStarters("3.2.5"))
                .extracting(MetadataService.StarterDto::label)
                .contains("Spring MVC + 内嵌 Tomcat", "Spring Data JPA 持久层");
    }

    @Test
    void starterWithoutCatalogDefinition_isNotListed() {
        // bootVersions.starters 列出清单未登记的 id：装载不报错，但清单接口不返回
        String yaml = """
                javaVersions:
                  - id: "8"
                bootVersions:
                  - id: "2.7.18"
                    supportedJavaVersions: ["8"]
                    starters: ["custom-starter"]
                """;
        MetadataService svc = new MetadataService(
                new com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder(),
                MetadataCatalog.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                        .merge(StarterCatalog.of(List.of())),
                StarterCatalog.of(List.of()));
        assertThat(svc.listStarters("2.7.18")).isEmpty();
    }
}
