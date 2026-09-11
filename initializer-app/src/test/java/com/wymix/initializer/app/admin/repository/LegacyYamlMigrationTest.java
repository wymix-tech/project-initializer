package com.wymix.initializer.app.admin.repository;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LegacyYamlMigration} 集成测试（T1.6）：旧版 {@code config_draft.content_yaml}
 * 库内直迁路径、旧表探测与失败语义。
 */
@SpringBootTest(classes = InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:legacy-migration-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token"
})
class LegacyYamlMigrationTest {

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
            linkages: {}
            """;

    @Autowired
    private LegacyYamlMigration migration;

    @Autowired
    private ConfigRepository repository;

    @Autowired
    private DataSource dataSource;

    /** 每个用例前重建旧表（模拟升级前旧库）并清空新库草稿。 */
    @BeforeEach
    void prepareLegacyTable() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS config_draft");
            statement.execute("CREATE TABLE config_draft ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, kind VARCHAR(32) NOT NULL, "
                    + "content_yaml CLOB, version INT NOT NULL DEFAULT 0, "
                    + "updated_by VARCHAR(64), updated_at TIMESTAMP)");
        }
        repository.clearDraftKind(ConfigKind.METADATA);
        repository.clearDraftKind(ConfigKind.STARTER);
    }

    private void insertLegacy(String kind, String yaml) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO config_draft (kind, content_yaml, version) VALUES ("
                    + "'" + kind + "', '" + (yaml == null ? "" : yaml.replace("'", "''")) + "', 0)");
        }
    }

    @Test
    void legacyDraftTableExists_trueWhenPresent() {
        assertThat(migration.legacyDraftTableExists()).isTrue();
    }

    @Test
    void migrateFromLegacyDraftTable_convertsYamlToStructuredRows() throws Exception {
        insertLegacy("METADATA", METADATA_YAML);
        insertLegacy("STARTER", STARTER_YAML);

        Map<String, Integer> written = migration.migrateFromLegacyDraftTable();

        assertThat(written).containsKeys("METADATA", "STARTER");
        assertThat(repository.readDraftScope(ConfigKind.METADATA).javaVersions()).hasSize(1);
        assertThat(repository.readDraftScope(ConfigKind.METADATA).bootVersions()).hasSize(1);
        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters()).hasSize(1);
    }

    @Test
    void migrateFromLegacyDraftTable_skipsBlankYamlRows() throws Exception {
        insertLegacy("METADATA", METADATA_YAML);
        insertLegacy("STARTER", null);

        Map<String, Integer> written = migration.migrateFromLegacyDraftTable();

        assertThat(written).containsOnlyKeys("METADATA");
    }

    @Test
    void migrateFromLegacyDraftTable_emptyLegacyTable_returnsEmptyMap() {
        assertThat(migration.migrateFromLegacyDraftTable()).isEmpty();
    }

    @Test
    void migrateFromLegacyDraftTable_rejectsUnknownKind() throws Exception {
        insertLegacy("NOT_A_KIND", METADATA_YAML);

        assertThatThrownBy(() -> migration.migrateFromLegacyDraftTable())
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void migrateFromLegacyDraftTable_missingLegacyTable_throws4002() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS config_draft");
        }

        assertThat(migration.legacyDraftTableExists()).isFalse();
        assertThatThrownBy(() -> migration.migrateFromLegacyDraftTable())
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void migrateFromLegacyDraftTable_invalidYaml_stopsWholeMigration() throws Exception {
        insertLegacy("METADATA", METADATA_YAML);
        insertLegacy("STARTER", "id: broken\n");

        assertThatThrownBy(() -> migration.migrateFromLegacyDraftTable())
                .isInstanceOf(BusinessException.class);
    }
}
