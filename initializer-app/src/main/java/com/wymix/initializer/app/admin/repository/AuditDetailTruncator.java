package com.wymix.initializer.app.admin.repository;

/**
 * 审计明细截断防护（M3 评审遗留项 4）：审计表 {@code detail} 列为
 * {@code VARCHAR(1024)}，超长发布失败明细直接落库会被目标数据库截断报错。
 *
 * <p>入库前统一截断到列上限（留省略标记），保证审计写入永不因明细超长失败。
 *
 * <p>线程安全性：纯静态工具，线程安全。
 */
public final class AuditDetailTruncator {

    /** 与 schema.sql 中 config_audit.detail 列长度一致。 */
    public static final int MAX_LENGTH = 1024;

    /** 截断后追加的省略标记长度。 */
    private static final String ELLIPSIS = "…(已截断)";

    private AuditDetailTruncator() {
    }

    /**
     * 截断审计明细到列上限：超长部分丢弃并追加省略标记。
     *
     * @param detail 原始明细（可空）
     * @return 安全长度内的明细（null 原样返回）
     */
    public static String truncate(String detail) {
        if (detail == null || detail.length() <= MAX_LENGTH) {
            return detail;
        }
        return detail.substring(0, MAX_LENGTH - ELLIPSIS.length()) + ELLIPSIS;
    }
}
