package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CatalogRefresher} 冲突规则装载测试（M3.5）：合法规则进快照、悬空引用拒绝发布。
 */
class CatalogRefresherConflictTest {

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

    private static final String STARTER_YAML = """
            id: xxx-redis-spring-boot-starter
            category: middleware
            maintainer: internal
            description: 内部 Redis 封装 starter
            dependencies:
              - groupId: com.company
                artifactId: xxx-redis-spring-boot-starter
                version: "1.2.0"
            linkages: {}
            """;

    private static final String CONFLICT_YAML = """
            rules:
              - id: web-vs-redis
                type: pair
                starters: [web, xxx-redis-spring-boot-starter]
                message: Web 与内部 Redis 封装互斥（测试规则）
            """;

    private final CatalogRefresher refresher = new CatalogRefresher();

    @Test
    void validateAndBuild_validConflictRules_builtIntoSnapshot() {
        CatalogSnapshot snapshot = refresher.validateAndBuild(METADATA_YAML, STARTER_YAML, null, CONFLICT_YAML);
        assertThat(snapshot.conflictCatalog().size()).isEqualTo(1);
        assertThat(snapshot.conflictCatalog().evaluate(Set.of("web", "xxx-redis-spring-boot-starter")))
                .hasSize(1);
    }

    @Test
    void validateAndBuild_nullConflictYaml_emptyCatalog() {
        CatalogSnapshot snapshot = refresher.validateAndBuild(METADATA_YAML, STARTER_YAML, null, null);
        assertThat(snapshot.conflictCatalog().size()).isZero();
    }

    @Test
    void validateAndBuild_blankConflictYaml_emptyCatalog() {
        CatalogSnapshot snapshot = refresher.validateAndBuild(METADATA_YAML, STARTER_YAML, null, "  ");
        assertThat(snapshot.conflictCatalog().size()).isZero();
    }

    @Test
    void validateAndBuild_danglingConflictReference_throws4002() {
        String dangling = CONFLICT_YAML.replace("xxx-redis-spring-boot-starter", "no-such-starter");
        assertThatThrownBy(() -> refresher.validateAndBuild(METADATA_YAML, STARTER_YAML, null, dangling))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED))
                .hasMessageContaining("web-vs-redis")
                .hasMessageContaining("no-such-starter");
    }

    @Test
    void validateAndBuild_conflictReferenceKnownByBothSides_allowed() {
        // 规则同时引用 metadata 登记条目（web）与仅 starter 清单登记条目（M3.3 自动补位语义）→ 合法
        CatalogSnapshot snapshot = refresher.validateAndBuild(METADATA_YAML, STARTER_YAML, null, CONFLICT_YAML);
        assertThat(snapshot.conflictCatalog().evaluate(Set.of("web", "xxx-redis-spring-boot-starter")))
                .extracting(c -> c.ruleId())
                .containsExactly("web-vs-redis");
    }

    @Test
    void validateAndBuild_illegalConflictStructure_throws4002() {
        assertThatThrownBy(() -> refresher.validateAndBuild(METADATA_YAML, STARTER_YAML, null, "rules: [bad]"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }
}
