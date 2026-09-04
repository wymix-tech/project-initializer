package com.wymix.initializer.app.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wymix.initializer.app.admin.entity.ConfigAuditEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理端审计日志表 Mapper（M3.1）：审计动作追加写入。
 *
 * <p>线程安全性：无状态 Mapper 代理，线程安全。
 */
@Mapper
public interface ConfigAuditMapper extends BaseMapper<ConfigAuditEntity> {
}
