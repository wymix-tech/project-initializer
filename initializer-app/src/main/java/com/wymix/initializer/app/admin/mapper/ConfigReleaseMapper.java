package com.wymix.initializer.app.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 配置发布历史表 Mapper（M3.1）：发布落库与历史查询。
 *
 * <p>线程安全性：无状态 Mapper 代理，线程安全。
 */
@Mapper
public interface ConfigReleaseMapper extends BaseMapper<ConfigReleaseEntity> {
}
