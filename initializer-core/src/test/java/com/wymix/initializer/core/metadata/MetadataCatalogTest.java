package com.wymix.initializer.core.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.starter.DependencyCoord;
import com.wymix.initializer.core.starter.LinkageSpec;
import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MetadataCatalog} 单元测试：覆盖装载、图校验、兼容矩阵遍历、starter 清单联动与异常路径。
 *
 * <p>新模型：starter 元信息（展示名/坐标/必选/依赖联动）全部由 starter 清单提供，
 * metadata.yml 不再维护 starters 节；{@link MetadataCatalog#merge} 是唯一联动入口。
 */
class MetadataCatalogTest {

    private static final String VALID_YAML = """
            javaVersions:
              - id: "8"
                label: "Java 8"
                recommendedBootVersion: "2.7.18"
              - id: "17"
                label: "Java 17"
                recommendedBootVersion: "3.2.5"
              - id: "21"
                label: "Java 21"
                recommendedBootVersion: "3.2.5"
            bootVersions:
              - id: "2.7.18"
                label: "Spring Boot 2.7.18"
                official: true
                supportedJavaVersions: ["8"]
                starters: ["web"]
              - id: "3.2.5"
                label: "Spring Boot 3.2.5"
                official: true
                supportedJavaVersions: ["17", "21"]
                starters: ["web", "data-jpa"]
              - id: "3.3.0-internal"
                label: "Boot 3.3.0 内部版"
                official: false
                supportedJavaVersions: ["17"]
                starters: ["web"]
            """;

    /** 测试用 starter 清单：与 VALID_YAML 的 boot starters 对齐（web / data-jpa）。 */
    private static StarterCatalog testStarterCatalog() {
        return entryCatalog(
                entry("web", "Spring MVC + 内嵌 Tomcat", false, List.of()),
                entry("data-jpa", "Spring Data JPA 持久层", false, List.of()));
    }

    private static StarterCatalogEntry entry(String id, String description, boolean required, List<String> requires) {
        return new StarterCatalogEntry(id, "web", Maintainer.OFFICIAL, description,
                List.of(new DependencyCoord("org.springframework.boot", "spring-boot-starter-" + id, null)),
                new LinkageSpec(null, List.of(), List.of(), List.of()), required, requires);
    }

    private static StarterCatalog entryCatalog(StarterCatalogEntry... entries) {
        return StarterCatalog.of(List.of(entries));
    }

    private static MetadataCatalog catalog(String yaml) {
        return MetadataCatalog.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Nested
    class MergeWithStarterCatalog {

        @Test
        void merge_suppliesStarterDefinitionsFromCatalog() {
            MetadataCatalog merged = catalog(VALID_YAML).merge(testStarterCatalog());
            var sd = merged.starterDefinition("web");
            assertThat(sd.id()).isEqualTo("web");
            assertThat(sd.label()).isEqualTo("Spring MVC + 内嵌 Tomcat");
            assertThat(sd.dependency().groupId()).isEqualTo("org.springframework.boot");
            assertThat(sd.dependency().artifactId()).isEqualTo("spring-boot-starter-web");
        }

        @Test
        void merge_emptyBootStarters_meansAllCatalogEntriesAvailable() {
            String yaml = """
                    javaVersions:
                      - id: "17"
                    bootVersions:
                      - id: "3.2.5"
                        supportedJavaVersions: ["17"]
                    """;
            MetadataCatalog merged = catalog(yaml).merge(testStarterCatalog());
            // bootVersions[].starters 省略 → 全部清单条目可选
            assertThat(merged.starters("3.2.5")).containsExactlyInAnyOrder("web", "data-jpa");
            assertThat(merged.starterDefinitionIds()).containsExactlyInAnyOrder("web", "data-jpa");
        }

        @Test
        void merge_unknownStarterIdInBootList_isNotVisible() {
            // bootVersions.starters 列出清单没有的 id：装载不报错，但查询/生成不可见
            String yaml = """
                    javaVersions:
                      - id: "8"
                    bootVersions:
                      - id: "2.7.18"
                        supportedJavaVersions: ["8"]
                        starters: ["ghost"]
                    """;
            MetadataCatalog merged = catalog(yaml).merge(StarterCatalog.of(List.of()));
            assertThat(merged.starters("2.7.18")).containsExactly("ghost");
            assertThat(merged.starterDefinitionOrNull("ghost")).isNull();
        }

        @Test
        void merge_carriesRequiredAndRequiresFromCatalog() {
            StarterCatalog catalog = entryCatalog(
                    entry("core", "框架核心", true, List.of()),
                    entry("dal", "数据访问", false, List.of("core")));
            String yaml = """
                    javaVersions:
                      - id: "17"
                    bootVersions:
                      - id: "3.2.5"
                        supportedJavaVersions: ["17"]
                    """;
            MetadataCatalog merged = catalog(yaml).merge(catalog);
            assertThat(merged.starterDefinition("core").required()).isTrue();
            assertThat(merged.requiredStarters("3.2.5")).containsExactly("core");
            // M3.7：不再自动带上 requires 依赖——依赖已满足时原样返回选中项
            assertThat(merged.resolveRequires("3.2.5", List.of("dal", "core")))
                    .containsExactly("dal", "core");
            // 依赖未满足（选了 dal 却没选 core）即拒绝
            assertThatThrownBy(() -> merged.resolveRequires("3.2.5", List.of("dal")))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MISSING_DEPENDENCY));
        }

        @Test
        void merge_keepsWithAdditionalStartersCompatible() {
            MetadataCatalog base = catalog(VALID_YAML).merge(testStarterCatalog());
            MetadataCatalog extended = base.withAdditionalStarters(
                    List.of(new StarterDefinition("xxx-oss", "内部 OSS",
                            new MavenDependency("com.company", "xxx-oss"))),
                    List.of("xxx-oss"));
            assertThat(base.starterDefinitionIds()).doesNotContain("xxx-oss");
            for (BootVersion bv : extended.bootVersions()) {
                assertThat(bv.starters()).contains("xxx-oss");
            }
            assertThat(extended.starterDefinition("xxx-oss").dependency().groupId())
                    .isEqualTo("com.company");
        }

        @Test
        void startersDefinitionIds_aliasMatchesStarterDefinitionIds() {
            MetadataCatalog c = catalog(VALID_YAML).merge(testStarterCatalog());
            assertThat(c.startersDefinitionIds()).isEqualTo(c.starterDefinitionIds());
        }
    }

    @Nested
    class AdditionalStarters {

        @Test
        void withAdditionalStarters_appendsToAllBootVersionsAndDefinitions() {
            MetadataCatalog c = catalog(VALID_YAML).merge(testStarterCatalog());
            MetadataCatalog extended = c.withAdditionalStarters(
                    List.of(new StarterDefinition("xxx-oss", "内部 OSS",
                            new MavenDependency("com.company", "xxx-oss"))),
                    List.of("xxx-oss"));
            // 原 catalog 不变（不可变语义）
            assertThat(c.starterDefinitionIds()).doesNotContain("xxx-oss");
            // 全部 Boot 版本可选 + 元信息登记
            for (BootVersion bv : extended.bootVersions()) {
                assertThat(bv.starters()).contains("xxx-oss");
            }
            assertThat(extended.starterDefinition("xxx-oss").dependency().groupId())
                    .isEqualTo("com.company");
            assertThat(extended.starterDefinitionIds()).contains("xxx-oss");
            // 原有条目保留
            assertThat(extended.starterDefinitionIds()).contains("web", "data-jpa");
        }

        @Test
        void withAdditionalStarters_existingIdNotDuplicatedInBootStarters() {
            MetadataCatalog c = catalog(VALID_YAML).merge(testStarterCatalog());
            MetadataCatalog extended = c.withAdditionalStarters(
                    List.of(new StarterDefinition("web", "dup", new MavenDependency("g", "a"))),
                    List.of("web"));
            // 已存在的 ID 不会重复追加进 starters 节
            for (BootVersion bv : extended.bootVersions()) {
                assertThat(bv.starters().stream().filter("web"::equals).count()).isEqualTo(1);
            }
        }
    }

    @Nested
    class RequiresValidation {

        /** 迁移至 StarterCatalog：requires 悬空/自引用/成环在清单装载期拒绝（9002）。 */
        @Test
        void requiresUnknownStarter_throwsStarterCatalogInvalid() {
            assertThatThrownBy(() -> entryCatalog(
                    entry("dal", "数据访问", false, List.of("nope"))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID));
        }

        @Test
        void requiresSelf_throwsStarterCatalogInvalid() {
            assertThatThrownBy(() -> entryCatalog(
                    entry("dal", "数据访问", false, List.of("dal"))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID));
        }

        @Test
        void requiresCycle_throwsStarterCatalogInvalid() {
            assertThatThrownBy(() -> entryCatalog(
                    entry("a", "A", false, List.of("b")),
                    entry("b", "B", false, List.of("a"))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID));
        }
    }

    @Nested
    class BootVersionScopedStarters {

        /** 同一 ID 在两个不同 Boot 版本各登记一份（坐标/版本不同）→ 合法。 */
        @Test
        void sameIdInDifferentBootVersions_allowed() {
            StarterCatalog catalog = StarterCatalog.of(List.of(
                    scopedEntry("web", "3.2.5", "1.0.0"),
                    scopedEntry("web", "4.0.0", "2.0.0")));
            assertThat(catalog.size()).isEqualTo(1);
            assertThat(catalog.entry("3.2.5", "web").dependencies().get(0).version()).isEqualTo("1.0.0");
            assertThat(catalog.entry("4.0.0", "web").dependencies().get(0).version()).isEqualTo("2.0.0");
            assertThat(catalog.contains("3.2.5", "web")).isTrue();
            assertThat(catalog.contains("4.0.0", "web")).isTrue();
        }

        /** 同一 Boot 版本内同 ID 重复 → 9002。 */
        @Test
        void duplicateIdInSameBootVersion_throws9002() {
            assertThatThrownBy(() -> StarterCatalog.of(List.of(
                    scopedEntry("web", "3.2.5", "1.0.0"),
                    scopedEntry("web", "3.2.5", "1.0.1"))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID))
                    .hasMessageContaining("Boot 版本 3.2.5")
                    .hasMessageContaining("web");
        }

        /** 未声明 bootVersions 的条目对全部版本可用，同一 ID 全局重复 → 9002。 */
        @Test
        void duplicateGlobalEntry_throws9002() {
            assertThatThrownBy(() -> StarterCatalog.of(List.of(
                    scopedEntry("web", List.of(), "1.0.0"),
                    scopedEntry("web", List.of(), "2.0.0"))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID))
                    .hasMessageContaining("ID 重复");
        }

        /** merge 后：声明了其他版本的条目不会出现在本版本可选清单中。 */
        @Test
        void merge_scopedEntryOnlyAvailableInDeclaredBootVersion() {
            String yaml = """
                    javaVersions:
                      - id: "17"
                      - id: "21"
                    bootVersions:
                      - id: "3.2.5"
                        supportedJavaVersions: ["17"]
                        starters: ["web"]
                      - id: "4.0.0"
                        supportedJavaVersions: ["21"]
                        starters: ["web"]
                    """;
            MetadataCatalog merged = catalog(yaml).merge(StarterCatalog.of(List.of(
                    scopedEntry("web", "3.2.5", "1.0.0"),
                    scopedEntry("web", "4.0.0", "2.0.0"))));
            assertThat(merged.starters("3.2.5")).containsExactly("web");
            assertThat(merged.starters("4.0.0")).containsExactly("web");
            // 未声明版本限制的条目才对所有版本生效
            MetadataCatalog globalOnly = catalog(yaml).merge(StarterCatalog.of(List.of(
                    scopedEntry("web", List.of(), "1.0.0"))));
            assertThat(globalOnly.starters("3.2.5")).containsExactly("web");
            assertThat(globalOnly.starters("4.0.0")).containsExactly("web");
        }

        private static StarterCatalogEntry scopedEntry(String id, String bootVersion, String version) {
            return scopedEntry(id, bootVersion == null ? List.of() : List.of(bootVersion), version);
        }

        private static StarterCatalogEntry scopedEntry(String id, List<String> bootVersions, String version) {
            return new StarterCatalogEntry(id, "web", Maintainer.OFFICIAL, "Web 封装",
                    List.of(new DependencyCoord("com.example", id, version)),
                    new LinkageSpec(null, List.of(), List.of(), List.of()), false, List.of(), bootVersions);
        }
    }

    @Nested
    class LoadAndValidate {

        @Test
        void load_validYaml_buildsCatalog() {
            MetadataCatalog c = catalog(VALID_YAML);
            assertThat(c.javaVersions()).extracting(JavaVersion::id).containsExactly("8", "17", "21");
            assertThat(c.bootVersions()).extracting(BootVersion::id).containsExactly("2.7.18", "3.2.5", "3.3.0-internal");
        }

        @Test
        void load_emptyStream_throwsInternalError() {
            assertThatThrownBy(() -> catalog(""))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }

        @Test
        void load_malformedYaml_throwsInternalErrorWithCause() {
            assertThatThrownBy(() -> catalog("javaVersions: [unclosed"))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                        assertThat(e.getCause()).isNotNull();
                    });
        }

        @Test
        void load_missingBootVersions_throwsInternalError() {
            assertThatThrownBy(() -> catalog("javaVersions:\n  - id: \"8\"\n"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        void load_duplicateJavaId_throwsInternalError() {
            String yaml = """
                    javaVersions:
                      - id: "8"
                      - id: "8"
                    bootVersions:
                      - id: "2.7.18"
                        supportedJavaVersions: ["8"]
                    """;
            assertThatThrownBy(() -> catalog(yaml)).isInstanceOf(BusinessException.class);
        }

        @Test
        void load_duplicateBootId_throwsInternalError() {
            String yaml = """
                    javaVersions:
                      - id: "8"
                    bootVersions:
                      - id: "2.7.18"
                        supportedJavaVersions: ["8"]
                      - id: "2.7.18"
                        supportedJavaVersions: ["8"]
                    """;
            assertThatThrownBy(() -> catalog(yaml)).isInstanceOf(BusinessException.class);
        }

        @Test
        void load_danglingJavaReference_throwsInternalError() {
            String yaml = """
                    javaVersions:
                      - id: "8"
                    bootVersions:
                      - id: "3.2.5"
                        supportedJavaVersions: ["17"]
                    """;
            assertThatThrownBy(() -> catalog(yaml))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getMessage()).contains("17"));
        }

        @Test
        void load_missingJavaId_throwsInternalError() {
            String yaml = """
                    javaVersions:
                      - label: "Java 8"
                    bootVersions:
                      - id: "2.7.18"
                        supportedJavaVersions: ["8"]
                    """;
            assertThatThrownBy(() -> catalog(yaml)).isInstanceOf(BusinessException.class);
        }

        @Test
        void load_javaItemNotMap_throwsInternalError() {
            String yaml = """
                    javaVersions:
                      - "8"
                    bootVersions:
                      - id: "2.7.18"
                        supportedJavaVersions: ["8"]
                    """;
            assertThatThrownBy(() -> catalog(yaml)).isInstanceOf(BusinessException.class);
        }

        @Test
        void load_supportedJavaNotList_throwsInternalError() {
            String yaml = """
                    javaVersions:
                      - id: "8"
                    bootVersions:
                      - id: "2.7.18"
                        supportedJavaVersions: "8"
                    """;
            assertThatThrownBy(() -> catalog(yaml)).isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    class CompatibilityTraversal {

        @Test
        void java8_onlyBoot2x() {
            List<BootVersion> result = catalog(VALID_YAML).compatibleBootVersions("8");
            assertThat(result).extracting(BootVersion::id).containsExactly("2.7.18");
        }

        @Test
        void java17_excludesInternalVersions() {
            List<BootVersion> result = catalog(VALID_YAML).compatibleBootVersions("17");
            assertThat(result).extracting(BootVersion::id).containsExactly("3.2.5");
        }

        @Test
        void unknownJava_throwsMetadataNotFound() {
            assertThatThrownBy(() -> catalog(VALID_YAML).compatibleBootVersions("99"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
        }

        @Test
        void starters_returnsBootStarterList() {
            assertThat(catalog(VALID_YAML).starters("3.2.5")).containsExactly("web", "data-jpa");
        }

        @Test
        void starters_unknownBoot_throwsMetadataNotFound() {
            assertThatThrownBy(() -> catalog(VALID_YAML).starters("9.9.9"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
        }

        @Test
        void javaVersionLookup_unknownId_throwsMetadataNotFound() {
            assertThatThrownBy(() -> catalog(VALID_YAML).javaVersion("99"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
        }

        @Test
        void bootVersionLookup_unknownId_throwsMetadataNotFound() {
            assertThatThrownBy(() -> catalog(VALID_YAML).bootVersion("9.9.9"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
        }

        @Test
        void recommendedBootVersion_presentAndAbsent() {
            MetadataCatalog c = catalog(VALID_YAML);
            assertThat(c.javaVersion("17").recommendedBootVersion()).isEqualTo("3.2.5");
            assertThat(c.javaVersion("8").recommendedBootVersion()).isEqualTo("2.7.18");
        }

        @Test
        void starterDefinition_unknownId_throwsMetadataNotFound() {
            assertThatThrownBy(() -> catalog(VALID_YAML).merge(testStarterCatalog()).starterDefinition("no-such"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
        }

        @Test
        void bootVersion_supportsJavaCheck() {
            BootVersion bv = catalog(VALID_YAML).bootVersion("3.2.5");
            assertThat(bv.supportsJava("17")).isTrue();
            assertThat(bv.supportsJava("8")).isFalse();
        }
    }

    @Nested
    class ClasspathCatalog {

        @Test
        void realMetadataFile_loadsAndFilters() throws Exception {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("catalog/metadata.yml")) {
                assertThat(in).as("classpath:catalog/metadata.yml 应存在").isNotNull();
                MetadataCatalog c = MetadataCatalog.load(in);
                // Java 8 → Boot 2.x，Java 17+ → Boot 3.x（验收标准 4）
                assertThat(c.compatibleBootVersions("8")).allSatisfy(bv -> assertThat(bv.id()).startsWith("2."));
                assertThat(c.compatibleBootVersions("17")).allSatisfy(bv -> assertThat(bv.id()).startsWith("3."));
                assertThat(c.compatibleBootVersions("21")).allSatisfy(bv -> assertThat(bv.id()).startsWith("3."));
                assertThat(c.starters("3.2.5")).contains("web");
            }
        }
    }
}
