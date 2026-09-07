package com.wymix.initializer.core.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MetadataCatalog} 单元测试：覆盖装载、图校验、兼容矩阵遍历、异常路径。
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
            starters:
              web:
                label: "Spring Web"
                dependency:
                  groupId: "org.springframework.boot"
                  artifactId: "spring-boot-starter-web"
              data-jpa:
                label: "Spring Data JPA"
                dependency:
                  groupId: "org.springframework.boot"
                  artifactId: "spring-boot-starter-data-jpa"
            """;

    private static MetadataCatalog catalog(String yaml) {
        return MetadataCatalog.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Nested
    class AdditionalStarters {

        @Test
        void withAdditionalStarters_appendsToAllBootVersionsAndDefinitions() {
            MetadataCatalog c = catalog(VALID_YAML);
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
            MetadataCatalog c = catalog(VALID_YAML);
            MetadataCatalog extended = c.withAdditionalStarters(
                    List.of(new StarterDefinition("web", "dup", new MavenDependency("g", "a"))),
                    List.of("web"));
            // 已存在的 ID 不会重复追加进 starters 节
            for (BootVersion bv : extended.bootVersions()) {
                assertThat(bv.starters().stream().filter("web"::equals).count()).isEqualTo(1);
            }
        }

        @Test
        void startersDefinitionIds_aliasMatchesStarterDefinitionIds() {
            MetadataCatalog c = catalog(VALID_YAML);
            assertThat(c.startersDefinitionIds()).isEqualTo(c.starterDefinitionIds());
        }
    }

    @Nested
    class Requires {

        /** dal 依赖 core（一级依赖，闭包应自动带出 core）。 */
        private static final String REQUIRES_YAML = """
                javaVersions:
                  - id: "17"
                    label: "Java 17"
                bootVersions:
                  - id: "3.2.5"
                    label: "Spring Boot 3.2.5"
                    supportedJavaVersions: ["17"]
                    starters: ["web", "core", "dal"]
                starters:
                  web:
                    label: "Web"
                    dependency:
                      groupId: org.springframework.boot
                      artifactId: spring-boot-starter-web
                  core:
                    label: "核心"
                    dependency:
                      groupId: com.company
                      artifactId: core
                  dal:
                    label: "数据访问"
                    requires: [core]
                    dependency:
                      groupId: com.company
                      artifactId: dal
                """;

        /** a → b → a 成环，装载期必须拒绝。 */
        private static final String CYCLE_YAML = """
                javaVersions:
                  - id: "17"
                    label: "Java 17"
                bootVersions:
                  - id: "3.2.5"
                    label: "Spring Boot 3.2.5"
                    supportedJavaVersions: ["17"]
                    starters: ["a", "b"]
                starters:
                  a:
                    label: "A"
                    requires: [b]
                    dependency:
                      groupId: com.company
                      artifactId: a
                  b:
                    label: "B"
                    requires: [a]
                    dependency:
                      groupId: com.company
                      artifactId: b
                """;

        @Test
        void resolveRequires_returnsTransitiveClosure() {
            MetadataCatalog c = catalog(REQUIRES_YAML);
            // dal 依赖 core：闭包自动带出 core；无依赖的 web 保持单项
            assertThat(c.resolveRequires("3.2.5", List.of("dal"))).containsExactly("dal", "core");
            assertThat(c.resolveRequires("3.2.5", List.of("web"))).containsExactly("web");
        }

        @Test
        void resolveRequires_ignoresStartersNotAvailableInBootVersion() {
            MetadataCatalog c = catalog(REQUIRES_YAML);
            // 未登记在本 Boot 版本可选清单的 id 被忽略
            assertThat(c.resolveRequires("3.2.5", List.of("web", "unknown"))).containsExactly("web");
        }

        @Test
        void requiresUnknownStarter_throwsInternalError() {
            String yaml = REQUIRES_YAML.replace("requires: [core]", "requires: [nope]");
            assertThatThrownBy(() -> catalog(yaml))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }

        @Test
        void requiresSelf_throwsInternalError() {
            String yaml = REQUIRES_YAML.replace("requires: [core]", "requires: [dal]");
            assertThatThrownBy(() -> catalog(yaml))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }

        @Test
        void requiresCycle_throwsInternalError() {
            assertThatThrownBy(() -> catalog(CYCLE_YAML))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
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
        void starterDefinition_returnsConfiguredMetadata() {
            var sd = catalog(VALID_YAML).starterDefinition("web");
            assertThat(sd.id()).isEqualTo("web");
            assertThat(sd.label()).isEqualTo("Spring Web");
            assertThat(sd.dependency().groupId()).isEqualTo("org.springframework.boot");
            assertThat(sd.dependency().artifactId()).isEqualTo("spring-boot-starter-web");
        }

        @Test
        void starterDefinition_unknownId_throwsMetadataNotFound() {
            assertThatThrownBy(() -> catalog(VALID_YAML).starterDefinition("no-such"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
        }

        @Test
        void load_danglingStarterReference_throwsInternalError() {
            String yaml = """
                    javaVersions:
                      - id: "8"
                    bootVersions:
                      - id: "2.7.18"
                        supportedJavaVersions: ["8"]
                        starters: ["ghost"]
                    """;
            assertThatThrownBy(() -> catalog(yaml))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getMessage()).contains("ghost"));
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
