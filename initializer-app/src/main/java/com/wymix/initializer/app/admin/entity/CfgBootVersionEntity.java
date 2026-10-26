package com.wymix.initializer.app.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Spring Boot 版本行（含框架父 POM 三列，三列同为空 = 回退官方父 POM）。
 *
 * <p>版本作用域：{@code releaseId} = 0 为当前草稿工作区，&gt; 0 为对应发布快照
 * （等于 {@code config_release.id}）。本表全部唯一约束均带 {@code releaseId}，
 * 保证发布复制行为新 release 时不会与草稿行冲突。
 *
 * <p>本类为纯数据载体，仅承载 @TableName 映射与字段读写，不含业务逻辑；
 * 线程安全性：POJO 数据载体，不共享，无并发问题。
 */
@TableName("cfg_boot_version")
public class CfgBootVersionEntity {

    /** 自增主键（跨 release 全局唯一） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本作用域：0 = 草稿工作区，> 0 = 发布快照 ID */
    private Long releaseId;

    /** Boot 版本 ID（同一 release 内唯一），如 {@code 3.2.5} */
    private String versionId;

    /** 展示名，如 {@code Spring Boot 3.2.5} */
    private String label;

    /** 是否官方发布版本 */
    private Boolean official;

    /** 框架父 POM groupId（与另两列同为 null 时回退官方父 POM） */
    private String fpGroup;

    /** 框架父 POM artifactId（可空） */
    private String fpArtifact;

    /** 框架父 POM version（可空，支持 ${revision} 等占位符） */
    private String fpVersion;

    /** 真实 Spring Boot 版本（可空，null 时回退 versionId）；版本 ID 可能是内部框架版本号 */
    private String sbVersion;

    /** 同 release 内展示顺序（升序） */
    private Integer sort;

    public String getSbVersion() {
        return sbVersion;
    }

    public void setSbVersion(String sbVersion) {
        this.sbVersion = sbVersion;
    }

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

    public Boolean getOfficial() {
        return official;
    }

    public void setOfficial(Boolean official) {
        this.official = official;
    }

    public String getFpGroup() {
        return fpGroup;
    }

    public void setFpGroup(String fpGroup) {
        this.fpGroup = fpGroup;
    }

    public String getFpArtifact() {
        return fpArtifact;
    }

    public void setFpArtifact(String fpArtifact) {
        this.fpArtifact = fpArtifact;
    }

    public String getFpVersion() {
        return fpVersion;
    }

    public void setFpVersion(String fpVersion) {
        this.fpVersion = fpVersion;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}
