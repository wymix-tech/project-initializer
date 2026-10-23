package com.wymix.initializer.app.admin.service;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.entity.ConfigAuditEntity;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshot;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder;
import com.wymix.initializer.app.admin.refresh.StructuredCatalogAssembler;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.entity.ConfigDraftMetaEntity;
import com.wymix.initializer.app.admin.repository.StructuredDraftWriter;
import com.wymix.initializer.app.admin.repository.StructuredSnapshot;
import com.wymix.initializer.app.admin.repository.StructuredYamlCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 管理端配置应用服务（M3.1/M3.2 → 结构化改造 T1）：草稿 CRUD、发布、回滚、发布历史与审计。
 *
 * <p><b>结构化改造（T1/T2）后的草稿载体与快照链路</b>：草稿正文已从
 * {@code config_draft.content_yaml} 迁到 {@code cfg_*} 关系表的 {@code release_id = 0} 行；
 * kind 级乐观锁迁到 {@code config_draft_meta.version}。
 * <b>T2 起发布/回滚的快照构建改由 {@link StructuredCatalogAssembler} 直装表行</b>
 * （不再经 YAML 往返）；本类对**草稿读写**仍以 YAML 文本为契约
 * （HTTP 层与既有测试不变），经 {@link StructuredYamlCodec} 在「结构行 ↔ 语义视图」之间转换，
 * 该契约将在 T3 表单化 API 落地后收敛为结构化 DTO。
 *
 * <p>核心原则（概要设计 §6）：
 * <ul>
 *   <li>草稿-生效分离：CRUD 只写草稿，「发布」才触发全量校验 + 快照重建 + 原子切换</li>
 *   <li>发布整体拒绝：校验失败返回 4002 + 明细，绝不半生效；草稿保留可继续编辑，
 *       旧快照继续服务（热刷新失败自动回滚旧配置的语义在快照层天然成立）</li>
 *   <li>乐观锁：草稿携带加载时 version 更新，交叉修改后写方失败（4003）</li>
 *   <li>回滚（M3.2）：回滚 = 取历史发布全文重新走完整发布校验，通过后切换——
 *       不绕过校验通道，历史版本也可能因后续规则演进变得非法</li>
 *   <li>发布原子性：发布/回滚整体处于同一数据库事务内，并叠加进程内互斥锁；
 *       草稿写入与发布共用数据库工作区锁，验证与归档复用同一份结构行；
 *       快照仅在数据库提交成功后切换，提交失败不暴露未提交内容</li>
 * </ul>
 *
 * <p>线程安全性：无状态单例（并发发布由进程内锁 + 数据库事务 + 快照原子切换保证一致性），线程安全。
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
    @org.springframework.beans.factory.annotation.Autowired
    private FailureAuditService failureAudit;

    private void failedAudit(String action, String operator, String detail) {
        if (failureAudit != null) {
            failureAudit.record(action, null, operator, detail);
        } else {
            repository.insertAudit(action, null, operator, detail);
        }
    }
    private final StructuredCatalogAssembler assembler;
    private final CatalogSnapshotHolder snapshotHolder;
    /** 结构行 ↔ 语义视图码编器（T1 过渡；T2 装配器落地后由装配器取代）。 */
    private final StructuredYamlCodec codec;
    /** YAML 语义文本 → 结构行写入器（T1 过渡；T3 表单化 API 落地后收缩为导入通道专用）。 */
    private final StructuredDraftWriter draftWriter;

    /** 发布/回滚进程内互斥锁：避免并发发布同时通过校验后互相覆盖快照与发布记录。 */
    private final Object publishLock = new Object();

    /**
     * @param repository     配置存储仓库（结构化 cfg_* 表读写）
     * @param assembler      结构化目录装配器（T2：表行 → 核心模型 → 快照，替代 YAML 校验通道）
     * @param snapshotHolder 当前快照持有器
     * @param codec          结构行 ↔ 语义视图码编器（草稿读取过渡桥；T3/T4 收缩为导入导出旁路）
     * @param draftWriter    YAML 语义文本 → 结构行写入器（草稿保存过渡桥；T3/T4 收缩为导入通道）
     */
    public ConfigService(ConfigRepository repository, StructuredCatalogAssembler assembler,
                         CatalogSnapshotHolder snapshotHolder, StructuredYamlCodec codec,
                         StructuredDraftWriter draftWriter) {
        this.repository = repository;
        this.assembler = assembler;
        this.snapshotHolder = snapshotHolder;
        this.codec = codec;
        this.draftWriter = draftWriter;
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
     * <p><b>T2 起发布链路不再调用本方法</b>：{@code STARTER} 与 {@code STARTER_SYNC}
     * 的行同处草稿作用域，由 {@link StructuredCatalogAssembler} 一次性读取装配，
     * 「同名优先」由写入期 {@code CatalogMerger} 保证（装配期 ID 重复即 4002）。
     * 本方法保留给**导入导出（T4）与三通道拼接参照**，避免删除公共 API 造成二次改动。
     *
     * @param manual 手工 starter 多文档 yml（可空）
     * @param synced 制品库同步层多文档 yml（可空）
     * @return 合并后的 starter 多文档 yml（两通道均空返回 null）
     */
    public static String mergedStarterYaml(String manual, String synced) {
        if (manual == null || manual.isBlank()) {
            return synced == null || synced.isBlank() ? null : synced;
        }
        if (synced == null || synced.isBlank()) {
            return manual;
        }
        return manual + "\n---\n" + synced;
    }

    /**
     * 读取指定种类当前草稿（结构化）：读 {@code cfg_*} 草稿行 → 渲染为 YAML 同构文本。
     *
     * <p>对外契约保持 YAML 文本不变（HTTP 层与既有测试零改动），
     * 结构化 DTO 契约由 T3 交付；版本取 {@code config_draft_meta.version}（kind 级乐观锁）。
     *
     * @param kind 配置种类
     * @return 草稿视图（该 kind 尚无结构行时 contentYaml / version 为 null）
     * @throws BusinessException kind 非法（4001，由 ConfigKind.parse 抛出）
     */
    public DraftView getDraft(ConfigKind kind) {
        ConfigDraftMetaEntity meta = repository.findDraftMeta(kind);
        String yaml = renderDraft(kind);
        return new DraftView(kind.lowerName(), yaml,
                meta == null ? null : meta.getVersion(),
                meta == null ? null : meta.getUpdatedAt());
    }

    /**
     * 渲染指定 kind 的草稿结构行（T1 过渡；T2 装配器接管后本方法删除）。
     *
     * @param kind 配置种类
     * @return YAML 同构文本；该 kind 无结构行时返回 {@code null}
     */
    private String renderDraft(ConfigKind kind) {
        StructuredSnapshot snapshot = repository.readDraftScope(kind);
        return switch (kind) {
            case METADATA -> codec.renderMetadata(snapshot);
            case STARTER, STARTER_SYNC -> codec.renderStarters(snapshot);
            case CONFLICT_RULE -> codec.renderConflictRules(snapshot);
            case TEMPLATE -> codec.renderTemplates(snapshot);
        };
    }

    /**
     * 保存草稿（结构化）：kind 级乐观锁判定通过后，整体替换该 kind 的结构行。
     *
     * <p>写入顺序：先按 {@code config_draft_meta.version} 抢占 kind 级乐观锁（4003 语义），
     * 再把 YAML 语义文本解析为结构行并整体替换（先清后写，天然无孤儿子行）。
     *
     * <p>本方法对外仍收 YAML 文本（T1 过渡契约）；T3 表单化 API 落地后改收结构化 DTO，
     * 届时本方法收缩为「导入通道（T4）」专用。
     *
     * @param kind     配置种类
     * @param yaml     配置原文（与 kind 语义同构的 YAML）
     * @param ifMatch  乐观锁期望版本（GET 草稿返回的 version；首次保存传 null）
     * @param operator 操作人
     * @throws BusinessException 乐观锁冲突 / 版本不匹配（4003）；YAML 结构非法（4002）
     */
    @Transactional
    public void saveDraft(ConfigKind kind, String yaml, Integer ifMatch, String operator) {
        ConfigDraftMetaEntity meta = repository.findDraftMeta(kind);
        if (meta == null) {
            if (ifMatch != null) {
                throw new BusinessException(ErrorCode.CONFIG_CONCURRENT_MODIFY,
                        "草稿尚不存在，无法按版本 " + ifMatch + " 更新（请先 GET 获取最新版本）");
            }
            meta = repository.insertDraftMeta(kind, operator);
        } else {
            if (ifMatch != null && !ifMatch.equals(meta.getVersion())) {
                throw new BusinessException(ErrorCode.CONFIG_CONCURRENT_MODIFY,
                        "草稿已被他人修改（当前版本 " + meta.getVersion() + "，请求基于版本 " + ifMatch + "）");
            }
            if (ifMatch == null) {
                // 未携带 If-Match 的更新：按当前版本走乐观锁（等价 last-write-wins 但保留乐观锁路径）
                ifMatch = meta.getVersion();
            }
            meta.setVersion(ifMatch);
            repository.updateDraftMeta(meta, operator);
        }
        writeDraft(kind, yaml);
        repository.insertAudit(ACTION_UPDATE_DRAFT, kind, operator, "草稿已更新");
        log.info("草稿已更新: kind={}, operator={}", kind.lowerName(), operator);
    }

    /**
     * 把 YAML 语义文本写入对应 kind 的结构行（T1 过渡；T3/T4 接管后收缩为导入通道专用）。
     *
     * <p>T1 阶段只交付持久层，结构行与 YAML 之间的转换由
     * {@link StructuredDraftWriter} 承担；T3 表单化 API 交付后本方法不再被管理端调用。
     *
     * @param kind 配置种类
     * @param yaml 与 kind 语义同构的 YAML 文本（可空 = 清空该 kind）
     * @throws BusinessException YAML 结构非法（4002）
     */
    private void writeDraft(ConfigKind kind, String yaml) {
        repository.clearDraftKind(kind);
        if (yaml == null || yaml.isBlank()) {
            return;
        }
        int rows = draftWriter.write(kind, yaml);
        log.debug("结构化草稿已写入: kind={}, rows={}", kind.lowerName(), rows);
    }

    /**
     * 发布：读全部草稿 → 全量校验 → 快照构建 → 事务内落库 + 原子切换。
     *
     * <p>校验失败整体拒绝（4002 + 明细清单），草稿保留；成功后新快照立即对全部读路径生效。
     *
     * <p>发布记录与成功审计同处一个事务；业务异常也回滚全部修改。
     * 失败审计经独立事务保留，内存快照通过 afterCommit 切换。
     *
     * @param operator 发布人
     * @param remark   发布备注（可空）
     * @return 发布结果（发布记录 ID + 快照概况）
     * @throws BusinessException 校验失败（4002/9002）或草稿缺失（4002）
     */
    @Transactional
    public PublishResult publish(String operator, String remark) {
        synchronized (publishLock) {
            return doPublish(operator, remark);
        }
    }

    /**
     * 发布主流程（结构化装配，T2）。
     *
     * <p>快照由 {@link StructuredCatalogAssembler} 从草稿作用域（{@code release_id = 0}）的
     * **结构行**直接装配——不再把草稿渲染成 YAML 再解析（省去一次往返，且口径与落库一致）。
     * 装配失败即发布校验失败（4002 + 明细），草稿保留、旧快照继续服务。
     *
     * <p>装配成功后写 {@code config_release} 头 + 把草稿结构行整体
     * {@link ConfigRepository#insertScope(StructuredSnapshot, long) 复制为带新 release_id 的不可变快照行}；
     * 快照在数据库提交成功后切换，提交失败继续服务旧快照。
     *
     * @param operator 发布人
     * @param remark   发布备注
     * @return 发布结果
     */
    private PublishResult doPublish(String operator, String remark) {
        CatalogSnapshot snapshot;
        StructuredSnapshot frozen;
        try {
            // 草稿作用域一次性读全量结构行（1 次 readScope = 15 条批量查询，无 N+1）后内存装配
            repository.lockDraftWorkspace();
            frozen = repository.readScope(ConfigRepository.DRAFT_RELEASE_ID);
            snapshot = assembler.assemble(frozen);
            new com.wymix.initializer.core.generator.CatalogValidator().validate(
                    snapshot.metadataCatalog(), snapshot.starterCatalog(), snapshot.templateOverrides(),
                    snapshot.conflictCatalog());
        } catch (BusinessException e) {
            // 发布失败不写发布历史；审计留痕（含失败明细），供管理端展示
            failedAudit("PUBLISH_FAILED", operator, e.getMessage());
            throw e;
        }

        ConfigReleaseEntity release = repository.insertRelease(operator, remark);
        int rows = repository.insertScope(frozen, release.getId());
        repository.insertAudit(ACTION_PUBLISH, null, operator,
                "发布 #" + release.getId() + "：结构行 " + rows
                        + " 行 / Java " + snapshot.metadataCatalog().javaVersions().size()
                        + " 个版本 / starter 清单 " + snapshot.starterCatalog().size()
                        + " 项 / 模板 " + snapshot.templateOverrides().size()
                        + " 个 / 冲突规则 " + snapshot.conflictCatalog().size() + " 条");
        log.info("配置已发布: releaseId={}, rows={}, operator={}", release.getId(), rows, operator);
        PublishResult result = new PublishResult(release.getId(),
                snapshot.metadataCatalog().javaVersions().size(),
                snapshot.starterCatalog().size(),
                snapshot.templateOverrides().size(),
                snapshot.conflictCatalog().size());
        // 数据库提交成功后才向读路径暴露新版本。
        activateAfterCommit(snapshot);
        return result;
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
    @Transactional
    public PublishResult rollback(long releaseId, String operator) {
        synchronized (publishLock) {
            return doRollback(releaseId, operator);
        }
    }

    /**
     * 回滚主流程（结构化装配，T2）：读目标 release 的**结构行快照**直接装配，通过后
     * 把该快照复制为新的 release（不在原地改写历史行）。
     *
     * <p>历史快照本身就是 {@code cfg_*} 表里 {@code release_id = N} 的一组行，
     * 因此回滚 = 「读 N 作用域行 → 装配校验 → 复制为 N+1 作用域」，历史版本零改写，
     * 且不再依赖发布记录里的 YAML 全文（YAML 反编译路径已从发布/回滚链路移除）。
     *
     * <p>不绕过校验通道：历史快照仍要过一遍装载期校验，因规则演进而失效的历史版本
     * 会被拒（4002），旧快照继续服务。
     *
     * @param releaseId 目标发布记录 ID
     * @param operator  操作人
     * @return 回滚结果（新发布记录 ID + 快照概况）
     * @throws BusinessException 发布记录不存在（4002）或目标版本校验失败（4002）
     */
    private PublishResult doRollback(long releaseId, String operator) {
        repository.lockDraftWorkspace();
        ConfigReleaseEntity release = repository.findRelease(releaseId);
        if (release == null) {
            failedAudit(ACTION_ROLLBACK_FAILED, operator, "回滚失败: 发布记录不存在: #" + releaseId);
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "回滚失败: 发布记录不存在: #" + releaseId);
        }
        StructuredSnapshot historical = repository.readScope(releaseId);
        if (historical.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "回滚失败: 发布记录 #" + releaseId + " 缺失结构行，无法重建快照");
        }
        CatalogSnapshot snapshot;
        try {
            // 复用已读出的结构行装配（不再二次 readScope），装配失败即回滚失败（4002）
            snapshot = assembler.assemble(historical);
            new com.wymix.initializer.core.generator.CatalogValidator().validate(
                    snapshot.metadataCatalog(), snapshot.starterCatalog(), snapshot.templateOverrides(),
                    snapshot.conflictCatalog());
        } catch (BusinessException e) {
            failedAudit(ACTION_ROLLBACK_FAILED, operator,
                    "回滚 #" + releaseId + " 失败: " + e.getMessage());
            throw e;
        }

        ConfigReleaseEntity newRelease = repository.insertRelease(operator, "回滚自 #" + releaseId);
        int rows = repository.insertScope(historical, newRelease.getId());
        repository.insertAudit(ACTION_ROLLBACK, null, operator,
                "回滚 #" + releaseId + " → 新发布 #" + newRelease.getId() + "（结构行 " + rows + " 行）");
        log.info("配置已回滚: from={}, to={}, rows={}, operator={}",
                releaseId, newRelease.getId(), rows, operator);
        PublishResult result = new PublishResult(newRelease.getId(),
                snapshot.metadataCatalog().javaVersions().size(),
                snapshot.starterCatalog().size(),
                snapshot.templateOverrides().size(),
                snapshot.conflictCatalog().size());
        // 回滚也仅在新发布记录提交成功后切换。
        activateAfterCommit(snapshot);
        return result;
    }

    /** 发布预检报告；RENDERED 表示试渲染成功，NOT_RUN 表示未执行外部依赖构建。 */
    public record ValidationReport(String stage, String buildValidation, int bootVersions, int starters) { }

    /**
     * 只读校验当前一致草稿，不推进版本、不发布。
     * @return 已完成的验证层级与规模
     * @throws BusinessException 发布预检失败（4002）
     */
    @Transactional
    public ValidationReport validateDraft() {
        repository.lockDraftWorkspace();
        CatalogSnapshot snapshot = assembler.assemble(repository.readScope(ConfigRepository.DRAFT_RELEASE_ID));
        new com.wymix.initializer.core.generator.CatalogValidator().validate(
                snapshot.metadataCatalog(), snapshot.starterCatalog(), snapshot.templateOverrides(), snapshot.conflictCatalog());
        return new ValidationReport("RENDERED", "NOT_RUN", snapshot.metadataCatalog().bootVersions().size(),
                snapshot.starterCatalog().size());
    }

    private void activateAfterCommit(CatalogSnapshot snapshot) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            snapshotHolder.swap(snapshot);
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        snapshotHolder.swap(snapshot);
                    }
                });
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
     * 发布/回滚结果 DTO（M3.2 增加模板覆盖计数；M3 评审遗留项 5：补充冲突规则计数，
     * 管理端发布响应可直接展示生效规则规模）。
     *
     * <p>线程安全性：不可变对象，线程安全。
     */
    public record PublishResult(long releaseId, int javaVersionCount, int starterCount,
                                int templateOverrideCount, int conflictRuleCount) {

        /** M3.1/M3.2 兼容构造：冲突规则计数置 0。 */
        public PublishResult(long releaseId, int javaVersionCount, int starterCount, int templateOverrideCount) {
            this(releaseId, javaVersionCount, starterCount, templateOverrideCount, 0);
        }

        /** M3.1 兼容构造：模板覆盖与冲突规则计数置 0。 */
        public PublishResult(long releaseId, int javaVersionCount, int starterCount) {
            this(releaseId, javaVersionCount, starterCount, 0);
        }
    }
}
