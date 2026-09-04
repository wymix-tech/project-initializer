package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.generator.TemplateRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LinkageRenderer 单元测试（M2.3）：
 * 模板联动渲染 + 占位符替换 / target 冲突 3002 / yml 片段合并委托 / 抽象类注入 context / 依赖坐标汇总。
 */
class LinkageRendererTest {

    private static LinkageRenderer renderer;
    private static Map<String, Object> model;

    @BeforeAll
    static void setUp() {
        try (InputStream in = LinkageRendererTest.class.getResourceAsStream("/catalog/starters-test.yml")) {
            assertThat(in).isNotNull();
            StarterCatalog catalog = StarterCatalog.of(List.of(
                    com.wymix.initializer.core.starter.YamlStarterParser.parse(in)));
            renderer = new LinkageRenderer(catalog, new TemplateRenderer());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        model = Map.of(
                TemplateRenderer.K_BASE_PACKAGE, "com.example.demo",
                TemplateRenderer.K_PACKAGE_PATH, "com/example/demo",
                TemplateRenderer.K_ARTIFACT, "demo-app",
                TemplateRenderer.K_GROUP, "com.example");
    }

    @Test
    void renderShouldProduceTemplateFileWithPlaceholdersResolved() {
        LinkageRenderer.LinkageResult result = renderer.render(
                List.of("xxx-redis-spring-boot-starter"), model);
        assertThat(result.templateFiles()).containsKey("src/main/java/com/example/demo/config/RedisConfig.java");
        String src = new String(result.templateFiles()
                .get("src/main/java/com/example/demo/config/RedisConfig.java"), StandardCharsets.UTF_8);
        assertThat(src).contains("package com.example.demo.config;")
                .contains("RedisSupport");
    }

    @Test
    void renderShouldMergeYmlFragment() {
        LinkageRenderer.LinkageResult result = renderer.render(
                List.of("xxx-redis-spring-boot-starter"), model);
        assertThat(result.mergedYml()).isNotNull();
        Map<?, ?> spring = (Map<?, ?>) result.mergedYml().get("spring");
        assertThat(spring).isNotNull();
        assertThat(((Map<?, ?>) spring.get("redis")).get("host")).isEqualTo("${REDIS_HOST:localhost}");
    }

    @Test
    void renderWithLinkageFreeStartersShouldReturnEmpty() {
        LinkageRenderer.LinkageResult result = renderer.render(List.of(), model);
        assertThat(result.templateFiles()).isEmpty();
        assertThat(result.mergedYml()).isNull();
    }

    @Test
    void resolveConfigPackageShouldReplacePlaceholder() {
        assertThat(renderer.resolveConfigPackage("xxx-redis-spring-boot-starter", model))
                .isEqualTo("com.example.demo.config");
    }

    @Test
    void resolveDependenciesShouldKeepOrderAndDeduplicate() {
        List<DependencyCoord> deps = renderer.resolveDependencies(
                List.of("xxx-redis-spring-boot-starter", "xxx-redis-spring-boot-starter"));
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).artifactId()).isEqualTo("xxx-redis-spring-boot-starter");
        assertThat(deps.get(0).version()).isEqualTo("1.2.0");
    }

    @Test
    void unknownStarterShouldSkipLinkageGracefully() {
        // 未登记清单的 starter（如仅登记 metadata.yml 的官方 starter）无联动规则，静默跳过
        LinkageRenderer.LinkageResult result = renderer.render(List.of("nosuch-starter"), model);
        assertThat(result.templateFiles()).isEmpty();
        assertThat(result.mergedYml()).isNull();
    }

    @Test
    void registeredShouldReflectCatalog() {
        assertThat(renderer.registered("xxx-redis-spring-boot-starter")).isTrue();
        assertThat(renderer.registered("web")).isFalse();
    }

    @Test
    void missingFragmentResourceShouldThrow3004() throws Exception {
        // 构造一个声明了不存在片段的清单
        String yml = """
                id: broken-fragment-starter
                category: tool
                maintainer: internal
                description: 片段缺失用例
                dependencies:
                  - groupId: com.company
                    artifactId: broken
                linkages:
                  application-yml-fragments:
                    - source: "starters/no-such-fragment.yml"
                      merge-strategy: append
                """;
        StarterCatalog catalog = StarterCatalog.of(List.of(
                YamlStarterParser.parse(new java.io.ByteArrayInputStream(yml.getBytes(StandardCharsets.UTF_8)))));
        LinkageRenderer broken = new LinkageRenderer(catalog, new TemplateRenderer());
        assertThatThrownBy(() -> broken.render(List.of("broken-fragment-starter"), model))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.YML_MERGE_CONFLICT));
    }

    @Test
    void aliasConflictShouldThrowExplicitly() {
        // 评审修复：两个 starter 声明同 alias 不同 fqcn → 显式报错，不静默覆盖
        String a = """
                id: alias-a-starter
                category: tool
                maintainer: internal
                description: 别名冲突用例 A
                dependencies:
                  - groupId: com.company
                    artifactId: alias-a
                linkages:
                  abstract-classes:
                    - fqcn: "com.company.a.SupportA"
                      alias: support
                """;
        String b = """
                id: alias-b-starter
                category: tool
                maintainer: internal
                description: 别名冲突用例 B
                dependencies:
                  - groupId: com.company
                    artifactId: alias-b
                linkages:
                  abstract-classes:
                    - fqcn: "com.company.b.SupportB"
                      alias: support
                """;
        StarterCatalog catalog = StarterCatalog.of(List.of(
                YamlStarterParser.parse(new java.io.ByteArrayInputStream(a.getBytes(StandardCharsets.UTF_8))),
                YamlStarterParser.parse(new java.io.ByteArrayInputStream(b.getBytes(StandardCharsets.UTF_8)))));
        LinkageRenderer conflict = new LinkageRenderer(catalog, new TemplateRenderer());
        assertThatThrownBy(() -> conflict.render(List.of("alias-a-starter", "alias-b-starter"), model))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_TARGET_CONFLICT));
    }

    @Test
    void sameAliasSameFqcnShouldBeAllowed() {
        // 同 alias 同 fqcn 视为合法重复，不报错
        String a = """
                id: alias-same-a
                category: tool
                maintainer: internal
                description: 合法重复用例 A
                dependencies:
                  - groupId: com.company
                    artifactId: alias-same-a
                linkages:
                  abstract-classes:
                    - fqcn: "com.company.same.Support"
                      alias: support
                """;
        String b = """
                id: alias-same-b
                category: tool
                maintainer: internal
                description: 合法重复用例 B
                dependencies:
                  - groupId: com.company
                    artifactId: alias-same-b
                linkages:
                  abstract-classes:
                    - fqcn: "com.company.same.Support"
                      alias: support
                """;
        StarterCatalog catalog = StarterCatalog.of(List.of(
                YamlStarterParser.parse(new java.io.ByteArrayInputStream(a.getBytes(StandardCharsets.UTF_8))),
                YamlStarterParser.parse(new java.io.ByteArrayInputStream(b.getBytes(StandardCharsets.UTF_8)))));
        LinkageRenderer ok = new LinkageRenderer(catalog, new TemplateRenderer());
        LinkageRenderer.LinkageResult result = ok.render(List.of("alias-same-a", "alias-same-b"), model);
        assertThat(result.templateFiles()).isEmpty();
        assertThat(result.mergedYml()).isNull();
    }
}
