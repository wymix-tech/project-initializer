package com.wymix.initializer.app.admin.dto;

import com.wymix.initializer.app.admin.service.ConfigImportExportService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置导入导出 DTO（结构化改造 T4）：ZIP 归档导入的响应契约。
 *
 * <p>与 {@code CatalogDtos} 分开定义，避免把「归档通道」的字段混进「表单结构化契约」，
 * 也让 T6 前端同步时有明确的类型边界。
 *
 * <p>线程安全性：不可变 record（集合字段构造期做防御性拷贝），线程安全。
 */
public final class ConfigArchiveDtos {

    private ConfigArchiveDtos() {
    }

    /**
     * 导入结果视图。
     *
     * @param rows        写入的结构行合计
     * @param versions    各 kind 导入后的版本（kind 小写中划线名 → version）
     * @param summary     规模摘要（不含模板全文与 token）
     */
    public record ImportView(int rows, Map<String, Integer> versions, String summary) {

        public ImportView {
            versions = versions == null ? Map.of() : Map.copyOf(versions);
        }

        /**
         * 由服务层结果投影。
         *
         * @param result 导入结果
         * @return 视图
         */
        public static ImportView from(ConfigImportExportService.ImportResult result) {
            Map<String, Integer> versions = new LinkedHashMap<>();
            result.versions().forEach((kind, version) -> versions.put(kind.lowerName(), version));
            return new ImportView(result.rows(), versions, result.summary());
        }
    }

    /**
     * 导出结果视图（供管理端预检/提示；实际下载走二进制响应）。
     *
     * @param fileName 建议的下载文件名
     * @param bytes    归档字节数
     * @param summary  规模摘要
     */
    public record ExportView(String fileName, int bytes, String summary) {

        /**
         * 由服务层结果投影。
         *
         * @param result 导出结果
         * @return 视图
         */
        public static ExportView from(ConfigImportExportService.ExportResult result) {
            return new ExportView(result.fileName(), result.content().length, result.summary());
        }
    }
}
