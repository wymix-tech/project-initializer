package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.generator.ClasspathTemplates;
import com.wymix.initializer.core.generator.TemplateRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
    /** classpath 内置模板集（T5：模板集是唯一数据源，测试显式构造）。 */
    private static Map<String, String> builtInTemplates;
    /** 已并入 xxx-redis 联动模板的模板集。 */
    private static Map<String, String> linkageTemplates;
    private static Map<String, Object> model;

    /** 从测试资源读取联动模板文件（T5：不再依赖渲染器 classpath 兜底）。 */
    private static void mergeResource(Map<String, String> target, String name) {
        try (InputStream in = LinkageRendererTest.class.getClassLoader()
                .getResourceAsStream("templates/" + name)) {
            assertThat(in).isNotNull();
            target.put(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeAll
    static void setUp() {
        builtInTemplates = ClasspathTemplates.load();
        linkageTemplates = new LinkedHashMap<>(builtInTemplates);
        // xxx-redis 联动模板只随主资源（initializer-core/src/main/resources/templates/**）提供，
        // 与生产 --demo 种子口径一致
        mergeResource(linkageTemplates, "starters/xxx-redis/RedisConfig.java.ftl");
        mergeResource(linkageTemplates, "starters/xxx-redis/application-redis.yml");
        try (InputStream in = LinkageRendererTest.class.getResourceAsStream("/catalog/starters-test.yml")) {
            assertThat(in).isNotNull();
            StarterCatalog catalog = StarterCatalog.of(List.of(
                    com.wymix.initializer.core.starter.YamlStarterParser.parse(in)));
            renderer = new LinkageRenderer(catalog, new TemplateRenderer(linkageTemplates), linkageTemplates);
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
    void overrideYmlFragmentShouldMergeAndRestoreEscape() {
        // M3.5 扩展：管理端 TEMPLATE 草稿覆盖 yml 片段时，overrides 命中且 \${} → ${} 还原
        String starterYml = """
                id: override-frag-starter
                category: tool
                maintainer: internal
                description: 覆盖片段用例
                dependencies:
                  - groupId: com.company
                    artifactId: override-frag
                linkages:
                  application-yml-fragments:
                    - source: "starters/override/frag.yml"
                      merge-strategy: append
                """;
        StarterCatalog catalog = StarterCatalog.of(List.of(
                YamlStarterParser.parse(new java.io.ByteArrayInputStream(
                        starterYml.getBytes(StandardCharsets.UTF_8)))));
        java.util.Map<String, String> overrides = new java.util.HashMap<>();
        overrides.put("starters/override/frag.yml", "override:\n  secret: \\${SECRET:default-secret}\n");
        Map<String, String> templateSet = new LinkedHashMap<>(builtInTemplates);
        templateSet.putAll(overrides);
        LinkageRenderer r = new LinkageRenderer(catalog,
                new TemplateRenderer(templateSet), templateSet);
        LinkageRenderer.LinkageResult result = r.render(List.of("override-frag-starter"), Map.of());
        assertThat(result.mergedYml()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> overrideMap = (Map<String, Object>) result.mergedYml().get("override");
        assertThat(overrideMap).isNotNull();
        assertThat(overrideMap.get("secret")).isEqualTo("${SECRET:default-secret}");
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
        LinkageRenderer broken = new LinkageRenderer(catalog,
                new TemplateRenderer(builtInTemplates), builtInTemplates);
        assertThatThrownBy(() -> broken.render(List.of("broken-fragment-starter"), model))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_RENDER_FAILED))
                .hasMessageContaining("模板集缺失必需模板");
    }

    @Test
    void missingFragmentShouldThrow3004WhenReachabilityCheckDisabled() {
        // 可达性前置校验之外的兜底：直接读片段（不经校验）时缺片段仍抛 3004
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
                YamlStarterParser.parse(new ByteArrayInputStream(yml.getBytes(StandardCharsets.UTF_8)))));
        LinkageRenderer broken = new LinkageRenderer(catalog,
                new TemplateRenderer(builtInTemplates), builtInTemplates);
        assertThatThrownBy(() -> broken.validateRequirements(null, List.of("broken-fragment-starter")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_RENDER_FAILED));
    }

    @Test
    void validateRequirements_missingStarterTemplate_throws3001WithAllMissing() {
        // T5：模板集即 DB 全量模板集，无 classpath 兜底 —— 缺模板在渲染前先汇总明细失败
        LinkageRenderer noTemplates = new LinkageRenderer(starterCatalogOfTestYml(),
                new TemplateRenderer(Map.of()), Map.of());
        assertThatThrownBy(() -> noTemplates.validateRequirements(null,
                List.of("xxx-redis-spring-boot-starter")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_RENDER_FAILED);
                    assertThat(e.getMessage())
                            .contains("starters/xxx-redis/RedisConfig.java.ftl")
                            .contains("starters/xxx-redis/application-redis.yml");
                });
    }

    @Test
    void validateRequirements_noStartersSelected_passes() {
        // 空选择（或空清单输入）不触发模板可达性校验
        LinkageRenderer noTemplates = new LinkageRenderer(starterCatalogOfTestYml(),
                new TemplateRenderer(Map.of()), Map.of());
        noTemplates.validateRequirements(null, List.of());
        noTemplates.validateRequirements(null, null);
    }

    @Test
    void validateRequirements_unknownStarterSkipped_passes() {
        // 未登记清单的 starter（如仅登记在 metadata.yml 的官方 starter）无联动规则，跳过校验
        LinkageRenderer noTemplates = new LinkageRenderer(starterCatalogOfTestYml(),
                new TemplateRenderer(Map.of()), Map.of());
        noTemplates.validateRequirements(null, List.of("not-in-catalog"));
    }

    /** 从测试资源装载 starters-test.yml（含 xxx-redis 联动声明）。 */
    private static StarterCatalog starterCatalogOfTestYml() {
        try (InputStream in = LinkageRendererTest.class.getResourceAsStream("/catalog/starters-test.yml")) {
            assertThat(in).isNotNull();
            return StarterCatalog.of(List.of(YamlStarterParser.parse(in)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void renderShouldFailWithoutTemplateSetInsteadOfClasspathFallback() {
        // T5：模板集为空 → 渲染期不落 classpath 兜底（classpath 明明存在该模板也不得使用）
        LinkageRenderer noTemplates = new LinkageRenderer(starterCatalogOfTestYml(),
                new TemplateRenderer(Map.of()), Map.of());
        assertThatThrownBy(() -> noTemplates.render(List.of("xxx-redis-spring-boot-starter"), model))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_RENDER_FAILED));
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
        LinkageRenderer conflict = new LinkageRenderer(catalog,
                new TemplateRenderer(builtInTemplates), builtInTemplates);
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
        LinkageRenderer ok = new LinkageRenderer(catalog,
                new TemplateRenderer(builtInTemplates), builtInTemplates);
        LinkageRenderer.LinkageResult result = ok.render(List.of("alias-same-a", "alias-same-b"), model);
        assertThat(result.templateFiles()).isEmpty();
        assertThat(result.mergedYml()).isNull();
    }
}
