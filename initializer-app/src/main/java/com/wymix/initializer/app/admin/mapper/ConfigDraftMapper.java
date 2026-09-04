package com.wymix.initializer.app.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wymix.initializer.app.admin.entity.ConfigDraftEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 配置草稿表 Mapper（M3.1）：MyBatis-Plus {@link BaseMapper} 通用 CRUD。
 *
 * <p>线程安全性：无状态 Mapper 代理，线程安全。
 */
@Mapper
public interface ConfigDraftMapper extends BaseMapper<ConfigDraftEntity> {
}
