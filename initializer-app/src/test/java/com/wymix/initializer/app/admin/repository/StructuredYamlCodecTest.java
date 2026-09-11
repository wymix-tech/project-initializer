package com.wymix.initializer.app.admin.repository;

import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.entity.CfgBootJavaEntity;
import com.wymix.initializer.app.admin.entity.CfgBootStarterRefEntity;
import com.wymix.initializer.app.admin.entity.CfgBootVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgConflictRuleEntity;
import com.wymix.initializer.app.admin.entity.CfgConflictRuleStarterEntity;
import com.wymix.initializer.app.admin.entity.CfgJavaVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterDependencyEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageAbstractEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageTemplateEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageYmlEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterRequireGroupEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterRequireItemEntity;
import com.wymix.initializer.app.admin.entity.CfgTemplateEntity;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StructuredYamlCodec} 往返测试（结构化改造 T1）：
 * 「YAML → 结构行 → YAML」的语义保持性，以及各 kind 的空值语义。
 */
@SpringBootTest(classes = InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:codec-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token"
})
class StructuredYamlCodecTest {

    /** 覆盖元数据全部可选字段的输入（含兼容矩阵、白名单与框架父 POM）。 */
    private static final String METADATA_YAML = """
            javaVersions:
              - id: "17"
                label: "Java 17"
                deprecated: true
                recommendedBootVersion: "3.2.5"
            bootVersions:
              - id: "3.2.5"
                label: "Spring Boot 3.2.5"
                official: true
                supportedJavaVersions: ["17"]
                starters: ["web"]
                frameworkParent:
                  groupId: "com.company"
                  artifactId: "parent"
                  version: "1.0.0"
            """;

    /** 覆盖依赖、OR 分组、Boot 关联与三类联动的 starter 输入。 */
    private static final String STARTER_YAML = """
            id: web
            category: web
            maintainer: official
            description: Spring Web
            required: true
            dependencies:
              - groupId: org.springframework.boot
                artifactId: spring-boot-starter-web
            requires: [[a, b], [c]]
            bootVersions: ["3.2.5"]
            linkages:
              config-package: "{basePackage}.config"
              templates:
                - source: "starters/x/Config.java.ftl"
                  target: "src/main/java/{packagePath}/Config.java"
              application-yml-fragments:
                - source: "starters/x/app.yml"
                  merge-strategy: override
              abstract-classes:
                - fqcn: "com.company.Support"
                  alias: support
            """;

    private static final String CONFLICT_YAML = """
            rules:
              - id: web-vs-web2
                type: group
                starters: ["web", "web2", "web3"]
                message: 三者互斥
            """;

    private static final String TEMPLATE_YAML = """
            name: README.md.ftl
            content: |
              # 自定义说明
              ${group} / ${artifact}
            """;

    @Autowired
    private StructuredDraftWriter writer;

    @Autowired
    private StructuredYamlCodec codec;

    @Autowired
    private ConfigRepository repository;

    @Test
    void metadataRoundTrip_preservesAllOptionalFields() {
        int rows = writer.write(ConfigKind.METADATA, METADATA_YAML);
        assertThat(rows).isPositive();

        String rendered = codec.renderMetadata(repository.readDraftScope(ConfigKind.METADATA));
        assertThat(rendered)
                .contains("deprecated: true")
                .contains("recommendedBootVersion: 3.2.5")
                .contains("- '17'")
                .contains("frameworkParent")
                .contains("com.company")
                .contains("3.2.5");
    }

    @Test
    void starterRoundTrip_preservesDependenciesRequiresAndLinkages() {
        writer.write(ConfigKind.STARTER, STARTER_YAML);

        String rendered = codec.renderStarters(repository.readDraftScope(ConfigKind.STARTER));
        assertThat(rendered)
                .contains("id: web")
                .contains("maintainer: official")
                .contains("required: true")
                .contains("spring-boot-starter-web")
                .contains("requires")
                .contains("- a")
                .contains("bootVersions")
                .contains("config-package")
                .contains("application-yml-fragments")
                .contains("merge-strategy: override")
                .contains("abstract-classes")
                .contains("com.company.Support");
    }

    @Test
    void starterRoundTrip_rendersMultiDocumentWithSeparator() {
        writer.write(ConfigKind.STARTER, STARTER_YAML + "\n---\n" + STARTER_YAML.replace("id: web", "id: web2"));

        String rendered = codec.renderStarters(repository.readDraftScope(ConfigKind.STARTER));
        // 多文档必须以行首 --- 分隔，否则重复键覆盖会静默丢条目
        assertThat(rendered).contains("---");
        assertThat(rendered).contains("id: web").contains("id: web2");
    }

    @Test
    void conflictRuleRoundTrip_preservesMembersInOrder() {
        writer.write(ConfigKind.CONFLICT_RULE, CONFLICT_YAML);

        String rendered = codec.renderConflictRules(repository.readDraftScope(ConfigKind.CONFLICT_RULE));
        assertThat(rendered)
                .contains("id: web-vs-web2")
                .contains("type: group")
                .contains("三者互斥");
        assertThat(rendered.indexOf("web2")).isLessThan(rendered.indexOf("web3"));
    }

    @Test
    void templateRoundTrip_preservesContent() {
        writer.write(ConfigKind.TEMPLATE, TEMPLATE_YAML);

        String rendered = codec.renderTemplates(repository.readDraftScope(ConfigKind.TEMPLATE));
        assertThat(rendered).contains("README.md.ftl").contains("自定义说明");
    }

    @Test
    void render_emptyScopes_returnsNull() {
        repository.clearDraftKind(ConfigKind.METADATA);
        repository.clearDraftKind(ConfigKind.STARTER);
        repository.clearDraftKind(ConfigKind.CONFLICT_RULE);
        repository.clearDraftKind(ConfigKind.TEMPLATE);

        assertThat(codec.renderMetadata(repository.readDraftScope(ConfigKind.METADATA))).isNull();
        assertThat(codec.renderStarters(repository.readDraftScope(ConfigKind.STARTER))).isNull();
        assertThat(codec.renderConflictRules(repository.readDraftScope(ConfigKind.CONFLICT_RULE))).isNull();
        assertThat(codec.renderTemplates(repository.readDraftScope(ConfigKind.TEMPLATE))).isNull();
    }

    @BeforeEach
    void resetDrafts() {
        repository.clearDraftKind(ConfigKind.METADATA);
        repository.clearDraftKind(ConfigKind.STARTER);
        repository.clearDraftKind(ConfigKind.STARTER_SYNC);
        repository.clearDraftKind(ConfigKind.CONFLICT_RULE);
        repository.clearDraftKind(ConfigKind.TEMPLATE);
    }

    @Test
    void startersOfSource_filtersByChannel() {
        writer.write(ConfigKind.STARTER, STARTER_YAML);
        writer.write(ConfigKind.STARTER_SYNC, STARTER_YAML.replace("id: web", "id: synced"));

        StructuredSnapshot all = repository.readScope(ConfigRepository.DRAFT_RELEASE_ID);
        assertThat(StructuredYamlCodec.startersOfSource(all, ConfigRepository.SOURCE_MANUAL))
                .extracting(CfgStarterEntity::getStarterId).containsExactly("web");
        assertThat(StructuredYamlCodec.startersOfSource(all, ConfigRepository.SOURCE_SYNC))
                .extracting(CfgStarterEntity::getStarterId).containsExactly("synced");
        assertThat(StructuredYamlCodec.starterIds(all)).containsExactlyInAnyOrder("web", "synced");
    }

    @Test
    void sanityCheck_detectsDanglingStarterReference() {
        StructuredSnapshot broken = StructuredSnapshot.empty(0L);
        CfgStarterDependencyEntity dep = new CfgStarterDependencyEntity();
        dep.setStarterPk(999L);
        broken.starterDependencies().add(dep);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> codec.sanityCheck(broken))
                .isInstanceOf(com.wymix.initializer.api.error.BusinessException.class);
    }

    @Test
    void sanityCheck_passesForConsistentSnapshot() {
        StructuredSnapshot ok = StructuredSnapshot.empty(0L);
        CfgStarterEntity starter = new CfgStarterEntity();
        starter.setId(1L);
        ok.starters().add(starter);
        CfgStarterDependencyEntity dep = new CfgStarterDependencyEntity();
        dep.setStarterPk(1L);
        ok.starterDependencies().add(dep);
        CfgConflictRuleEntity rule = new CfgConflictRuleEntity();
        rule.setId(2L);
        ok.conflictRules().add(rule);
        CfgConflictRuleStarterEntity member = new CfgConflictRuleStarterEntity();
        member.setRulePk(2L);
        ok.conflictRuleStarters().add(member);

        codec.sanityCheck(ok);
    }

    @Test
    void metadataRendering_ignoresJavaRowsWithoutBootVersions() {
        StructuredSnapshot onlyJava = StructuredSnapshot.empty(0L);
        CfgJavaVersionEntity java = new CfgJavaVersionEntity();
        java.setVersionId("21");
        java.setLabel("Java 21");
        onlyJava.javaVersions().add(java);

        assertThat(codec.renderMetadata(onlyJava)).contains("javaVersions").contains("21");
    }

    @Test
    void bootVersionRendering_defaultsOfficialWhenNull() {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(0L);
        CfgBootVersionEntity boot = new CfgBootVersionEntity();
        boot.setId(1L);
        boot.setVersionId("3.2.5");
        snapshot.bootVersions().add(boot);
        snapshot.bootStarterRefs().add(bootRef(1L, "web"));

        String rendered = codec.renderMetadata(snapshot);
        assertThat(rendered).contains("official: true").contains("web");
    }

    @Test
    void starterRendering_omitsEmptyOptionalNodes() {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(0L);
        CfgStarterEntity starter = new CfgStarterEntity();
        starter.setId(1L);
        starter.setStarterId("web");
        starter.setCategory("web");
        starter.setMaintainer("OFFICIAL");
        snapshot.starters().add(starter);

        String rendered = codec.renderStarters(snapshot);
        assertThat(rendered).doesNotContain("requires").doesNotContain("bootVersions").doesNotContain("required");
    }

    @Test
    void linkageRendering_skipsBlankConfigPackage() {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(0L);
        CfgStarterEntity starter = new CfgStarterEntity();
        starter.setId(1L);
        starter.setStarterId("web");
        starter.setCategory("web");
        starter.setMaintainer("OFFICIAL");
        starter.setConfigPackage("  ");
        snapshot.starters().add(starter);
        CfgStarterLinkageYmlEntity yml = new CfgStarterLinkageYmlEntity();
        yml.setStarterPk(1L);
        yml.setSource("a.yml");
        snapshot.starterLinkageYmls().add(yml);

        String rendered = codec.renderStarters(snapshot);
        assertThat(rendered).doesNotContain("config-package");
        assertThat(rendered).contains("merge-strategy: append");
    }

    @Test
    void linkageRendering_keepsTemplateAndAbstractNodes() {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(0L);
        CfgStarterEntity starter = new CfgStarterEntity();
        starter.setId(1L);
        starter.setStarterId("web");
        starter.setCategory("web");
        starter.setMaintainer("OFFICIAL");
        snapshot.starters().add(starter);
        CfgStarterLinkageTemplateEntity template = new CfgStarterLinkageTemplateEntity();
        template.setStarterPk(1L);
        template.setSource("s.ftl");
        template.setTarget("t.java");
        snapshot.starterLinkageTemplates().add(template);
        CfgStarterLinkageAbstractEntity abstractClass = new CfgStarterLinkageAbstractEntity();
        abstractClass.setStarterPk(1L);
        abstractClass.setFqcn("com.x.Y");
        abstractClass.setAlias("y");
        snapshot.starterLinkageAbstracts().add(abstractClass);

        String rendered = codec.renderStarters(snapshot);
        assertThat(rendered).contains("templates").contains("abstract-classes").contains("com.x.Y");
    }

    @Test
    void metadataRendering_skipsIncompleteFrameworkParent() {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(0L);
        CfgBootVersionEntity boot = new CfgBootVersionEntity();
        boot.setId(1L);
        boot.setVersionId("3.2.5");
        boot.setFpGroup("only-group");
        snapshot.bootVersions().add(boot);

        assertThat(codec.renderMetadata(snapshot)).doesNotContain("frameworkParent");
    }

    @Test
    void requiresRendering_respectsGroupAndItemOrder() {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(0L);
        CfgStarterEntity starter = new CfgStarterEntity();
        starter.setId(1L);
        starter.setStarterId("web");
        starter.setCategory("web");
        starter.setMaintainer("OFFICIAL");
        snapshot.starters().add(starter);
        CfgStarterRequireGroupEntity group1 = group(1L, 1L, 1);
        CfgStarterRequireGroupEntity group0 = group(2L, 1L, 0);
        snapshot.starterRequireGroups().add(group1);
        snapshot.starterRequireGroups().add(group0);
        snapshot.starterRequireItems().addAll(List.of(item(1L, "b", 1), item(1L, "a", 0), item(2L, "c", 0)));

        String rendered = codec.renderStarters(snapshot);
        // groupIndex=0 的组（含 c）必须出现在 groupIndex=1 的组（含 b）之前
        // groupIndex 定序：group0（含 c）渲染在 group1（含 b）之前
        assertThat(rendered).contains("c").contains("b");
        assertThat(rendered.indexOf("- c")).isLessThan(rendered.indexOf("- b"));
    }

    private static CfgBootStarterRefEntity bootRef(long bootPk, String starterId) {
        CfgBootStarterRefEntity ref = new CfgBootStarterRefEntity();
        ref.setBootPk(bootPk);
        ref.setStarterId(starterId);
        return ref;
    }

    private static CfgStarterRequireGroupEntity group(long id, long starterPk, int index) {
        CfgStarterRequireGroupEntity group = new CfgStarterRequireGroupEntity();
        group.setId(id);
        group.setStarterPk(starterPk);
        group.setGroupIndex(index);
        return group;
    }

    private static CfgStarterRequireItemEntity item(long groupPk, String starterId, int sort) {
        CfgStarterRequireItemEntity item = new CfgStarterRequireItemEntity();
        item.setGroupPk(groupPk);
        item.setRequiredStarterId(starterId);
        item.setSort(sort);
        return item;
    }
}
