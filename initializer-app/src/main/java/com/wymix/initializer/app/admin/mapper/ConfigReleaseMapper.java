package com.wymix.initializer.app.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 发布历史头表 Mapper（{@code config_release}）：MyBatis-Plus {@link BaseMapper} 通用 CRUD。
 *
 * <p>线程安全性：无状态 Mapper 代理，线程安全。
 */
@Mapper
public interface ConfigReleaseMapper extends BaseMapper<ConfigReleaseEntity> {
}
