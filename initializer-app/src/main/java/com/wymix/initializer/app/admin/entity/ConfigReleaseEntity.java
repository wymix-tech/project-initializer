package com.wymix.initializer.app.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 配置发布历史头表实体（结构化改造 T1，{@code config_release}）：
 * 只保留发布元信息，配置正文全部落在 {@code cfg_*} 关系表（按 {@code release_id} 关联）。
 *
 * <p>结构化改造前本表持有 metadata/starter/template/conflict 四段 YAML 全文；
 * 改造后逐行为快照，{@code id} 即各 {@code cfg_*} 表的 {@code release_id}
 * （{@code 0} 保留给草稿工作区，故自增主键从 1 起天然避开冲突）。
 *
 * <p>线程安全性：POJO 数据载体，不共享，无并发问题。
 */
@TableName("config_release")
public class ConfigReleaseEntity {

    /** 主键（同时作为 {@code cfg_*} 关系表的 {@code release_id} 快照作用域值） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发布人 */
    private String releasedBy;

    /** 发布时间 */
    private Instant releasedAt;

    /** 发布备注（如「回滚自 #N」） */
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
