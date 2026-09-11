package com.wymix.initializer.app.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * starter 主行（{@code catalog/starters/*.yml} 同构）；{@code source} 区分手工与制品库同步通道。
 *
 * <p>版本作用域：{@code releaseId} = 0 为当前草稿工作区，&gt; 0 为对应发布快照
 * （等于 {@code config_release.id}）。本表全部唯一约束均带 {@code releaseId}，
 * 保证发布复制行为新 release 时不会与草稿行冲突。
 *
 * <p>本类为纯数据载体，仅承载 @TableName 映射与字段读写，不含业务逻辑；
 * 线程安全性：POJO 数据载体，不共享，无并发问题。
 */
@TableName("cfg_starter")
public class CfgStarterEntity {

    /** 自增主键（跨 release 全局唯一） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本作用域：0 = 草稿工作区，> 0 = 发布快照 ID */
    private Long releaseId;

    /** starter ID（同一 release 内唯一） */
    private String starterId;

    /** 分类：web / data / middleware / tool */
    private String category;

    /** 维护方：OFFICIAL / INTERNAL */
    private String maintainer;

    /** 展示描述（前端展示名，可空回退 ID） */
    private String description;

    /** 是否必选（前端默认勾选且禁止取消，生成期兜底补齐） */
    private Boolean required;

    /** 配置类目标包名（支持统一占位符，可空） */
    private String configPackage;

    /** 来源通道：MANUAL = 手工维护，SYNC = 制品库同步 */
    private String source;

    /** 同 release 内展示顺序（升序） */
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

    public String getStarterId() {
        return starterId;
    }

    public void setStarterId(String starterId) {
        this.starterId = starterId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getMaintainer() {
        return maintainer;
    }

    public void setMaintainer(String maintainer) {
        this.maintainer = maintainer;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public String getConfigPackage() {
        return configPackage;
    }

    public void setConfigPackage(String configPackage) {
        this.configPackage = configPackage;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}
