package com.wymix.initializer.app.admin.repository;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.entity.CfgStarterEntity;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StructuredDraftWriter} 集成测试（结构化改造 T1）：
 * YAML 语义文本 → 结构行的落库正确性、草稿态宽松校验与存量迁移入口。
 */
@SpringBootTest(classes = InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:draft-writer-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token"
})
class StructuredDraftWriterTest {

    private static final String METADATA_YAML = """
            javaVersions:
              - id: "17"
                label: "Java 17"
            bootVersions:
              - id: "3.2.5"
                label: "Spring Boot 3.2.5"
                supportedJavaVersions: ["17"]
                starters: ["web"]
            """;

    private static final String STARTER_YAML = """
            id: web
            category: web
            maintainer: official
            description: Spring Web
            dependencies:
              - groupId: org.springframework.boot
                artifactId: spring-boot-starter-web
            requires: [[a, b]]
            linkages: {}
            """;

    @Autowired
    private StructuredDraftWriter writer;

    @Autowired
    private ConfigRepository repository;

    @BeforeEach
    void resetDrafts() {
        repository.clearDraftKind(ConfigKind.METADATA);
        repository.clearDraftKind(ConfigKind.STARTER);
        repository.clearDraftKind(ConfigKind.STARTER_SYNC);
        repository.clearDraftKind(ConfigKind.CONFLICT_RULE);
        repository.clearDraftKind(ConfigKind.TEMPLATE);
    }

    @Test
    void writeMetadata_persistsAllGraphRows() {
        int rows = writer.write(ConfigKind.METADATA, METADATA_YAML);
        assertThat(rows).isEqualTo(4); // 1 java + 1 boot + 1 兼容矩阵 + 1 白名单

        StructuredSnapshot loaded = repository.readDraftScope(ConfigKind.METADATA);
        assertThat(loaded.javaVersions()).hasSize(1);
        assertThat(loaded.bootVersions()).hasSize(1);
        assertThat(loaded.bootJava()).hasSize(1);
        assertThat(loaded.bootStarterRefs()).hasSize(1);
        // 兼容矩阵按业务 ID 存储（草稿态允许悬空）
        assertThat(loaded.bootJava().get(0).getJavaVersionId()).isEqualTo("17");
    }

    @Test
    void writeMetadata_toleratesDanglingJavaReference() {
        // 草稿态必须能把拼错的 Java 版本原样存下来，发布期才报 4002 明细
        String dangling = METADATA_YAML.replace("[\"17\"]", "[\"ghost\"]");
        writer.write(ConfigKind.METADATA, dangling);

        assertThat(repository.readDraftScope(ConfigKind.METADATA).bootJava())
                .extracting("javaVersionId").containsExactly("ghost");
    }

    @Test
    void writeMetadata_rejectsMalformedStructure() {
        assertThatThrownBy(() -> writer.write(ConfigKind.METADATA, "javaVersions: not-a-list\nbootVersions: []"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void writeMetadata_rejectsEmptyContent() {
        assertThatThrownBy(() -> writer.write(ConfigKind.METADATA, ""))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void writeMetadata_rejectsIncompleteFrameworkParent() {
        String incomplete = METADATA_YAML + "    frameworkParent:\n      groupId: com.x\n";
        assertThatThrownBy(() -> writer.write(ConfigKind.METADATA, incomplete))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void writeMetadata_keepsFrameworkParentWhenComplete() {
        String complete = """
                javaVersions:
                  - id: "17"
                    label: "Java 17"
                bootVersions:
                  - id: "3.2.5"
                    label: "Spring Boot 3.2.5"
                    supportedJavaVersions: ["17"]
                    frameworkParent:
                      groupId: com.x
                      artifactId: p
                      version: "1.0"
                """;
        writer.write(ConfigKind.METADATA, complete);

        var boot = repository.readDraftScope(ConfigKind.METADATA).bootVersions().get(0);
        assertThat(boot.getFpGroup()).isEqualTo("com.x");
        assertThat(boot.getFpArtifact()).isEqualTo("p");
        assertThat(boot.getFpVersion()).isEqualTo("1.0");
    }

    @Test
    void writeStarters_marksSourceChannelAndPersistsRequiresGroups() {
        int rows = writer.write(ConfigKind.STARTER, STARTER_YAML);
        assertThat(rows).isPositive();

        StructuredSnapshot loaded = repository.readDraftScope(ConfigKind.STARTER);
        assertThat(loaded.starters()).hasSize(1);
        assertThat(loaded.starters().get(0).getSource()).isEqualTo(ConfigRepository.SOURCE_MANUAL);
        assertThat(loaded.starterRequireGroups()).hasSize(1);
        assertThat(loaded.starterRequireItems()).extracting("requiredStarterId")
                .containsExactlyInAnyOrder("a", "b");
        assertThat(loaded.starterDependencies()).hasSize(1);
    }

    @Test
    void writeStartersWithSyncSource_marksSyncChannel() {
        writer.write(ConfigKind.STARTER_SYNC, STARTER_YAML);

        assertThat(repository.readDraftScope(ConfigKind.STARTER_SYNC).starters())
                .allSatisfy(row -> assertThat(row.getSource()).isEqualTo(ConfigRepository.SOURCE_SYNC));
    }

    @Test
    void writeStarters_rejectsMalformedDocument() {
        assertThatThrownBy(() -> writer.write(ConfigKind.STARTER, "id: web\ncategory: web\n"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void writeConflictRules_persistsMembers() {
        writer.write(ConfigKind.CONFLICT_RULE, """
                rules:
                  - id: r1
                    type: pair
                    starters: ["a", "b"]
                    message: 互斥
                """);

        StructuredSnapshot loaded = repository.readDraftScope(ConfigKind.CONFLICT_RULE);
        assertThat(loaded.conflictRules()).hasSize(1);
        assertThat(loaded.conflictRuleStarters()).hasSize(2);
    }

    @Test
    void writeConflictRules_rejectsUnknownType() {
        assertThatThrownBy(() -> writer.write(ConfigKind.CONFLICT_RULE,
                "rules:\n  - id: r1\n    type: hmm\n    starters: [a, b]\n    message: m\n"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void writeTemplates_persistsNameAndContent() {
        writer.write(ConfigKind.TEMPLATE, "name: README.md.ftl\ncontent: |\n  # Hello\n");

        assertThat(repository.readDraftScope(ConfigKind.TEMPLATE).templates().get(0).getName())
                .isEqualTo("README.md.ftl");
    }

    @Test
    void writeTemplates_rejectsMissingName() {
        assertThatThrownBy(() -> writer.write(ConfigKind.TEMPLATE, "content: |\n  x\n"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void writeTemplates_rejectsMissingContent() {
        assertThatThrownBy(() -> writer.write(ConfigKind.TEMPLATE, "name: x.ftl\n"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void migrateLegacyDrafts_writesAllProvidedKindsAndSkipsBlank() {
        Map<String, Integer> written = writer.migrateLegacyDrafts(new java.util.LinkedHashMap<>(Map.of(
                "METADATA", METADATA_YAML,
                "STARTER", STARTER_YAML,
                "TEMPLATE", "   ")));

        assertThat(written).containsKeys("METADATA", "STARTER");
        assertThat(written).doesNotContainKey("TEMPLATE");
        assertThat(repository.readDraftScope(ConfigKind.METADATA).javaVersions()).hasSize(1);
        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters()).hasSize(1);
        assertThat(repository.readDraftScope(ConfigKind.TEMPLATE).templates()).isEmpty();
    }

    @Test
    void migrateLegacyDrafts_replacesPreviousRows() {
        writer.write(ConfigKind.STARTER, STARTER_YAML);
        writer.migrateLegacyDrafts(Map.of("STARTER", STARTER_YAML.replace("id: web", "id: replaced")));

        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters())
                .extracting(CfgStarterEntity::getStarterId).containsExactly("replaced");
    }

    @Test
    void migrateLegacyDrafts_rejectsInvalidKindName() {
        assertThatThrownBy(() -> writer.migrateLegacyDrafts(Map.of("NOPE", METADATA_YAML)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_DRAFT_NOT_FOUND));
    }

    @Test
    void writeMetadata_handlesOptionalFieldVariants() {
        // 覆盖：缺省 label（回退生成）、deprecated 缺省、supportedJavaVersions 缺省、
        // starters 缺省、official 显式 false、frameworkParent 为 null
        String variants = """
                javaVersions:
                  - id: "21"
                bootVersions:
                  - id: "2.7.18"
                    official: false
                """;
        writer.write(ConfigKind.METADATA, variants);

        StructuredSnapshot loaded = repository.readDraftScope(ConfigKind.METADATA);
        assertThat(loaded.javaVersions().get(0).getLabel()).isEqualTo("Java 21");
        assertThat(loaded.javaVersions().get(0).getDeprecated()).isFalse();
        assertThat(loaded.bootVersions().get(0).getLabel()).isEqualTo("Spring Boot 2.7.18");
        assertThat(loaded.bootVersions().get(0).getOfficial()).isFalse();
        assertThat(loaded.bootJava()).isEmpty();
        assertThat(loaded.bootStarterRefs()).isEmpty();
    }

    @Test
    void writeMetadata_rejectsNonMapBootRow() {
        assertThatThrownBy(() -> writer.write(ConfigKind.METADATA,
                "javaVersions:\n  - id: \"17\"\nbootVersions:\n  - not-a-map\n"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void writeMetadata_rejectsNonListBootVersions() {
        assertThatThrownBy(() -> writer.write(ConfigKind.METADATA,
                "javaVersions:\n  - id: \"17\"\nbootVersions: nope\n"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void writeMetadata_rejectsMissingJavaId() {
        assertThatThrownBy(() -> writer.write(ConfigKind.METADATA,
                "javaVersions:\n  - label: x\nbootVersions:\n  - id: \"3.2.5\"\n"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void writeMetadata_rejectsNonListSupportedJavaVersions() {
        assertThatThrownBy(() -> writer.write(ConfigKind.METADATA,
                "javaVersions:\n  - id: \"17\"\nbootVersions:\n  - id: \"3.2.5\"\n"
                        + "    supportedJavaVersions: not-a-list\n"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void writeMetadata_rejectsNonMapFrameworkParent() {
        assertThatThrownBy(() -> writer.write(ConfigKind.METADATA,
                "javaVersions:\n  - id: \"17\"\nbootVersions:\n  - id: \"3.2.5\"\n"
                        + "    frameworkParent: nope\n"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void writeMetadata_rejectsInvalidYamlSyntax() {
        assertThatThrownBy(() -> writer.write(ConfigKind.METADATA, "javaVersions: [ unclosed"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void writeStarters_handlesMergeStrategyDefaultAndBlankElements() {
        String yaml = """
                id: web
                category: web
                maintainer: official
                description: d
                dependencies:
                  - groupId: g
                    artifactId: a
                bootVersions: ["3.2.5"]
                linkages:
                  application-yml-fragments:
                    - source: a.yml
                """;
        writer.write(ConfigKind.STARTER, yaml);

        StructuredSnapshot loaded = repository.readDraftScope(ConfigKind.STARTER);
        assertThat(loaded.starterLinkageYmls().get(0).getMergeStrategy()).isEqualTo("APPEND");
        assertThat(loaded.starterBootVersions()).hasSize(1);
    }

    @Test
    void writeStarters_multipleDocumentsInOneInput() {
        writer.write(ConfigKind.STARTER, STARTER_YAML + "\n---\n" + STARTER_YAML.replace("id: web", "id: web2"));

        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters()).hasSize(2);
    }

    @Test
    void writeConflictRules_blankYaml_meansNoRules() {
        assertThat(repository.readDraftScope(ConfigKind.CONFLICT_RULE).conflictRules()).isEmpty();
    }

    @Test
    void writeTemplates_multipleDocuments() {
        writer.write(ConfigKind.TEMPLATE, """
                name: a.ftl
                content: |
                  A
                ---
                name: b.ftl
                content: |
                  B
                """);

        assertThat(repository.readDraftScope(ConfigKind.TEMPLATE).templates())
                .extracting("name").containsExactly("a.ftl", "b.ftl");
    }

    @Test
    void writeTemplates_rejectsNonMapDocument() {
        assertThatThrownBy(() -> writer.write(ConfigKind.TEMPLATE, "- not-a-map\n"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void writeTemplates_rejectsInvalidYaml() {
        assertThatThrownBy(() -> writer.write(ConfigKind.TEMPLATE, "name: [unclosed\n"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void migrateLegacyDrafts_emptyMap_writesNothing() {
        assertThat(writer.migrateLegacyDrafts(java.util.Map.of())).isEmpty();
    }
}
