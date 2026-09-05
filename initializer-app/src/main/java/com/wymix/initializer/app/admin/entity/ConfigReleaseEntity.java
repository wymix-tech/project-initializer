package com.wymix.initializer.app.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 配置发布历史表实体（M3.1）：每次发布存一版发布时点的草稿全文，
 * 支持发布历史查询与回滚（M3.2 消费）。
 *
 * <p>线程安全性：POJO 数据载体，不共享，无并发问题。
 */
@TableName("config_release")
public class ConfigReleaseEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发布时的 metadata 配置全文（YAML） */
    private String metadataYaml;

    /** 发布时的 starter 配置全文（YAML） */
    private String starterYaml;

    /** 发布时的 template 配置全文（YAML 多文档，M3.2；可空 = 无模板覆盖） */
    private String templateYaml;

    /** 发布时的 conflict-rule 配置全文（M3.5，conflict-rules.yml 同构；可空 = 无冲突规则） */
    private String conflictYaml;

    /** 发布人 */
    private String releasedBy;

    /** 发布时间 */
    private Instant releasedAt;

    /** 发布备注 */
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMetadataYaml() {
        return metadataYaml;
    }

    public void setMetadataYaml(String metadataYaml) {
        this.metadataYaml = metadataYaml;
    }

    public String getStarterYaml() {
        return starterYaml;
    }

    public String getTemplateYaml() {
        return templateYaml;
    }

    public void setTemplateYaml(String templateYaml) {
        this.templateYaml = templateYaml;
    }

    public String getConflictYaml() {
        return conflictYaml;
    }

    public void setConflictYaml(String conflictYaml) {
        this.conflictYaml = conflictYaml;
    }

    public void setStarterYaml(String starterYaml) {
        this.starterYaml = starterYaml;
    }

    public String getReleasedBy() {
        return releasedBy;
    }

    public void setReleasedBy(String releasedBy) {
        this.releasedBy = releasedBy;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
