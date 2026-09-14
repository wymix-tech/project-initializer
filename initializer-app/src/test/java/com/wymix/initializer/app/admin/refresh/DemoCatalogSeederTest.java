package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.metadata.MetadataCatalogConfig;
import com.wymix.initializer.app.starter.StarterCatalogConfig;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import com.wymix.initializer.core.starter.LinkageSpec;
import com.wymix.initializer.core.starter.TemplateLinkage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DemoCatalogSeeder} 单元/集成测试（T5.3）：
 * 内置清单与内置模板入库 → 自动发布一版；模板可达性自检；确定性全量替换语义。
 *
 * <p>用真实 H2（{@code @SpringBootTest}）验证结构行落库与发布复制，避免 mock 掉持久层语义。
 */
@SpringBootTest(classes = com.wymix.initializer.app.InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:demo-seeder-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token",
        "initializer.demo.enabled=false",
        "initializer.catalog.dir=/nonexistent-catalog-dir-for-test"
})
class DemoCatalogSeederTest {

    @Autowired
    private DemoCatalogSeeder seeder;

    @Autowired
    private ConfigRepository repository;

    private MetadataCatalog metadata;
    private StarterCatalog starters;

    @BeforeEach
    void setUp() {
        starters = new StarterCatalogConfig().starterCatalog();
        metadata = new MetadataCatalogConfig().metadataCatalog(starters, true);
    }

    @Test
    void loadClasspathTemplates_shouldReturnExactlyEightBuiltInTemplates() {
        // 内置模板集 = 基础模板 6 个 + xxx-redis 联动模板/片段 2 个（目录条目必须被排除）
        Map<String, String> templates = seeder.loadClasspathTemplates();
        assertThat(templates).containsOnlyKeys(
                "Application.java.ftl", "README.md.ftl", "application.yml.ftl",
                "build.gradle.ftl", "pom.xml.ftl", "settings.gradle.ftl",
                "starters/xxx-redis/RedisConfig.java.ftl",
                "starters/xxx-redis/application-redis.yml");
    }

    @Test
    void seedAndPublish_shouldStoreTemplatesAndCatalogThenPublishRelease() {
        int before = repository.listReleases().size();

        ConfigReleaseEntity release = seeder.seedAndPublish(metadata, starters, "test-seeder");
        assertThat(release).isNotNull();
        assertThat(release.getReleasedBy()).isEqualTo("test-seeder");
        assertThat(repository.listReleases()).hasSize(before + 1);

        // 发布快照的结构行：模板 8 个 + starter 清单 + 元数据（Java/Boot）
        var rows = repository.readScope(release.getId());
        assertThat(rows.templates()).hasSize(8);
        assertThat(rows.starters()).hasSize(starters.size());
        assertThat(rows.javaVersions()).isNotEmpty();
        assertThat(rows.bootVersions()).isNotEmpty();
        // 内置 starter 统一走 MANUAL 通道（同步层由 CatalogSyncService 单独维护）
        assertThat(rows.starters())
                .allSatisfy(row -> assertThat(row.getSource()).isEqualTo(ConfigRepository.SOURCE_MANUAL));

        // 模板全文可从发布快照读回（CLOB 落库完整）
        String pomContent = rows.templates().stream()
                .filter(row -> row.getName().equals("pom.xml.ftl"))
                .map(com.wymix.initializer.app.admin.entity.CfgTemplateEntity::getContent)
                .findFirst()
                .orElseThrow();
        assertThat(pomContent).contains("modelVersion").contains("starterDependencies");

        for (ConfigKind kind : ConfigKind.values()) {
            repository.clearDraftKind(kind);
        }
        repository.deleteScope(release.getId());
    }

    @Test
    void seedAndPublish_shouldBeDeterministicFullReplacement() {
        // 第一次入库后人为改坏草稿模板，再次入库必须整体还原（不增量、不残留）
        ConfigReleaseEntity first = seeder.seedAndPublish(metadata, starters, "test-seeder");
        assertThat(first).isNotNull();

        var broken = com.wymix.initializer.app.admin.repository.StructuredSnapshot
                .empty(ConfigRepository.DRAFT_RELEASE_ID);
        com.wymix.initializer.app.admin.entity.CfgTemplateEntity row =
                new com.wymix.initializer.app.admin.entity.CfgTemplateEntity();
        row.setName("garbage.ftl");
        row.setContent("garbage");
        row.setSort(0);
        row.setUpdatedAt(java.time.Instant.now());
        broken.templates().add(row);
        repository.replaceDraftTemplates(broken);

        ConfigReleaseEntity second = seeder.seedAndPublish(metadata, starters, "test-seeder");
        assertThat(second).isNotNull();
        var rows = repository.readScope(second.getId());
        assertThat(rows.templates()).hasSize(8);
        assertThat(rows.templates()).noneSatisfy(t -> assertThat(t.getName()).isEqualTo("garbage.ftl"));

        for (ConfigKind kind : ConfigKind.values()) {
            repository.clearDraftKind(kind);
        }
        repository.deleteScope(first.getId());
        repository.deleteScope(second.getId());
    }

    @Test
    void verifyTemplateReachability_missingTemplate_throws9002WithMissingDetail() {
        StarterCatalogEntry broken = new StarterCatalogEntry("broken-starter", "tool",
                Maintainer.INTERNAL, "引用缺失模板的 starter",
                List.of(new com.wymix.initializer.core.starter.DependencyCoord("com.company", "broken", null)),
                new LinkageSpec(null,
                        List.of(new TemplateLinkage("starters/no-such/NoSuch.ftl", "target.java")),
                        List.of(), List.of()),
                false, List.of(), List.of(), false);
        StarterCatalog catalog = StarterCatalog.of(List.of(broken));

        assertThatThrownBy(() -> seeder.verifyTemplateReachability(catalog))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID);
                    assertThat(e.getMessage()).contains("starters/no-such/NoSuch.ftl");
                });
    }

    @Test
    void verifyTemplateReachability_builtInCatalog_passes() {
        seeder.verifyTemplateReachability(starters);
    }
}
