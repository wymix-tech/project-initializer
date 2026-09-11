package com.wymix.initializer.app.admin.repository;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

/**
 * 存量 YAML 迁移（T1.6）：把旧版 {@code config_draft.content_yaml} 四类草稿
 * 一次性转换为结构化 {@code cfg_*} 行。
 *
 * <p><b>升级路径</b>：旧版（T1 之前）配置正文存于 {@code config_draft} 表的
 * {@code content_yaml} 列，发布历史存于 {@code config_release} 的四段 {@code *_yaml} 列。
 * T1 之后正文改为行存，故存量数据必须迁移。两条路径：
 * <ol>
 *   <li><b>推荐（有现网数据）</b>：用旧版 jar 启动一次，调
 *       {@code GET /api/admin/config/{kind}} 导出四类 YAML 文本，
 *       升级后用 {@code PUT /api/admin/config/{kind}} 回填（即「旧版本导出 → 新版本导入」），
 *       由管理端 UI 校验通过后再发布</li>
 *   <li><b>库内直迁（本类）</b>：若旧库的 {@code config_draft} 表仍在，
 *       直接读该表把 YAML 转成结构行写进新库 —— 省去人工导出导入，
 *       适合可停机维护的升级窗口</li>
 * </ol>
 *
 * <p><b>发布历史</b>：旧版 {@code config_release} 的 {@code *_yaml} 列在新 schema 中已移除，
 * 历史版本的正文无法逐行还原为「当时的作用域行」（旧数据没有行级作用域信息）。
 * 因此**历史发布记录只保留头信息**（发布人 / 时间 / 备注），
 * 迁移后的回滚基线为「迁移时点的草稿发布一版」。此取舍已在
 * {@code docs/guide/configuration.md} 的升级章节中说明，避免运维误判可回滚范围。
 *
 * <p><b>幂等与事务</b>：整体在单个事务内执行；任一 kind 迁移失败则全部回滚，
 * 新库草稿保持迁移前状态。重复执行结果一致（按 kind 先清后写）。
 *
 * <p>线程安全性：无状态单例（状态只在入参与局部变量中），线程安全。
 */
@Component
public class LegacyYamlMigration {

    private static final Logger log = LoggerFactory.getLogger(LegacyYamlMigration.class);

    /** 旧版草稿表名（迁移源）。 */
    static final String LEGACY_DRAFT_TABLE = "config_draft";

    private final DataSource dataSource;
    private final ConfigRepository repository;
    private final StructuredDraftWriter draftWriter;

    /**
     * @param dataSource    数据源（探测旧表是否存在并读取旧 YAML）
     * @param repository    配置存储仓库（结构行写入）
     * @param draftWriter   YAML → 结构行写入器（与导入通道同源，避免两套解析）
     */
    public LegacyYamlMigration(DataSource dataSource, ConfigRepository repository,
                               StructuredDraftWriter draftWriter) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.draftWriter = draftWriter;
    }

    /**
     * 旧版草稿表是否仍存在（升级前探测；不存在即无需迁移）。
     *
     * @return 存在返回 {@code true}
     */
    public boolean legacyDraftTableExists() {
        try (Connection connection = dataSource.getConnection()) {
            return tableExists(connection, LEGACY_DRAFT_TABLE);
        } catch (SQLException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "探测旧版草稿表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行库内直迁：读旧 {@code config_draft} 的四类 YAML → 写结构化草稿行。
     *
     * <p>调用方需自行确认旧表存在（{@link #legacyDraftTableExists()}）且已备份数据库。
     *
     * @return 各 kind 写入的结构行数（kind 枚举名 → 行数）
     * @throws BusinessException 旧表缺失或 YAML 结构非法（4002）
     */
    @Transactional
    public Map<String, Integer> migrateFromLegacyDraftTable() {
        Map<String, String> legacy = readLegacyDrafts();
        if (legacy.isEmpty()) {
            log.info("旧版 config_draft 表存在但无有效 YAML 行，无需迁移");
            return Map.of();
        }
        Map<String, Integer> written = draftWriter.migrateLegacyDrafts(legacy);
        log.info("存量 YAML 草稿迁移完成: {}", written);
        return written;
    }

    /**
     * 读取旧版 {@code config_draft} 表中的全部有效 YAML（kind → 文本）。
     *
     * <p>旧表结构为 {@code (id, kind, content_yaml, version, updated_by, updated_at)}；
     * {@code content_yaml} 为空的行被跳过（等价于该 kind 无草稿）。
     *
     * @return kind 枚举名 → YAML 文本（保持旧表主键顺序）
     * @throws BusinessException 旧表缺失或读取失败
     */
    private Map<String, String> readLegacyDrafts() {
        Map<String, String> drafts = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, LEGACY_DRAFT_TABLE)) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "旧版草稿表不存在: " + LEGACY_DRAFT_TABLE + "（无需迁移或已完成迁移）");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT kind, content_yaml FROM " + LEGACY_DRAFT_TABLE + " ORDER BY id")) {
                while (rows.next()) {
                    String kind = rows.getString(1);
                    String yaml = rows.getString(2);
                    if (kind == null || kind.isBlank() || yaml == null || yaml.isBlank()) {
                        continue;
                    }
                    // kind 合法性在此校验：旧库若存在脏 kind，迁移整体失败优于静默丢弃
                    drafts.put(ConfigKind.parse(kind).name(), yaml);
                }
            }
        } catch (SQLException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "读取旧版草稿表失败: " + e.getMessage(), e);
        }
        return drafts;
    }

    /**
     * 判断表是否存在（大小写不敏感，兼容 H2 / 常见 JDBC 元数据差异）。
     *
     * @param connection 数据库连接
     * @param tableName  目标表名
     * @return 存在返回 {@code true}
     * @throws SQLException 元数据读取失败
     */
    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet tables = connection.getMetaData()
                .getTables(null, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                if (tableName.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
