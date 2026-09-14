package com.wymix.initializer.app.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Boot ↔ Java 兼容矩阵行（多对多，两端以同作用域父行主键关联）。
 *
 * <p>版本作用域：{@code releaseId} = 0 为当前草稿工作区，&gt; 0 为对应发布快照
 * （等于 {@code config_release.id}）。本表全部唯一约束均带 {@code releaseId}，
 * 保证发布复制行为新 release 时不会与草稿行冲突。
 *
 * <p>本类为纯数据载体，仅承载 @TableName 映射与字段读写，不含业务逻辑；
 * 线程安全性：POJO 数据载体，不共享，无并发问题。
 */
@TableName("cfg_boot_java")
public class CfgBootJavaEntity {

    /** 自增主键（跨 release 全局唯一） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本作用域：0 = 草稿工作区，> 0 = 发布快照 ID */
    private Long releaseId;

    /** 所属 Boot 版本行主键（父行必然存在，引用 {@code cfg_boot_version.id}） */
    private Long bootPk;

    /** 兼容的 Java 版本业务 ID（装配期按 {@code cfg_java_version.version_id} 解析；允许悬空以支持草稿态） */
    private String javaVersionId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(Long releaseId) {
        this.releaseId = releaseId;
    }

    public Long getBootPk() {
        return bootPk;
    }

    public void setBootPk(Long bootPk) {
        this.bootPk = bootPk;
    }

    public String getJavaVersionId() {
        return javaVersionId;
    }

    public void setJavaVersionId(String javaVersionId) {
        this.javaVersionId = javaVersionId;
    }
}
