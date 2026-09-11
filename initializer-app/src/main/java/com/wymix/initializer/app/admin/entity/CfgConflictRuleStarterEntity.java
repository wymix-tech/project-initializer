package com.wymix.initializer.app.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 冲突规则涉及的 starter 行（rule ↔ starter 多选）。
 *
 * <p>版本作用域：{@code releaseId} = 0 为当前草稿工作区，&gt; 0 为对应发布快照
 * （等于 {@code config_release.id}）。本表全部唯一约束均带 {@code releaseId}，
 * 保证发布复制行为新 release 时不会与草稿行冲突。
 *
 * <p>本类为纯数据载体，仅承载 @TableName 映射与字段读写，不含业务逻辑；
 * 线程安全性：POJO 数据载体，不共享，无并发问题。
 */
@TableName("cfg_conflict_rule_starter")
public class CfgConflictRuleStarterEntity {

    /** 自增主键（跨 release 全局唯一） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本作用域：0 = 草稿工作区，> 0 = 发布快照 ID */
    private Long releaseId;

    /** 所属冲突规则行主键 */
    private Long rulePk;

    /** 规则涉及的 starter ID（PAIR 恰好 2 个，GROUP 至少 2 个） */
    private String starterId;

    /** 规则内 starter 顺序（升序，影响提示明细展示） */
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

    public Long getRulePk() {
        return rulePk;
    }

    public void setRulePk(Long rulePk) {
        this.rulePk = rulePk;
    }

    public String getStarterId() {
        return starterId;
    }

    public void setStarterId(String starterId) {
        this.starterId = starterId;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}
