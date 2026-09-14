package com.wymix.initializer.app.admin.repository;

import com.wymix.initializer.api.error.BusinessException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LegacyYamlMigration} 边界路径单元测试（T8）：数据库故障下的错误收敛语义。
 *
 * <p>SQL 异常分支（元数据读取 / 探测 / 统计 / 读旧表失败）无法在正常 H2 集成测试中触发，
 * 此处以「故障 {@link DataSource}」定向覆盖，保证这些路径的失败语义是
 * 「包成 {@code INTERNAL_ERROR} 业务异常并有明细」而非裸抛 {@code SQLException}。
 *
 * <p>线程安全性：JUnit 单线程执行。
 */
class LegacyYamlMigrationEdgeTest {

    /** 连接获取即抛错的 DataSource（模拟数据库不可用）。 */
    private static final class FailingDataSource implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("db down");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            // no-op
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // no-op
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    private static LegacyYamlMigration migration(DataSource dataSource, ConfigRepository repository) {
        return new LegacyYamlMigration(dataSource, repository, mock(StructuredDraftWriter.class));
    }

    @Test
    void legacyDraftTableExists_wrapsSqlExceptionAsInternalError() {
        LegacyYamlMigration migration = migration(new FailingDataSource(), mock(ConfigRepository.class));

        assertThatThrownBy(migration::legacyDraftTableExists)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("探测旧版草稿表失败");
    }

    @Test
    void probe_wrapsSqlExceptionAsInternalError() {
        LegacyYamlMigration migration = migration(new FailingDataSource(), mock(ConfigRepository.class));

        assertThatThrownBy(migration::probe)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("探测旧版草稿表失败");
    }

    @Test
    void migrate_missingLegacyTable_reportsFailureAuditAndThrows() throws Exception {
        ConfigRepository repository = mock(ConfigRepository.class);
        DataSource dataSource = mock(DataSource.class);
        // 连得上，但元数据里没有任何表 → 旧表不存在
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(mock(java.sql.DatabaseMetaData.class));
        when(connection.getMetaData().getTables(any(), any(), any(), any()))
                .thenReturn(mock(java.sql.ResultSet.class));
        LegacyYamlMigration migration = migration(dataSource, repository);

        assertThatThrownBy(() -> migration.migrateFromLegacyDraftTable("ops"))
                .isInstanceOf(BusinessException.class);
        // 失败留痕：审计必须落库（noRollbackFor 策略）
        org.mockito.Mockito.verify(repository).insertAudit(
                org.mockito.ArgumentMatchers.eq(LegacyYamlMigration.ACTION_MIGRATE),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("ops"),
                org.mockito.ArgumentMatchers.contains("迁移失败"));
    }

    @Test
    void probe_legacyTableExistsButNoValidRows_notRequired() throws Exception {
        ConfigRepository repository = mock(ConfigRepository.class);
        Connection connection = mock(Connection.class);
        java.sql.DatabaseMetaData meta = mock(java.sql.DatabaseMetaData.class);
        java.sql.ResultSet tables = mock(java.sql.ResultSet.class);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_NAME")).thenReturn("config_draft");
        when(meta.getTables(any(), any(), any(), any())).thenReturn(tables);

        java.sql.Statement statement = mock(java.sql.Statement.class);
        java.sql.ResultSet counts = mock(java.sql.ResultSet.class);
        // 无任何分组行 → rows=0
        when(counts.next()).thenReturn(false);
        when(statement.executeQuery(any())).thenReturn(counts);

        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(meta);
        when(connection.createStatement()).thenReturn(statement);

        LegacyYamlMigration migration = migration(dataSource, repository);
        var probe = migration.probe();

        assertThat(probe).containsEntry("legacyTableExists", true)
                .containsEntry("migrationRequired", false)
                .containsEntry("rows", 0);
    }

    @Test
    void countLegacyRows_reportsKindsAndRequiresMigration() throws Exception {
        // 一条有效分组行（METADATA ×3）→ kinds 非空、rows=3、migrationRequired=true
        LegacyYamlMigration migration = migration(statementDataSource(
                tableMeta("config_draft"), statementWithCounts("METADATA", 3)), mock(ConfigRepository.class));

        var probe = migration.probe();
        assertThat(probe).containsEntry("legacyTableExists", true)
                .containsEntry("migrationRequired", true)
                .containsEntry("rows", 3);
        assertThat(probe.get("kinds")).isEqualTo(java.util.Map.of("METADATA", 3));
    }

    @Test
    void migrate_emptyAndNonEmpty_writesAuditForBothOutcomes() throws Exception {
        // 迁移成功路径：审计动作 MIGRATE_LEGACY 必须落库（success 分支）
        ConfigRepository repository = mock(ConfigRepository.class);
        StructuredDraftWriter writer = mock(StructuredDraftWriter.class);
        when(writer.migrateLegacyDrafts(any())).thenReturn(java.util.Map.of("METADATA", 2));
        LegacyYamlMigration migration = new LegacyYamlMigration(
                statementDataSource(tableMeta("config_draft"), statementWithDrafts("METADATA", "javaVersions: []")),
                repository, writer);

        assertThat(migration.migrateFromLegacyDraftTable("ops")).containsEntry("METADATA", 2);
        org.mockito.Mockito.verify(repository).insertAudit(
                org.mockito.ArgumentMatchers.eq(LegacyYamlMigration.ACTION_MIGRATE),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("ops"),
                org.mockito.ArgumentMatchers.contains("迁移完成"));
    }

    @Test
    void countLegacyRows_skipsBlankKind() throws Exception {
        // 行存在但 kind 为空 → 计入前被跳过（rows 仍为 0）
        LegacyYamlMigration migration = migration(statementDataSource(
                tableMeta("config_draft"), statementWithCounts("", 1)), mock(ConfigRepository.class));

        assertThat(migration.probe()).containsEntry("rows", 0)
                .containsEntry("migrationRequired", false);
    }

    @Test
    void readLegacyDrafts_skipsBlankKindAndBlankYaml() throws Exception {
        // 两行：一行 kind 为空；一行 kind 合法但 content_yaml 为空 → 均可跳过
        ConfigRepository repository = mock(ConfigRepository.class);
        StructuredDraftWriter writer = mock(StructuredDraftWriter.class);
        Connection connection = mock(Connection.class);
        java.sql.DatabaseMetaData meta = mock(java.sql.DatabaseMetaData.class);
        java.sql.ResultSet tables = mock(java.sql.ResultSet.class);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_NAME")).thenReturn("config_draft");
        when(meta.getTables(any(), any(), any(), any())).thenReturn(tables);
        java.sql.Statement statement = mock(java.sql.Statement.class);
        java.sql.ResultSet rows = mock(java.sql.ResultSet.class);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString(1)).thenReturn("", "METADATA");
        when(rows.getString(2)).thenReturn("a: 1", "");
        when(statement.executeQuery(any())).thenReturn(rows);
        when(connection.getMetaData()).thenReturn(meta);
        when(connection.createStatement()).thenReturn(statement);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        LegacyYamlMigration migration = new LegacyYamlMigration(dataSource, repository, writer);
        assertThat(migration.migrateFromLegacyDraftTable("ops")).isEmpty();
        // 全部行被跳过 → 不触碰写入器
        org.mockito.Mockito.verifyNoInteractions(writer);
    }

    /** 构造「表存在 + 指定分组结果」的 DataSource。 */
    private static DataSource statementDataSource(java.sql.DatabaseMetaData meta, java.sql.Statement statement)
            throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(meta);
        when(connection.createStatement()).thenReturn(statement);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }

    /** 构造含单张 config_draft 表的元数据。 */
    private static java.sql.DatabaseMetaData tableMeta(String tableName) throws Exception {
        java.sql.DatabaseMetaData meta = mock(java.sql.DatabaseMetaData.class);
        java.sql.ResultSet tables = mock(java.sql.ResultSet.class);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_NAME")).thenReturn(tableName);
        when(meta.getTables(any(), any(), any(), any())).thenReturn(tables);
        return meta;
    }

    /** 构造「一条分组行（kind + count）」的 Statement。 */
    private static java.sql.Statement statementWithCounts(String kind, int count) throws Exception {
        java.sql.Statement statement = mock(java.sql.Statement.class);
        java.sql.ResultSet counts = mock(java.sql.ResultSet.class);
        when(counts.next()).thenReturn(true, false);
        when(counts.getString(1)).thenReturn(kind);
        when(counts.getInt(2)).thenReturn(count);
        when(statement.executeQuery(any())).thenReturn(counts);
        return statement;
    }

    /** 构造「一行 kind + content_yaml」的 Statement（供旧表全量读取）。 */
    private static java.sql.Statement statementWithDrafts(String kind, String yaml) throws Exception {
        java.sql.Statement statement = mock(java.sql.Statement.class);
        java.sql.ResultSet rows = mock(java.sql.ResultSet.class);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn(kind);
        when(rows.getString(2)).thenReturn(yaml);
        when(statement.executeQuery(any())).thenReturn(rows);
        return statement;
    }
}
