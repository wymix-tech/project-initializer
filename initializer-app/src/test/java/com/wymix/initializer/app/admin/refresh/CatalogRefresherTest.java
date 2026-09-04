package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CatalogRefresher} 单元测试（M3.1）：发布校验成功 / 各类失败语义 / 多文档切分。
 *
 * <p>对应验收标准（Issue #6）：注入非法配置 → 发布被拒 + 明确错误码；
 * 校验复用 M1/M2 装载期规则（ID 重复、悬空引用、占位符越界等）。
 */
class CatalogRefresherTest {

    private CatalogRefresher refresher;

    /** 测试 metadata 配置：Java 17/21 + Boot 3.2.5 + starters 节登记 web 与内部 redis。 */
    private static final String METADATA_YAML = """
            javaVersions:
              - id: "17"
                label: "Java 17"
                recommendedBootVersion: "3.2.5"
            bootVersions:
              - id: "3.2.5"
                label: "Spring Boot 3.2.5"
                official: true
                supportedJavaVersions: ["17"]
                starters: ["web", "xxx-redis-spring-boot-starter"]
            starters:
              web:
                label: "Spring Web"
                dependency:
                  groupId: "org.springframework.boot"
                  artifactId: "spring-boot-starter-web"
              xxx-redis-spring-boot-starter:
                label: "内部 Redis 封装 starter"
                dependency:
                  groupId: "com.company"
                  artifactId: "xxx-redis-spring-boot-starter"
            """;

    /** 合法 starter 配置：多文档（web 无联动 + 内部 redis 完整联动）。 */
    private static final String STARTER_YAML = """
            id: web
            category: web
            maintainer: official
            description: Spring MVC
            dependencies:
              - groupId: org.springframework.boot
                artifactId: spring-boot-starter-web
            linkages: {}
            ---
            id: xxx-redis-spring-boot-starter
            category: middleware
            maintainer: internal
            description: 内部 Redis 封装 starter
            dependencies:
              - groupId: com.company
                artifactId: xxx-redis-spring-boot-starter
                version: "1.2.0"
            linkages:
              config-package: "{basePackage}.config"
              templates:
                - source: "starters/xxx-redis/RedisConfig.java.ftl"
                  target: "src/main/java/{packagePath}/config/RedisConfig.java"
              application-yml-fragments:
                - source: "starters/xxx-redis/application-redis.yml"
                  merge-strategy: append
              abstract-classes:
                - fqcn: "com.company.redis.RedisSupport"
                  alias: redisSupport
            """;

    @BeforeEach
    void setUp() {
        refresher = new CatalogRefresher();
    }

    @Test
    void validateAndBuild_successBuildsSnapshot() {
        CatalogSnapshot snapshot = refresher.validateAndBuild(METADATA_YAML, STARTER_YAML);
        assertThat(snapshot.metadataCatalog().javaVersions()).hasSize(1);
        assertThat(snapshot.starterCatalog().size()).isEqualTo(2);
        assertThat(snapshot.metadataCatalog().starterDefinitionOrNull("web")).isNotNull();
    }

    @Test
    void validateAndBuild_nullMetadata_throws4002() {
        assertThatThrownBy(() -> refresher.validateAndBuild(null, STARTER_YAML))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void validateAndBuild_blankMetadata_throws4002() {
        assertThatThrownBy(() -> refresher.validateAndBuild("  ", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void validateAndBuild_danglingStarterReferenceInMetadata_throws4002() {
        // metadata.bootVersions.starters 引用未登记的 starter → 悬空引用拒绝发布（验收标准）
        String dangling = METADATA_YAML.replace(
                "starters: [\"web\", \"xxx-redis-spring-boot-starter\"]",
                "starters: [\"web\", \"no-such-starter\"]");
        assertThatThrownBy(() -> refresher.validateAndBuild(dangling, STARTER_YAML))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED))
                .hasMessageContaining("no-such-starter");
    }

    @Test
    void validateAndBuild_duplicateStarterId_throws9002() {
        String firstDoc = STARTER_YAML.split("\n---\n", 2)[0];
        String duplicated = STARTER_YAML + "\n---\n" + firstDoc;
        assertThatThrownBy(() -> refresher.validateAndBuild(METADATA_YAML, duplicated))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID))
                .hasMessageContaining("ID 重复");
    }

    @Test
    void validateAndBuild_badPlaceholder_throws9002() {
        String badPlaceholder = STARTER_YAML.replace("{basePackage}.config", "{evil}.config");
        assertThatThrownBy(() -> refresher.validateAndBuild(METADATA_YAML, badPlaceholder))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID))
                .hasMessageContaining("{evil}");
    }

    @Test
    void validateAndBuild_missingStarterField_throws9002() {
        String missing = STARTER_YAML.replace("category: web\n", "");
        assertThatThrownBy(() -> refresher.validateAndBuild(METADATA_YAML, missing))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID));
    }

    @Test
    void validateAndBuild_emptyStarterYaml_meansEmptyCatalog() {
        CatalogSnapshot snapshot = refresher.validateAndBuild(METADATA_YAML, null);
        assertThat(snapshot.starterCatalog().size()).isZero();
        // metadata 读路径不依赖 starter 清单
        assertThat(snapshot.metadataCatalog().bootVersions()).hasSize(1);
    }

    @Test
    void splitDocuments_splitsOnDocumentSeparator() {
        List<String> docs = CatalogRefresher.splitDocuments("---\na: 1\n---\nb: 2\n");
        assertThat(docs).hasSize(2);
        assertThat(docs.get(0)).contains("a: 1");
        assertThat(docs.get(1)).contains("b: 2");
    }

    @Test
    void splitDocuments_singleDocumentPassthrough() {
        List<String> docs = CatalogRefresher.splitDocuments("id: web\n");
        assertThat(docs).hasSize(1);
    }

    @Test
    void realClasspathFixtures_passValidation() throws Exception {
        // 真实资源回归：classpath 内置清单通过同一校验器（保证发布链路与启动装载口径一致）
        try (InputStream metadataIn = getClass().getResourceAsStream("/catalog/metadata.yml")) {
            CatalogSnapshot snapshot = refresher.validateAndBuild(
                    new String(metadataIn.readAllBytes(), StandardCharsets.UTF_8), null);
            assertThat(snapshot.metadataCatalog().javaVersions()).isNotEmpty();
        }
    }
}
