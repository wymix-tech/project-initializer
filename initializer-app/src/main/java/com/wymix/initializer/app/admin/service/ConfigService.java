package com.wymix.initializer.app.admin.service;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.entity.ConfigAuditEntity;
import com.wymix.initializer.app.admin.entity.ConfigDraftEntity;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import com.wymix.initializer.app.admin.refresh.CatalogRefresher;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshot;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理端配置应用服务（M3.1/M3.2）：草稿 CRUD、发布、回滚、发布历史与审计。
 *
 * <p>核心原则（概要设计 §6）：
 * <ul>
 *   <li>草稿-生效分离：CRUD 只写草稿，「发布」才触发全量校验 + 快照重建 + 原子切换</li>
 *   <li>发布整体拒绝：校验失败返回 4002 + 明细，绝不半生效；草稿保留可继续编辑，
 *       旧快照继续服务（热刷新失败自动回滚旧配置的语义在快照层天然成立）</li>
 *   <li>乐观锁：草稿携带加载时 version 更新，交叉修改后写方失败（4003）</li>
 *   <li>回滚（M3.2）：回滚 = 取历史发布全文重新走完整发布校验，通过后切换——
 *       不绕过校验通道，历史版本也可能因后续规则演进变得非法</li>
 * </ul>
 *
 * <p>线程安全性：无状态单例（并发发布由数据库乐观锁 + 快照原子切换保证一致性），线程安全。
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    /** 审计动作：草稿更新 */
    public static final String ACTION_UPDATE_DRAFT = "UPDATE_DRAFT";
    /** 审计动作：发布 */
    public static final String ACTION_PUBLISH = "PUBLISH";
    /** 审计动作：回滚（M3.2） */
    public static final String ACTION_ROLLBACK = "ROLLBACK";
    /** 审计动作：回滚失败（目标发布校验未通过） */
    public static final String ACTION_ROLLBACK_FAILED = "ROLLBACK_FAILED";

    private final ConfigRepository repository;
    private final CatalogRefresher refresher;
    private final CatalogSnapshotHolder snapshotHolder;

    /**
     * @param repository      配置存储仓库
     * @param refresher       清单校验/快照构建器
     * @param snapshotHolder  当前快照持有器
     */
    public ConfigService(ConfigRepository repository, CatalogRefresher refresher,
                         CatalogSnapshotHolder snapshotHolder) {
        this.repository = repository;
        this.refresher = refresher;
        this.snapshotHolder = snapshotHolder;
    }

    /**
     * 三通道 starter 清单合并（M3.3）：手工草稿（STARTER）+ 制品库同步层草稿
     * （STARTER_SYNC）多文档拼接为单一配置流。
     *
     * <p>同名条目以手工配置为准：拼接后 StarterCatalog 装载 ID 重复即失败（9002），
     * 因此同步层草稿在写入期已剔除与手工同名条目（CatalogMerger 合并语义），
     * 此处拼接是「先过滤后合并」链路的终点；若两通道出现同名（如同步后手工又新增），
     * 发布期整体拒绝并在错误明细中提示，绝不静默覆盖手工配置。
     *
     * @param starterDraft 手工 starter 草稿（可空）
     * @param syncDraft    制品库同步层草稿（可空）
     * @return 合并后的 starter 多文档 yml（两通道均空返回 null）
     */
    public static String mergedStarterYaml(ConfigDraftEntity starterDraft, ConfigDraftEntity syncDraft) {
        String manual = starterDraft == null ? null : starterDraft.getContentYaml();
        String synced = syncDraft == null ? null : syncDraft.getContentYaml();
        if (manual == null || manual.isBlank()) {
            return synced == null || synced.isBlank() ? null : synced;
        }
        if (synced == null || synced.isBlank()) {
            return manual;
        }
        return manual + "\n---\n" + synced;
    }

    /**
     * 读取指定种类当前草稿。
     *
     * @param kind 配置种类
     * @return 草稿视图（未保存过时 contentYaml 为 null）
     * @throws BusinessException kind 非法（4001，由 ConfigKind.parse 抛出）
     */
    public DraftView getDraft(ConfigKind kind) {
        ConfigDraftEntity entity = repository.findDraft(kind);
        return entity == null
                ? new DraftView(kind.lowerName(), null, null, null)
                : new DraftView(kind.lowerName(), entity.getContentYaml(), entity.getVersion(), entity.getUpdatedAt());
    }

    /**
     * 保存草稿（存在则乐观锁更新，不存在则首次插入）。
     *
     * @param kind   配置种类
     * @param yaml   配置原文
     * @param ifMatch 乐观锁期望版本（GET 草稿返回的 version；首次保存传 null）
     * @param operator 操作人
     * @throws BusinessException 乐观锁冲突（4003）/ 版本不匹配（4003 语义：If-Match 与当前不符）
     */
    public void saveDraft(ConfigKind kind, String yaml, Integer ifMatch, String operator) {
        ConfigDraftEntity entity = repository.findDraft(kind);
        if (entity == null) {
            if (ifMatch != null) {
                throw new BusinessException(ErrorCode.CONFIG_CONCURRENT_MODIFY,
                        "草稿尚不存在，无法按版本 " + ifMatch + " 更新（请先 GET 获取最新版本）");
            }
            repository.insertDraft(kind, yaml, operator);
        } else {
            if (ifMatch != null && !ifMatch.equals(entity.getVersion())) {
                throw new BusinessException(ErrorCode.CONFIG_CONCURRENT_MODIFY,
                        "草稿已被他人修改（当前版本 " + entity.getVersion() + "，请求基于版本 " + ifMatch + "）");
            }
            if (ifMatch == null) {
                // 未携带 If-Match 的更新：按当前版本走乐观锁（等价 last-write-wins 但保留 MyBatis-Plus 乐观锁路径）
                ifMatch = entity.getVersion();
            }
            entity.setVersion(ifMatch);
            repository.updateDraft(entity, yaml);
        }
        repository.insertAudit(ACTION_UPDATE_DRAFT, kind, operator, "草稿已更新");
        log.info("草稿已更新: kind={}, operator={}", kind.lowerName(), operator);
    }

    /**
     * 发布：读全部草稿 → 全量校验 → 快照构建 → 原子切换 → 发布历史落库。
     *
     * <p>校验失败整体拒绝（4002 + 明细清单），草稿保留；成功后新快照立即对全部读路径生效。
     *
     * @param operator 发布人
     * @param remark   发布备注（可空）
     * @return 发布结果（发布记录 ID + 快照概况）
     * @throws BusinessException 校验失败（4002/9002）或草稿缺失（4002）
     */
    public PublishResult publish(String operator, String remark) {
        ConfigDraftEntity metadataDraft = repository.findDraft(ConfigKind.METADATA);
        ConfigDraftEntity starterDraft = repository.findDraft(ConfigKind.STARTER);
        ConfigDraftEntity templateDraft = repository.findDraft(ConfigKind.TEMPLATE);
        ConfigDraftEntity conflictDraft = repository.findDraft(ConfigKind.CONFLICT_RULE);
        ConfigDraftEntity syncDraft = repository.findDraft(ConfigKind.STARTER_SYNC);

        CatalogSnapshot snapshot;
        try {
            snapshot = refresher.validateAndBuild(
                    metadataDraft == null ? null : metadataDraft.getContentYaml(),
                    mergedStarterYaml(starterDraft, syncDraft),
                    templateDraft == null ? null : templateDraft.getContentYaml(),
                    conflictDraft == null ? null : conflictDraft.getContentYaml());
        } catch (BusinessException e) {
            // 发布失败不写发布历史；审计留痕（含失败明细），供管理端展示
            repository.insertAudit("PUBLISH_FAILED", null, operator, e.getMessage());
            throw e;
        }

        ConfigReleaseEntity release = repository.insertRelease(
                metadataDraft.getContentYaml(),
                starterDraft == null ? null : starterDraft.getContentYaml(),
                templateDraft == null ? null : templateDraft.getContentYaml(),
                conflictDraft == null ? null : conflictDraft.getContentYaml(),
                operator, remark);
        snapshotHolder.swap(snapshot);
        repository.insertAudit(ACTION_PUBLISH, null, operator,
                "发布 #" + release.getId() + "：Java " + snapshot.metadataCatalog().javaVersions().size()
                        + " 个版本 / starter 清单 " + snapshot.starterCatalog().size()
                        + " 项 / 模板覆盖 " + snapshot.templateOverrides().size()
                        + " 个 / 冲突规则 " + snapshot.conflictCatalog().size() + " 条");
        log.info("配置已发布: releaseId={}, operator={}", release.getId(), operator);
        return new PublishResult(release.getId(),
                snapshot.metadataCatalog().javaVersions().size(),
                snapshot.starterCatalog().size(),
                snapshot.templateOverrides().size());
    }

    /**
     * 回滚到指定历史发布（M3.2）：取该版发布全文重新走完整发布校验，通过后原子切换。
     *
     * <p>不绕过校验通道：历史版本也可能因装载规则演进而不再合法，此时回滚被拒
     * （4002），旧快照继续服务；回滚成功写入新发布记录（链路可追溯）。
     *
     * @param releaseId 目标发布记录 ID
     * @param operator  操作人
     * @return 回滚结果（新发布记录 ID + 快照概况）
     * @throws BusinessException 发布记录不存在（4002 语义）或目标版本校验失败（4002）
     */
    public PublishResult rollback(long releaseId, String operator) {
        ConfigReleaseEntity release = repository.findRelease(releaseId);
        if (release == null) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "回滚失败: 发布记录不存在: #" + releaseId);
        }
        if (release.getMetadataYaml() == null) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "回滚失败: 发布记录 #" + releaseId + " 缺失 metadata 配置全文，无法重建快照");
        }
        CatalogSnapshot snapshot;
        try {
            snapshot = refresher.validateAndBuild(
                    release.getMetadataYaml(), release.getStarterYaml(), release.getTemplateYaml(),
                    release.getConflictYaml());
        } catch (BusinessException e) {
            repository.insertAudit(ACTION_ROLLBACK_FAILED, null, operator,
                    "回滚 #" + releaseId + " 失败: " + e.getMessage());
            throw e;
        }

        ConfigReleaseEntity newRelease = repository.insertRelease(
                release.getMetadataYaml(), release.getStarterYaml(), release.getTemplateYaml(),
                release.getConflictYaml(),
                operator, "回滚自 #" + releaseId);
        snapshotHolder.swap(snapshot);
        repository.insertAudit(ACTION_ROLLBACK, null, operator,
                "回滚 #" + releaseId + " → 新发布 #" + newRelease.getId());
        log.info("配置已回滚: from={}, to={}, operator={}", releaseId, newRelease.getId(), operator);
        return new PublishResult(newRelease.getId(),
                snapshot.metadataCatalog().javaVersions().size(),
                snapshot.starterCatalog().size(),
                snapshot.templateOverrides().size());
    }

    /** @return 发布历史（最新在前） */
    public List<ConfigReleaseEntity> listReleases() {
        return repository.listReleases();
    }

    /** @return 审计日志（最新在前） */
    public List<ConfigAuditEntity> listAudits() {
        return repository.listAudits();
    }

    /**
     * 草稿视图 DTO：kind + 内容 + 乐观锁版本 + 修改时间。
     *
     * <p>线程安全性：不可变对象，线程安全。
     */
    public record DraftView(String kind, String contentYaml, Integer version, java.time.Instant updatedAt) {
    }

    /**
     * 发布/回滚结果 DTO（M3.2 增加模板覆盖计数）。
     *
     * <p>线程安全性：不可变对象，线程安全。
     */
    public record PublishResult(long releaseId, int javaVersionCount, int starterCount, int templateOverrideCount) {

        /** M3.1 兼容构造：模板覆盖计数置 0。 */
        public PublishResult(long releaseId, int javaVersionCount, int starterCount) {
            this(releaseId, javaVersionCount, starterCount, 0);
        }
    }
}
