package com.wymix.initializer.app.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wymix.initializer.app.admin.entity.ConfigDraftMetaEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 草稿种类元信息 Mapper（{@code config_draft_meta}）：MyBatis-Plus {@link BaseMapper} 通用 CRUD。
 *
 * <p>乐观锁由 {@link ConfigDraftMetaEntity#getVersion()} 上的 {@code @Version} 与
 * {@code OptimisticLockerInnerInterceptor} 共同承担，无需自定义 SQL。
 *
 * <p>线程安全性：无状态 Mapper 代理，线程安全。
 */
@Mapper
public interface ConfigDraftMetaMapper extends BaseMapper<ConfigDraftMetaEntity> {
}
