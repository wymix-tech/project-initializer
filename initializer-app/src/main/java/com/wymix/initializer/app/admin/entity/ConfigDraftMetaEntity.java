package com.wymix.initializer.app.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.Instant;

/**
 * 草稿种类元信息表实体（结构化改造 T1，{@code config_draft_meta}）：
 * 一 kind 一行，承载 kind 级乐观锁，替代原 {@code config_draft.content_yaml}。
 *
 * <p>结构化之后配置正文分散在各 {@code cfg_*} 关系表（按 {@code release_id} 区分草稿与快照），
 * 种类级乐观锁无处安放，故独立本表：表单保存时先按本行 {@code version} 做乐观锁判定，
 * 通过后再整体替换该 kind 对应的结构行。
 *
 * <p>线程安全性：POJO 数据载体，不共享，无并发问题。
 */
@TableName("config_draft_meta")
public class ConfigDraftMetaEntity {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置种类（{@link ConfigKind} 枚举名，如 {@code STARTER_SYNC}） */
    private String kind;

    /** 乐观锁版本号（每次草稿保存单调递增） */
    @Version
    private Integer version;

    /** 最近修改人 */
    private String updatedBy;

    /** 最近修改时间 */
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
