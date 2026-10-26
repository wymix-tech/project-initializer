package com.wymix.initializer.app.admin.service;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.entity.CfgConflictRuleEntity;
import com.wymix.initializer.app.admin.entity.CfgConflictRuleStarterEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterBootVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterDependencyEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageAbstractEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageTemplateEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageYmlEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterRequireGroupEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterRequireItemEntity;
import com.wymix.initializer.app.admin.entity.CfgTemplateEntity;
import com.wymix.initializer.app.admin.entity.ConfigDraftMetaEntity;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.repository.ConfigArchiveCodec;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.repository.StructuredSnapshot;
import com.wymix.initializer.app.admin.repository.StructuredSnapshotCodec;
import com.wymix.initializer.app.admin.repository.StructuredYamlCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置导入导出应用服务（结构化改造 T4）：数据库 ↔ ZIP 归档的双向通道。
 *
 * <p><b>导出</b>：读草稿作用域（{@code release_id = 0}）的**结构行**（一次
 * {@link ConfigRepository#readScope(long)}，15 条批量查询，无 N+1）→ 渲染为与原 catalog
 * 目录一致的 YAML 文本 → 打包 ZIP 下载。渲染复用 {@link StructuredYamlCodec}
 * （结构行 → YAML 语义视图）与 {@link ConfigArchiveCodec}（结构行 → 元数据 YAML + 归档布局），
 * <b>包含 {@code hidden} 列</b>（PR #40 语义），保证「导出 → 再导入」幂等。
 *
 * <p><b>导入</b>：multipart 接收 ZIP → {@link ConfigArchiveCodec#readArchive} 解包并做安全加固
 * （路径穿越 / 条目数 / 单文件与总量上限 / 严格 UTF-8）→ {@link StructuredSnapshotCodec}
 * 解析校验（复用 core 的 {@code YamlStarterParser} / {@code ConflictRuleParser} /
 * 模板语法校验器）→ <b>同一事务内</b>「按 kind 清空草稿 → 写入结构行」，
 * 任一环节失败整体回滚，草稿不被污染。
 *
 * <p><b>与发布链路的关系</b>：导入只写草稿，<b>不发布、不触碰快照</b>；
 * 导入完成后仍需人工核对并发布（发布期才做跨模块引用与图合法性的强校验）。
 * 导入期采用草稿模式（{@code allowDangling = true}），只校验 YAML 格式与必填字段，
 * 不做 requires 依赖关系、metadata 白名单引用等依赖相关校验，格式正确即可导入草稿。
 *
 * <p>线程安全性：无状态单例（全部状态在入参与局部变量中），线程安全。
 */
@Service
public class ConfigImportExportService {

    private static final Logger log = LoggerFactory.getLogger(ConfigImportExportService.class);

    /** 审计动作：导入配置归档。 */
    public static final String ACTION_IMPORT = "IMPORT";
    /** 审计动作：导出配置归档。 */
    public static final String ACTION_EXPORT = "EXPORT";
    /** 审计动作：导入失败（归档非法/校验失败，草稿未变更）。 */
    public static final String ACTION_IMPORT_FAILED = "IMPORT_FAILED";

    /** 导入条目的固定数据来源通道（与表单维护同一权威来源）。 */
    private static final String SOURCE_MANUAL = ConfigRepository.SOURCE_MANUAL;

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
    private final ConfigArchiveCodec archiveCodec;
    private final StructuredSnapshotCodec snapshotCodec;
    private final StructuredYamlCodec yamlCodec;

    /**
     * @param repository     配置存储仓库（草稿作用域结构行读写）
     * @param archiveCodec   归档编解码器（ZIP 打包/解包 + 安全加固）
     * @param snapshotCodec  导入文档解析器（YAML → 结构行）
     * @param yamlCodec      结构行 → YAML 语义视图渲染器（导出侧 starter / 规则 / 模板）
     */
    public ConfigImportExportService(ConfigRepository repository, ConfigArchiveCodec archiveCodec,
                                     StructuredSnapshotCodec snapshotCodec, StructuredYamlCodec yamlCodec) {
        this.repository = repository;
        this.archiveCodec = archiveCodec;
        this.snapshotCodec = snapshotCodec;
        this.yamlCodec = yamlCodec;
    }

    // ==================== 导出 ====================

    /**
     * 导出当前草稿为 ZIP 归档。
     *
     * @param operator 操作人（用于审计）
     * @return 导出结果（文件名 + 归档字节 + 规模摘要）
     * @throws BusinessException 序列化失败（4010）
     */
    public ExportResult export(String operator) {
        StructuredSnapshot rows = repository.readScope(ConfigRepository.DRAFT_RELEASE_ID);
        String metadataYaml = archiveCodec.renderMetadata(rows);
        Map<String, String> starterDocuments = renderStarterDocuments(rows);
        String conflictYaml = yamlCodec.renderConflictRules(rows);
        Map<String, String> templates = renderTemplates(rows);
        byte[] archive = archiveCodec.writeArchive(metadataYaml, starterDocuments, conflictYaml, templates);

        String summary = "元数据 " + rows.javaVersions().size() + " 个 Java / "
                + rows.bootVersions().size() + " 个 Boot；starter " + rows.starters().size()
                + " 项；冲突规则 " + rows.conflictRules().size() + " 条；模板 " + rows.templates().size() + " 个";
        repository.insertAudit(ACTION_EXPORT, null, operator,
                "配置归档已导出: " + summary + "；归档 " + archive.length + " 字节");
        log.info("配置归档已导出: operator={}, bytes={}, {}", operator, archive.length, summary);
        return new ExportResult("initializer-config.zip", archive, summary);
    }

    private static final List<ConfigKind> IMPORT_KINDS = List.of(ConfigKind.METADATA, ConfigKind.STARTER,
            ConfigKind.CONFLICT_RULE, ConfigKind.TEMPLATE);
    private static final long PREVIEW_TTL_MS = 10 * 60 * 1000;
    private final Map<String, PendingImport> previews = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 无副作用预检：解析归档，基于一致版本向量展示新增、修改和删除。
     * @param archive 上传 ZIP 输入流，最多 16 MiB
     * @return 10 分钟内有效的预览；进程最多保存 16 份
     * @throws BusinessException 归档非法或预览容量已满
     */
    @Transactional
    public ImportPreview previewImport(InputStream archive) {
        try {
            byte[] bytes = archive.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) {
                throw new BusinessException(ErrorCode.CONFIG_IMPORT_VALIDATION_FAILED, "归档超过 16 MiB");
            }
            Map<String, String> entries = archiveCodec.readArchive(new java.io.ByteArrayInputStream(bytes));
            String metadataYaml = archiveCodec.metadataOf(entries);
            if (metadataYaml == null) {
                throw new BusinessException(ErrorCode.CONFIG_IMPORT_VALIDATION_FAILED, "归档缺少必需文件: metadata.yml");
            }
            StructuredSnapshot starters = snapshotCodec.parseStarters(archiveCodec.startersOf(entries), SOURCE_MANUAL, true);
            StructuredSnapshot metadata = snapshotCodec.parseMetadata(metadataYaml, true, declaredStarterIds(starters));
            StructuredSnapshot rules = snapshotCodec.parseConflictRules(archiveCodec.conflictRulesOf(entries), false);
            StructuredSnapshot templates = snapshotCodec.parseTemplates(archiveCodec.templatesOf(entries));
            repository.lockDraftWorkspace();
            Map<ConfigKind, Integer> baseline = draftVersions();
            StructuredSnapshot current = repository.readScope(ConfigRepository.DRAFT_RELEASE_ID);
            Map<String, String> before = previewFiles(current, current, current, current);
            Map<String, String> after = previewFiles(metadata, starters, rules, templates);
            List<ImportChange> changes = new ArrayList<>();
            Set<String> paths = new java.util.TreeSet<>(before.keySet());
            paths.addAll(after.keySet());
            for (String path : paths) {
                String oldValue = before.get(path);
                String newValue = after.get(path);
                if (!java.util.Objects.equals(oldValue, newValue)) {
                    changes.add(new ImportChange(path, oldValue == null ? "ADD" : newValue == null ? "DELETE" : "UPDATE",
                            oldValue, newValue));
                }
            }
            String id = java.util.UUID.randomUUID().toString();
            long expires = System.currentTimeMillis() + PREVIEW_TTL_MS;
            synchronized (previews) {
                previews.entrySet().removeIf(e -> e.getValue().expiresAt() < System.currentTimeMillis());
                if (previews.size() >= 16) {
                    throw new BusinessException(ErrorCode.CONFIG_IMPORT_VALIDATION_FAILED, "导入预览数量已达上限，请稍后重试");
                }
                previews.put(id, new PendingImport(bytes, baseline, expires));
            }
            return new ImportPreview(id, baseline, expires, List.copyOf(changes));
        } catch (java.io.IOException e) {
            throw new BusinessException(ErrorCode.CONFIG_IMPORT_VALIDATION_FAILED, "读取归档失败", e);
        }
    }

    /**
     * 按预览归档应用修改，任何模块版本变化均拒绝覆盖。
     * @param previewId 预览标识
     * @param operator 填写的操作人
     * @return 导入结果；只写草稿，不自动发布
     * @throws BusinessException 预览过期、版本冲突或导入失败
     */
    @Transactional
    public ImportResult applyPreview(String previewId, String operator) {
        PendingImport pending = previews.get(previewId);
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()) {
            throw new BusinessException(ErrorCode.CONFIG_CONCURRENT_MODIFY, "导入预览已过期，请重新预览");
        }
        repository.lockDraftWorkspace();
        if (!pending.versions().equals(draftVersions())) {
            failedAudit(ACTION_IMPORT_FAILED, operator, "导入预览后草稿已变化，拒绝覆盖，请重新预览");
            throw new BusinessException(ErrorCode.CONFIG_CONCURRENT_MODIFY, "草稿已变化，请重新预览差异后导入");
        }
        ImportResult result = importArchive(new java.io.ByteArrayInputStream(pending.archive()), operator, null);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            previews.remove(previewId);
                        }
                    });
        }
        return result;
    }

    private Map<ConfigKind, Integer> draftVersions() {
        Map<ConfigKind, Integer> versions = new LinkedHashMap<>();
        for (ConfigKind kind : IMPORT_KINDS) {
            ConfigDraftMetaEntity meta = repository.findDraftMeta(kind);
            versions.put(kind, meta == null ? -1 : meta.getVersion());
        }
        return Map.copyOf(versions);
    }

    private Map<String, String> previewFiles(StructuredSnapshot metadata, StructuredSnapshot starters,
                                           StructuredSnapshot rules, StructuredSnapshot templates) {
        Map<String, String> files = new LinkedHashMap<>();
        String yaml = archiveCodec.renderMetadata(metadata);
        if (yaml != null) { files.put("metadata.yml", yaml); }
        renderStarterDocuments(starters).forEach((name, content) -> files.put("starters/" + name, content));
        yaml = yamlCodec.renderConflictRules(rules);
        if (yaml != null) { files.put("conflict-rules.yml", yaml); }
        renderTemplates(templates).forEach((name, content) -> files.put("templates/" + name, content));
        return files;
    }

    private record PendingImport(byte[] archive, Map<ConfigKind, Integer> versions, long expiresAt) { }
    /** 单文件语义差异；action 为 ADD、UPDATE 或 DELETE，空正文表示不存在。 */
    public record ImportChange(String path, String action, String before, String after) { }
    /** 绑定归档与草稿版本向量的导入预览，expiresAt 为毫秒时间戳。 */
    public record ImportPreview(String previewId, Map<ConfigKind, Integer> versions, long expiresAt,
                                List<ImportChange> changes) { }

    // ==================== 导入 ====================

    /**
     * 从 ZIP 归档导入配置到草稿（整体替换，失败整体回滚）。
     *
     * @param archive  上传的归档输入流
     * @param operator 操作人（同时作为草稿乐观锁行的修改人）
     * @param ifMatch  可选的整体乐观锁期望版本（任一 kind 不匹配即拒；{@code null} = 按当前版本推进）
     * <p>业务失败也整体回滚，包括先前模块的版本推进；失败审计经独立事务保存。
     * 管理界面使用 previewImport/applyPreview 携带完整版本向量，本入口保留旧客户端兼容。
     *
     * @return 导入结果（各 kind 写入行数 + 规模摘要）
     * @throws BusinessException 归档非法（4008）/ 校验失败（4009）/ 乐观锁冲突（4003）
     */
    @Transactional
    public ImportResult importArchive(InputStream archive, String operator, Integer ifMatch) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(ErrorCode.CONFIG_IMPORT_VALIDATION_FAILED, "缺少必需字段: operator");
        }
        try {
            Map<String, String> entries = archiveCodec.readArchive(archive);
            String metadataYaml = archiveCodec.metadataOf(entries);
            String starterYaml = archiveCodec.startersOf(entries);
            String conflictYaml = archiveCodec.conflictRulesOf(entries);
            String templateYaml = archiveCodec.templatesOf(entries);
            if (metadataYaml == null) {
                throw new BusinessException(ErrorCode.CONFIG_IMPORT_VALIDATION_FAILED,
                        "归档缺少必需文件: metadata.yml");
            }

            // 草稿模式解析：导入期只校验格式与必填字段，不校验 requires 依赖关系、
            // 不校验 metadata 白名单引用是否已登记；这些依赖相关的强一致性统一留到发布期处理。
            // 先解析 starter 清单取得「归档内已声明的 starter ID」，再原样解析 metadata。
            StructuredSnapshot starters = snapshotCodec.parseStarters(starterYaml, SOURCE_MANUAL, true);
            StructuredSnapshot metadata = snapshotCodec.parseMetadata(metadataYaml, true,
                    declaredStarterIds(starters));
            StructuredSnapshot rules = snapshotCodec.parseConflictRules(conflictYaml, false);
            StructuredSnapshot templates = snapshotCodec.parseTemplates(templateYaml);

            ImportSummary summary = new ImportSummary(
                    metadata.javaVersions().size(), metadata.bootVersions().size(), metadata.bootJava().size(),
                    metadata.bootStarterRefs().size(), starters.starters().size(),
                    starters.starterDependencies().size(), starters.starterRequireItems().size(),
                    rules.conflictRules().size(), templates.templates().size());
            String describe = summary.describe();

            // 乐观锁口径与表单端点一致：任一 kind 携带 If-Match 时校验版本，通过后统一推进
            Map<ConfigKind, Integer> versions = acquireLocks(operator, ifMatch);
            int rows = 0;
            rows += repository.replaceDraftMetadata(metadata);
            rows += repository.replaceDraftStartersBySource(SOURCE_MANUAL, starters);
            rows += repository.replaceDraftConflictRules(rules);
            rows += repository.replaceDraftTemplates(templates);

            repository.insertAudit(ACTION_IMPORT, null, operator,
                    "配置归档已导入草稿: " + describe + "；结构行 " + rows + " 行");
            log.info("配置归档已导入: operator={}, rows={}, {}", operator, rows, describe);
            return new ImportResult(rows, versions, describe);
        } catch (BusinessException e) {
            // 独立审计事务保留失败证据，外层导入事务回滚内容与版本。
            failedAudit(ACTION_IMPORT_FAILED, operator,
                    "配置导入失败: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 抽取 starter 结构行中的业务 ID 集合（严格模式白名单校验用）。
     *
     * @param starters starter 结构行快照
     * @return ID 集合（去重保序）
     */
    private static Set<String> declaredStarterIds(StructuredSnapshot starters) {
        Set<String> ids = new LinkedHashSet<>();
        starters.starters().forEach(row -> ids.add(row.getStarterId()));
        return ids;
    }

    // ==================== 内部：导出渲染 ====================

    /**
     * 渲染单 starter YAML 文档（文件名 → 文本）：优先用 starter ID 作文件名，
     * 非法文件名字符统一替换为 {@code -}，ID 冲突时追加序号后缀。
     *
     * <p>每条目单独渲染（{@link StructuredYamlCodec#renderStarters(StructuredSnapshot)} 一次），
     * 保证「一 starter 一文件」的归档布局与原 catalog 目录一致。
     *
     * <p><b>只导出手工通道（{@code source = MANUAL}）</b>：同步通道（{@code source = SYNC}）由
     * 制品库同步链路机器写入、可随时重建，不属于人工维护的配置资产；把同步行一并导出会在
     * 「导出 → 导入」后把它们复制成手工行，既污染手工清单、又破坏「同名手工优先」语义。
     * 因此归档只承载人工可编辑的那一份，「导出 → 再导入」对手工清单严格幂等。
     *
     * @param rows 草稿全量结构行
     * @return 文件名（不含目录与扩展名）→ YAML 文本
     */
    private Map<String, String> renderStarterDocuments(StructuredSnapshot rows) {
        Map<String, String> documents = new LinkedHashMap<>();
        Set<String> usedNames = new LinkedHashSet<>();
        List<CfgStarterEntity> starters = new ArrayList<>(rows.starters());
        starters.removeIf(row -> !SOURCE_MANUAL.equals(row.getSource()));
        starters.sort(Comparator.comparingInt(row -> row.getSort() == null ? Integer.MAX_VALUE : row.getSort()));
        for (CfgStarterEntity starter : starters) {
            StructuredSnapshot single = singleStarter(rows, starter);
            String yaml = yamlCodec.renderStarters(single);
            if (yaml == null) {
                continue;
            }
            documents.put(uniqueFileName(starter.getStarterId(), usedNames), yaml);
        }
        return documents;
    }

    /**
     * 抽取单个 starter 的父行及其全部子行（内存过滤，不查库）。
     *
     * @param rows    草稿全量结构行
     * @param starter 目标 starter 父行
     * @return 只含该 starter 的结构行快照
     */
    private static StructuredSnapshot singleStarter(StructuredSnapshot rows, CfgStarterEntity starter) {
        Long pk = starter.getId();
        List<CfgStarterRequireGroupEntity> groups = rows.starterRequireGroups().stream()
                .filter(row -> pk.equals(row.getStarterPk())).toList();
        Set<Long> groupPks = new LinkedHashSet<>();
        groups.forEach(row -> groupPks.add(row.getId()));
        return new StructuredSnapshot(ConfigRepository.DRAFT_RELEASE_ID,
                List.of(), List.of(), List.of(), List.of(),
                List.of(starter),
                filter(rows.starterDependencies(), CfgStarterDependencyEntity::getStarterPk, pk),
                groups,
                rows.starterRequireItems().stream()
                        .filter(row -> groupPks.contains(row.getGroupPk())).toList(),
                filter(rows.starterBootVersions(), CfgStarterBootVersionEntity::getStarterPk, pk),
                filter(rows.starterLinkageTemplates(), CfgStarterLinkageTemplateEntity::getStarterPk, pk),
                filter(rows.starterLinkageYmls(), CfgStarterLinkageYmlEntity::getStarterPk, pk),
                filter(rows.starterLinkageAbstracts(), CfgStarterLinkageAbstractEntity::getStarterPk, pk),
                List.of(), List.of(), List.of());
    }

    /**
     * 渲染模板映射（模板名 → 全文）：列表语义同样是「DB 全量模板集」，故导出全量。
     *
     * @param rows 草稿全量结构行
     * @return 模板名 → 模板全文（保持 sort 顺序）
     */
    private static Map<String, String> renderTemplates(StructuredSnapshot rows) {
        Map<String, String> templates = new LinkedHashMap<>();
        List<CfgTemplateEntity> ordered = new ArrayList<>(rows.templates());
        ordered.sort(Comparator.comparingInt(row -> row.getSort() == null ? Integer.MAX_VALUE : row.getSort()));
        for (CfgTemplateEntity row : ordered) {
            templates.put(row.getName(), row.getContent());
        }
        return templates;
    }

    /**
     * 过滤子行（按父键列）。
     *
     * @param rows   子行集合
     * @param keyOf  父键提取
     * @param pk     父主键
     * @param <T>    行类型
     * @return 命中的子行
     */
    private static <T> List<T> filter(List<T> rows, java.util.function.Function<T, Long> keyOf, Long pk) {
        return rows.stream().filter(row -> pk.equals(keyOf.apply(row))).toList();
    }

    /**
     * 生成稳定的归档文件名（非法字符替换 + 去重后缀）。
     *
     * @param starterId  starter ID
     * @param usedNames  已用文件名集合（就地更新）
     * @return 唯一文件名（不含扩展名）
     */
    private static String uniqueFileName(String starterId, Set<String> usedNames) {
        String base = starterId == null || starterId.isBlank() ? "starter" : starterId;
        base = base.replaceAll("[^A-Za-z0-9._-]", "-");
        String candidate = base;
        int suffix = 2;
        while (!usedNames.add(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    // ==================== 内部：导入的乐观锁 ====================

    /**
     * 导入前统一抢占四类 kind 的乐观锁（与表单端点同口径，保证 {@code If-Match} 可混用）。
     *
     * @param operator 操作人
     * @param ifMatch  期望版本（可空）
     * @return kind → 导入后的版本
     * @throws BusinessException 版本不匹配（4003）
     */
    private Map<ConfigKind, Integer> acquireLocks(String operator, Integer ifMatch) {
        Map<ConfigKind, Integer> versions = new LinkedHashMap<>();
        for (ConfigKind kind : List.of(ConfigKind.METADATA, ConfigKind.STARTER,
                ConfigKind.CONFLICT_RULE, ConfigKind.TEMPLATE)) {
            versions.put(kind, advanceLock(kind, ifMatch, operator));
        }
        return versions;
    }

    /**
     * 推进单个 kind 的乐观锁（与 {@link StructuredDraftService} / {@link ConfigService} 同口径）。
     *
     * @param kind     配置种类
     * @param ifMatch  期望版本（可空）
     * @param operator 操作人
     * @return 推进后的版本
     * @throws BusinessException 版本不匹配（4003）
     */
    private Integer advanceLock(ConfigKind kind, Integer ifMatch, String operator) {
        ConfigDraftMetaEntity meta = repository.findDraftMeta(kind);
        if (meta == null) {
            if (ifMatch != null) {
                throw new BusinessException(ErrorCode.CONFIG_CONCURRENT_MODIFY,
                        "草稿尚不存在，无法按版本 " + ifMatch + " 导入（请先 GET 获取最新版本）");
            }
            return repository.insertDraftMeta(kind, operator).getVersion();
        }
        if (ifMatch != null && !ifMatch.equals(meta.getVersion())) {
            throw new BusinessException(ErrorCode.CONFIG_CONCURRENT_MODIFY,
                    "草稿已被他人修改（kind=" + kind.lowerName() + " 当前版本 " + meta.getVersion()
                            + "，请求基于版本 " + ifMatch + "）");
        }
        meta.setVersion(ifMatch == null ? meta.getVersion() : ifMatch);
        repository.updateDraftMeta(meta, operator);
        return meta.getVersion();
    }

    // ==================== DTO ====================

    /**
     * 导出结果。
     *
     * @param fileName 建议的下载文件名
     * @param content  归档字节
     * @param summary  规模摘要（不含模板全文与 token）
     */
    public record ExportResult(String fileName, byte[] content, String summary) {
    }

    /**
     * 导入结果。
     *
     * @param rows     写入的结构行合计
     * @param versions 各 kind 导入后的版本（kind → version）
     * @param summary  规模摘要
     */
    public record ImportResult(int rows, Map<ConfigKind, Integer> versions, String summary) {
    }

    /**
     * 导入规模摘要（供审计与响应展示；不含模板全文）。
     *
     * @param javaVersions   Java 版本数
     * @param bootVersions   Boot 版本数
     * @param bootJavaRows   兼容矩阵行数
     * @param starterRefRows starter 白名单行数
     * @param starters       starter 数
     * @param dependencyRows 依赖坐标行数
     * @param requireItemRows 依赖联动组内项行数
     * @param conflictRules  冲突规则数
     * @param templates      模板数
     */
    public record ImportSummary(int javaVersions, int bootVersions, int bootJavaRows, int starterRefRows,
                                int starters, int dependencyRows, int requireItemRows,
                                int conflictRules, int templates) {

        /**
         * 渲染规模摘要文本（半角空格分隔，便于日志与断言的可读性，不含模板全文）。
         *
         * @return 摘要（含各模块条目数）
         */
        public String describe() {
            return "metadata[java=" + javaVersions + ",boot=" + bootVersions
                    + ",bootJava=" + bootJavaRows + ",whitelist=" + starterRefRows
                    + "] starters[" + starters + ",deps=" + dependencyRows
                    + ",requireItems=" + requireItemRows + "] conflictRules[" + conflictRules
                    + "] templates[" + templates + "]";
        }
    }

}
