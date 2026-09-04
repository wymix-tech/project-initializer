package com.wymix.initializer.app.admin.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wymix.initializer.app.admin.entity.ConfigAuditEntity;
import com.wymix.initializer.app.admin.entity.ConfigDraftEntity;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import com.wymix.initializer.app.admin.mapper.ConfigAuditMapper;
import com.wymix.initializer.app.admin.mapper.ConfigDraftMapper;
import com.wymix.initializer.app.admin.mapper.ConfigReleaseMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 配置存储仓库（M3.1）：收口三张 config 表的全部读写，service 层不接触 Mapper 细节。
 *
 * <p>乐观锁依赖 MyBatis-Plus {@link OptimisticLockerInnerInterceptor}：
 * {@link #updateDraft} 携带加载时 version 更新，版本不匹配影响行数为 0 → 抛 4003。
 *
 * <p>线程安全性：无状态单例，线程安全。
 */
@Repository
public class ConfigRepository {

    private final ConfigDraftMapper draftMapper;
    private final ConfigReleaseMapper releaseMapper;
    private final ConfigAuditMapper auditMapper;

    /**
     * @param draftMapper   草稿表 Mapper
     * @param releaseMapper 发布历史表 Mapper
     * @param auditMapper   审计表 Mapper
     */
    public ConfigRepository(ConfigDraftMapper draftMapper, ConfigReleaseMapper releaseMapper,
                            ConfigAuditMapper auditMapper) {
        this.draftMapper = draftMapper;
        this.releaseMapper = releaseMapper;
        this.auditMapper = auditMapper;
    }

    /**
     * 读取指定种类的当前草稿。
     *
     * @param kind 配置种类
     * @return 草稿实体；无记录时返回 {@code null}
     */
    public ConfigDraftEntity findDraft(ConfigKind kind) {
        return draftMapper.selectOne(new LambdaQueryWrapper<ConfigDraftEntity>()
                .eq(ConfigDraftEntity::getKind, kind.name()));
    }

    /**
     * 插入草稿（该 kind 首次保存）。
     *
     * @param kind      配置种类
     * @param yaml      配置原文
     * @param updatedBy 修改人
     * @return 落库后的实体（含自增 id 与初始 version）
     */
    public ConfigDraftEntity insertDraft(ConfigKind kind, String yaml, String updatedBy) {
        ConfigDraftEntity entity = new ConfigDraftEntity();
        entity.setKind(kind.name());
        entity.setContentYaml(yaml);
        entity.setUpdatedBy(updatedBy);
        entity.setUpdatedAt(Instant.now());
        draftMapper.insert(entity);
        return entity;
    }

    /**
     * 乐观锁更新草稿内容：实体携带的 {@code version} 与库中不一致时更新 0 行 → 抛 4003。
     *
     * @param entity 加载后修改过 contentYaml 的草稿实体
     * @param yaml   新配置原文
     * @throws com.wymix.initializer.api.error.BusinessException 乐观锁冲突（错误码 4003）
     */
    public void updateDraft(ConfigDraftEntity entity, String yaml) {
        entity.setContentYaml(yaml);
        entity.setUpdatedAt(Instant.now());
        int rows = draftMapper.updateById(entity);
        if (rows == 0) {
            throw new com.wymix.initializer.api.error.BusinessException(
                    com.wymix.initializer.api.error.ErrorCode.CONFIG_CONCURRENT_MODIFY,
                    com.wymix.initializer.api.error.ErrorCode.CONFIG_CONCURRENT_MODIFY.getMessage());
        }
    }

    /**
     * 发布落库：写一版发布历史。
     *
     * @param metadataYaml 发布时点 metadata 配置全文
     * @param starterYaml  发布时点 starter 配置全文
     * @param releasedBy   发布人
     * @param remark       发布备注
     * @return 落库后的发布记录（含自增 id）
     */
    public ConfigReleaseEntity insertRelease(String metadataYaml, String starterYaml,
                                             String releasedBy, String remark) {
        ConfigReleaseEntity release = new ConfigReleaseEntity();
        release.setMetadataYaml(metadataYaml);
        release.setStarterYaml(starterYaml);
        release.setReleasedBy(releasedBy);
        release.setReleasedAt(Instant.now());
        release.setRemark(remark);
        releaseMapper.insert(release);
        return release;
    }

    /** @return 发布历史（最新在前） */
    public List<ConfigReleaseEntity> listReleases() {
        return releaseMapper.selectList(new LambdaQueryWrapper<ConfigReleaseEntity>()
                .orderByDesc(ConfigReleaseEntity::getId));
    }

    /**
     * 按 ID 查发布记录（回滚入口）。
     *
     * @param id 发布记录 ID
     * @return 发布记录；不存在返回 {@code null}
     */
    public ConfigReleaseEntity findRelease(long id) {
        return releaseMapper.selectById(id);
    }

    /**
     * 追加审计日志。
     *
     * @param action     动作类型
     * @param targetKind 目标配置种类（可空）
     * @param operator   操作人
     * @param detail     明细（可空）
     */
    public void insertAudit(String action, ConfigKind targetKind, String operator, String detail) {
        ConfigAuditEntity audit = new ConfigAuditEntity();
        audit.setAction(action);
        audit.setTargetKind(targetKind == null ? null : targetKind.name());
        audit.setOperator(operator);
        audit.setDetail(detail);
        audit.setCreatedAt(Instant.now());
        auditMapper.insert(audit);
    }

    /** @return 全量审计日志（最新在前，管理端展示用） */
    public List<ConfigAuditEntity> listAudits() {
        return auditMapper.selectList(new LambdaQueryWrapper<ConfigAuditEntity>()
                .orderByDesc(ConfigAuditEntity::getId));
    }
}
