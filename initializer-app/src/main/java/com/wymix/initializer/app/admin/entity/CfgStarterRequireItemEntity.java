package com.wymix.initializer.app.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 依赖联动 OR 组内项行（AND-of-OR 第二层：组内任一被选即满足本组）。
 *
 * <p>版本作用域：{@code releaseId} = 0 为当前草稿工作区，&gt; 0 为对应发布快照
 * （等于 {@code config_release.id}）。本表全部唯一约束均带 {@code releaseId}，
 * 保证发布复制行为新 release 时不会与草稿行冲突。
 *
 * <p>本类为纯数据载体，仅承载 @TableName 映射与字段读写，不含业务逻辑；
 * 线程安全性：POJO 数据载体，不共享，无并发问题。
 */
@TableName("cfg_starter_require_item")
public class CfgStarterRequireItemEntity {

    /** 自增主键（跨 release 全局唯一） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本作用域：0 = 草稿工作区，> 0 = 发布快照 ID */
    private Long releaseId;

    /** 所属联动分组行主键 */
    private Long groupPk;

    /** 组内候选 starter ID（任一被选即满足本组） */
    private String requiredStarterId;

    /** 组内顺序（升序） */
    private Integer sort;

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

    public Long getGroupPk() {
        return groupPk;
    }

    public void setGroupPk(Long groupPk) {
        this.groupPk = groupPk;
    }

    public String getRequiredStarterId() {
        return requiredStarterId;
    }

    public void setRequiredStarterId(String requiredStarterId) {
        this.requiredStarterId = requiredStarterId;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}
