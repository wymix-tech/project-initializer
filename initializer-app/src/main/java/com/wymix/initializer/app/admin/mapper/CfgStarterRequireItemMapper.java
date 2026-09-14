package com.wymix.initializer.app.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wymix.initializer.app.admin.entity.CfgStarterRequireItemEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 依赖联动 OR 组内项行 Mapper（{@code cfg_starter_require_item}）：MyBatis-Plus {@link BaseMapper} 通用 CRUD +
 * 结构化装配链路需要的「按 {@code release_id} 一次性批量查询」。
 *
 * <p>查询契约（性能红线）：装配器对每张表**只发一次**查询，
 * 在内存按父键分组，严禁逐行查库（N+1）。
 *
 * <p>本接口不含业务逻辑，批量写入由 MyBatis-Plus 与 JDBC 批处理承担；
 * 线程安全性：无状态 Mapper 代理，线程安全。
 */
@Mapper
public interface CfgStarterRequireItemMapper extends BaseMapper<CfgStarterRequireItemEntity> {

    /**
     * 按版本作用域一次性查询本表全部行（装配器批量入口，避免 N+1）。
     *
     * @param releaseId 版本作用域：0 = 草稿工作区，&gt; 0 = 发布快照 ID
     * @return 该作用域内的全部行（按自增主键升序，保证装配结果确定）
     */
    @Select("SELECT * FROM cfg_starter_require_item WHERE release_id = #{releaseId} ORDER BY id")
    List<CfgStarterRequireItemEntity> selectByReleaseId(@Param("releaseId") long releaseId);
}
