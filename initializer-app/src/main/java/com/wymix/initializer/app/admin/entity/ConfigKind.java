package com.wymix.initializer.app.admin.entity;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;

import java.util.Locale;

/**
 * 配置草稿种类（M3.1）：一 kind 一行存储，发布时按 kind 归并构建快照。
 *
 * <p>M3.1 首期落地 METADATA / STARTER，M3.2 扩展 TEMPLATE，M3.3 扩展 STARTER_SYNC
 * （制品库同步层，只读贡献者）；CONFLICT_RULE 随 M3.5 扩展。枚举先行收敛，
 * 避免 kind 字段散落魔法字符串。
 *
 * <p>线程安全性：枚举实例天然线程安全。
 */
public enum ConfigKind {

    /** 元数据配置（metadata.yml 同构：Java/Boot 版本、兼容矩阵、starter 元信息） */
    METADATA,

    /** starter 清单（catalog/starters/*.yml 同构） */
    STARTER,

    /** 依赖冲突规则（M3.5） */
    CONFLICT_RULE,

    /** 制品库同步层草稿（M3.3）：同步条目多文档 yml，不触碰手工 STARTER 草稿 */
    STARTER_SYNC,

    /** 生成模板（M3.2） */
    TEMPLATE;

    /**
     * 宽松解析请求路径中的 kind 值（大小写不敏感）。
     *
     * @param raw 原始字符串
     * @return 配置种类
     * @throws BusinessException 取值非法（错误码 {@link ErrorCode#CONFIG_DRAFT_NOT_FOUND}，404 语义）
     */
    public static ConfigKind parse(String raw) {
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.CONFIG_DRAFT_NOT_FOUND,
                    "配置种类非法: " + raw + "（仅允许 metadata / starter / conflict-rule / template / starter-sync）");
        }
    }

    /** @return 小写中划线形式（对外 API 路径与展示用），如 {@code conflict-rule} */
    public String lowerName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
