package com.wymix.initializer.app.admin.repository;

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
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.refresh.CatalogRefresher;
import com.wymix.initializer.core.conflict.ConflictCatalog;
import com.wymix.initializer.core.conflict.ConflictRule;
import com.wymix.initializer.core.conflict.ConflictRuleParser;
import com.wymix.initializer.core.generator.TemplateRenderer;
import com.wymix.initializer.core.starter.AbstractClassLinkage;
import com.wymix.initializer.core.starter.DependencyCoord;
import com.wymix.initializer.core.starter.LinkageSpec;
import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import com.wymix.initializer.core.starter.TemplateLinkage;
import com.wymix.initializer.core.starter.YamlStarterParser;
import com.wymix.initializer.core.starter.YmlFragmentLinkage;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 导入文档编解码器（结构化改造 T4）：把「归档里的 YAML 文档」解析为可直接落库的
 * {@link StructuredSnapshot} 结构行，**复用 initializer-core 的既有解析器**
 * （{@link YamlStarterParser} / {@code MetadataCatalog.load} / {@link ConflictRuleParser} /
 * {@link TemplateRenderer#validate}），不另造一套解析语义。
 *
 * <p><b>双模式（读路径解耦）</b>：
 * <ul>
 *   <li><b>草稿模式（{@code allowDangling = true}）</b>：草稿态必须允许悬空引用与重复 ID
 *       （用户在表单里逐步补全配置），故 metadata 的兼容矩阵 / starter 白名单走哑映射，
 *       结构行原样入库，语义校验统一留到发布期（4002 明细）。</li>
 *   <li><b>严格模式（{@code allowDangling = false}）</b>：归档内文档必须彼此自洽——
 *       starter 清单的展示名/坐标必须在 metadata 登记，跨档描述符必须解析成功；
 *       用于「归档整体替换」的导入前置断言。</li>
 * </ul>
 *
 * <p><b>与 {@link StructuredDraftWriter} 的关系</b>：写入器负责「YAML 文本 → 结构行」，
 * 本类负责「归档文档 → 文档集合 + 跨档一致性」，再把单档文本交给写入器/解析器落库；
 * 两者共用同一套解析口径与占位主键约定，避免语义漂移。
 *
 * <p><b>安全边界</b>：只做 UTF-8 文本解析；SnakeYAML 一律 {@link SafeConstructor}；
 * 条目数/文本长度上限在本类收敛（路径穿越与压缩炸弹由 {@code CatalogArchiveCodec} 负责）。
 *
 * <p>线程安全性：无状态单例（全部状态在入参/返回值中），线程安全。
 */
@Component
public class StructuredSnapshotCodec {

    /** 单模块条目数上限（与 {@code StructuredDraftService.MAX_ITEMS} 同口径）。 */
    public static final int MAX_ITEMS = 512;

    /** 单模块结构行数上限（与 {@code StructuredDraftService.MAX_ROWS} 同口径）。 */
    public static final int MAX_ROWS = 20000;

    /** 单条模板 content 字符数上限（与 {@code StructuredDraftService.MAX_TEMPLATE_LENGTH} 同口径）。 */
    public static final int MAX_TEMPLATE_LENGTH = 512 * 1024;

    /** FreeMarker 语法校验器（仅 .ftl；YAML 片段由生成预检解析）。 */
    private final TemplateRenderer templateValidator = new TemplateRenderer();

    /**
     * 解析 metadata 文档为结构行快照。
     *
     * @param yaml           metadata.yml 同构文本（可空 = 空元数据）
     * @param allowDangling  是否容忍悬空引用与重复 ID（草稿模式 true）
     * @return 元数据结构行快照（父行带负数占位主键，供子行引用）
     * @throws BusinessException 解析失败或（严格模式下）图非法（4002/4009）
     */
    public StructuredSnapshot parseMetadata(String yaml, boolean allowDangling) {
        return parseMetadata(yaml, allowDangling, Set.of());
    }

    /**
     * 解析 metadata 文档为结构行快照（严格模式可传入归档内 starter 清单做白名单校验）。
     *
     * @param yaml               metadata.yml 同构文本（可空 = 空元数据）
     * @param allowDangling      是否容忍悬空引用与重复 ID（草稿模式 true）
     * @param declaredStarters   归档内 starter 清单的 ID 集合（严格模式白名单校验用；可空）
     * @return 元数据结构行快照
     * @throws BusinessException 解析失败或（严格模式下）图非法（4002/4009）
     */
    public StructuredSnapshot parseMetadata(String yaml, boolean allowDangling, Set<String> declaredStarters) {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        if (isBlank(yaml)) {
            return snapshot;
        }
        MetadataDoc.Parsed parsed = parseMetadataDoc(yaml);
        requireWithin("metadata.javaVersions", parsed.javaVersions().size());
        requireWithin("metadata.bootVersions", parsed.bootVersions().size());

        int sort = 0;
        Map<String, CfgBootVersionEntity> bootById = new LinkedHashMap<>();
        List<CfgJavaVersionEntity> javaRows = new ArrayList<>();
        for (MetadataDoc.JavaRow java : parsed.javaVersions()) {
            CfgJavaVersionEntity row = new CfgJavaVersionEntity();
            row.setVersionId(java.id());
            row.setLabel(java.label());
            row.setDeprecated(java.deprecated());
            row.setRecommendedBootVersion(java.recommendedBootVersion());
            row.setSort(sort++);
            javaRows.add(row);
        }
        sort = 0;
        long placeholderPk = 0L;
        List<CfgBootVersionEntity> bootRows = new ArrayList<>();
        for (MetadataDoc.BootRow boot : parsed.bootVersions()) {
            CfgBootVersionEntity row = new CfgBootVersionEntity();
            // 显式占位主键（负数区间）：子行父键据此唯一定位父行，写入期换算为真实主键
            row.setId(--placeholderPk);
            row.setVersionId(boot.id());
            row.setLabel(boot.label());
            row.setOfficial(boot.official());
            if (boot.frameworkParent() != null) {
                row.setFpGroup(boot.frameworkParent().groupId());
                row.setFpArtifact(boot.frameworkParent().artifactId());
                row.setFpVersion(boot.frameworkParent().version());
            }
            row.setSort(sort++);
            // 保留查询顺序的父行清单（重复 ID 校验以它为准），映射仅用于子行父键定位；
            // 若直接以映射 keys 做重复校验，重复业务 ID 会在建映射时静默折叠而漏检
            bootRows.add(row);
            bootById.putIfAbsent(boot.id(), row);
        }
        List<CfgBootJavaEntity> matrix = new ArrayList<>();
        List<CfgBootStarterRefEntity> starterRefs = new ArrayList<>();
        for (MetadataDoc.BootRow boot : parsed.bootVersions()) {
            CfgBootVersionEntity bootRow = bootById.get(boot.id());
            for (String javaId : boot.supportedJavaVersions()) {
                CfgBootJavaEntity link = new CfgBootJavaEntity();
                link.setBootPk(bootRow.getId());
                link.setJavaVersionId(javaId);
                matrix.add(link);
            }
            int refSort = 0;
            Set<String> seen = new LinkedHashSet<>();
            for (String starterId : boot.starters()) {
                if (!seen.add(starterId)) {
                    throw invalid("metadata bootVersions " + boot.id() + " 的 starters 白名单存在重复项: " + starterId);
                }
                CfgBootStarterRefEntity ref = new CfgBootStarterRefEntity();
                ref.setBootPk(bootRow.getId());
                ref.setStarterId(starterId);
                ref.setSort(refSort++);
                starterRefs.add(ref);
            }
        }
        snapshot.javaVersions().addAll(javaRows);
        snapshot.bootVersions().addAll(bootRows);
        snapshot.bootJava().addAll(matrix);
        snapshot.bootStarterRefs().addAll(starterRefs);
        requireWithinRows(snapshot.rowCount());
        if (!allowDangling) {
            validateMetadataStrict(javaRows, bootRows, matrix, starterRefs, declaredStarters);
        }
        return snapshot;
    }

    /**
     * 解析 starter 清单文档为结构行快照（支持多文档，每文档一个 starter）。
     *
     * @param yaml          多文档 starter yml（{@code ---} 分隔；可空 = 空清单）
     * @param source         来源通道（{@link ConfigRepository#SOURCE_MANUAL} 等）
     * @param allowDangling  是否容忍悬空 requires 引用（草稿模式 true）
     * @return starter 结构行快照（父行带负数占位主键；分组行同样带负占位主键）
     * @throws BusinessException 单文档解析失败或（严格模式下）清单非法
     */
    public StructuredSnapshot parseStarters(String yaml, String source, boolean allowDangling) {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        List<StarterCatalogEntry> entries = parseStarterEntries(yaml);
        requireWithin("starters", entries.size());
        validateStarterMeta(entries);
        if (!allowDangling) {
            validateStarterCatalog(entries, source);
        }
        int sort = 0;
        long placeholderPk = 0L;
        for (StarterCatalogEntry entry : entries) {
            CfgStarterEntity row = new CfgStarterEntity();
            row.setId(--placeholderPk);
            row.setStarterId(entry.id());
            row.setCategory(entry.category());
            row.setMaintainer(entry.maintainer() == null ? null : entry.maintainer().name());
            row.setDescription(entry.description());
            row.setRequired(entry.required());
            row.setHidden(entry.hidden());
            row.setConfigPackage(entry.linkages() == null ? null : entry.linkages().configPackage());
            row.setSource(source);
            row.setSort(sort++);
            snapshot.starters().add(row);
        }
        long groupPlaceholderPk = 0L;
        int entryIndex = 0;
        for (StarterCatalogEntry entry : entries) {
            CfgStarterEntity starterRow = snapshot.starters().get(entryIndex++);
            int childSort = 0;
            for (DependencyCoord dep : entry.dependencies()) {
                CfgStarterDependencyEntity row = new CfgStarterDependencyEntity();
                row.setStarterPk(starterRow.getId());
                row.setGroupId(dep.groupId());
                row.setArtifactId(dep.artifactId());
                row.setVersion(dep.version());
                row.setSort(childSort++);
                snapshot.starterDependencies().add(row);
            }
            int groupIndex = 0;
            for (List<String> group : entry.requires()) {
                CfgStarterRequireGroupEntity groupRow = new CfgStarterRequireGroupEntity();
                groupRow.setId(--groupPlaceholderPk);
                groupRow.setStarterPk(starterRow.getId());
                groupRow.setGroupIndex(groupIndex++);
                snapshot.starterRequireGroups().add(groupRow);
                int itemSort = 0;
                for (String requiredStarterId : group) {
                    CfgStarterRequireItemEntity itemRow = new CfgStarterRequireItemEntity();
                    itemRow.setGroupPk(groupRow.getId());
                    itemRow.setRequiredStarterId(requiredStarterId);
                    itemRow.setSort(itemSort++);
                    snapshot.starterRequireItems().add(itemRow);
                }
            }
            Set<String> bootIds = new LinkedHashSet<>();
            for (String bootVersionId : entry.bootVersions()) {
                if (!bootIds.add(bootVersionId)) {
                    throw invalid("starter " + entry.id() + " 的 bootVersions 存在重复项: " + bootVersionId);
                }
                CfgStarterBootVersionEntity row = new CfgStarterBootVersionEntity();
                row.setStarterPk(starterRow.getId());
                row.setBootVersionId(bootVersionId);
                snapshot.starterBootVersions().add(row);
            }
            LinkageSpec linkages = entry.linkages();
            if (linkages != null) {
                int templateSort = 0;
                for (TemplateLinkage template : linkages.templates()) {
                    CfgStarterLinkageTemplateEntity row = new CfgStarterLinkageTemplateEntity();
                    row.setStarterPk(starterRow.getId());
                    row.setSource(template.source());
                    row.setTarget(template.target());
                    row.setSort(templateSort++);
                    snapshot.starterLinkageTemplates().add(row);
                }
                int ymlSort = 0;
                for (YmlFragmentLinkage fragment : linkages.ymlFragments()) {
                    CfgStarterLinkageYmlEntity row = new CfgStarterLinkageYmlEntity();
                    row.setStarterPk(starterRow.getId());
                    row.setSource(fragment.source());
                    row.setMergeStrategy(fragment.mergeStrategy() == null
                            ? "APPEND" : fragment.mergeStrategy().name());
                    row.setSort(ymlSort++);
                    snapshot.starterLinkageYmls().add(row);
                }
                int abstractSort = 0;
                for (AbstractClassLinkage abstractClass : linkages.abstractClasses()) {
                    CfgStarterLinkageAbstractEntity row = new CfgStarterLinkageAbstractEntity();
                    row.setStarterPk(starterRow.getId());
                    row.setFqcn(abstractClass.fqcn());
                    row.setAlias(abstractClass.alias());
                    row.setSort(abstractSort++);
                    snapshot.starterLinkageAbstracts().add(row);
                }
            }
        }
        requireWithinRows(snapshot.rowCount());
        return snapshot;
    }

    /**
     * 解析冲突规则文档为结构行快照。
     *
     * @param yaml          conflict-rules.yml 同构文本（可空 = 空规则集）
     * @param allowDangling 是否容忍悬空 starter 引用（草稿模式 true）
     * @return 冲突规则结构行快照（规则父行带负数占位主键）
     * @throws BusinessException 解析失败或（严格模式下）规则非法
     */
    public StructuredSnapshot parseConflictRules(String yaml, boolean allowDangling) {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        if (isBlank(yaml)) {
            return snapshot;
        }
        ConflictCatalog catalog = ConflictRuleParser.parseText(yaml);
        requireWithin("conflictRules", catalog.size());
        int sort = 0;
        long placeholderPk = 0L;
        for (ConflictRule rule : catalog.all()) {
            CfgConflictRuleEntity row = new CfgConflictRuleEntity();
            row.setId(--placeholderPk);
            row.setRuleId(rule.id());
            row.setRuleType(rule.type().name());
            row.setMessage(rule.message());
            row.setSort(sort++);
            snapshot.conflictRules().add(row);
        }
        for (ConflictRule rule : catalog.all()) {
            CfgConflictRuleEntity ruleRow = snapshot.conflictRules().stream()
                    .filter(row -> row.getRuleId().equals(rule.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("冲突规则父行构造缺失: " + rule.id()));
            int memberSort = 0;
            for (String starterId : rule.starters()) {
                CfgConflictRuleStarterEntity row = new CfgConflictRuleStarterEntity();
                row.setRulePk(ruleRow.getId());
                row.setStarterId(starterId);
                row.setSort(memberSort++);
                snapshot.conflictRuleStarters().add(row);
            }
        }
        requireWithinRows(snapshot.rowCount());
        return snapshot;
    }

    /**
     * 解析多文档模板文本为模板结构行（{@code {name, content}} 文档流）。
     *
     * @param yaml 多文档模板文本（可空 = 空模板集）
     * @return 模板结构行快照
     * @throws BusinessException 文档结构非法、模板名重复或语法非法（4002）
     */
    public StructuredSnapshot parseTemplates(String yaml) {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        if (isBlank(yaml)) {
            return snapshot;
        }
        List<String> documents = splitDocuments(yaml);
        requireWithin("templates", documents.size());
        int sort = 0;
        Set<String> names = new LinkedHashSet<>();
        Instant now = Instant.now();
        for (String document : documents) {
            Object root;
            try {
                root = new Yaml(new SafeConstructor(new LoaderOptions())).load(document);
            } catch (RuntimeException e) {
                throw invalid("template 配置解析失败: " + e.getMessage(), e);
            }
            if (!(root instanceof Map<?, ?> map)) {
                throw invalid("template 文档顶层必须为映射结构（含 name 与 content 字段）");
            }
            Object name = map.get("name");
            Object content = map.get("content");
            if (name == null || name.toString().isBlank()) {
                throw invalid("template 缺少必需字段: name");
            }
            if (content == null || content.toString().isBlank()) {
                throw invalid("template " + name + " 缺少必需字段: content");
            }
            String templateName = name.toString().trim();
            String templateContent = content.toString();
            if (!names.add(templateName)) {
                throw invalid("模板名称重复: " + templateName);
            }
            requireTemplateSize(templateName, templateContent);
            try {
                // Only .ftl is FreeMarker; YAML fragments may contain Spring runtime placeholders.
                if (templateName.endsWith(".ftl")) {
                    templateValidator.validate(templateName, templateContent);
                }
            } catch (BusinessException e) {
                throw invalid("模板语法非法: " + templateName + "（" + e.getMessage() + "）", e);
            }
            CfgTemplateEntity row = new CfgTemplateEntity();
            row.setName(templateName);
            row.setContent(templateContent);
            row.setSort(sort++);
            row.setUpdatedAt(now);
            snapshot.templates().add(row);
        }
        requireWithinRows(snapshot.rowCount());
        return snapshot;
    }

    // ==================== 严格模式：跨档/图一致性 ====================

    /**
     * 严格模式元数据校验：Java 引用必须已定义、Boot ID 不得重复、白名单引用必须已在 starter 清单登记。
     *
     * @param javaRows     Java 版本行
     * @param bootRows     Boot 版本行
     * @param matrix       兼容矩阵行
     * @param starterRefs  白名单行
     * @throws BusinessException 图非法（4002）
     */
    private void validateMetadataStrict(List<CfgJavaVersionEntity> javaRows, Iterable<CfgBootVersionEntity> bootRows,
                                        List<CfgBootJavaEntity> matrix, List<CfgBootStarterRefEntity> starterRefs,
                                        Set<String> declaredStarters) {
        Set<String> javaIds = new LinkedHashSet<>();
        for (CfgJavaVersionEntity row : javaRows) {
            if (!javaIds.add(row.getVersionId())) {
                throw invalid("metadata Java 版本 ID 重复: " + row.getVersionId());
            }
        }
        Set<String> bootIds = new LinkedHashSet<>();
        for (CfgBootVersionEntity row : bootRows) {
            if (!bootIds.add(row.getVersionId())) {
                throw invalid("metadata Boot 版本 ID 重复: " + row.getVersionId());
            }
        }
        for (CfgBootJavaEntity link : matrix) {
            if (!javaIds.contains(link.getJavaVersionId())) {
                throw invalid("metadata 兼容矩阵引用了未定义的 Java 版本: " + link.getJavaVersionId());
            }
        }
        for (CfgBootStarterRefEntity ref : starterRefs) {
            if (!declaredStarters.isEmpty() && !declaredStarters.contains(ref.getStarterId())) {
                throw invalid("metadata bootVersions 白名单引用了归档内不存在的 starter: " + ref.getStarterId());
            }
        }
    }

    /**
     * 严格模式 starter 校验：ID 重复、展示名/坐标与 metadata 白名单一致性、requires 悬空引用。
     *
     * @param entries starter 条目
     * @param source  来源通道（仅用于错误明细）
     * @throws BusinessException 清单非法（4002）
     */
    private void validateStarterCatalog(List<StarterCatalogEntry> entries, String source) {
        try {
            StarterCatalog.of(entries);
        } catch (BusinessException e) {
            throw invalid("starter 清单非法（source=" + source + "）: " + e.getMessage(), e);
        }
    }

    /**
     * 严格模式跨档校验：归档内 starter 清单必须已被元数据登记（展示名与坐标的唯一数据源）。
     *
     * @param metadata metadata 结构行
     * @param starters starter 结构行
     * @throws BusinessException 存在未登记的 starter（4002）
     */
    public void validateCrossDocument(StructuredSnapshot metadata, StructuredSnapshot starters) {
        Set<String> declared = new LinkedHashSet<>();
        for (CfgBootStarterRefEntity ref : metadata.bootStarterRefs()) {
            declared.add(ref.getStarterId());
        }
        for (CfgStarterEntity row : starters.starters()) {
            if (!declared.contains(row.getStarterId())) {
                throw invalid("starter " + row.getStarterId()
                        + " 未在 metadata 的 bootVersions[].starters 白名单中登记");
            }
        }
    }

    // ==================== 内部：解析与校验 ====================

    /**
     * 解析多文档 starter 文本为条目清单（复用 core 的单文档解析器）。
     *
     * @param yaml 多文档 starter yml（可空）
     * @return 条目清单（保持配置顺序）
     * @throws BusinessException 单文档解析失败（4009）
     */
    private List<StarterCatalogEntry> parseStarterEntries(String yaml) {
        List<StarterCatalogEntry> entries = new ArrayList<>();
        if (isBlank(yaml)) {
            return entries;
        }
        for (String document : splitDocuments(yaml)) {
            try (var in = new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8))) {
                entries.add(YamlStarterParser.parse(in));
            } catch (BusinessException e) {
                throw invalid("starter 配置非法: " + e.getMessage(), e);
            } catch (Exception e) {
                throw invalid("starter 配置读取失败: " + e.getMessage(), e);
            }
        }
        return entries;
    }

    /**
     * starter 条目自身字段校验（ID 重复、必填、requires 组非空）。
     *
     * @param entries 条目清单
     * @throws BusinessException 结构非法（4002）
     */
    private void validateStarterMeta(List<StarterCatalogEntry> entries) {
        StarterScopeIndex identities = new StarterScopeIndex();
        for (StarterCatalogEntry entry : entries) {
            try {
                identities.add(entry.id(), entry.bootVersions());
            } catch (IllegalArgumentException e) {
                throw invalid(e.getMessage(), e);
            }
            if (entry.category() == null || entry.category().isBlank()) {
                throw invalid("starter " + entry.id() + " 缺少必需字段: category");
            }
            if (entry.maintainer() == null) {
                throw invalid("starter " + entry.id() + " 缺少必需字段或取值非法: maintainer");
            }
            for (List<String> group : entry.requires()) {
                if (group.isEmpty()) {
                    throw invalid("starter " + entry.id() + " 的 requires 存在空 OR 组");
                }
            }
        }
    }

    /**
     * 解析 metadata 文档为「结构映射」文档（容忍悬空引用与重复 ID）。
     *
     * @param yaml metadata.yml 同构文本
     * @return 结构映射文档
     * @throws BusinessException YAML 语法错误或字段类型非法（4009）
     */
    private static MetadataDoc.Parsed parseMetadataDoc(String yaml) {
        Map<String, Object> root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw invalid("metadata 配置解析失败: " + e.getMessage(), e);
        }
        if (root == null) {
            throw invalid("metadata 配置内容为空");
        }
        List<MetadataDoc.JavaRow> javaRows = new ArrayList<>();
        for (Object item : requireList(root.get("javaVersions"), "javaVersions")) {
            Map<String, Object> map = requireMap(item, "javaVersions[]");
            javaRows.add(new MetadataDoc.JavaRow(
                    requireString(map, "id", "javaVersions[].id"),
                    stringOrDefault(map, "label", "Java " + map.get("id")),
                    Boolean.TRUE.equals(map.get("deprecated")),
                    stringOrNull(map, "recommendedBootVersion")));
        }
        List<MetadataDoc.BootRow> bootRows = new ArrayList<>();
        for (Object item : requireList(root.get("bootVersions"), "bootVersions")) {
            Map<String, Object> map = requireMap(item, "bootVersions[]");
            bootRows.add(new MetadataDoc.BootRow(
                    requireString(map, "id", "bootVersions[].id"),
                    stringOrDefault(map, "label", "Spring Boot " + map.get("id")),
                    !Boolean.FALSE.equals(map.get("official")),
                    toStringList(map.get("supportedJavaVersions"), "bootVersions[].supportedJavaVersions"),
                    toStringList(map.get("starters"), "bootVersions[].starters"),
                    parseFrameworkParent(map.get("frameworkParent"))));
        }
        return new MetadataDoc.Parsed(javaRows, bootRows);
    }

    /**
     * 解析框架父 POM 节（三字段齐全才生效）。
     *
     * @param node frameworkParent 节点
     * @return 三字段；未配置返回 {@code null}
     * @throws BusinessException 三字段不齐（4009）
     */
    private static MetadataDoc.Parent parseFrameworkParent(Object node) {
        if (node == null) {
            return null;
        }
        Map<String, Object> map = requireMap(node, "frameworkParent");
        String groupId = stringOrNull(map, "groupId");
        String artifactId = stringOrNull(map, "artifactId");
        String version = stringOrNull(map, "version");
        if (groupId == null || artifactId == null || version == null) {
            throw invalid("metadata frameworkParent 字段不完整，需 groupId/artifactId/version 三项齐全");
        }
        return new MetadataDoc.Parent(groupId, artifactId, version);
    }

    // ==================== 内部：小工具 ====================

    /**
     * 按 YAML 文档分隔符切分多文档。
     *
     * <p>委托 {@link CatalogRefresher#splitDocuments(String)}：分隔符判定严格限定为行首列 0
     * 的 {@code ---}，避免块标量（模板正文）内部的缩进 {@code ---} 被误切。
     *
     * @param yaml 配置全文（可空）
     * @return 非空文档清单（保持配置顺序）
     */
    public static List<String> splitDocuments(String yaml) {
        return CatalogRefresher.splitDocuments(yaml);
    }

    /**
     * 必需列表字段断言。
     *
     * @param node  原始节点
     * @param field 字段名
     * @return 列表视图
     * @throws BusinessException 非列表或为空（4009）
     */
    private static List<Object> requireList(Object node, String field) {
        if (!(node instanceof List<?> list) || list.isEmpty()) {
            throw invalid("metadata 缺少必需字段或类型非法: " + field);
        }
        return new ArrayList<>(list);
    }

    /**
     * 必需映射字段断言。
     *
     * @param node  原始节点
     * @param field 字段名
     * @return 映射视图
     * @throws BusinessException 非映射（4009）
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Object node, String field) {
        if (!(node instanceof Map)) {
            throw invalid("metadata 字段类型非法: " + field);
        }
        return (Map<String, Object>) node;
    }

    /**
     * 必需字符串字段断言。
     *
     * @param map   所属映射
     * @param key   字段名
     * @param field 错误明细路径
     * @return 非空字符串
     * @throws BusinessException 缺失或空白（4009）
     */
    private static String requireString(Map<String, Object> map, String key, String field) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw invalid("metadata 缺少必需字段: " + field);
        }
        return value.toString();
    }

    /**
     * 宽松字符串读取（缺失/空白取默认值）。
     *
     * @param map          所属映射
     * @param key          字段名
     * @param defaultValue 默认值
     * @return 字段值或默认值
     */
    private static String stringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

    /**
     * 宽松字符串读取（缺失/空白返回 null）。
     *
     * @param map 所属映射
     * @param key 字段名
     * @return 字段值或 {@code null}
     */
    private static String stringOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    /**
     * 字符串列表读取（元素去空白、过滤空串）。
     *
     * @param node  原始节点
     * @param field 字段名
     * @return 字符串列表
     * @throws BusinessException 元素类型非法（4009）
     */
    private static List<String> toStringList(Object node, String field) {
        if (node == null) {
            return List.of();
        }
        if (!(node instanceof List<?> list)) {
            throw invalid("metadata 字段类型非法: " + field + "（必须为列表）");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = item.toString().trim();
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return result;
    }

    /**
     * 文本空判定。
     *
     * @param text 文本
     * @return 空或全空白返回 {@code true}
     */
    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * 条目数量上限断言。
     *
     * @param field 字段路径
     * @param count 条目数
     * @throws BusinessException 超限（4002）
     */
    private static void requireWithin(String field, int count) {
        if (count > MAX_ITEMS) {
            throw invalid(field + " 条目数超限（最多 " + MAX_ITEMS + " 项，当前 " + count + "）");
        }
    }

    /**
     * 结构行数量上限断言。
     *
     * @param rows 结构行数
     * @throws BusinessException 超限（4002）
     */
    private static void requireWithinRows(int rows) {
        if (rows > MAX_ROWS) {
            throw invalid("结构行数超限（最多 " + MAX_ROWS + " 行，当前 " + rows + "）");
        }
    }

    /**
     * 模板内容长度上限断言。
     *
     * @param name    模板名
     * @param content 模板全文
     * @throws BusinessException 超长（4002）
     */
    private static void requireTemplateSize(String name, String content) {
        if (content.length() > MAX_TEMPLATE_LENGTH) {
            throw invalid("模板 " + name + " 内容超长（最多 " + MAX_TEMPLATE_LENGTH + " 字符）");
        }
    }

    /**
     * 构造导入校验失败异常（4009）。
     *
     * @param message 明细
     * @return 业务异常
     */
    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.CONFIG_IMPORT_VALIDATION_FAILED, message);
    }

    /**
     * 构造导入校验失败异常（带原因）。
     *
     * @param message 明细
     * @param cause   根因
     * @return 业务异常
     */
    private static BusinessException invalid(String message, Throwable cause) {
        return new BusinessException(ErrorCode.CONFIG_IMPORT_VALIDATION_FAILED, message, cause);
    }

    /**
     * 宽松解析维护方名（仅用于异常明细可读性）。
     *
     * @param raw 原始值
     * @return 枚举名；非法返回 {@code null}
     */
    static Maintainer parseMaintainerOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Maintainer.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
