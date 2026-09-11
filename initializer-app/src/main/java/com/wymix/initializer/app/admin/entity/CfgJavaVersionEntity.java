package com.wymix.initializer.app.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Java 版本行（元数据，{@code metadata.yml} 的 {@code javaVersions} 节同构）。
 *
 * <p>版本作用域：{@code releaseId} = 0 为当前草稿工作区，&gt; 0 为对应发布快照
 * （等于 {@code config_release.id}）。本表全部唯一约束均带 {@code releaseId}，
 * 保证发布复制行为新 release 时不会与草稿行冲突。
 *
 * <p>本类为纯数据载体，仅承载 @TableName 映射与字段读写，不含业务逻辑；
 * 线程安全性：POJO 数据载体，不共享，无并发问题。
 */
@TableName("cfg_java_version")
public class CfgJavaVersionEntity {

    /** 自增主键（跨 release 全局唯一） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本作用域：0 = 草稿工作区，> 0 = 发布快照 ID */
    private Long releaseId;

    /** Java 版本 ID（同一 release 内唯一），如 {@code "17"} */
    private String versionId;

    /** 展示名，如 {@code Java 17} */
    private String label;

    /** 是否已废弃（前端提示用） */
    private Boolean deprecated;

    /** 推荐 Boot 版本 ID（可空） */
    private String recommendedBootVersion;

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

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Boolean getDeprecated() {
        return deprecated;
    }

    public void setDeprecated(Boolean deprecated) {
        this.deprecated = deprecated;
    }

    public String getRecommendedBootVersion() {
        return recommendedBootVersion;
    }

    public void setRecommendedBootVersion(String recommendedBootVersion) {
        this.recommendedBootVersion = recommendedBootVersion;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}
