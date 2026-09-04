package com.wymix.initializer.app.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 管理端审计日志表实体（M3.1）：记录草稿修改 / 发布 / 回滚等关键动作。
 *
 * <p>线程安全性：POJO 数据载体，不共享，无并发问题。
 */
@TableName("config_audit")
public class ConfigAuditEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 动作类型：UPDATE_DRAFT / PUBLISH / ROLLBACK */
    private String action;

    /** 目标配置种类（{@link ConfigKind} 枚举名；PUBLISH/ROLLBACK 记 PUBLISH/ROLLBACK 动作全集时可空） */
    private String targetKind;

    /** 操作人 */
    private String operator;

    /** 明细（如校验失败原因、发布备注） */
    private String detail;

    /** 操作时间 */
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetKind() {
        return targetKind;
    }

    public void setTargetKind(String targetKind) {
        this.targetKind = targetKind;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
