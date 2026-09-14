package com.wymix.initializer.app.admin.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存量 YAML 迁移 DTO（结构化改造 T8）：库内直迁的预检与执行契约。
 *
 * <p>与 {@code ConfigArchiveDtos}（ZIP 归档通道）分开定义：归档通道是「文件往返」，
 * 本通道是「旧库表直读」，两者的运维语义与风险不同，类型不混用。
 *
 * <p>线程安全性：不可变 record（集合字段构造期做防御性拷贝），线程安全。
 */
public final class LegacyMigrationDtos {

    private LegacyMigrationDtos() {
    }

    /**
     * 迁移预检视图（{@code GET /api/admin/config/migrate-legacy}）。
     *
     * @param legacyTable        旧版草稿表名
     * @param legacyTableExists  旧表是否存在
     * @param kinds              各 kind 的有效存量行数（kind 枚举名 → 行数）
     * @param rows               存量有效行合计
     * @param migrationRequired  是否需要执行迁移
     * @param hint               运维提示（人类可读）
     */
    public record MigrationProbeView(String legacyTable, boolean legacyTableExists,
                                     Map<String, Integer> kinds, int rows,
                                     boolean migrationRequired, String hint) {

        public MigrationProbeView {
            kinds = kinds == null ? Map.of() : Map.copyOf(kinds);
        }

        /**
         * 由探测结果投影。
         *
         * @param report {@code LegacyYamlMigration#probe()} 的原始报告
         * @return 视图
         */
        @SuppressWarnings("unchecked")
        public static MigrationProbeView from(Map<String, Object> report) {
            return new MigrationProbeView(
                    String.valueOf(report.get("legacyTable")),
                    Boolean.TRUE.equals(report.get("legacyTableExists")),
                    (Map<String, Integer>) report.getOrDefault("kinds", Map.of()),
                    report.get("rows") instanceof Integer rows ? rows : 0,
                    Boolean.TRUE.equals(report.get("migrationRequired")),
                    String.valueOf(report.getOrDefault("hint", "")));
        }
    }

    /**
     * 迁移执行视图（{@code POST /api/admin/config/migrate-legacy}）。
     *
     * @param written 各 kind 写入的结构行数（kind 枚举名 → 行数）
     * @param rows    写入结构行合计
     * @param summary 规模摘要（不含配置正文与 token）
     */
    public record MigrationRunView(Map<String, Integer> written, int rows, String summary) {

        public MigrationRunView {
            written = written == null ? Map.of() : Map.copyOf(written);
        }

        /**
         * 由迁移结果构造视图。
         *
         * @param written 各 kind 写入行数
         * @return 视图
         */
        public static MigrationRunView from(Map<String, Integer> written) {
            Map<String, Integer> ordered = new LinkedHashMap<>(written);
            int rows = ordered.values().stream().mapToInt(Integer::intValue).sum();
            String summary = ordered.isEmpty()
                    ? "无有效存量行，未执行写入"
                    : "kind=" + ordered.keySet() + "，结构行 " + rows + " 行";
            return new MigrationRunView(ordered, rows, summary);
        }
    }
}
