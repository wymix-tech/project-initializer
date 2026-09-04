package com.wymix.initializer.app.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.Instant;

/**
 * 配置草稿表实体（M3.1）：管理端 CRUD 只写本表，「发布」动作才触发校验 + 快照切换。
 *
 * <p>一 kind 一行；{@code version} 为乐观锁版本（双管理员交叉修改时后写方失败），
 * {@code contentYaml} 保存配置原文（SnakeYAML 安全装载与校验延迟到发布期）。
 *
 * <p>线程安全性：POJO 数据载体，不共享，无并发问题。
 */
@TableName("config_draft")
public class ConfigDraftEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置种类（{@link ConfigKind} 枚举名） */
    private String kind;

    /** 配置原文（YAML 文本） */
    private String contentYaml;

    /** 乐观锁版本号 */
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

    public String getContentYaml() {
        return contentYaml;
    }

    public void setContentYaml(String contentYaml) {
        this.contentYaml = contentYaml;
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
