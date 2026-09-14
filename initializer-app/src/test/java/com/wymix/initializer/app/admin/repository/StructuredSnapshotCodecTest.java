package com.wymix.initializer.app.admin.repository;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StructuredSnapshotCodec} 单元测试（结构化改造 T4）：metadata / starter / 冲突规则 / 模板
 * 四类文档的「YAML → 结构行」映射、草稿宽松与严格两种模式的边界、上限与安全校验。
 */
class StructuredSnapshotCodecTest {

    private final StructuredSnapshotCodec codec = new StructuredSnapshotCodec();

    private static final String METADATA = """
            javaVersions:
              - id: "17"
                label: "Java 17"
                deprecated: true
                recommendedBootVersion: "3.2.5"
              - id: "21"
            bootVersions:
              - id: "3.2.5"
                label: "Spring Boot 3.2.5"
                official: false
                supportedJavaVersions: ["17", "21"]
                starters: ["web"]
                frameworkParent:
                  groupId: "com.example"
                  artifactId: "framework-parent"
                  version: "1.0.0"
            """;

    // ==================== metadata ====================

    @Test
    void parseMetadata_mapsAllColumns() {
        var snapshot = codec.parseMetadata(METADATA, true);

        assertThat(snapshot.javaVersions()).hasSize(2);
        assertThat(snapshot.javaVersions().get(0).getVersionId()).isEqualTo("17");
        assertThat(snapshot.javaVersions().get(0).getDeprecated()).isTrue();
        assertThat(snapshot.javaVersions().get(0).getRecommendedBootVersion()).isEqualTo("3.2.5");
        // label 缺省兜底
        assertThat(snapshot.javaVersions().get(1).getLabel()).isEqualTo("Java 21");

        assertThat(snapshot.bootVersions()).hasSize(1);
        var boot = snapshot.bootVersions().get(0);
        assertThat(boot.getVersionId()).isEqualTo("3.2.5");
        assertThat(boot.getOfficial()).isFalse();
        assertThat(boot.getFpGroup()).isEqualTo("com.example");
        assertThat(boot.getFpArtifact()).isEqualTo("framework-parent");
        assertThat(boot.getFpVersion()).isEqualTo("1.0.0");
        // 父行占位主键为负数，子行据此引用
        assertThat(boot.getId()).isNegative();

        assertThat(snapshot.bootJava()).hasSize(2);
        assertThat(snapshot.bootJava()).extracting("javaVersionId").containsExactly("17", "21");
        assertThat(snapshot.bootStarterRefs()).extracting("starterId").containsExactly("web");
    }

    @Test
    void parseMetadata_blankOrNull_returnsEmptySnapshot() {
        assertThat(codec.parseMetadata(null, true).isEmpty()).isTrue();
        assertThat(codec.parseMetadata("   ", true).isEmpty()).isTrue();
    }

    @Test
    void parseMetadata_draftMode_toleratesDanglingAndDuplicates() {
        // 草稿宽松：悬空 Java 引用与重复 ID 允许落库，语义校验留给发布期
        String yaml = """
                javaVersions:
                  - id: "17"
                  - id: "17"
                bootVersions:
                  - id: "3.2.5"
                    supportedJavaVersions: ["ghost"]
                """;
        assertThat(codec.parseMetadata(yaml, true).bootJava()).extracting("javaVersionId")
                .containsExactly("ghost");
    }

    @Test
    void parseMetadata_strictMode_rejectsDanglingAndDuplicate() {
        assertThatThrownBy(() -> codec.parseMetadata("javaVersions:\n  - id: \"17\"\n"
                + "bootVersions:\n  - id: \"3.2.5\"\n    supportedJavaVersions: [\"ghost\"]\n", false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_VALIDATION_FAILED))
                .hasMessageContaining("未定义的 Java 版本");

        assertThatThrownBy(() -> codec.parseMetadata("javaVersions:\n  - id: \"17\"\n  - id: \"17\"\n"
                + "bootVersions:\n  - id: \"3.2.5\"\n    supportedJavaVersions: [\"17\"]\n", false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("Java 版本 ID 重复"));

        // 两个 Boot 版本行携带相同的业务 ID（YAML 层是两个元素，结构层 ID 才是冲突点）
        assertThatThrownBy(() -> codec.parseMetadata("javaVersions:\n  - id: \"17\"\n"
                + "bootVersions:\n  - id: \"3.2.5\"\n    supportedJavaVersions: [\"17\"]\n"
                + "  - id: \"3.2.5\"\n    label: dup\n", false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("Boot 版本 ID 重复"));
    }

    @Test
    void parseMetadata_strictMode_validatesWhitelistAgainstArchive() {
        String yaml = """
                javaVersions:
                  - id: "17"
                bootVersions:
                  - id: "3.2.5"
                    supportedJavaVersions: ["17"]
                    starters: ["ghost"]
                """;
        assertThatThrownBy(() -> codec.parseMetadata(yaml, false, Set.of("web")))
                .hasMessageContaining("白名单引用了归档内不存在的 starter");
        // 白名单命中归档内条目 → 通过
        assertThat(codec.parseMetadata(yaml, false, Set.of("ghost")).bootStarterRefs()).hasSize(1);
    }

    @Test
    void parseMetadata_rejectsStructuralGarbage() {
        // 缺少 javaVersions 节
        assertThatThrownBy(() -> codec.parseMetadata("javaVersions: []\nbootVersions: []\n", true))
                .hasMessageContaining("javaVersions");
        // 顶层非映射：文档是列表 → 字段访问类型转换失败（同样收敛为 4009 语义）
        assertThatThrownBy(() -> codec.parseMetadata("- a\n- b\n", true))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_VALIDATION_FAILED));
        // 空文档
        assertThatThrownBy(() -> codec.parseMetadata("# only comment\n", true))
                .hasMessageContaining("内容为空");
        // 类型非法
        assertThatThrownBy(() -> codec.parseMetadata("javaVersions: {}\nbootVersions: []\n", true))
                .hasMessageContaining("类型非法");
        // YAML 语法错误
        assertThatThrownBy(() -> codec.parseMetadata("javaVersions: [\n", true))
                .hasMessageContaining("解析失败");
        // supportedJavaVersions 非列表
        assertThatThrownBy(() -> codec.parseMetadata("""
                javaVersions:
                  - id: "17"
                bootVersions:
                  - id: "3.2.5"
                    supportedJavaVersions: "17"
                """, true))
                .hasMessageContaining("必须为列表");
        // frameworkParent 三字段不齐
        assertThatThrownBy(() -> codec.parseMetadata("""
                javaVersions:
                  - id: "17"
                bootVersions:
                  - id: "3.2.5"
                    supportedJavaVersions: ["17"]
                    frameworkParent:
                      groupId: "g"
                """, true))
                .hasMessageContaining("frameworkParent 字段不完整");
        // 白名单重复
        assertThatThrownBy(() -> codec.parseMetadata("""
                javaVersions:
                  - id: "17"
                bootVersions:
                  - id: "3.2.5"
                    supportedJavaVersions: ["17"]
                    starters: ["web", "web"]
                """, true))
                .hasMessageContaining("白名单存在重复项");
        // Java 版本 id 缺失
        assertThatThrownBy(() -> codec.parseMetadata("""
                javaVersions:
                  - label: "Java 17"
                bootVersions: []
                """, true))
                .hasMessageContaining("javaVersions[].id");
    }

    // ==================== starter ====================

    @Test
    void parseStarters_mapsAllColumnsIncludingHidden() {
        String yaml = """
                id: redis
                category: middleware
                maintainer: internal
                description: 内部 Redis
                required: true
                hidden: true
                dependencies:
                  - groupId: com.company
                    artifactId: redis-starter
                    version: "1.2.0"
                requires:
                  - [web, mvc]
                  - [cache]
                bootVersions: ["3.2.5"]
                linkages:
                  config-package: "{basePackage}.config"
                  templates:
                    - source: t.ftl
                      target: src/T.java
                  application-yml-fragments:
                    - source: f.yml
                      merge-strategy: override
                  abstract-classes:
                    - fqcn: com.x.Base
                      alias: base
                """;
        var snapshot = codec.parseStarters(yaml, ConfigRepository.SOURCE_MANUAL, true);

        assertThat(snapshot.starters()).hasSize(1);
        var row = snapshot.starters().get(0);
        assertThat(row.getStarterId()).isEqualTo("redis");
        assertThat(row.getRequired()).isTrue();
        assertThat(row.getHidden()).isTrue();
        assertThat(row.getConfigPackage()).isEqualTo("{basePackage}.config");
        assertThat(row.getSource()).isEqualTo(ConfigRepository.SOURCE_MANUAL);
        assertThat(row.getId()).isNegative();

        assertThat(snapshot.starterDependencies()).hasSize(1);
        assertThat(snapshot.starterDependencies().get(0).getVersion()).isEqualTo("1.2.0");
        assertThat(snapshot.starterRequireGroups()).hasSize(2);
        assertThat(snapshot.starterRequireItems()).extracting("requiredStarterId")
                .containsExactly("web", "mvc", "cache");
        assertThat(snapshot.starterBootVersions()).extracting("bootVersionId").containsExactly("3.2.5");
        assertThat(snapshot.starterLinkageTemplates()).extracting("source").containsExactly("t.ftl");
        assertThat(snapshot.starterLinkageYmls().get(0).getMergeStrategy()).isEqualTo("OVERRIDE");
        assertThat(snapshot.starterLinkageAbstracts().get(0).getFqcn()).isEqualTo("com.x.Base");
    }

    @Test
    void parseStarters_multiDocumentAndBlankInput() {
        assertThat(codec.parseStarters(null, ConfigRepository.SOURCE_MANUAL, true).isEmpty()).isTrue();
        assertThat(codec.parseStarters("", ConfigRepository.SOURCE_MANUAL, true).isEmpty()).isTrue();

        var snapshot = codec.parseStarters("""
                id: a
                category: web
                maintainer: official
                description: d
                dependencies:
                  - groupId: g
                    artifactId: a
                ---
                id: b
                category: web
                maintainer: official
                description: d
                dependencies:
                  - groupId: g
                    artifactId: b
                """, ConfigRepository.SOURCE_SYNC, true);
        assertThat(snapshot.starters()).extracting("starterId").containsExactly("a", "b");
        assertThat(snapshot.starters()).extracting("source").containsOnly(ConfigRepository.SOURCE_SYNC);
    }

    @Test
    void parseStarters_rejectsDuplicateIdAndStructuralGarbage() {
        String one = """
                id: dup
                category: web
                maintainer: official
                description: d
                dependencies:
                  - groupId: g
                    artifactId: a
                """;
        assertThatThrownBy(() -> codec.parseStarters(one + "---\n" + one, ConfigRepository.SOURCE_MANUAL, true))
                .hasMessageContaining("starter ID 重复");

        // 缺 maintainer
        assertThatThrownBy(() -> codec.parseStarters(
                "id: x\ncategory: web\ndescription: d\n", ConfigRepository.SOURCE_MANUAL, true))
                .hasMessageContaining("缺少必需字段");

        // bootVersions 重复
        assertThatThrownBy(() -> codec.parseStarters("""
                id: x
                category: web
                maintainer: official
                description: d
                dependencies:
                  - groupId: g
                    artifactId: a
                bootVersions: ["3.2.5", "3.2.5"]
                """, ConfigRepository.SOURCE_MANUAL, true))
                .hasMessageContaining("bootVersions 存在重复项");
    }

    @Test
    void parseStarters_strictMode_rejectsDanglingRequires() {
        String yaml = """
                id: x
                category: web
                maintainer: official
                description: d
                dependencies:
                  - groupId: g
                    artifactId: a
                requires: [ghost]
                """;
        assertThatThrownBy(() -> codec.parseStarters(yaml, ConfigRepository.SOURCE_MANUAL, false))
                .hasMessageContaining("starter 清单非法");
    }

    // ==================== 冲突规则 ====================

    @Test
    void parseConflictRules_mapsRulesAndMembers() {
        var snapshot = codec.parseConflictRules("""
                rules:
                  - id: r1
                    type: pair
                    starters: [a, b]
                    message: 互斥
                  - id: r2
                    type: group
                    starters: [c, d, e]
                    message: 组互斥
                """, true);
        assertThat(snapshot.conflictRules()).extracting("ruleId").containsExactlyInAnyOrder("r1", "r2");
        assertThat(snapshot.conflictRules()).extracting("ruleType")
                .containsExactlyInAnyOrder("PAIR", "GROUP");
        assertThat(snapshot.conflictRules()).allSatisfy(row -> assertThat(row.getId()).isNegative());
        assertThat(snapshot.conflictRuleStarters()).extracting("starterId")
                .containsExactlyInAnyOrder("a", "b", "c", "d", "e");
    }

    @Test
    void parseConflictRules_blankAndInvalid() {
        assertThat(codec.parseConflictRules(null, true).isEmpty()).isTrue();
        assertThat(codec.parseConflictRules("  ", true).isEmpty()).isTrue();
        // pair 类型成员数不是 2 → 解析器拒绝
        assertThatThrownBy(() -> codec.parseConflictRules("""
                rules:
                  - id: r
                    type: pair
                    starters: [a, b, c]
                    message: m
                """, true))
                .isInstanceOf(BusinessException.class);
    }

    // ==================== 模板 ====================

    @Test
    void parseTemplates_mapsNameContent() {
        var snapshot = codec.parseTemplates("""
                name: pom.xml.ftl
                content: "<project/>"
                ---
                name: App.java.ftl
                content: "class App {}"
                """);
        assertThat(snapshot.templates()).extracting("name")
                .containsExactly("pom.xml.ftl", "App.java.ftl");
        assertThat(snapshot.templates().get(1).getContent()).isEqualTo("class App {}");
        assertThat(snapshot.templates().get(0).getUpdatedAt()).isNotNull();
    }

    @Test
    void parseTemplates_rejectsGarbageAndSyntaxErrors() {
        assertThat(codec.parseTemplates(null).isEmpty()).isTrue();

        assertThatThrownBy(() -> codec.parseTemplates("- a\n- b\n"))
                .hasMessageContaining("顶层必须为映射结构");
        assertThatThrownBy(() -> codec.parseTemplates("content: x\n"))
                .hasMessageContaining("缺少必需字段: name");
        assertThatThrownBy(() -> codec.parseTemplates("name: a.ftl\n"))
                .hasMessageContaining("缺少必需字段: content");
        assertThatThrownBy(() -> codec.parseTemplates("name: a\ncontent: x\n---\nname: a\ncontent: y\n"))
                .hasMessageContaining("模板名称重复");
        // FreeMarker 语法非法（缺 </#if>）
        assertThatThrownBy(() -> codec.parseTemplates("name: b.ftl\ncontent: \"<#if x>\"\n"))
                .hasMessageContaining("模板语法非法");
        // YAML 语法错误
        assertThatThrownBy(() -> codec.parseTemplates("name: [\n"))
                .hasMessageContaining("解析失败");
        // 超长模板
        assertThatThrownBy(() -> codec.parseTemplates(
                "name: big.ftl\ncontent: \"" + "x".repeat(StructuredSnapshotCodec.MAX_TEMPLATE_LENGTH + 1) + "\"\n"))
                .hasMessageContaining("内容超长");
    }

    // ==================== 上限与工具 ====================

    @Test
    void parseTemplates_rejectsTooManyDocuments() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i <= StructuredSnapshotCodec.MAX_ITEMS; i++) {
            if (i > 0) {
                builder.append("---\n");
            }
            builder.append("name: t").append(i).append("\ncontent: x\n");
        }
        assertThatThrownBy(() -> codec.parseTemplates(builder.toString()))
                .hasMessageContaining("条目数超限");
    }

    @Test
    void parseMetadata_rejectsTooManyJavaVersions() {
        StringBuilder builder = new StringBuilder("javaVersions:\n");
        for (int i = 0; i <= StructuredSnapshotCodec.MAX_ITEMS; i++) {
            builder.append("  - id: \"").append(i).append("\"\n");
        }
        builder.append("bootVersions:\n  - id: \"3.2.5\"\n    supportedJavaVersions: [\"0\"]\n");
        assertThatThrownBy(() -> codec.parseMetadata(builder.toString(), true))
                .hasMessageContaining("条目数超限");
    }

    @Test
    void validateCrossDocument_rejectsUndeclaredStarter() {
        var metadata = codec.parseMetadata("""
                javaVersions:
                  - id: "17"
                bootVersions:
                  - id: "3.2.5"
                    supportedJavaVersions: ["17"]
                """, true);
        var starters = codec.parseStarters("""
                id: web
                category: web
                maintainer: official
                description: d
                dependencies:
                  - groupId: g
                    artifactId: a
                """, ConfigRepository.SOURCE_MANUAL, true);
        assertThatThrownBy(() -> codec.validateCrossDocument(metadata, starters))
                .hasMessageContaining("未在 metadata");
    }

    @Test
    void splitDocuments_isReusedFromRefresherAndIgnoresIndentedSeparators() {
        List<String> documents = StructuredSnapshotCodec.splitDocuments(
                "a: 1\n---\nb: |\n  ---\n  内容\n---\nc: 3\n");
        assertThat(documents).hasSize(3);
        assertThat(documents.get(1)).contains("---").contains("内容");
    }

    @Test
    void parseMaintainerOrNull_isLenientForDiagnostics() {
        assertThat(StructuredSnapshotCodec.parseMaintainerOrNull("OFFICIAL")).isNotNull();
        assertThat(StructuredSnapshotCodec.parseMaintainerOrNull(" bogus ")).isNull();
        assertThat(StructuredSnapshotCodec.parseMaintainerOrNull(null)).isNull();
        assertThat(Map.of().isEmpty()).isTrue();
    }
}
