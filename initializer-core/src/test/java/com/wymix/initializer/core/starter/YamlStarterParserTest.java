package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link YamlStarterParser} 单元测试：合法映射 + 非法结构负向用例。
 */
class YamlStarterParserTest {

    private static InputStream res(String name) {
        return YamlStarterParserTest.class.getClassLoader()
                .getResourceAsStream("catalog/starters/" + name);
    }

    private static InputStream yaml(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parse_fullInternalStarter_mapsAllFields() {
        StarterCatalogEntry entry = YamlStarterParser.parse(res("valid-full.yml"));
        assertThat(entry.id()).isEqualTo("xxx-redis-spring-boot-starter");
        assertThat(entry.category()).isEqualTo("middleware");
        assertThat(entry.maintainer()).isEqualTo(Maintainer.INTERNAL);
        assertThat(entry.description()).isEqualTo("内部 Redis 封装 starter");
        assertThat(entry.dependencies()).hasSize(1);
        assertThat(entry.dependencies().get(0).groupId()).isEqualTo("com.company");
        assertThat(entry.dependencies().get(0).artifactId()).isEqualTo("xxx-redis-spring-boot-starter");
        assertThat(entry.dependencies().get(0).version()).isEqualTo("1.2.0");
        LinkageSpec linkages = entry.linkages();
        assertThat(linkages.configPackage()).isEqualTo("{basePackage}.config");
        assertThat(linkages.templates()).hasSize(1);
        assertThat(linkages.templates().get(0).source()).isEqualTo("starters/xxx-redis/RedisConfig.java.ftl");
        assertThat(linkages.templates().get(0).target())
                .isEqualTo("src/main/java/{packagePath}/config/RedisConfig.java");
        assertThat(linkages.ymlFragments()).hasSize(1);
        assertThat(linkages.ymlFragments().get(0).source()).isEqualTo("starters/xxx-redis/application-redis.yml");
        assertThat(linkages.ymlFragments().get(0).mergeStrategy())
                .isEqualTo(YmlFragmentLinkage.MergeStrategy.APPEND);
        assertThat(linkages.abstractClasses()).hasSize(1);
        assertThat(linkages.abstractClasses().get(0).fqcn()).isEqualTo("com.company.redis.RedisSupport");
        assertThat(linkages.abstractClasses().get(0).alias()).isEqualTo("redisSupport");
        assertThat(linkages.isEmpty()).isFalse();
    }

    @Test
    void parse_officialMinimal_emptyLinkages() {
        StarterCatalogEntry entry = YamlStarterParser.parse(res("valid-official-minimal.yml"));
        assertThat(entry.maintainer()).isEqualTo(Maintainer.OFFICIAL);
        assertThat(entry.linkages().isEmpty()).isTrue();
        assertThat(entry.linkages().configPackage()).isNull();
        assertThat(entry.linkages().templates()).isEmpty();
        assertThat(entry.dependencies().get(0).version()).isNull();
    }

    @Test
    void parse_mergeStrategyOverride_mapsOverride() {
        StarterCatalogEntry entry = YamlStarterParser.parse(yaml("""
                id: s
                category: data
                maintainer: official
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages:
                  application-yml-fragments:
                    - source: "frag.yml"
                      merge-strategy: override
                """));
        assertThat(entry.linkages().ymlFragments().get(0).mergeStrategy())
                .isEqualTo(YmlFragmentLinkage.MergeStrategy.OVERRIDE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "invalid-bad-maintainer.yml",
            "invalid-no-dependencies.yml",
            "invalid-anchor.yml",
            "invalid-empty.yml"})
    void parse_invalidResource_throws9002(String name) {
        assertThatThrownBy(() -> YamlStarterParser.parse(res(name)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID));
    }

    @Test
    void parse_templatesMissingIndexCounter_coversLoop() {
        // 多条目循环 + 下标推进覆盖（PIT 存活突变：i++）
        StarterCatalogEntry entry = YamlStarterParser.parse(yaml("""
                id: s
                category: middleware
                maintainer: internal
                dependencies:
                  - groupId: g1
                    artifactId: a1
                    version: "1.0"
                  - groupId: g2
                    artifactId: a2
                linkages:
                  templates:
                    - source: "t1.ftl"
                      target: "t1"
                    - source: "t2.ftl"
                      target: "t2"
                  application-yml-fragments:
                    - source: "f1.yml"
                    - source: "f2.yml"
                      merge-strategy: override
                  abstract-classes:
                    - fqcn: "c1.C"
                      alias: c1
                    - fqcn: "c2.C"
                      alias: c2
                """));
        assertThat(entry.dependencies()).extracting(DependencyCoord::artifactId)
                .containsExactly("a1", "a2");
        assertThat(entry.dependencies().get(0).version()).isEqualTo("1.0");
        assertThat(entry.dependencies().get(1).version()).isNull();
        assertThat(entry.linkages().templates()).extracting(TemplateLinkage::source)
                .containsExactly("t1.ftl", "t2.ftl");
        assertThat(entry.linkages().ymlFragments()).extracting(YmlFragmentLinkage::mergeStrategy)
                .containsExactly(YmlFragmentLinkage.MergeStrategy.APPEND, YmlFragmentLinkage.MergeStrategy.OVERRIDE);
        assertThat(entry.linkages().abstractClasses()).extracting(AbstractClassLinkage::alias)
                .containsExactly("c1", "c2");
    }

    @Test
    void parse_linkagesEmptyMap_mapsAllEmpty() {
        // linkages 显式 null 分支覆盖（PIT 存活突变：null → 空规格）
        StarterCatalogEntry entry = YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g
                    artifactId: a
                """));
        assertThat(entry.linkages().isEmpty()).isTrue();
        assertThat(entry.linkages().templates()).isEmpty();
        assertThat(entry.linkages().ymlFragments()).isEmpty();
        assertThat(entry.linkages().abstractClasses()).isEmpty();
    }

    @Test
    void parse_emptyTemplatesList_throws9002WithFieldName() {
        // PIT：requireList 空清单必须抛错（removed call 存活回归）
        assertThatThrownBy(() -> YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages:
                  templates: []
                """)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("linkages.templates"));
    }

    @Test
    void parse_emptyYmlFragmentsList_throws9002() {
        assertThatThrownBy(() -> YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages:
                  application-yml-fragments: []
                """)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("application-yml-fragments"));
    }

    @Test
    void parse_emptyAbstractClassesList_throws9002() {
        assertThatThrownBy(() -> YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages:
                  abstract-classes: []
                """)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("abstract-classes"));
    }

    @Test
    void parse_secondTemplateEntryInvalid_errorIndexIsOne() {
        // PIT：下标推进必须真实生效（increment 变异回归——错误信息携带 [1]）
        assertThatThrownBy(() -> YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages:
                  templates:
                    - source: "ok.ftl"
                      target: "ok"
                    - source: ""
                      target: "bad"
                """)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("templates[1]"));
    }

    @Test
    void parse_secondDependencyEntryMissingArtifactId_errorMentionsField() {
        // 依赖第二条缺失 artifactId → requireString 必须执行（下标变异回归）
        assertThatThrownBy(() -> YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g
                    artifactId: a
                  - groupId: g2
                linkages: {}
                """)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID));
    }

    @Test
    void parse_secondDependencyInvalid_errorIndexIsOne() {
        assertThatThrownBy(() -> YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g1
                    artifactId: a1
                  - groupId: ""
                    artifactId: a2
                linkages: {}
                """)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("dependencies[1].groupId"));
    }

    @Test
    void parse_secondYmlFragmentInvalid_errorIndexIsOne() {
        assertThatThrownBy(() -> YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages:
                  application-yml-fragments:
                    - source: "ok.yml"
                    - source: "f2.yml"
                      merge-strategy: bogus
                """)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("application-yml-fragments[1].merge-strategy 非法"));
    }

    @Test
    void parse_secondAbstractClassInvalid_errorIndexIsOne() {
        assertThatThrownBy(() -> YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages:
                  abstract-classes:
                    - fqcn: "c1.C"
                      alias: c1
                    - fqcn: "c2.C"
                      alias: ""
                """)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("abstract-classes[1].alias"));
    }

    @Test
    void parse_unknownPlaceholderAtParserLevel_passesThrough() {
        // 占位符越界由 StarterCatalog 装载期校验（见 StarterCatalogTest），解析层放行结构合法的 yml
        StarterCatalogEntry entry = YamlStarterParser.parse(res("invalid-bad-placeholder.yml"));
        assertThat(entry.id()).isEqualTo("bad");
    }

    @Test
    void parse_missingRequiredField_throws9002() {
        assertThatThrownBy(() -> YamlStarterParser.parse(yaml("""
                id: s
                category: web
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages: {}
                """)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID);
                    assertThat(e.getMessage()).contains("maintainer");
                });
    }

    @Test
    void parse_templatesEntryMissingTarget_throws9002() {
        assertThatThrownBy(() -> YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages:
                  templates:
                    - source: "a.ftl"
                """)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID));
    }

    @Test
    void parse_linkagesWrongType_throws9002() {
        assertThatThrownBy(() -> YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages: not-a-map
                """)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID));
    }

    @Test
    void parse_missingDependencyArtifactId_throws9002() {
        assertThatThrownBy(() -> YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g
                linkages: {}
                """)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID));
    }

    @Test
    void parse_mergeStrategyIllegal_throws9002() {
        assertThatThrownBy(() -> YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages:
                  application-yml-fragments:
                    - source: "f.yml"
                      merge-strategy: replace-all
                """)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID));
    }

    @Test
    void parse_hiddenTrue_mapsHidden() {
        StarterCatalogEntry entry = YamlStarterParser.parse(yaml("""
                id: ghost
                category: tool
                maintainer: internal
                hidden: true
                dependencies:
                  - groupId: g
                    artifactId: ghost
                linkages: {}
                """));
        assertThat(entry.hidden()).isTrue();
    }

    @Test
    void parse_hiddenAbsentOrFalse_defaultsFalse() {
        StarterCatalogEntry absent = YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages: {}
                """));
        assertThat(absent.hidden()).isFalse();

        StarterCatalogEntry explicitFalse = YamlStarterParser.parse(yaml("""
                id: s
                category: web
                maintainer: official
                hidden: false
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages: {}
                """));
        assertThat(explicitFalse.hidden()).isFalse();
    }
}
