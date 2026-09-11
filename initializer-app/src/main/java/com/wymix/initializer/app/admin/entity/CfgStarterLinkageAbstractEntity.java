package com.wymix.initializer.app.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 集成抽象类联动行（{@code linkages.abstract-classes}）。
 *
 * <p>版本作用域：{@code releaseId} = 0 为当前草稿工作区，&gt; 0 为对应发布快照
 * （等于 {@code config_release.id}）。本表全部唯一约束均带 {@code releaseId}，
 * 保证发布复制行为新 release 时不会与草稿行冲突。
 *
 * <p>本类为纯数据载体，仅承载 @TableName 映射与字段读写，不含业务逻辑；
 * 线程安全性：POJO 数据载体，不共享，无并发问题。
 */
@TableName("cfg_starter_linkage_abstract")
public class CfgStarterLinkageAbstractEntity {

    /** 自增主键（跨 release 全局唯一） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本作用域：0 = 草稿工作区，> 0 = 发布快照 ID */
    private Long releaseId;

    /** 所属 starter 行主键 */
    private Long starterPk;

    /** 抽象类全限定名 */
    private String fqcn;

    /** 模板 context 引用名（同 starter 内唯一） */
    private String alias;

    /** 抽象类联动顺序（升序） */
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

    public Long getStarterPk() {
        return starterPk;
    }

    public void setStarterPk(Long starterPk) {
        this.starterPk = starterPk;
    }

    public String getFqcn() {
        return fqcn;
    }

    public void setFqcn(String fqcn) {
        this.fqcn = fqcn;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}
