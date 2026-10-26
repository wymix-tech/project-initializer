package com.wymix.initializer.app.admin.service;

import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.sync.CatalogSyncService;
import com.wymix.initializer.app.generator.TestTemplates;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateUpsertRequest;
import com.wymix.initializer.core.generator.BuiltInBuildTemplates;
import com.wymix.initializer.core.sync.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:reliability-regression;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.sync.mock.enabled=false"
})
class ReliabilityRegressionTest {
    @Autowired ConfigService config;
    @Autowired ConfigImportExportService archives;
    @Autowired ConfigRepository repository;
    @Autowired StructuredDraftService drafts;
    @Autowired CatalogSnapshotHolder holder;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void seed() {
        for (ConfigKind kind : ConfigKind.values()) {
            repository.clearDraftKind(kind);
        }
        config.saveDraft(ConfigKind.METADATA, """
                javaVersions:
                  - id: "17"
                    label: Java 17
                bootVersions:
                  - id: "3.2.5"
                    label: Boot
                    official: true
                    supportedJavaVersions: ["17"]
                    starters: []
                """, null, "reviewer");
        config.saveDraft(ConfigKind.TEMPLATE, TestTemplates.templateYaml(), null, "reviewer");
    }

    private Map<ConfigKind, Integer> versions() {
        return java.util.Arrays.stream(ConfigKind.values()).collect(Collectors.toMap(
                k -> k, k -> repository.findDraftMeta(k).getVersion()));
    }

    /** 模板集 YAML：仅含非构建脚本的基础模板（构建脚本交给平台内置兜底）。 */
    private static String templateYamlWithoutBuildScripts() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : TestTemplates.load().entrySet()) {
            String name = entry.getKey();
            if (name.contains("starters/") || BuiltInBuildTemplates.isBuiltIn(name)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("---\n");
            }
            sb.append("name: \"").append(name).append("\"\n");
            sb.append("content: |\n");
            for (String line : entry.getValue().split("\n", -1)) {
                sb.append("  ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    void missingBuiltInBuildScriptIsBackfilledFromPlatformTemplate() {
        // 构建脚本是平台内置基线：草稿里没有也能发布，快照用内置内容补齐
        config.saveDraft(ConfigKind.TEMPLATE, templateYamlWithoutBuildScripts(), null, "reviewer");
        config.publish("reviewer", "backfilled");
        Map<String, String> templates = holder.current().templateOverrides();
        assertThat(templates).containsKeys("pom.xml.ftl", "build.gradle.ftl", "settings.gradle.ftl");
        assertThat(templates.get("pom.xml.ftl")).contains("<project");
        assertThat(templates.get("build.gradle.ftl")).contains("org.springframework.boot");
    }

    @Test
    void builtInBuildScriptCannotBeDeletedButCanBeRestored() {
        // 内置构建脚本不允许删除
        assertThatThrownBy(() -> drafts.deleteTemplate("pom.xml.ftl", "reviewer", null))
                .hasMessageContaining("不允许删除");

        // 用户可保存为覆盖
        drafts.saveTemplate(new TemplateUpsertRequest("pom.xml.ftl", "overridden", "reviewer", null), null);
        assertThat(drafts.getTemplate("pom.xml.ftl").content()).isEqualTo("overridden");

        // 恢复内置后回到平台版本（CLOB 不保证保留尾部换行，按 trim 比较）
        drafts.restoreTemplate("pom.xml.ftl", "reviewer", null);
        String restored = drafts.getTemplate("pom.xml.ftl").content();
        assertThat(restored.trim()).isEqualTo(BuiltInBuildTemplates.contentOf("pom.xml.ftl").trim());
        assertThat(restored).contains("<project");
    }

    @Test
    void failedTransactionCommitCannotExposeUncommittedSnapshot() {
        config.publish("reviewer", "old");
        var old = holder.current();
        int releases = config.listReleases().size();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            config.publish("reviewer", "must roll back");
            assertThat(holder.current()).isSameAs(old);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("simulated commit failure");
                }
            });
            return null;
        })).hasMessageContaining("simulated commit failure");
        assertThat(holder.current()).isSameAs(old);
        assertThat(config.listReleases()).hasSize(releases);
    }

    @Test
    void importConflictRollsBackEarlierModuleVersionsAndKeepsFailureAudit() {
        byte[] archive = archives.export("reviewer").content();
        // Force a later module to differ from the first module's expected version.
        int expected = repository.findDraftMeta(ConfigKind.METADATA).getVersion();
        while (repository.findDraftMeta(ConfigKind.TEMPLATE).getVersion() == expected) {
            config.saveDraft(ConfigKind.TEMPLATE, TestTemplates.templateYaml(), null, "other");
        }
        var before = versions();
        String templates = config.getDraft(ConfigKind.TEMPLATE).contentYaml();
        assertThatThrownBy(() -> archives.importArchive(new ByteArrayInputStream(archive), "reviewer", expected))
                .hasMessageContaining("草稿已被他人修改");
        assertThat(versions()).isEqualTo(before);
        assertThat(config.getDraft(ConfigKind.TEMPLATE).contentYaml()).isEqualTo(templates);
        assertThat(config.listAudits()).anyMatch(a -> a.getAction().equals("IMPORT_FAILED"));
    }

    @Test
    void previewShowsDeletesAndRejectsConcurrentDraftEdits() {
        byte[] archive = archives.export("reviewer").content();
        config.saveDraft(ConfigKind.TEMPLATE, TestTemplates.templateYaml() +
                "---\nname: extra.txt\ncontent: delete-me\n", null, "reviewer");
        var preview = archives.previewImport(new ByteArrayInputStream(archive));
        assertThat(preview.changes()).anyMatch(c -> c.path().equals("templates/extra.txt") && c.action().equals("DELETE"));
        var before = versions();
        assertThat(versions()).isEqualTo(before);
        config.saveDraft(ConfigKind.CONFLICT_RULE, "rules: []", null, "other");
        var changed = versions();
        assertThatThrownBy(() -> archives.applyPreview(preview.previewId(), "reviewer"))
                .hasMessageContaining("草稿已变化");
        assertThat(versions()).isEqualTo(changed);
        var fresh = archives.previewImport(new ByteArrayInputStream(archive));
        archives.applyPreview(fresh.previewId(), "reviewer");
        assertThat(config.getDraft(ConfigKind.TEMPLATE).contentYaml()).doesNotContain("delete-me");
    }

    @Test
    void draftWriterWaitsUntilFrozenReleaseCommits() throws Exception {
        var published = new java.util.concurrent.CountDownLatch(1);
        var allowCommit = new java.util.concurrent.CountDownLatch(1);
        String originalReadme = repository.readScope(0).templates().stream()
                .filter(t -> t.getName().equals("README.md.ftl")).findFirst().orElseThrow().getContent();
        var releaseFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                new TransactionTemplate(transactions).execute(status -> {
                    var release = config.publish("reviewer", "concurrent publication");
                    published.countDown();
                    try {
                        if (!allowCommit.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IllegalStateException("commit latch timeout");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return release;
                }));
        assertThat(published.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var started = new java.util.concurrent.CountDownLatch(1);
        var writer = java.util.concurrent.CompletableFuture.runAsync(() -> {
            started.countDown();
            drafts.saveTemplate(new com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateUpsertRequest(
                    "README.md.ftl", "concurrent change", "other", null), null);
        });
        try {
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> writer.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
        } finally {
            allowCommit.countDown();
        }
        var release = releaseFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
        writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(repository.readScope(release.releaseId()).templates().stream()
                .filter(t -> t.getName().equals("README.md.ftl")).findFirst().orElseThrow().getContent())
                .isEqualTo(originalReadme);
        assertThat(holder.current().templateOverrides().get("README.md.ftl")).isEqualTo(originalReadme);
        assertThat(config.getDraft(ConfigKind.TEMPLATE).contentYaml()).contains("concurrent change");
    }

    @Test
    void previewReportsAddsAndUpdatesWithoutChangingDraftVersions() {
        byte[] archive = archives.export("reviewer").content();
        config.saveDraft(ConfigKind.TEMPLATE, "", null, "reviewer");
        var before = versions();
        var preview = archives.previewImport(new ByteArrayInputStream(archive));
        assertThat(preview.changes()).anyMatch(c -> c.action().equals("ADD") && c.path().equals("templates/pom.xml.ftl"));
        assertThat(versions()).isEqualTo(before);
        config.saveDraft(ConfigKind.TEMPLATE, TestTemplates.templateYaml().replace("# ${artifact}", "# changed"), null, "reviewer");
        var update = archives.previewImport(new ByteArrayInputStream(archive));
        assertThat(update.changes()).anyMatch(c -> c.action().equals("UPDATE") && c.path().equals("templates/README.md.ftl"));
    }

    @Test
    void invalidAndOversizedPreviewStreamsAreRejectedWithoutWrites() throws Exception {
        var before = versions();
        byte[] oversized = new byte[16 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> archives.previewImport(new ByteArrayInputStream(oversized)))
                .hasMessageContaining("16 MiB");
        assertThatThrownBy(() -> archives.previewImport(new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException { throw new java.io.IOException("failed stream"); }
        })).hasMessageContaining("读取归档失败");
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("templates/test.txt"));
            zip.write("test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThatThrownBy(() -> archives.previewImport(new ByteArrayInputStream(bytes.toByteArray())))
                .hasMessageContaining("metadata.yml");
        assertThatThrownBy(() -> archives.applyPreview("missing-preview", "reviewer"))
                .hasMessageContaining("预览已过期");
        assertThat(versions()).isEqualTo(before);
    }

    @Test
    void duplicateProviderIdentityAndCrossProviderStarterCollisionsAreRejected() {
        var first = new CatalogSyncService(List.of(provider("a", "alpha", false), provider("a", "beta", false)), repository, drafts);
        var second = new CatalogSyncService(List.of(provider("a", "alpha", false), provider("b", "alpha", false)), repository, drafts);
        var before = versions();
        assertThatThrownBy(() -> first.syncAll("reviewer")).hasMessageContaining("整轮未写入")
                .hasCauseInstanceOf(com.wymix.initializer.api.error.BusinessException.class);
        assertThatThrownBy(() -> second.syncAll("reviewer")).hasMessageContaining("整轮未写入")
                .hasCauseInstanceOf(com.wymix.initializer.api.error.BusinessException.class);
        assertThat(versions()).isEqualTo(before);
    }

    private CatalogSyncProvider provider(String id, String starter, boolean fail) {
        return new CatalogSyncProvider() {
            public String providerId() { return id; }
            public boolean enabled() { return true; }
            public SyncResult fetch() {
                if (fail) { throw new IllegalStateException("unavailable"); }
                return new SyncResult(id, List.of(new SyncedStarter(starter, "tool", starter,
                        "com.example", starter, "1.0")));
            }
        };
    }

    @Test
    void twoProvidersKeepUnionAndAnyFailureLeavesPreviousDraftUntouched() {
        var sync = new CatalogSyncService(List.of(provider("a", "alpha", false), provider("b", "beta", false)), repository, drafts);
        sync.syncAll("reviewer");
        String original = config.getDraft(ConfigKind.STARTER_SYNC).contentYaml();
        assertThat(original).contains("alpha").contains("beta");
        sync.syncAll("reviewer");
        assertThat(config.getDraft(ConfigKind.STARTER_SYNC).contentYaml()).isEqualTo(original);
        var broken = new CatalogSyncService(List.of(provider("a", "changed", false), provider("b", "beta", true)), repository, drafts);
        var before = versions();
        assertThatThrownBy(() -> broken.syncAll("reviewer")).hasMessageContaining("整轮未写入");
        assertThat(config.getDraft(ConfigKind.STARTER_SYNC).contentYaml()).isEqualTo(original);
        assertThat(versions()).isEqualTo(before);
    }
}
