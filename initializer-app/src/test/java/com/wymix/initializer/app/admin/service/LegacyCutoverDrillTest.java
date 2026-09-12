package com.wymix.initializer.app.admin.service;

import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.repository.LegacyYamlMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存量 YAML 迁移与切换演练（T8.1，H2 内存库，端到端）。
 *
 * <p>完整复现生产升级窗口的迁移路径并逐段断言，覆盖两条通道：
 * <ol>
 *   <li><b>路径 B｜库内直迁</b>：旧 {@code config_draft} 表存在 → 预检 → 直迁 → 发布 →
 *       <b>回退预案</b>（回滚到该发布）</li>
 *   <li><b>路径 A｜导出 → 导入</b>：直迁/发布后导出 ZIP → 清库 → 导入归档 → 再发布</li>
 * </ol>
 *
 * <p>断言重点：
 * <ul>
 *   <li>预检在迁移前后返回「需迁 / 不需迁」两种结论</li>
 *   <li>迁移后的草稿结构与旧 YAML 语义一致，且已发布快照可读回</li>
 *   <li>导出 → 导入对手工清单幂等（迁移演练的往返通道）</li>
 *   <li>回滚基线 = 迁移后发布的版本（与 §6.3 口径一致）</li>
 * </ul>
 *
 * <p>线程安全性：JUnit 单线程执行。
 */
@SpringBootTest(classes = InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:legacy-cutover-drill-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token"
})
class LegacyCutoverDrillTest {

    private static final String METADATA_YAML = """
            javaVersions:
              - id: "17"
                label: "Java 17"
            bootVersions:
              - id: "3.2.5"
                label: "Spring Boot 3.2.5"
                supportedJavaVersions: ["17"]
                starters: ["web", "other"]
            """;

    private static final String STARTER_YAML = """
            id: web
            category: web
            maintainer: official
            description: Spring Web
            dependencies:
              - groupId: org.springframework.boot
                artifactId: spring-boot-starter-web
            linkages: {}
            ---
            id: other
            category: web
            maintainer: official
            description: 另一 starter
            dependencies:
              - groupId: com.example
                artifactId: other
            linkages: {}
            """;

    private static final String CONFLICT_YAML = """
            rules:
              - id: web-vs-other
                type: pair
                starters: ["web", "other"]
                message: 二者互斥
            """;

    @Autowired
    private LegacyYamlMigration migration;

    @Autowired
    private ConfigRepository repository;

    @Autowired
    private ConfigService configService;

    @Autowired
    private ConfigImportExportService importExportService;

    @Autowired
    private DataSource dataSource;

    /** 每个用例前重建旧表（模拟升级前旧库）并清空新库草稿与空冲突规则。 */
    @BeforeEach
    void prepare() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS config_draft");
            statement.execute("CREATE TABLE config_draft ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, kind VARCHAR(32) NOT NULL, "
                    + "content_yaml CLOB, version INT NOT NULL DEFAULT 0, "
                    + "updated_by VARCHAR(64), updated_at TIMESTAMP)");
        }
        for (ConfigKind kind : ConfigKind.values()) {
            repository.clearDraftKind(kind);
        }
    }

    private void insertLegacy(String kind, String yaml) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO config_draft (kind, content_yaml, version) VALUES ("
                    + "'" + kind + "', '" + yaml.replace("'", "''") + "', 0)");
        }
    }

    /**
     * 演练主链：旧库探测 → 直迁 → 发布 → 导出 → 清库 → 导入 → 再发布 → 回滚。
     */
    @Test
    void drill_libraryDirectMigrationThenArchiveRoundTripThenRollback() throws Exception {
        // ---- 0. 升级前：旧库只有 YAML 草稿 ----
        insertLegacy("METADATA", METADATA_YAML);
        insertLegacy("STARTER", STARTER_YAML);
        insertLegacy("CONFLICT_RULE", CONFLICT_YAML);

        // ---- 1. 预检：旧表存在且需迁移 ----
        Map<String, Object> probeBefore = migration.probe();
        assertThat(probeBefore).containsEntry("legacyTableExists", true)
                .containsEntry("migrationRequired", true).containsEntry("rows", 3);

        // ---- 2. 库内直迁（路径 B）----
        Map<String, Integer> written = migration.migrateFromLegacyDraftTable("ops");
        assertThat(written).containsKeys("METADATA", "STARTER", "CONFLICT_RULE");
        assertThat(repository.readDraftScope(ConfigKind.METADATA).javaVersions()).hasSize(1);
        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters())
                .extracting("starterId").containsExactlyInAnyOrder("web", "other");

        // 迁移留痕（审计可追溯）
        assertThat(configService.listAudits())
                .anyMatch(audit -> LegacyYamlMigration.ACTION_MIGRATE.equals(audit.getAction()));

        // ---- 3. 迁移后发布一版 = 回滚基线 ----
        ConfigService.PublishResult baseline = configService.publish("ops", "迁移后首版");
        long baselineId = baseline.releaseId();
        assertThat(baselineId).isPositive();
        assertThat(repository.readScope(baselineId).javaVersions()).hasSize(1);

        // ---- 4. 导出归档（路径 A 的上半段）----
        ConfigImportExportService.ExportResult exported = importExportService.export("ops");
        assertThat(exported.content()).isNotEmpty();
        assertThat(exported.summary()).contains("Java").contains("starter");

        // ---- 5. 清空草稿后导入归档（模拟新库/重放）----
        for (ConfigKind kind : ConfigKind.values()) {
            repository.clearDraftKind(kind);
        }
        ConfigImportExportService.ImportResult imported = importExportService.importArchive(
                new ByteArrayInputStream(exported.content()), "ops", null);
        assertThat(imported.rows()).isPositive();
        assertThat(repository.readDraftScope(ConfigKind.METADATA).javaVersions()).hasSize(1);
        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters()).hasSize(2);

        // ---- 6. 再发布一版，并回滚到迁移后的基线版本 ----
        ConfigService.PublishResult second = configService.publish("ops", "导入后发布");
        assertThat(second.releaseId()).isGreaterThan(baselineId);
        ConfigService.PublishResult rollback = configService.rollback(baselineId, "ops");
        assertThat(rollback.releaseId()).isGreaterThan(second.releaseId());
        // 回滚生成的是新 release（历史快照零改写），内容仍与基线一致
        assertThat(repository.readScope(rollback.releaseId()).javaVersions()).hasSize(1);
    }

    /**
     * 回退预案口径锁定：迁移后未发布时无回滚基线；发布后该版本即为回滚点。
     */
    @Test
    void drill_rollbackBaselineIsPostMigrationRelease() throws Exception {
        insertLegacy("METADATA", METADATA_YAML);
        migration.migrateFromLegacyDraftTable("ops");

        ConfigService.PublishResult baseline = configService.publish("ops", "基线");
        List<ConfigReleaseEntity> releases = configService.listReleases();
        assertThat(releases).extracting(ConfigReleaseEntity::getId).contains(baseline.releaseId());

        // 回滚到该基线成功（历史版本正文可读 → 说明发布时已固化结构行作用域）
        ConfigService.PublishResult rollback = configService.rollback(baseline.releaseId(), "ops");
        assertThat(repository.readScope(rollback.releaseId()).javaVersions()).hasSize(1);
    }

    /**
     * 预检在「无旧表 / 旧表无有效行」两种不需迁移场景下均给出正确结论。
     */
    @Test
    void probe_reportsNotRequiredWhenNothingToMigrate() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS config_draft");
        }
        assertThat(migration.probe())
                .containsEntry("legacyTableExists", false)
                .containsEntry("migrationRequired", false)
                .containsEntry("rows", 0);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE config_draft ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, kind VARCHAR(32) NOT NULL, "
                    + "content_yaml CLOB, version INT NOT NULL DEFAULT 0, "
                    + "updated_by VARCHAR(64), updated_at TIMESTAMP)");
        }
        assertThat(migration.probe())
                .containsEntry("legacyTableExists", true)
                .containsEntry("migrationRequired", false);
    }
}
