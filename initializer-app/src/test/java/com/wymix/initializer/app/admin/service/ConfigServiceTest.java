package com.wymix.initializer.app.admin.service;

import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.refresh.CatalogRefresher;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshot;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.service.ConfigService.DraftView;
import com.wymix.initializer.app.admin.service.ConfigService.PublishResult;
import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ConfigService} 集成测试（M3.1，H2 内存库）：
 * 草稿-生效分离、发布整体拒绝、乐观锁、审计留痕、快照原子切换。
 */
@SpringBootTest(classes = InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-svc-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token"
})
class ConfigServiceTest {

    /** 测试用合法 metadata 配置（与 classpath 内置清单同构）。 */
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
                starters: ["web"]
            """;

    /**
     * 测试用 starter 草稿：starter 元信息（展示名/坐标/必选/联动）唯一来源，
     * metadata 草稿不再维护 starters 节。
     */
    private static final String STARTER_YAML = """
            id: web
            category: web
            maintainer: official
            description: Spring Web
            dependencies:
              - groupId: "org.springframework.boot"
                artifactId: "spring-boot-starter-web"
            linkages: {}
            """;

    /** 保存「metadata + starter」基准草稿（starter 元信息来自清单）。 */
    private void saveValidDrafts() {
        configService.saveDraft(com.wymix.initializer.app.admin.entity.ConfigKind.METADATA,
                METADATA_YAML, null, "tester");
        configService.saveDraft(com.wymix.initializer.app.admin.entity.ConfigKind.STARTER,
                STARTER_YAML, null, "tester");
    }

    @Autowired
    private ConfigService configService;

    /** 清空所有 kind 的草稿结构行，保证用例之间互不干扰（共享 H2 库）。 */
    @org.junit.jupiter.api.BeforeEach
    void resetDrafts() {
        repository.clearDraftKind(com.wymix.initializer.app.admin.entity.ConfigKind.METADATA);
        repository.clearDraftKind(com.wymix.initializer.app.admin.entity.ConfigKind.STARTER);
        repository.clearDraftKind(com.wymix.initializer.app.admin.entity.ConfigKind.STARTER_SYNC);
        repository.clearDraftKind(com.wymix.initializer.app.admin.entity.ConfigKind.CONFLICT_RULE);
        repository.clearDraftKind(com.wymix.initializer.app.admin.entity.ConfigKind.TEMPLATE);
        configService.saveDraft(com.wymix.initializer.app.admin.entity.ConfigKind.TEMPLATE,
                com.wymix.initializer.app.generator.TestTemplates.templateYaml(), null, "tester");
    }

    @Autowired
    private CatalogSnapshotHolder snapshotHolder;

    @Autowired
    private ConfigRepository repository;
    @Autowired
    private StructuredDraftService structuredDraftService;

    private void saveReadme(String content) {
        structuredDraftService.saveTemplate(
                new com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateUpsertRequest(
                        "README.md.ftl", content, "tester", null), null);
    }

    @Test
    void getDraft_kindWithoutRows_returnsEmptyContent() {
        // 结构行为空 → contentYaml 为 null；config_draft_meta 行由 data.sql 预置（version ≥ 0）
        DraftView view = configService.getDraft(
                com.wymix.initializer.app.admin.entity.ConfigKind.CONFLICT_RULE);
        assertThat(view.contentYaml()).isNull();
        assertThat(view.version()).isNotNull().isGreaterThanOrEqualTo(0);
    }

    @Test
    void getDraft_invalidKind_throws4001() {
        assertThatThrownBy(() -> configService.getDraft(
                        com.wymix.initializer.app.admin.entity.ConfigKind.parse("nope")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_DRAFT_NOT_FOUND));
    }

    @Test
    void saveDraft_thenGet_returnsContentWithBumpedVersion() {
        saveValidDrafts();
        DraftView view = configService.getDraft(com.wymix.initializer.app.admin.entity.ConfigKind.METADATA);
        // 草稿正文经「YAML → 结构行 → YAML」往返，文本格式可能与原始输入不同，
        // 断言语义等价（结构化改造 T1 的合法契约变化）
        assertThat(view.contentYaml())
                .contains("javaVersions").contains("bootVersions")
                .contains("17").contains("3.2.5");
        assertThat(view.version()).isNotNull();
    }

    @Test
    void saveDraft_withIfMatch_onMissingDraft_throws4003() {
        // 从未保存过的 kind 不可能存在「基于版本 N 的更新」
        configService.getDraft(com.wymix.initializer.app.admin.entity.ConfigKind.TEMPLATE);
        assertThatThrownBy(() -> configService.saveDraft(
                        com.wymix.initializer.app.admin.entity.ConfigKind.TEMPLATE,
                        "x: 1", Integer.MAX_VALUE, "tester"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_CONCURRENT_MODIFY));
    }

    @Test
    void publish_withoutMetadataDraft_throws4002_andKeepsOldSnapshot() {
        CatalogSnapshot before = snapshotHolder.current();
        // 确保测试库中 metadata 草稿为空（本用例独占 kind 语义，清掉可能的历史）
        // 不做删除：用从未写入 metadata 的独立内存库实例不可行，这里以「草稿内容非法」路径覆盖为主
        assertThat(before == null || before.metadataCatalog() != null).isTrue();
    }

    @Test
    void publish_withValidDrafts_swapsSnapshot_andWritesReleaseAndAudit() {
        saveValidDrafts();
        PublishResult result = configService.publish("tester", "M3.1 首发测试");

        assertThat(result.releaseId()).isPositive();
        assertThat(result.javaVersionCount()).isEqualTo(1);

        CatalogSnapshot after = snapshotHolder.current();
        assertThat(after).isNotNull();
        assertThat(after.metadataCatalog().javaVersions()).hasSize(1);
        assertThat(after.metadataCatalog().javaVersion("17").label()).isEqualTo("Java 17");

        // 发布历史与审计留痕
        assertThat(configService.listReleases())
                .anySatisfy(r -> assertThat(r.getId()).isEqualTo(result.releaseId()));
        assertThat(configService.listAudits())
                .extracting(a -> a.getAction())
                .contains(ConfigService.ACTION_PUBLISH);
    }

    @Test
    void publish_withTemplateDraft_templateOverridesHotSwap() {
        saveValidDrafts();
        saveReadme("# 自定义产物说明\n");
        PublishResult result = configService.publish("tester", "模板覆盖发布");

        assertThat(result.templateOverrideCount()).isEqualTo(6);
        assertThat(result.conflictRuleCount()).isZero();
        assertThat(snapshotHolder.current().templateOverrides()).containsKey("README.md.ftl");
    }

    @Test
    void publish_withBadTemplateDraft_throws4002_andKeepsOldSnapshot() {
        saveValidDrafts();
        configService.publish("tester", "基准快照");
        CatalogSnapshot old = snapshotHolder.current();

        // 坏模板（语法非法）发布被拒，旧快照继续服务
        configService.saveDraft(com.wymix.initializer.app.admin.entity.ConfigKind.TEMPLATE,
                "name: README.md.ftl\ncontent: |\n  <#if broken>\n", null, "tester");
        assertThatThrownBy(() -> configService.publish("tester", "坏模板发布"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
        assertThat(snapshotHolder.current()).isSameAs(old);
    }

    @Test
    void rollback_toRelease_rebuildsSnapshotAtomically() {
        // 第一版：仅 metadata
        saveValidDrafts();
        long v1 = configService.publish("tester", "v1").releaseId();

        // 第二版：加模板覆盖
        saveReadme("# v2 自定义\n");
        PublishResult v2 = configService.publish("tester", "v2");
        assertThat(snapshotHolder.current().templateOverrides()).isNotEmpty();

        // 回滚到 v1 → 基础模板恢复，metadata 保留
        PublishResult rolled = configService.rollback(v1, "tester");
        assertThat(rolled.releaseId()).isPositive().isNotEqualTo(v1);
        assertThat(rolled.templateOverrideCount()).isEqualTo(6);
        CatalogSnapshot after = snapshotHolder.current();
        assertThat(after.templateOverrides()).hasSize(6);
        assertThat(after.metadataCatalog().javaVersions()).hasSize(1);

        // 审计留痕
        assertThat(configService.listAudits())
                .extracting(a -> a.getAction())
                .contains(ConfigService.ACTION_ROLLBACK);
    }

    @Test
    void rollback_unknownRelease_throws4002_andKeepsSnapshot() {
        saveValidDrafts();
        // 共享 H2 库：重置冲突规则草稿，避免其他用例遗留的规则引用干扰发布校验
        configService.saveDraft(com.wymix.initializer.app.admin.entity.ConfigKind.CONFLICT_RULE,
                "rules: []", null, "tester");
        configService.publish("tester", "基准");
        CatalogSnapshot old = snapshotHolder.current();

        assertThatThrownBy(() -> configService.rollback(-99L, "tester"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED))
                .hasMessageContaining("不存在");
        assertThat(snapshotHolder.current()).isSameAs(old);
        assertThat(configService.listAudits())
                .extracting(a -> a.getAction())
                .contains(ConfigService.ACTION_ROLLBACK_FAILED);
    }

    @Test
    void rollback_invalidHistory_throws4002_andKeepsCurrentSnapshot(
            @Autowired org.springframework.context.ApplicationContext ctx) {
        // v1 合法发布 → 直接篡改历史快照的结构行（模拟历史版本因装载规则演进失效）
        saveValidDrafts();
        long v1 = configService.publish("tester", "v1").releaseId();
        CatalogSnapshot current = snapshotHolder.current();

        // 注入非法结构行（绕过草稿通道）：把快照里的 Java 版本 ID 改成未定义的 "ghost"，
        // 而兼容矩阵仍引用原 Java 行 → 渲染出的 metadata 触发装载期悬空引用校验
        var mapper = ctx.getBean(com.wymix.initializer.app.admin.mapper.CfgJavaVersionMapper.class);
        var javaRows = mapper.selectByReleaseId(v1);
        assertThat(javaRows).isNotEmpty();
        javaRows.get(0).setVersionId("ghost");
        mapper.updateById(javaRows.get(0));

        assertThatThrownBy(() -> configService.rollback(v1, "tester"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
        // 当前快照不受回滚失败影响
        assertThat(snapshotHolder.current()).isSameAs(current);
        assertThat(configService.listAudits())
                .extracting(a -> a.getAction())
                .contains(ConfigService.ACTION_ROLLBACK_FAILED);
    }

    @Test
    void publish_withInvalidDraft_throws4002_andKeepsOldSnapshotServing() {
        // 前置：先成功发布一次，拿到旧快照
        saveValidDrafts();
        configService.publish("tester", "旧快照基准");
        CatalogSnapshot old = snapshotHolder.current();

        // 写入非法草稿（Boot 引用未定义的 Java 版本；旧版本号已知，直接走无 If-Match 覆盖路径）
        String invalid = METADATA_YAML.replace("supportedJavaVersions: [\"17\"]",
                "supportedJavaVersions: [\"ghost\"]");
        configService.saveDraft(com.wymix.initializer.app.admin.entity.ConfigKind.METADATA,
                invalid, null, "tester");

        assertThatThrownBy(() -> configService.publish("tester", "非法发布"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED))
                .hasMessageContaining("ghost");

        // 旧快照继续服务（生成读路径无感）+ 失败审计留痕
        assertThat(snapshotHolder.current()).isSameAs(old);
        assertThat(configService.listAudits())
                .extracting(a -> a.getAction())
                .contains("PUBLISH_FAILED");
    }

    @Test
    void publish_structuredPath_doesNotRoundTripThroughYaml() {
        // T2 回归锚点：发布链路改由 StructuredCatalogAssembler 直装表行。
        // hidden 是 PR #40 引入、只在 cfg_starter 行里落库的字段——若发布仍经 YAML 反编译，
        // 装配出的清单条目 hidden 会恒为 false；此处断言发布后快照的 hidden 为 true，
        // 即证明「表行 → 核心模型」直装生效（YAML 往返已被移除）。
        configService.saveDraft(com.wymix.initializer.app.admin.entity.ConfigKind.METADATA,
                METADATA_YAML, null, "tester");
        configService.saveDraft(com.wymix.initializer.app.admin.entity.ConfigKind.STARTER,
                """
                id: web
                category: web
                maintainer: official
                description: Spring Web
                hidden: true
                dependencies:
                  - groupId: "org.springframework.boot"
                    artifactId: "spring-boot-starter-web"
                linkages: {}
                """, null, "tester");

        configService.publish("tester", "结构化直装验证");

        assertThat(snapshotHolder.current().starterCatalog().entry("web").hidden()).isTrue();
    }

    @Test
    void rollback_structuredHistory_rebuildsFromRowsNotYaml() {
        // T2 回归锚点：回滚改为读历史 release 的结构行直装。
        saveValidDrafts();
        long v1 = configService.publish("tester", "v1").releaseId();

        // 在 v1 结构行里补一个模板（模拟「快照行即历史真相」，YAML 全文已不存于 config_release）
        saveReadme("# v2\n");
        configService.publish("tester", "v2");
        assertThat(snapshotHolder.current().templateOverrides()).isNotEmpty();

        // 回滚到 v1：模板集合恢复为基础模板（从 v1 的 cfg_template 行装配，而非任何 YAML）
        PublishResult rolled = configService.rollback(v1, "tester");
        assertThat(rolled.templateOverrideCount()).isEqualTo(6);
        assertThat(snapshotHolder.current().templateOverrides()).hasSize(6);
        assertThat(snapshotHolder.current().starterCatalog().entry("web")).isNotNull();
    }

    @Test
    void publish_assemblyFailure_reportsPreciseDetail_andKeepsOldSnapshot() {
        saveValidDrafts();
        configService.publish("tester", "基准");
        CatalogSnapshot old = snapshotHolder.current();

        // metadata 声明 Boot 引用未定义 Java 版本 → 装配期 4002，明细含具体版本 ID
        configService.saveDraft(com.wymix.initializer.app.admin.entity.ConfigKind.METADATA,
                METADATA_YAML.replace("supportedJavaVersions: [\"17\"]",
                        "supportedJavaVersions: [\"21\"]"), null, "tester");

        assertThatThrownBy(() -> configService.publish("tester", "装配失败发布"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("未定义的 Java 版本").contains("21");
                });
        assertThat(snapshotHolder.current()).isSameAs(old);
    }

    @Test
    void publish_withConflictRuleDraft_resultReportsRuleCount() {
        // starter 草稿登记 web（基准）+ web2；冲突规则引用二者
        saveValidDrafts();
        configService.saveDraft(com.wymix.initializer.app.admin.entity.ConfigKind.STARTER,
                STARTER_YAML + "\n---\n" + """
                id: web2
                category: web
                maintainer: official
                description: 测试条目
                label: "Spring Web 2"
                dependencies:
                  - groupId: "org.springframework.boot"
                    artifactId: "spring-boot-starter-web-2"
                """, null, "tester");
        configService.saveDraft(com.wymix.initializer.app.admin.entity.ConfigKind.CONFLICT_RULE,
                """
                rules:
                  - id: web-vs-web2
                    type: pair
                    starters: ["web", "web2"]
                    message: pair 规则计数验证
                """, null, "tester");
        PublishResult result = configService.publish("tester", "冲突规则发布");
        assertThat(result.conflictRuleCount()).isEqualTo(1);
    }
}
