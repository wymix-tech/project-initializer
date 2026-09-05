package com.wymix.initializer.core.starter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 前端 starter 配置向导生成的 YAML 格式兼容性测试。
 *
 * <p>管理端「向导式接入」由 {@code starter-yaml-builder.ts} 纯函数渲染 starter yml，
 * 产出 {@code catalog/starters/*.yml} 同构文档后追加进草稿并发布。本测试锁定前端渲染
 * 格式（不加引号的坐标 / 描述、{@code linkages: {}} 与多行块）能被后端
 * {@link YamlStarterParser} 正确解析，防止前后端契约漂移。
 *
 * <p>字段值与前端 {@code starter-yaml-builder.spec.ts} 的用例保持一致：如格式回归
 * （误加尾随空格、误改缩进），本测试先于发布期 9002 失败暴露。
 */
class FrontendBuilderFormatCompatTest {

    private static ByteArrayInputStream yaml(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void officialMinimal_unquotedCoordsAndEmptyLinkages_parses() {
        // 与 web-starter.yml 同构：坐标不加引号、linkages: {}
        String frontendGenerated = """
            id: web
            category: web
            maintainer: official
            description: Spring MVC + 内嵌 Tomcat
            dependencies:
              - groupId: org.springframework.boot
                artifactId: spring-boot-starter-web
            linkages: {}
            """;
        StarterCatalogEntry entry = YamlStarterParser.parse(yaml(frontendGenerated));
        assertThat(entry.id()).isEqualTo("web");
        assertThat(entry.maintainer()).isEqualTo(Maintainer.OFFICIAL);
        assertThat(entry.description()).isEqualTo("Spring MVC + 内嵌 Tomcat");
        assertThat(entry.dependencies()).hasSize(1);
        assertThat(entry.dependencies().get(0).groupId()).isEqualTo("org.springframework.boot");
        assertThat(entry.dependencies().get(0).artifactId()).isEqualTo("spring-boot-starter-web");
        assertThat(entry.dependencies().get(0).version()).isNull();
        assertThat(entry.linkages().isEmpty()).isTrue();
    }

    @Test
    void internalFull_unquotedBlock_parsesAllLinkages() {
        // 与 xxx-redis-spring-boot-starter.yml 同构：config-package / 模板 / 片段 / 抽象类
        String frontendGenerated = """
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
              - source: starters/xxx-redis/RedisConfig.java.ftl
                target: "src/main/java/{packagePath}/config/RedisConfig.java"
              application-yml-fragments:
              - source: starters/xxx-redis/application-redis.yml
                merge-strategy: append
              abstract-classes:
              - fqcn: com.company.redis.RedisSupport
                alias: redisSupport
            """;
        StarterCatalogEntry entry = YamlStarterParser.parse(yaml(frontendGenerated));
        assertThat(entry.id()).isEqualTo("xxx-redis-spring-boot-starter");
        assertThat(entry.maintainer()).isEqualTo(Maintainer.INTERNAL);
        assertThat(entry.dependencies().get(0).version()).isEqualTo("1.2.0");
        LinkageSpec linkages = entry.linkages();
        assertThat(linkages.configPackage()).isEqualTo("{basePackage}.config");
        assertThat(linkages.templates()).hasSize(1);
        assertThat(linkages.templates().get(0).target())
                .isEqualTo("src/main/java/{packagePath}/config/RedisConfig.java");
        assertThat(linkages.ymlFragments()).hasSize(1);
        assertThat(linkages.ymlFragments().get(0).mergeStrategy())
                .isEqualTo(YmlFragmentLinkage.MergeStrategy.APPEND);
        assertThat(linkages.abstractClasses()).hasSize(1);
        assertThat(linkages.abstractClasses().get(0).fqcn()).isEqualTo("com.company.redis.RedisSupport");
        assertThat(linkages.abstractClasses().get(0).alias()).isEqualTo("redisSupport");
    }

    @Test
    void multiDoc_draftAppend_parsesEachDocument() {
        // 向导追加场景：草稿为多文档（--- 分隔），每文档独立可解析
        String draft = """
            id: old
            category: web
            maintainer: official
            description: old starter
            dependencies:
              - groupId: g
                artifactId: old
            linkages: {}
            ---
            id: new-starter
            category: tool
            maintainer: internal
            description: new
            dependencies:
              - groupId: com.company
                artifactId: new-starter
                version: "2.0.0"
            linkages: {}
            """;
        String[] docs = draft.split("\\n---\\n");
        assertThat(docs).hasSize(2);
        StarterCatalogEntry first = YamlStarterParser.parse(yaml(docs[0]));
        StarterCatalogEntry second = YamlStarterParser.parse(yaml(docs[1]));
        assertThat(first.id()).isEqualTo("old");
        assertThat(second.id()).isEqualTo("new-starter");
        assertThat(second.dependencies().get(0).version()).isEqualTo("2.0.0");
    }

    @Test
    void pureDigitVersion_quoted_parsesAsString() {
        // 纯数字版本号前端强制加引号，防止 YAML 解析为整数
        String frontendGenerated = """
            id: demo
            category: tool
            maintainer: internal
            description: demo
            dependencies:
              - groupId: com.example
                artifactId: demo
                version: "2024"
            linkages: {}
            """;
        StarterCatalogEntry entry = YamlStarterParser.parse(yaml(frontendGenerated));
        assertThat(entry.dependencies().get(0).version()).isEqualTo("2024");
    }
}
