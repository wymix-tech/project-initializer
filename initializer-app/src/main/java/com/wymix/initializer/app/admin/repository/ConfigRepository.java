package com.wymix.initializer.app.admin.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.entity.CfgBootJavaEntity;
import com.wymix.initializer.app.admin.entity.CfgBootStarterRefEntity;
import com.wymix.initializer.app.admin.entity.CfgBootVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgConflictRuleEntity;
import com.wymix.initializer.app.admin.entity.CfgConflictRuleStarterEntity;
import com.wymix.initializer.app.admin.entity.CfgJavaVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterBootVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterDependencyEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageAbstractEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageTemplateEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageYmlEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterRequireGroupEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterRequireItemEntity;
import com.wymix.initializer.app.admin.entity.CfgTemplateEntity;
import com.wymix.initializer.app.admin.entity.ConfigAuditEntity;
import com.wymix.initializer.app.admin.entity.ConfigDraftMetaEntity;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import com.wymix.initializer.app.admin.mapper.CfgBootJavaMapper;
import com.wymix.initializer.app.admin.mapper.CfgBootStarterRefMapper;
import com.wymix.initializer.app.admin.mapper.CfgBootVersionMapper;
import com.wymix.initializer.app.admin.mapper.CfgConflictRuleMapper;
import com.wymix.initializer.app.admin.mapper.CfgConflictRuleStarterMapper;
import com.wymix.initializer.app.admin.mapper.CfgJavaVersionMapper;
import com.wymix.initializer.app.admin.mapper.CfgStarterBootVersionMapper;
import com.wymix.initializer.app.admin.mapper.CfgStarterDependencyMapper;
import com.wymix.initializer.app.admin.mapper.CfgStarterLinkageAbstractMapper;
import com.wymix.initializer.app.admin.mapper.CfgStarterLinkageTemplateMapper;
import com.wymix.initializer.app.admin.mapper.CfgStarterLinkageYmlMapper;
import com.wymix.initializer.app.admin.mapper.CfgStarterMapper;
import com.wymix.initializer.app.admin.mapper.CfgStarterRequireGroupMapper;
import com.wymix.initializer.app.admin.mapper.CfgStarterRequireItemMapper;
import com.wymix.initializer.app.admin.mapper.CfgTemplateMapper;
import com.wymix.initializer.app.admin.mapper.ConfigAuditMapper;
import com.wymix.initializer.app.admin.mapper.ConfigDraftMetaMapper;
import com.wymix.initializer.app.admin.mapper.ConfigReleaseMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置存储仓库（结构化改造 T1）：收口 {@code cfg_*} 关系表与版本/审计表的全部读写，
 * service 层不接触 Mapper 细节。
 *
 * <p><b>版本作用域</b>：全部 {@code cfg_*} 业务表以 {@code release_id} 分区——
 * {@link #DRAFT_RELEASE_ID}（0）为当前草稿工作区，{@code > 0} 为某次发布快照
 * （等于 {@code config_release.id}）。本类的全部读写方法都显式带作用域，
 * 不提供「无作用域」入口，从结构上杜绝「发布改写草稿」。
 *
 * <p><b>结构化快照载体</b>：{@link StructuredSnapshot} 把 15 张表的行按 {@code release_id}
 * 一次性批量查出（每表一条 SQL，无 N+1）后打包，供装配器消费；
 * {@link #copyAsRelease(long, long)} 逐表复制行实现「草稿 → 快照」，
 * {@link #deleteScope(long)} 整体清空某作用域。
 *
 * <p><b>乐观锁</b>：{@link #findDraftMeta}/{@link #updateDraftMeta} 围绕
 * {@code config_draft_meta.version} 工作，依赖 MyBatis-Plus
 * {@link com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor}：
 * 携带加载时 version 更新影响行数为 0 时收敛为 4003。
 *
 * <p>线程安全性：无状态单例（全部状态在入参/返回值中传递），线程安全。
 */
@Repository
public class ConfigRepository {

    /** 草稿工作区的版本作用域值（{@code cfg_*} 表 {@code release_id}）。 */
    public static final long DRAFT_RELEASE_ID = 0L;

    /** starter 来源通道：手工维护。 */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** starter 来源通道：制品库同步。 */
    public static final String SOURCE_SYNC = "SYNC";

    /** 「无旧主键」父行的映射哨兵键（见 {@link #registerParentPk}）。 */
    private static final long FIRST_PARENT_KEY = -1L;

    private final ConfigDraftMetaMapper draftMetaMapper;
    private final ConfigReleaseMapper releaseMapper;
    private final ConfigAuditMapper auditMapper;

    private final CfgJavaVersionMapper javaVersionMapper;
    private final CfgBootVersionMapper bootVersionMapper;
    private final CfgBootJavaMapper bootJavaMapper;
    private final CfgBootStarterRefMapper bootStarterRefMapper;
    private final CfgStarterMapper starterMapper;
    private final CfgStarterDependencyMapper starterDependencyMapper;
    private final CfgStarterRequireGroupMapper starterRequireGroupMapper;
    private final CfgStarterRequireItemMapper starterRequireItemMapper;
    private final CfgStarterBootVersionMapper starterBootVersionMapper;
    private final CfgStarterLinkageTemplateMapper starterLinkageTemplateMapper;
    private final CfgStarterLinkageYmlMapper starterLinkageYmlMapper;
    private final CfgStarterLinkageAbstractMapper starterLinkageAbstractMapper;
    private final CfgConflictRuleMapper conflictRuleMapper;
    private final CfgConflictRuleStarterMapper conflictRuleStarterMapper;
    private final CfgTemplateMapper templateMapper;

    /**
     * @param draftMetaMapper             草稿种类元信息 Mapper（kind 级乐观锁）
     * @param releaseMapper               发布历史头表 Mapper
     * @param auditMapper                 审计 Mapper
     * @param javaVersionMapper           Java 版本 Mapper
     * @param bootVersionMapper           Boot 版本 Mapper
     * @param bootJavaMapper              兼容矩阵 Mapper
     * @param bootStarterRefMapper        Boot 白名单 Mapper
     * @param starterMapper               starter 主表 Mapper
     * @param starterDependencyMapper     starter 依赖 Mapper
     * @param starterRequireGroupMapper   依赖联动分组 Mapper
     * @param starterRequireItemMapper    依赖联动组内项 Mapper
     * @param starterBootVersionMapper    starter ↔ Boot 版本 Mapper
     * @param starterLinkageTemplateMapper 模板联动 Mapper
     * @param starterLinkageYmlMapper     yml 片段联动 Mapper
     * @param starterLinkageAbstractMapper 抽象类联动 Mapper
     * @param conflictRuleMapper          冲突规则主表 Mapper
     * @param conflictRuleStarterMapper   冲突规则 starter 子表 Mapper
     * @param templateMapper              模板 Mapper
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ConfigRepository(ConfigDraftMetaMapper draftMetaMapper, ConfigReleaseMapper releaseMapper,
                            ConfigAuditMapper auditMapper,
                            CfgJavaVersionMapper javaVersionMapper,
                            CfgBootVersionMapper bootVersionMapper,
                            CfgBootJavaMapper bootJavaMapper,
                            CfgBootStarterRefMapper bootStarterRefMapper,
                            CfgStarterMapper starterMapper,
                            CfgStarterDependencyMapper starterDependencyMapper,
                            CfgStarterRequireGroupMapper starterRequireGroupMapper,
                            CfgStarterRequireItemMapper starterRequireItemMapper,
                            CfgStarterBootVersionMapper starterBootVersionMapper,
                            CfgStarterLinkageTemplateMapper starterLinkageTemplateMapper,
                            CfgStarterLinkageYmlMapper starterLinkageYmlMapper,
                            CfgStarterLinkageAbstractMapper starterLinkageAbstractMapper,
                            CfgConflictRuleMapper conflictRuleMapper,
                            CfgConflictRuleStarterMapper conflictRuleStarterMapper,
                            CfgTemplateMapper templateMapper) {
        this.draftMetaMapper = draftMetaMapper;
        this.releaseMapper = releaseMapper;
        this.auditMapper = auditMapper;
        this.javaVersionMapper = javaVersionMapper;
        this.bootVersionMapper = bootVersionMapper;
        this.bootJavaMapper = bootJavaMapper;
        this.bootStarterRefMapper = bootStarterRefMapper;
        this.starterMapper = starterMapper;
        this.starterDependencyMapper = starterDependencyMapper;
        this.starterRequireGroupMapper = starterRequireGroupMapper;
        this.starterRequireItemMapper = starterRequireItemMapper;
        this.starterBootVersionMapper = starterBootVersionMapper;
        this.starterLinkageTemplateMapper = starterLinkageTemplateMapper;
        this.starterLinkageYmlMapper = starterLinkageYmlMapper;
        this.starterLinkageAbstractMapper = starterLinkageAbstractMapper;
        this.conflictRuleMapper = conflictRuleMapper;
        this.conflictRuleStarterMapper = conflictRuleStarterMapper;
        this.templateMapper = templateMapper;
    }

    // ==================== 草稿种类元信息（kind 级乐观锁） ====================

    /**
     * 读取指定种类的草稿元信息。
     *
     * @param kind 配置种类
     * @return 元信息实体；无记录时返回 {@code null}
     */
    /** Hold until transaction completion; every draft writer takes this lock before reading versions. */
    public void lockDraftWorkspace() {
        draftMetaMapper.lockWorkspace();
    }

    public ConfigDraftMetaEntity findDraftMeta(ConfigKind kind) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            lockDraftWorkspace();
        }
        return draftMetaMapper.selectOne(new LambdaQueryWrapper<ConfigDraftMetaEntity>()
                .eq(ConfigDraftMetaEntity::getKind, kind.name()));
    }

    /** @return 全部草稿种类元信息（按自增主键升序，便于稳定断言） */
    public List<ConfigDraftMetaEntity> listDraftMeta() {
        return draftMetaMapper.selectList(new LambdaQueryWrapper<ConfigDraftMetaEntity>()
                .orderByAsc(ConfigDraftMetaEntity::getId));
    }

    /**
     * 插入草稿种类元信息（该 kind 首次保存）。
     *
     * @param kind      配置种类
     * @param updatedBy 修改人
     * @return 落库后的实体（含自增 id 与初始 version）
     */
    public ConfigDraftMetaEntity insertDraftMeta(ConfigKind kind, String updatedBy) {
        ConfigDraftMetaEntity entity = new ConfigDraftMetaEntity();
        entity.setKind(kind.name());
        entity.setVersion(0);
        entity.setUpdatedBy(updatedBy);
        entity.setUpdatedAt(Instant.now());
        draftMetaMapper.insert(entity);
        return entity;
    }

    /**
     * 乐观锁更新草稿元信息：实体携带的 {@code version} 与库中不一致时更新 0 行 → 抛 4003。
     *
     * <p>结构化草稿的保存流程约定：先调用本方法抢占 kind 级乐观锁，
     * 通过后再整体替换该 kind 对应的结构行（同一事务内），保证交叉编辑后写方失败。
     *
     * @param entity    加载后待更新的草稿元信息实体（携带 version）
     * @param updatedBy 修改人
     * @throws BusinessException 乐观锁冲突（错误码 {@link ErrorCode#CONFIG_CONCURRENT_MODIFY}）
     */
    public void updateDraftMeta(ConfigDraftMetaEntity entity, String updatedBy) {
        entity.setUpdatedBy(updatedBy);
        entity.setUpdatedAt(Instant.now());
        int rows = draftMetaMapper.updateById(entity);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CONFIG_CONCURRENT_MODIFY,
                    ErrorCode.CONFIG_CONCURRENT_MODIFY.getMessage());
        }
    }

    // ==================== 结构化快照读写 ====================

    /**
     * 一次性读出某版本作用域内的全部结构行（每表一条 SQL，共 15 条，无 N+1）。
     *
     * @param releaseId 版本作用域：0 = 草稿工作区，&gt; 0 = 发布快照 ID
     * @return 结构化快照载体（全部集合非 null）
     */
    public StructuredSnapshot readScope(long releaseId) {
        return new StructuredSnapshot(releaseId,
                javaVersionMapper.selectByReleaseId(releaseId),
                bootVersionMapper.selectByReleaseId(releaseId),
                bootJavaMapper.selectByReleaseId(releaseId),
                bootStarterRefMapper.selectByReleaseId(releaseId),
                starterMapper.selectByReleaseId(releaseId),
                starterDependencyMapper.selectByReleaseId(releaseId),
                starterRequireGroupMapper.selectByReleaseId(releaseId),
                starterRequireItemMapper.selectByReleaseId(releaseId),
                starterBootVersionMapper.selectByReleaseId(releaseId),
                starterLinkageTemplateMapper.selectByReleaseId(releaseId),
                starterLinkageYmlMapper.selectByReleaseId(releaseId),
                starterLinkageAbstractMapper.selectByReleaseId(releaseId),
                conflictRuleMapper.selectByReleaseId(releaseId),
                conflictRuleStarterMapper.selectByReleaseId(releaseId),
                templateMapper.selectByReleaseId(releaseId));
    }

    /**
     * 整体替换草稿工作区（{@code release_id = 0}）的全部结构行：先清空再写入入参快照。
     *
     * <p>表单化维护的语义就是「整体提交」：一次保存提交该模块的完整结构（而非增量 diff），
     * 因此以整体替换实现，天然避免孤儿子行残留。调用方须处于事务内。
     *
     * @param snapshot 新的草稿内容（{@code releaseId} 会被强制置为 {@link #DRAFT_RELEASE_ID}）
     * @return 实际写入的行数合计（审计用）
     */
    public int replaceDraft(StructuredSnapshot snapshot) {
        deleteScope(DRAFT_RELEASE_ID);
        return insertScope(snapshot, DRAFT_RELEASE_ID);
    }

    /**
     * 整体替换草稿（便捷重载）：由父行集合与「已解析父键」的子行集合构成。
     *
     * <p>调用方（表单化 API / 导入通道）自行构造好父子引用后调用
     * {@link #replaceDraft(StructuredSnapshot)}；唯一父行场景可用
     * {@link #replaceDraftSingleParent(StructuredSnapshot)} 让子行的空父键自动绑定该父行。
     *
     * @param snapshot 待写入快照
     * @param allowSingleParentBinding 是否允许子行空父键绑定到该类型的唯一父行
     * @return 实际写入行数
     */
    public int replaceDraft(StructuredSnapshot snapshot, boolean allowSingleParentBinding) {
        deleteScope(DRAFT_RELEASE_ID);
        return insertScope(snapshot, DRAFT_RELEASE_ID, allowSingleParentBinding);
    }

    /**
     * 整体替换草稿（唯一父行便捷版）：子行父键为空时自动绑定到该类型的唯一父行。
     *
     * <p>用于测试夹具与「单 starter / 单规则」这类天然唯一父行的写入场景；
     * 生产写入路径请显式给出父键，避免多父行场景下绑定歧义。
     *
     * @param snapshot 待写入快照
     * @return 实际写入行数
     */
    public int replaceDraftSingleParent(StructuredSnapshot snapshot) {
        return replaceDraft(snapshot, true);
    }

    /**
     * 把某作用域的全部结构行复制为新的发布快照（发布 = 复制草稿行为新 {@code release_id}）。
     *
     * <p>复制为整行浅拷贝 + 重置自增主键（置 null 由数据库重新分配），
     * 父行新主键在内存里建立「旧主键 → 新主键」映射后改写子行外键列，
     * 因此同一事务内一次完成，无需二次查库。
     *
     * @param sourceReleaseId 源作用域（发布场景通常是草稿 {@link #DRAFT_RELEASE_ID}，
     *                        回滚场景是历史快照 ID）
     * @param targetReleaseId 目标快照作用域（新 {@code config_release.id}）
     * @return 实际写入的行数合计（审计用）
     */
    public int copyAsRelease(long sourceReleaseId, long targetReleaseId) {
        StructuredSnapshot source = readScope(sourceReleaseId);
        return insertScope(source, targetReleaseId);
    }

    /**
     * 清空某版本作用域内的全部结构行（子表先删、父表后删，避免悬挂外键语义）。
     *
     * @param releaseId 版本作用域
     */
    public void deleteScope(long releaseId) {
        templateMapper.delete(new LambdaQueryWrapper<CfgTemplateEntity>()
                .eq(CfgTemplateEntity::getReleaseId, releaseId));
        conflictRuleStarterMapper.delete(new LambdaQueryWrapper<CfgConflictRuleStarterEntity>()
                .eq(CfgConflictRuleStarterEntity::getReleaseId, releaseId));
        conflictRuleMapper.delete(new LambdaQueryWrapper<CfgConflictRuleEntity>()
                .eq(CfgConflictRuleEntity::getReleaseId, releaseId));
        starterLinkageAbstractMapper.delete(new LambdaQueryWrapper<CfgStarterLinkageAbstractEntity>()
                .eq(CfgStarterLinkageAbstractEntity::getReleaseId, releaseId));
        starterLinkageYmlMapper.delete(new LambdaQueryWrapper<CfgStarterLinkageYmlEntity>()
                .eq(CfgStarterLinkageYmlEntity::getReleaseId, releaseId));
        starterLinkageTemplateMapper.delete(new LambdaQueryWrapper<CfgStarterLinkageTemplateEntity>()
                .eq(CfgStarterLinkageTemplateEntity::getReleaseId, releaseId));
        starterRequireItemMapper.delete(new LambdaQueryWrapper<CfgStarterRequireItemEntity>()
                .eq(CfgStarterRequireItemEntity::getReleaseId, releaseId));
        starterRequireGroupMapper.delete(new LambdaQueryWrapper<CfgStarterRequireGroupEntity>()
                .eq(CfgStarterRequireGroupEntity::getReleaseId, releaseId));
        starterBootVersionMapper.delete(new LambdaQueryWrapper<CfgStarterBootVersionEntity>()
                .eq(CfgStarterBootVersionEntity::getReleaseId, releaseId));
        starterDependencyMapper.delete(new LambdaQueryWrapper<CfgStarterDependencyEntity>()
                .eq(CfgStarterDependencyEntity::getReleaseId, releaseId));
        starterMapper.delete(new LambdaQueryWrapper<CfgStarterEntity>()
                .eq(CfgStarterEntity::getReleaseId, releaseId));
        bootStarterRefMapper.delete(new LambdaQueryWrapper<CfgBootStarterRefEntity>()
                .eq(CfgBootStarterRefEntity::getReleaseId, releaseId));
        bootJavaMapper.delete(new LambdaQueryWrapper<CfgBootJavaEntity>()
                .eq(CfgBootJavaEntity::getReleaseId, releaseId));
        bootVersionMapper.delete(new LambdaQueryWrapper<CfgBootVersionEntity>()
                .eq(CfgBootVersionEntity::getReleaseId, releaseId));
        javaVersionMapper.delete(new LambdaQueryWrapper<CfgJavaVersionEntity>()
                .eq(CfgJavaVersionEntity::getReleaseId, releaseId));
    }

    /**
     * 把入参快照的全部行写入指定作用域，并重建父子主键映射。
     *
     * <p>写库顺序：先写全部父行（Java/Boot/starter/冲突规则/模板）并登记新旧主键映射，
     * 再写全部子行（兼容矩阵/白名单/依赖/联动/规则成员），子行外键取映射后的新主键。
     * 因此本方法可安全用于「复制到新 release」与「整体替换草稿」两种场景。
     *
     * @param snapshot 源快照（{@code releaseId} 被忽略，以 {@code targetReleaseId} 落库）
     * @param targetReleaseId 目标作用域
     * @return 实际写入的行数合计
     */
    public int insertScope(StructuredSnapshot snapshot, long targetReleaseId) {
        return insertScope(snapshot, targetReleaseId, false);
    }

    /**
     * 把入参快照的全部行写入指定作用域，并重建父子主键映射（内部实现）。
     *
     * <p>写库顺序：先写全部父行（Java / Boot / starter / 冲突规则 / 模板）并登记新旧主键映射，
     * 再写全部子行（兼容矩阵 / 白名单 / 依赖 / 联动 / 规则成员 / 依赖分组），
     * 子行外键取映射后的新主键。因此本方法可安全用于「复制到新 release」与「整体替换草稿」。
     *
     * <p>唯一父行绑定（{@code allowSingleParentBinding}）：子行父键为 {@code null} 时，
     * 若该类型父行恰好只有一行则自动绑定之，否则抛异常。
     * 该开关主要服务于「单 starter / 单规则」的测试夹具与轻量调用，
     * 生产写入路径应显式给出父键，避免多父行场景的绑定歧义。
     *
     * @param snapshot               源快照（{@code releaseId} 被忽略，以 {@code targetReleaseId} 落库）
     * @param targetReleaseId        目标作用域
     * @param allowSingleParentBinding 是否允许子行空父键绑定唯一父行
     * @return 实际写入的行数合计
     */
    private int insertScope(StructuredSnapshot snapshot, long targetReleaseId,
                            boolean allowSingleParentBinding) {
        int rows = 0;

        for (CfgJavaVersionEntity row : snapshot.javaVersions()) {
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            javaVersionMapper.insert(row);
            rows++;
        }
        for (CfgBootVersionEntity row : snapshot.bootVersions()) {
            Long oldPk = row.getId();
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            bootVersionMapper.insert(row);
            registerParentPk(snapshot.bootPkMapping(), oldPk, row.getId());
            rows++;
        }
        for (CfgStarterEntity row : snapshot.starters()) {
            Long oldPk = row.getId();
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            starterMapper.insert(row);
            registerParentPk(snapshot.starterPkMapping(), oldPk, row.getId());
            rows++;
        }
        for (CfgConflictRuleEntity row : snapshot.conflictRules()) {
            Long oldPk = row.getId();
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            conflictRuleMapper.insert(row);
            registerParentPk(snapshot.conflictRulePkMapping(), oldPk, row.getId());
            rows++;
        }
        for (CfgTemplateEntity row : snapshot.templates()) {
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            templateMapper.insert(row);
            rows++;
        }

        for (CfgBootJavaEntity row : snapshot.bootJava()) {
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            row.setBootPk(resolveParentPk(snapshot, row.getBootPk(), ParentKind.BOOT, allowSingleParentBinding));
            // java_version_id 为业务 ID：故意不做主键换算，悬空引用原样保留，交发布期校验
            bootJavaMapper.insert(row);
            rows++;
        }
        for (CfgBootStarterRefEntity row : snapshot.bootStarterRefs()) {
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            row.setBootPk(resolveParentPk(snapshot, row.getBootPk(), ParentKind.BOOT, allowSingleParentBinding));
            bootStarterRefMapper.insert(row);
            rows++;
        }
        for (CfgStarterDependencyEntity row : snapshot.starterDependencies()) {
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            row.setStarterPk(resolveParentPk(snapshot, row.getStarterPk(), ParentKind.STARTER, allowSingleParentBinding));
            starterDependencyMapper.insert(row);
            rows++;
        }
        for (CfgStarterRequireGroupEntity row : snapshot.starterRequireGroups()) {
            Long oldPk = row.getId();
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            row.setStarterPk(resolveParentPk(snapshot, row.getStarterPk(), ParentKind.STARTER, allowSingleParentBinding));
            starterRequireGroupMapper.insert(row);
            registerParentPk(snapshot.requireGroupPkMapping(), oldPk, row.getId());
            rows++;
        }
        for (CfgStarterRequireItemEntity row : snapshot.starterRequireItems()) {
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            row.setGroupPk(resolveParentPk(snapshot, row.getGroupPk(), ParentKind.REQUIRE_GROUP, allowSingleParentBinding));
            starterRequireItemMapper.insert(row);
            rows++;
        }
        for (CfgStarterBootVersionEntity row : snapshot.starterBootVersions()) {
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            row.setStarterPk(resolveParentPk(snapshot, row.getStarterPk(), ParentKind.STARTER, allowSingleParentBinding));
            starterBootVersionMapper.insert(row);
            rows++;
        }
        for (CfgStarterLinkageTemplateEntity row : snapshot.starterLinkageTemplates()) {
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            row.setStarterPk(resolveParentPk(snapshot, row.getStarterPk(), ParentKind.STARTER, allowSingleParentBinding));
            starterLinkageTemplateMapper.insert(row);
            rows++;
        }
        for (CfgStarterLinkageYmlEntity row : snapshot.starterLinkageYmls()) {
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            row.setStarterPk(resolveParentPk(snapshot, row.getStarterPk(), ParentKind.STARTER, allowSingleParentBinding));
            starterLinkageYmlMapper.insert(row);
            rows++;
        }
        for (CfgStarterLinkageAbstractEntity row : snapshot.starterLinkageAbstracts()) {
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            row.setStarterPk(resolveParentPk(snapshot, row.getStarterPk(), ParentKind.STARTER, allowSingleParentBinding));
            starterLinkageAbstractMapper.insert(row);
            rows++;
        }
        for (CfgConflictRuleStarterEntity row : snapshot.conflictRuleStarters()) {
            row.setId(null);
            row.setReleaseId(targetReleaseId);
            row.setRulePk(resolveParentPk(snapshot, row.getRulePk(), ParentKind.CONFLICT_RULE, allowSingleParentBinding));
            conflictRuleStarterMapper.insert(row);
            rows++;
        }
        return rows;
    }

    // ==================== T1 → T2 过渡桥（待 T2 装配器接管后移除） ====================

    /**
     * 兼容读取入口（T1 过渡）：返回指定 kind 在草稿作用域内的结构行投影。
     *
     * <p>T1 只交付持久层，尚未引入结构化装配器（T2），服务层仍按语义文本工作。
     * 本方法为过渡期提供给服务层的唯一读取入口：kind → 结构行投影由
     * {@link #draftScopeOf(ConfigKind, StructuredSnapshot)} 决定，待 T2 的
     * {@code StructuredCatalogAssembler} 落地后由装配器替代本方法。
     *
     * @param kind 配置种类（决定结构行投影范围）
     * @return 草稿作用域内的结构行快照（不可空；无行时为空快照）
     */
    public StructuredSnapshot readDraftScope(ConfigKind kind) {
        return draftScopeOf(kind, readScope(DRAFT_RELEASE_ID));
    }

    /**
     * kind 语义 → 草稿结构行投影（T1 过渡）。
     *
     * <p>{@code STARTER} 与 {@code STARTER_SYNC} 共享 {@code cfg_starter} 表，
     * 以 {@code source} 列区分（MANUAL / SYNC），故两者投影为同一批父行但只保留各自来源的行。
     *
     * @param kind 配置种类
     * @param all  草稿作用域全量快照
     * @return 该 kind 语义下的结构行快照
     */
    private static StructuredSnapshot draftScopeOf(ConfigKind kind, StructuredSnapshot all) {
        return switch (kind) {
            case METADATA -> new StructuredSnapshot(all.releaseId(), all.javaVersions(), all.bootVersions(),
                    all.bootJava(), all.bootStarterRefs(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            case STARTER -> filterSource(all, SOURCE_MANUAL);
            case STARTER_SYNC -> filterSource(all, SOURCE_SYNC);
            case CONFLICT_RULE -> new StructuredSnapshot(all.releaseId(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), all.conflictRules(), all.conflictRuleStarters(), List.of());
            case TEMPLATE -> new StructuredSnapshot(all.releaseId(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), all.templates());
        };
    }

    /**
     * 按来源通道过滤 starter 结构行（T1 过渡）：只保留指定 {@code source} 的 starter 父行
     * 及其全部子行（依赖 / 联动 / Boot 版本关联 / 依赖分组与组内项）。
     *
     * @param all    草稿作用域全量快照
     * @param source 目标来源通道（{@link #SOURCE_MANUAL} / {@link #SOURCE_SYNC}）
     * @return 只含目标通道 starter 的快照
     */
    private static StructuredSnapshot filterSource(StructuredSnapshot all, String source) {
        Set<Long> keptPks = new HashSet<>();
        List<CfgStarterEntity> starters = new ArrayList<>();
        for (CfgStarterEntity row : all.starters()) {
            if (source.equals(row.getSource())) {
                starters.add(row);
                keptPks.add(row.getId());
            }
        }
        List<CfgStarterRequireGroupEntity> groups = new ArrayList<>();
        Set<Long> keptGroupPks = new HashSet<>();
        for (CfgStarterRequireGroupEntity row : all.starterRequireGroups()) {
            if (keptPks.contains(row.getStarterPk())) {
                groups.add(row);
                keptGroupPks.add(row.getId());
            }
        }
        return new StructuredSnapshot(all.releaseId(), List.of(), List.of(), List.of(), List.of(),
                starters,
                all.starterDependencies().stream().filter(r -> keptPks.contains(r.getStarterPk())).toList(),
                groups,
                all.starterRequireItems().stream().filter(r -> keptGroupPks.contains(r.getGroupPk())).toList(),
                all.starterBootVersions().stream().filter(r -> keptPks.contains(r.getStarterPk())).toList(),
                all.starterLinkageTemplates().stream().filter(r -> keptPks.contains(r.getStarterPk())).toList(),
                all.starterLinkageYmls().stream().filter(r -> keptPks.contains(r.getStarterPk())).toList(),
                all.starterLinkageAbstracts().stream().filter(r -> keptPks.contains(r.getStarterPk())).toList(),
                List.of(), List.of(), List.of());
    }

    /**
     * 追加写入（T1 过渡）：把结构行快照写入草稿作用域（不先清空）。
     *
     * <p>供 {@code StructuredDraftWriter} 的两阶段写库（先写父行拿主键、再写子行）
     * 与制品库同步链路使用；手工表单的「整体替换」语义走
     * {@link #replaceDraft(StructuredSnapshot)}。
     *
     * @param snapshot 待写入的结构行快照（{@code releaseId} 被强制为草稿作用域）
     * @return 实际写入的行数合计
     */
    public int appendToDraft(StructuredSnapshot snapshot) {
        return insertScope(snapshot, DRAFT_RELEASE_ID, false);
    }

    /**
     * 追加写入草稿作用域，并允许子行空父键绑定到该类型的唯一父行（写入器专用）。
     *
     * <p>{@code StructuredDraftWriter} 构造的是「父行对象尚无主键、子行引用该对象」的内存图，
     * 落库顺序由 {@link #insertScope} 保证，父键由写入期映射（含空键哨兵）换算，
     * 因此需要本重载；普通调用方请用 {@link #appendToDraft(StructuredSnapshot)}
     * 以获得「缺父键即拒绝」的显式保护。
     *
     * @param snapshot 待写入的结构行快照（含父子引用）
     * @return 实际写入的行数合计
     */
    public int appendDraftAllowParentBinding(StructuredSnapshot snapshot) {
        return insertScope(snapshot, DRAFT_RELEASE_ID, true);
    }

    /**
     * 删除草稿作用域内指定来源通道的全部 starter 行（同名覆盖语义所需，T1 过渡）。
     *
     * <p>子表先删、父表后删，避免悬挂引用。
     *
     * @param source 来源通道（{@link #SOURCE_MANUAL} / {@link #SOURCE_SYNC}）
     * @return 删除的 starter 父行数
     */
    public int deleteDraftStartersBySource(String source) {
        StructuredSnapshot all = readScope(DRAFT_RELEASE_ID);
        List<Long> pks = all.starters().stream()
                .filter(r -> source.equals(r.getSource()))
                .map(CfgStarterEntity::getId)
                .toList();
        if (pks.isEmpty()) {
            return 0;
        }
        starterLinkageAbstractMapper.delete(new LambdaQueryWrapper<CfgStarterLinkageAbstractEntity>()
                .eq(CfgStarterLinkageAbstractEntity::getReleaseId, DRAFT_RELEASE_ID)
                .in(CfgStarterLinkageAbstractEntity::getStarterPk, pks));
        starterLinkageYmlMapper.delete(new LambdaQueryWrapper<CfgStarterLinkageYmlEntity>()
                .eq(CfgStarterLinkageYmlEntity::getReleaseId, DRAFT_RELEASE_ID)
                .in(CfgStarterLinkageYmlEntity::getStarterPk, pks));
        starterLinkageTemplateMapper.delete(new LambdaQueryWrapper<CfgStarterLinkageTemplateEntity>()
                .eq(CfgStarterLinkageTemplateEntity::getReleaseId, DRAFT_RELEASE_ID)
                .in(CfgStarterLinkageTemplateEntity::getStarterPk, pks));
        List<Long> groupPks = all.starterRequireGroups().stream()
                .filter(r -> pks.contains(r.getStarterPk()))
                .map(CfgStarterRequireGroupEntity::getId)
                .toList();
        if (!groupPks.isEmpty()) {
            starterRequireItemMapper.delete(new LambdaQueryWrapper<CfgStarterRequireItemEntity>()
                    .eq(CfgStarterRequireItemEntity::getReleaseId, DRAFT_RELEASE_ID)
                    .in(CfgStarterRequireItemEntity::getGroupPk, groupPks));
        }
        starterRequireGroupMapper.delete(new LambdaQueryWrapper<CfgStarterRequireGroupEntity>()
                .eq(CfgStarterRequireGroupEntity::getReleaseId, DRAFT_RELEASE_ID)
                .in(CfgStarterRequireGroupEntity::getStarterPk, pks));
        starterBootVersionMapper.delete(new LambdaQueryWrapper<CfgStarterBootVersionEntity>()
                .eq(CfgStarterBootVersionEntity::getReleaseId, DRAFT_RELEASE_ID)
                .in(CfgStarterBootVersionEntity::getStarterPk, pks));
        starterDependencyMapper.delete(new LambdaQueryWrapper<CfgStarterDependencyEntity>()
                .eq(CfgStarterDependencyEntity::getReleaseId, DRAFT_RELEASE_ID)
                .in(CfgStarterDependencyEntity::getStarterPk, pks));
        return starterMapper.delete(new LambdaQueryWrapper<CfgStarterEntity>()
                .eq(CfgStarterEntity::getReleaseId, DRAFT_RELEASE_ID)
                .in(CfgStarterEntity::getId, pks));
    }

    /**
     * 按 kind 整体清空草稿作用域（T1 过渡）：只清该 kind 语义下的结构行，不触碰其他 kind。
     *
     * @param kind 配置种类
     * @return 删除的行数合计
     */
    public int clearDraftKind(ConfigKind kind) {
        return switch (kind) {
            case STARTER -> deleteDraftStartersBySource(SOURCE_MANUAL);
            case STARTER_SYNC -> deleteDraftStartersBySource(SOURCE_SYNC);
            case TEMPLATE -> templateMapper.delete(new LambdaQueryWrapper<CfgTemplateEntity>()
                    .eq(CfgTemplateEntity::getReleaseId, DRAFT_RELEASE_ID));
            case CONFLICT_RULE -> clearDraftConflictRules();
            case METADATA -> clearDraftMetadata();
        };
    }

    /**
     * 清空草稿作用域内的元数据行（兼容矩阵 / 白名单子表先删，父表后删）。
     *
     * @return 删除的父行数合计
     */
    private int clearDraftMetadata() {
        bootJavaMapper.delete(new LambdaQueryWrapper<CfgBootJavaEntity>()
                .eq(CfgBootJavaEntity::getReleaseId, DRAFT_RELEASE_ID));
        bootStarterRefMapper.delete(new LambdaQueryWrapper<CfgBootStarterRefEntity>()
                .eq(CfgBootStarterRefEntity::getReleaseId, DRAFT_RELEASE_ID));
        int bootRows = bootVersionMapper.delete(new LambdaQueryWrapper<CfgBootVersionEntity>()
                .eq(CfgBootVersionEntity::getReleaseId, DRAFT_RELEASE_ID));
        int javaRows = javaVersionMapper.delete(new LambdaQueryWrapper<CfgJavaVersionEntity>()
                .eq(CfgJavaVersionEntity::getReleaseId, DRAFT_RELEASE_ID));
        return bootRows + javaRows;
    }

    /**
     * 清空草稿作用域内的冲突规则行（成员子表先删）。
     *
     * @return 删除的规则父行数
     */
    private int clearDraftConflictRules() {
        conflictRuleStarterMapper.delete(new LambdaQueryWrapper<CfgConflictRuleStarterEntity>()
                .eq(CfgConflictRuleStarterEntity::getReleaseId, DRAFT_RELEASE_ID));
        return conflictRuleMapper.delete(new LambdaQueryWrapper<CfgConflictRuleEntity>()
                .eq(CfgConflictRuleEntity::getReleaseId, DRAFT_RELEASE_ID));
    }

    /** 父行类型（决定使用哪张父主键映射表）。 */
    private enum ParentKind {
        /** Boot 版本父行（{@code cfg_boot_java.boot_pk} / {@code cfg_boot_starter_ref.boot_pk} 引用） */
        BOOT,
        /** starter 父行（starter 各子表 {@code starter_pk} 引用） */
        STARTER,
        /** 依赖联动分组父行（{@code cfg_starter_require_item.group_pk} 引用） */
        REQUIRE_GROUP,
        /** 冲突规则父行（{@code cfg_conflict_rule_starter.rule_pk} 引用） */
        CONFLICT_RULE
    }

    /**
     * 登记父行「旧主键 → 新主键」映射（写入期调用）。
     *
     * <p>{@code oldPk} 为空时（调用方构造的父行对象尚未落库，属于「无旧主键」场景）
     * 以 {@link #FIRST_PARENT_KEY} 哨兵键登记，使「子行空父键绑定唯一父行」有据可查；
     * 同一类型父行只允许一个「无旧主键」行，出现第二个即说明调用方构造有误。
     *
     * @param mapping 该父行类型的主键映射
     * @param oldPk   源行旧主键（可空）
     * @param newPk   落库后的新主键
     * @throws IllegalStateException 同一类型出现多个「无旧主键」父行
     */
    private static void registerParentPk(Map<Long, Long> mapping, Long oldPk, Long newPk) {
        if (oldPk == null) {
            if (mapping.putIfAbsent(FIRST_PARENT_KEY, newPk) != null) {
                throw new IllegalStateException("同一类型出现多个「无旧主键」父行，父键引用有歧义");
            }
            return;
        }
        mapping.put(oldPk, newPk);
    }

    /**
     * 父子主键解析：把入参快照里的旧父主键换算为新写入行的自增主键，
     * 或在允许时把空父键绑定到该类型的唯一父行。
     *
     * <p>正常路径（子行显式给出父键）只查 {@link StructuredSnapshot} 上的写入期映射，
     * 不查库；映射由父行落库时登记。
     *
     * @param snapshot               源快照（持有写入期映射）
     * @param oldPk                  源行旧父主键（可 null，此时走唯一父行绑定）
     * @param kind                   父行类型
     * @param allowSingleParentBinding 是否允许空父键绑定唯一父行
     * @return 新写入的父主键
     * @throws IllegalStateException 父键缺失、无映射或唯一父行绑定歧义（数据不自洽，属编程错误）
     */
    private long resolveParentPk(StructuredSnapshot snapshot, Long oldPk, ParentKind kind,
                                 boolean allowSingleParentBinding) {
        Map<Long, Long> mapping = switch (kind) {
            case BOOT -> snapshot.bootPkMapping();
            case STARTER -> snapshot.starterPkMapping();
            case REQUIRE_GROUP -> snapshot.requireGroupPkMapping();
            case CONFLICT_RULE -> snapshot.conflictRulePkMapping();
        };
        if (oldPk == null) {
            if (!allowSingleParentBinding) {
                throw new IllegalStateException(
                        "子行缺少父键引用（kind=" + kind + "），拒绝写入悬挂外键行");
            }
            Long bound = mapping.get(FIRST_PARENT_KEY);
            if (bound == null) {
                throw new IllegalStateException(
                        "子行父键缺失且 " + kind + " 父行未写入（无绑定目标）");
            }
            if (mapping.size() != 1) {
                throw new IllegalStateException(
                        "子行父键缺失且 " + kind + " 父行不唯一（" + mapping.size() + " 个映射键），绑定歧义");
            }
            return bound;
        }
        Long mapped = mapping.get(oldPk);
        if (mapped == null) {
            throw new IllegalStateException(
                    "子行父键无映射（kind=" + kind + ", pk=" + oldPk + "），数据不自洽");
        }
        return mapped;
    }

    // ==================== T3：按 kind 的结构化草稿替换（表单化写入） ====================

    /**
     * 整体替换草稿作用域内的元数据行（T3 表单化写入）：子表先删、父表后删，再整行写入。
     *
     * @param snapshot 新的元数据结构行（{@code releaseId} 被强制为草稿作用域）
     * @return 实际写入的行数合计
     */
    public int replaceDraftMetadata(StructuredSnapshot snapshot) {
        clearDraftMetadata();
        return insertScope(snapshot, DRAFT_RELEASE_ID);
    }

    /**
     * 整体替换草稿作用域内指定来源通道的 starter 行（T3 表单化写入）。
     *
     * <p>只清目标通道（{@code MANUAL} / {@code SYNC}）的父行及其子行，
     * 另一通道零改动——这是「手工清单」与「制品库同步层」并存语义的持久层保证。
     *
     * @param source   来源通道（{@link #SOURCE_MANUAL} / {@link #SOURCE_SYNC}）
     * @param snapshot 新的 starter 结构行（父行可带占位负主键，写入期换算）
     * @return 实际写入的行数合计
     */
    public int replaceDraftStartersBySource(String source, StructuredSnapshot snapshot) {
        deleteDraftStartersBySource(source);
        return insertScope(snapshot, DRAFT_RELEASE_ID);
    }

    /**
     * 整体替换草稿作用域内的冲突规则行（T3 表单化写入）：成员子表先删、规则父表后删，再整行写入。
     *
     * @param snapshot 新的冲突规则结构行（父行可带占位负主键，写入期换算）
     * @return 实际写入的行数合计
     */
    public int replaceDraftConflictRules(StructuredSnapshot snapshot) {
        clearDraftConflictRules();
        return insertScope(snapshot, DRAFT_RELEASE_ID);
    }

    /**
     * 整体替换草稿作用域内的模板行（T3 表单化写入）。
     *
     * @param snapshot 新的模板结构行
     * @return 实际写入的行数合计
     */
    public int replaceDraftTemplates(StructuredSnapshot snapshot) {
        templateMapper.delete(new LambdaQueryWrapper<CfgTemplateEntity>()
                .eq(CfgTemplateEntity::getReleaseId, DRAFT_RELEASE_ID));
        return insertScope(snapshot, DRAFT_RELEASE_ID);
    }

    // ==================== T3：模板轻量查询（列表不搬 CLOB） ====================

    /**
     * 查询草稿作用域内的模板**摘要**（只取 name / sort / updated_at，**不查 content CLOB**）。
     *
     * <p>模板全文（{@code cfg_template.content}）可能很大，列表接口只展示名称与更新时间；
     * 调用方按需再走 {@link #findDraftTemplate(String)} 取全文（T3 实现要点：CLOB 不进列表）。
     *
     * @return 模板摘要行（{@code content} 恒为 {@code null}；无模板返回空列表）
     */
    public List<CfgTemplateEntity> listDraftTemplateSummaries() {
        return templateMapper.selectList(new LambdaQueryWrapper<CfgTemplateEntity>()
                .select(CfgTemplateEntity::getId, CfgTemplateEntity::getReleaseId,
                        CfgTemplateEntity::getName, CfgTemplateEntity::getSort,
                        CfgTemplateEntity::getUpdatedAt)
                .eq(CfgTemplateEntity::getReleaseId, DRAFT_RELEASE_ID)
                .orderByAsc(CfgTemplateEntity::getSort));
    }

    /**
     * 查询草稿作用域内的模板**全量行（含 content 全文）**。
     *
     * <p>用于「按 name 幂等 upsert / 单条删除」这类需要保留其余模板全文的写入路径；
     * 只读列表请用 {@link #listDraftTemplateSummaries()} 以避免搬运 CLOB。
     *
     * @return 模板全量行（无模板返回空列表）
     */
    public List<CfgTemplateEntity> listDraftTemplates() {
        return templateMapper.selectList(new LambdaQueryWrapper<CfgTemplateEntity>()
                .eq(CfgTemplateEntity::getReleaseId, DRAFT_RELEASE_ID)
                .orderByAsc(CfgTemplateEntity::getSort));
    }

    /**
     * 按模板名查询草稿作用域内的单条模板（含 content 全文）。
     *
     * @param name 模板名（相对 templates/ 路径）
     * @return 模板行；不存在返回 {@code null}
     */
    public CfgTemplateEntity findDraftTemplate(String name) {
        return templateMapper.selectOne(new LambdaQueryWrapper<CfgTemplateEntity>()
                .eq(CfgTemplateEntity::getReleaseId, DRAFT_RELEASE_ID)
                .eq(CfgTemplateEntity::getName, name));
    }

    // ==================== 发布历史与审计 ====================

    /**
     * 写入一版发布历史头（结构化快照行由 {@link #copyAsRelease(long, long)} 写）。
     *
     * @param releasedBy 发布人
     * @param remark     发布备注（可空）
     * @return 落库后的发布记录（含自增 id，即新快照的 {@code release_id}）
     */
    public ConfigReleaseEntity insertRelease(String releasedBy, String remark) {
        ConfigReleaseEntity release = new ConfigReleaseEntity();
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
     * 取最新一版发布记录（启动装表入口）。
     *
     * @return 最新发布记录；从未发布过返回 {@code null}
     */
    public ConfigReleaseEntity findLatestRelease() {
        return releaseMapper.selectList(new LambdaQueryWrapper<ConfigReleaseEntity>()
                        .orderByDesc(ConfigReleaseEntity::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    /**
     * 追加审计日志。
     *
     * @param action     动作类型
     * @param targetKind 目标配置种类（可空）
     * @param operator   操作人
     * @param detail     明细（可空；超出长度自动截断，不打印模板全文与 token）
     */
    public void insertAudit(String action, ConfigKind targetKind, String operator, String detail) {
        ConfigAuditEntity audit = new ConfigAuditEntity();
        audit.setAction(action);
        audit.setTargetKind(targetKind == null ? null : targetKind.name());
        audit.setOperator(operator);
        audit.setDetail(AuditDetailTruncator.truncate(detail));
        audit.setCreatedAt(Instant.now());
        auditMapper.insert(audit);
    }

    /** @return 全量审计日志（最新在前，管理端展示用） */
    public List<ConfigAuditEntity> listAudits() {
        return auditMapper.selectList(new LambdaQueryWrapper<ConfigAuditEntity>()
                .orderByDesc(ConfigAuditEntity::getId));
    }
}
