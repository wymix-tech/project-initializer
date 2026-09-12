package com.wymix.initializer.app.admin.refresh;

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
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.repository.StructuredSnapshot;
import com.wymix.initializer.core.conflict.ConflictCatalog;
import com.wymix.initializer.core.conflict.ConflictRule;
import com.wymix.initializer.core.generator.TemplateRenderer;
import com.wymix.initializer.core.metadata.BootVersion;
import com.wymix.initializer.core.metadata.FrameworkParent;
import com.wymix.initializer.core.metadata.JavaVersion;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.AbstractClassLinkage;
import com.wymix.initializer.core.starter.DependencyCoord;
import com.wymix.initializer.core.starter.LinkageSpec;
import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import com.wymix.initializer.core.starter.TemplateLinkage;
import com.wymix.initializer.core.starter.YmlFragmentLinkage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 结构化目录装配器（结构化改造 T2）：把 {@code cfg_*} 关系表的**表行**直接装配为
 * {@link CatalogSnapshot} 所需的核心模型（{@link MetadataCatalog} / {@link StarterCatalog} /
 * {@link ConflictCatalog} / 模板映射），**不再经过 YAML 文本往返**。
 *
 * <p><b>与 T1 过渡桥的分工</b>：T1 的 {@code StructuredYamlCodec} 走「表行 → YAML 文本 →
 * 既有解析器」的间接通道；本装配器改走「表行 → record 模型」直装，
 * 复用 core 的**装载期校验**（{@code MetadataCatalog.of} / {@code StarterCatalog.of} /
 * {@link ConflictRule} 构造）保证合法性与 YAML 通道完全一致。
 * T2 落地后 {@code StructuredYamlCodec} 的渲染职责收缩为「导入导出（T4）专用旁路」。
 *
 * <p><b>性能红线（禁 N+1）</b>：{@link ConfigRepository#readScope(long)} 已按
 * {@code release_id} 一次性批量查出 15 张表的全部行（每表一条 SQL），
 * 本类只在内存里按父键分组（各 1 次遍历，不查库），
 * {@link #assemble(long)} 全程的数据库交互次数恒等于 1 次调用 {@code readScope}。
 *
 * <p><b>草稿宽松 / 发布严格的边界</b>：本装配器只服务**发布、回滚、启动装表**三条
 * 严格路径——装配失败一律收敛为 4002（发布校验失败）并给出精确明细；
 * 草稿态的「允许悬空引用与重复 ID、边补边存」由 T1 的 {@code MetadataDoc} /
 * {@code StructuredYamlCodec} 通道保留，本类不参与草稿读取。
 *
 * <p><b>业务 ID 解析</b>：{@code cfg_boot_java.java_version_id} 与
 * {@code cfg_boot_starter_ref.starter_id} 存**业务 ID**（非主键），
 * 装配时在作用域内解析；悬空引用由 {@link MetadataCatalog#of} /
 * {@link MetadataCatalog#merge(StarterCatalog)} 的装载期校验拦截。
 *
 * <p>线程安全性：无状态单例（全部状态在入参/返回值中），线程安全。
 */
@Component
public class StructuredCatalogAssembler {

    /** 模板语法校验器（发布期坏模板即拒绝，与 YAML 通道同源）。 */
    private final TemplateRenderer templateValidator = new TemplateRenderer();

    private final ConfigRepository repository;

    /**
     * @param repository 配置存储仓库（按 {@code release_id} 一次性读全量结构行）
     */
    public StructuredCatalogAssembler(ConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * 装配指定版本作用域的快照（发布、回滚、启动装表共用入口）。
     *
     * <p>数据库交互恰为 1 次（{@link ConfigRepository#readScope(long)} 内含 15 条批量查询），
     * 之后全部在内存分组装配；空作用域（无任何结构行）返回空快照占位
     * （metadata 空目录 + 空清单 + 空模板 + 空规则），由调用方决定是否视为「未配置」。
     *
     * @param releaseId 版本作用域：0 = 草稿工作区，&gt; 0 = 发布快照 ID
     * @return 该作用域的可生效快照
     * @throws BusinessException 装配/装载期校验失败（错误码 {@link ErrorCode#CONFIG_VALIDATION_FAILED}）
     */
    public CatalogSnapshot assemble(long releaseId) {
        return assemble(repository.readScope(releaseId));
    }

    /**
     * 装配已读出的结构行快照（供发布链路复用同一次读取，避免二次查库）。
     *
     * <p>装配顺序：元数据（Java/Boot 兼容矩阵 + starter 白名单）→ starter 清单
     * （依赖/联动/Boot 关联/requires）→ 模板映射 → 冲突规则 → 悬空引用校验 →
     * {@code metadata.merge(starterCatalog)} 联动。
     *
     * @param rows 单作用域的结构行快照（通常来自 {@link ConfigRepository#readScope(long)}）
     * @return 可生效快照
     * @throws BusinessException 装配/装载期校验失败（错误码 {@link ErrorCode#CONFIG_VALIDATION_FAILED}）
     */
    public CatalogSnapshot assemble(StructuredSnapshot rows) {
        MetadataCatalog metadata = assembleMetadata(rows);
        StarterCatalog starters = assembleStarters(rows);
        Map<String, String> templates = assembleTemplates(rows);
        ConflictCatalog conflicts = assembleConflictRules(rows);
        validateConflictReferences(conflicts, metadata, starters);
        // starter 清单联动：展示名与坐标以清单为准，清单条目自动追加为全部 Boot 版本可选
        return new CatalogSnapshot(metadata.merge(starters), starters, templates, conflicts);
    }

    // ==================== 元数据 ====================

    /**
     * 装配元数据目录（Java 版本 + Boot 版本 + 兼容矩阵 + 可选 starter 白名单）。
     *
     * @param rows 单作用域结构行
     * @return 元数据目录（无行时为空目录）
     * @throws BusinessException 重复 ID / 悬空 Java 引用（收敛为 4002）
     */
    private MetadataCatalog assembleMetadata(StructuredSnapshot rows) {
        List<JavaVersion> javaVersions = new ArrayList<>();
        for (CfgJavaVersionEntity row : sorted(rows.javaVersions(), CfgJavaVersionEntity::getSort)) {
            javaVersions.add(new JavaVersion(row.getVersionId(), row.getLabel(),
                    Boolean.TRUE.equals(row.getDeprecated()), row.getRecommendedBootVersion()));
        }
        // 兼容矩阵与 starter 白名单：按 Boot 父行主键分组（业务 ID 直接落库，无需主键换算）
        Map<Long, List<String>> javaRefs = new LinkedHashMap<>();
        for (CfgBootJavaEntity row : rows.bootJava()) {
            javaRefs.computeIfAbsent(row.getBootPk(), k -> new ArrayList<>()).add(row.getJavaVersionId());
        }
        Map<Long, List<String>> starterRefs = new LinkedHashMap<>();
        for (CfgBootStarterRefEntity row : sorted(rows.bootStarterRefs(), CfgBootStarterRefEntity::getSort)) {
            starterRefs.computeIfAbsent(row.getBootPk(), k -> new ArrayList<>()).add(row.getStarterId());
        }
        List<BootVersion> bootVersions = new ArrayList<>();
        for (CfgBootVersionEntity row : sorted(rows.bootVersions(), CfgBootVersionEntity::getSort)) {
            bootVersions.add(new BootVersion(row.getVersionId(), row.getLabel(),
                    !Boolean.FALSE.equals(row.getOfficial()),
                    javaRefs.getOrDefault(row.getId(), List.of()),
                    starterRefs.getOrDefault(row.getId(), List.of()),
                    frameworkParentOf(row)));
        }
        try {
            return MetadataCatalog.of(javaVersions, bootVersions);
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "发布校验失败: metadata 配置非法: " + e.getMessage(), e);
        }
    }

    /**
     * 还原 Boot 行的框架父 POM（三字段齐全才生效，与写入口径一致）。
     *
     * @param row Boot 版本行
     * @return 框架父 POM；未完整配置时返回 {@code null}
     */
    private static FrameworkParent frameworkParentOf(CfgBootVersionEntity row) {
        if (row.getFpGroup() == null || row.getFpArtifact() == null || row.getFpVersion() == null) {
            return null;
        }
        return new FrameworkParent(row.getFpGroup(), row.getFpArtifact(), row.getFpVersion());
    }

    // ==================== starter 清单 ====================

    /**
     * 装配 starter 清单（父行 + 依赖 / 联动 / Boot 关联 / 依赖分组）。
     *
     * <p>{@code hidden} 从 {@code cfg_starter} 直取（不经 YAML 反编译），
     * 与 PR #40 引入的前端隐藏语义端到端一致。
     *
     * @param rows 单作用域结构行
     * @return starter 清单（无行时为空清单）
     * @throws BusinessException 装载期校验失败（收敛为 4002，含 ID 重复/悬空 requires/成环）
     */
    private StarterCatalog assembleStarters(StructuredSnapshot rows) {
        Map<Long, List<CfgStarterDependencyEntity>> deps = group(rows.starterDependencies(),
                CfgStarterDependencyEntity::getStarterPk, CfgStarterDependencyEntity::getSort);
        Map<Long, List<CfgStarterRequireGroupEntity>> groups = group(rows.starterRequireGroups(),
                CfgStarterRequireGroupEntity::getStarterPk, CfgStarterRequireGroupEntity::getGroupIndex);
        Map<Long, List<CfgStarterRequireItemEntity>> items = group(rows.starterRequireItems(),
                CfgStarterRequireItemEntity::getGroupPk, CfgStarterRequireItemEntity::getSort);
        Map<Long, List<CfgStarterBootVersionEntity>> boots = group(rows.starterBootVersions(),
                CfgStarterBootVersionEntity::getStarterPk, null);
        Map<Long, List<CfgStarterLinkageTemplateEntity>> linkageTemplates = group(
                rows.starterLinkageTemplates(), CfgStarterLinkageTemplateEntity::getStarterPk,
                CfgStarterLinkageTemplateEntity::getSort);
        Map<Long, List<CfgStarterLinkageYmlEntity>> linkageYmls = group(rows.starterLinkageYmls(),
                CfgStarterLinkageYmlEntity::getStarterPk, CfgStarterLinkageYmlEntity::getSort);
        Map<Long, List<CfgStarterLinkageAbstractEntity>> linkageAbstracts = group(
                rows.starterLinkageAbstracts(), CfgStarterLinkageAbstractEntity::getStarterPk,
                CfgStarterLinkageAbstractEntity::getSort);

        List<StarterCatalogEntry> entries = new ArrayList<>();
        for (CfgStarterEntity row : sorted(rows.starters(), CfgStarterEntity::getSort)) {
            entries.add(new StarterCatalogEntry(
                    row.getStarterId(),
                    row.getCategory(),
                    maintainerOf(row),
                    row.getDescription(),
                    dependenciesOf(deps.getOrDefault(row.getId(), List.of())),
                    linkagesOf(row, linkageTemplates.getOrDefault(row.getId(), List.of()),
                            linkageYmls.getOrDefault(row.getId(), List.of()),
                            linkageAbstracts.getOrDefault(row.getId(), List.of())),
                    Boolean.TRUE.equals(row.getRequired()),
                    requiresOf(groups.getOrDefault(row.getId(), List.of()), items),
                    boots.getOrDefault(row.getId(), List.of()).stream()
                            .map(CfgStarterBootVersionEntity::getBootVersionId).toList(),
                    Boolean.TRUE.equals(row.getHidden())));
        }
        try {
            return StarterCatalog.of(entries);
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "发布校验失败: starter 清单非法: " + e.getMessage(), e);
        }
    }

    /**
     * 还原 starter 维护方（列存枚举名，大小写不敏感；未知/空视为 {@code null} 交装载期拒绝）。
     *
     * @param row starter 主行
     * @return 维护方枚举；无法解析时返回 {@code null}
     */
    private static Maintainer maintainerOf(CfgStarterEntity row) {
        if (row.getMaintainer() == null || row.getMaintainer().isBlank()) {
            return null;
        }
        try {
            return Maintainer.valueOf(row.getMaintainer().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 还原依赖坐标清单。
     *
     * @param rows 依赖行（已按 sort 定序）
     * @return 坐标清单
     */
    private static List<DependencyCoord> dependenciesOf(List<CfgStarterDependencyEntity> rows) {
        List<DependencyCoord> result = new ArrayList<>();
        for (CfgStarterDependencyEntity row : rows) {
            result.add(new DependencyCoord(row.getGroupId(), row.getArtifactId(), row.getVersion()));
        }
        return result;
    }

    /**
     * 还原联动规则集合（config-package 取 starter 主行列，三类联动取子表行）。
     *
     * @param starter           starter 主行
     * @param templates         模板联动行
     * @param ymls              yml 片段联动行
     * @param abstracts         抽象类联动行
     * @return 联动规则集合（永不为 null）
     */
    private static LinkageSpec linkagesOf(CfgStarterEntity starter,
                                          List<CfgStarterLinkageTemplateEntity> templates,
                                          List<CfgStarterLinkageYmlEntity> ymls,
                                          List<CfgStarterLinkageAbstractEntity> abstracts) {
        List<TemplateLinkage> templateLinks = new ArrayList<>();
        for (CfgStarterLinkageTemplateEntity row : templates) {
            templateLinks.add(new TemplateLinkage(row.getSource(), row.getTarget()));
        }
        List<YmlFragmentLinkage> ymlLinks = new ArrayList<>();
        for (CfgStarterLinkageYmlEntity row : ymls) {
            ymlLinks.add(new YmlFragmentLinkage(row.getSource(), mergeStrategyOf(row)));
        }
        List<AbstractClassLinkage> abstractLinks = new ArrayList<>();
        for (CfgStarterLinkageAbstractEntity row : abstracts) {
            abstractLinks.add(new AbstractClassLinkage(row.getFqcn(), row.getAlias()));
        }
        return new LinkageSpec(starter.getConfigPackage(), templateLinks, ymlLinks, abstractLinks);
    }

    /**
     * 还原 yml 片段合并策略（缺省 APPEND，与写入口径一致）。
     *
     * @param row yml 片段联动行
     * @return 合并策略
     */
    private static YmlFragmentLinkage.MergeStrategy mergeStrategyOf(CfgStarterLinkageYmlEntity row) {
        if (row.getMergeStrategy() == null || row.getMergeStrategy().isBlank()) {
            return YmlFragmentLinkage.MergeStrategy.APPEND;
        }
        try {
            return YmlFragmentLinkage.MergeStrategy.valueOf(row.getMergeStrategy().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return YmlFragmentLinkage.MergeStrategy.APPEND;
        }
    }

    /**
     * 还原依赖联动分组（AND-of-OR-groups）：分组按 {@code groupIndex} 定序，
     * 组内项按 {@code sort} 定序，空组不产出（与 {@code StarterCatalogEntry} 归一化口径一致）。
     *
     * @param groups 该 starter 的分组行（已定序）
     * @param items  组主键 → 组内项行
     * @return 分组列表（无分组时为空列表）
     */
    private static List<List<String>> requiresOf(List<CfgStarterRequireGroupEntity> groups,
                                                 Map<Long, List<CfgStarterRequireItemEntity>> items) {
        List<List<String>> result = new ArrayList<>();
        for (CfgStarterRequireGroupEntity group : groups) {
            List<String> members = items.getOrDefault(group.getId(), List.of()).stream()
                    .map(CfgStarterRequireItemEntity::getRequiredStarterId).toList();
            if (!members.isEmpty()) {
                result.add(members);
            }
        }
        return result;
    }

    // ==================== 模板 ====================

    /**
     * 装配模板映射（模板名 → 模板全文）：数据库全量模板集，并在装配期做 FreeMarker 语法预校验。
     *
     * <p>语法非法的模板直接拒绝（4002），与 YAML 通道的发布期模板校验同源——
     * 坏模板绝不进入快照。
     *
     * <p><b>只校验 {@code .ftl}</b>（T5）：模板集同时承载两类条目——
     * ①{@code .ftl} FreeMarker 模板；②starter 的 {@code application-*.yml} 片段
     * （按 {@code linkages.application-yml-fragments[].source} 引用，其内容含 Spring 占位符
     * {@code ${VAR:default}}，既非 FreeMarker 模板也无需转义）。若对 .yml 片段做 FreeMarker
     * 语法校验，合法的 Spring 占位符会被误判为「语法错误」。
     * 片段的可达性与内容合法性由 {@link com.wymix.initializer.core.starter.LinkageRenderer#validateRequirements}
     * 与实际合并时的 YAML 解析共同保障。
     *
     * @param rows 单作用域结构行
     * @return 模板名 → 模板全文（无模板时为空映射）
     * @throws BusinessException 模板名重复或 .ftl 语法非法（4002）
     */
    private Map<String, String> assembleTemplates(StructuredSnapshot rows) {
        Map<String, String> templates = new LinkedHashMap<>();
        for (CfgTemplateEntity row : sorted(rows.templates(), CfgTemplateEntity::getSort)) {
            String name = row.getName();
            if (name == null || name.isBlank()) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "发布校验失败: template 缺少必需字段: name");
            }
            if (row.getContent() == null || row.getContent().isBlank()) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "发布校验失败: template " + name + " 缺少必需字段: content");
            }
            if (templates.putIfAbsent(name, row.getContent()) != null) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "发布校验失败: template 名称重复: " + name);
            }
            if (!name.endsWith(".ftl")) {
                // yml 片段：内容含 Spring 占位符，不做 FreeMarker 语法校验（详见方法 Javadoc）
                continue;
            }
            try {
                templateValidator.validate(name, row.getContent());
            } catch (BusinessException e) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED, e.getMessage(), e);
            }
        }
        return templates;
    }

    // ==================== 冲突规则 ====================

    /**
     * 装配冲突规则目录（父行 + 成员子行），构造即触发装载期校验（规则 ID 重复 / 成员数非法）。
     *
     * @param rows 单作用域结构行
     * @return 冲突规则目录（无规则时为空目录）
     * @throws BusinessException 规则结构非法（4002）
     */
    private ConflictCatalog assembleConflictRules(StructuredSnapshot rows) {
        Map<Long, List<CfgConflictRuleStarterEntity>> members = group(rows.conflictRuleStarters(),
                CfgConflictRuleStarterEntity::getRulePk, CfgConflictRuleStarterEntity::getSort);
        List<ConflictRule> rules = new ArrayList<>();
        for (CfgConflictRuleEntity row : sorted(rows.conflictRules(), CfgConflictRuleEntity::getSort)) {
            List<String> starterIds = members.getOrDefault(row.getId(), List.of()).stream()
                    .map(CfgConflictRuleStarterEntity::getStarterId).toList();
            try {
                rules.add(new ConflictRule(row.getRuleId(), parseRuleType(row), starterIds, row.getMessage()));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "发布校验失败: 冲突规则非法: " + e.getMessage(), e);
            }
        }
        try {
            return new ConflictCatalog(rules);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "发布校验失败: 冲突规则非法: " + e.getMessage(), e);
        }
    }

    /**
     * 解析冲突规则类型（列存枚举名，大小写不敏感）。
     *
     * @param row 规则主行
     * @return 规则类型；取值非法时返回 {@code null}（交构造期拒绝并给出明细）
     */
    private static ConflictRule.Type parseRuleType(CfgConflictRuleEntity row) {
        if (row.getRuleType() == null || row.getRuleType().isBlank()) {
            return null;
        }
        try {
            return ConflictRule.Type.parse(row.getRuleType());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 冲突规则悬空引用校验：规则引用的 starter ID 必须在生效清单内
     * （metadata 白名单或 starter 清单任一登记即可），与 YAML 通道口径一致。
     *
     * @param conflicts 冲突规则目录
     * @param metadata  元数据目录
     * @param starters  starter 清单
     * @throws BusinessException 规则引用了双边均未登记的 starter（4002）
     */
    private static void validateConflictReferences(ConflictCatalog conflicts, MetadataCatalog metadata,
                                                   StarterCatalog starters) {
        for (ConflictRule rule : conflicts.all()) {
            for (String starterId : rule.starters()) {
                boolean known = metadata.starterDefinitionIds().contains(starterId)
                        || starters.contains(starterId);
                if (!known) {
                    throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                            "发布校验失败: 冲突规则 " + rule.id() + " 引用了清单中不存在的 starter: " + starterId);
                }
            }
        }
    }

    // ==================== 内存分组工具 ====================

    /**
     * 按父键分组（内存分组，不查库），并按排序键定序。
     *
     * @param rows    待分组行
     * @param keyOf   父键提取
     * @param sortOf  排序键提取（可传 null 表示保持查询顺序）
     * @param <T>     行类型
     * @return 父键 → 行列表（行列表已定序）
     */
    private static <T> Map<Long, List<T>> group(List<T> rows,
                                                java.util.function.Function<T, Long> keyOf,
                                                java.util.function.Function<T, Integer> sortOf) {
        Map<Long, List<T>> grouped = new LinkedHashMap<>();
        for (T row : rows) {
            Long key = keyOf.apply(row);
            if (key != null) {
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
        if (sortOf != null) {
            grouped.values().forEach(list -> list.sort(Comparator.comparingInt(
                    r -> {
                        Integer sort = sortOf.apply(r);
                        return sort == null ? Integer.MAX_VALUE : sort;
                    })));
        }
        return grouped;
    }

    /**
     * 表行按 {@code sort} 列定序（null 排最后，保持稳定）。
     *
     * @param rows   待排序行
     * @param sortOf 排序键提取
     * @param <T>    行类型
     * @return 定序后的新列表
     */
    private static <T> List<T> sorted(List<T> rows, java.util.function.Function<T, Integer> sortOf) {
        List<T> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparingInt(r -> {
            Integer sort = sortOf.apply(r);
            return sort == null ? Integer.MAX_VALUE : sort;
        }));
        return copy;
    }
}
