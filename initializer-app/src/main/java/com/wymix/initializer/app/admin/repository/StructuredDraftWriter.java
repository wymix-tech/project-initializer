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
import com.wymix.initializer.core.starter.AbstractClassLinkage;
import com.wymix.initializer.core.starter.DependencyCoord;
import com.wymix.initializer.core.starter.LinkageSpec;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import com.wymix.initializer.core.starter.TemplateLinkage;
import com.wymix.initializer.core.starter.YamlStarterParser;
import com.wymix.initializer.core.starter.YmlFragmentLinkage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 结构化草稿写入器（T1 过渡层）：把「YAML 语义文本」解析为 {@code cfg_*} 关系表行，
 * 供手工表单保存（T1 过渡期仍收 YAML）与存量迁移（T1.6）两处复用。
 *
 * <p><b>解析口径</b>：starter 与冲突规则复用 initializer-core 既有解析器
 * （{@link YamlStarterParser} / {@link ConflictRuleParser}），保证结构映射口径一致；
 * metadata 走本类的**结构映射**通道（{@link MetadataDoc}）——草稿态允许悬空引用与重复 ID，
 * 语义合法性统一在发布期校验（4002 明细），与「草稿可任意编辑、发布才整体拒绝」契约一致。
 *
 * <p><b>与 T4 导入的关系</b>：T4 的 ZIP 导入需要额外的安全约束（条目上限、路径穿越拒绝、
 * 单文件大小），但其「解析 → 结构行」的核心转换同样复用本类；本类只负责语义映射，
 * 安全边界与事务边界由调用方（T4 服务）负责。
 *
 * <p>线程安全性：无状态单例（全部状态在入参与返回值中），线程安全。
 */
@Component
public class StructuredDraftWriter {

    private static final Logger log = LoggerFactory.getLogger(StructuredDraftWriter.class);

    private final ConfigRepository repository;

    /**
     * @param repository 配置存储仓库（结构行写入）
     */
    public StructuredDraftWriter(ConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * 两阶段写库：把「父行 + 引用它们的子行」放进同一次写入，落库顺序由仓库层保证。
     *
     * <p>父子主键映射挂在 {@link StructuredSnapshot} 实例上，因此必须一次性提交；
     * 父行对象在写入前已带**显式占位主键**（负数区间）供子行引用，
     * 仓库层写父行时登记「占位主键 → 真实主键」映射，写子行时换算。
     *
     * @param parents  父行集合（Java / Boot / starter / 规则 / 模板）
     * @param children 子行集合（依赖 / 联动 / 兼容矩阵 / 白名单 / 规则成员 / 依赖分组）
     * @return 写入行数合计
     */
    private int writeTwoStage(StructuredSnapshot parents, StructuredSnapshot children) {
        StructuredSnapshot combined = new StructuredSnapshot(ConfigRepository.DRAFT_RELEASE_ID,
                parents.javaVersions(), parents.bootVersions(),
                children.bootJava(), children.bootStarterRefs(),
                parents.starters(), children.starterDependencies(), children.starterRequireGroups(),
                children.starterRequireItems(), children.starterBootVersions(),
                children.starterLinkageTemplates(), children.starterLinkageYmls(),
                children.starterLinkageAbstracts(), parents.conflictRules(),
                children.conflictRuleStarters(), parents.templates());
        return repository.appendToDraft(combined);
    }

    /**
     * 把 YAML 语义文本解析为结构行并写入草稿作用域（调用方须先清空对应 kind）。
     *
     * @param kind 配置种类（决定解析通道与落库目标表）
     * @param yaml 与 kind 语义同构的 YAML 文本
     * @return 实际写入的结构行数
     * @throws BusinessException YAML 结构非法（4002）或 kind 无对应解析通道（4001）
     */
    public int write(ConfigKind kind, String yaml) {
        return switch (kind) {
            case METADATA -> writeMetadata(yaml);
            case STARTER -> writeStarters(yaml, ConfigRepository.SOURCE_MANUAL);
            case STARTER_SYNC -> writeStarters(yaml, ConfigRepository.SOURCE_SYNC);
            case CONFLICT_RULE -> writeConflictRules(yaml);
            case TEMPLATE -> writeTemplates(yaml);
        };
    }

    /**
     * 写入元数据结构行（Java 版本 / Boot 版本 / 兼容矩阵 / starter 白名单）。
     *
     * <p>兼容矩阵与白名单以「行的自增主键」表达 ID 引用，故先落父行拿到主键，
     * 再在内存映射 ID → 主键后写子行。
     *
     * @param yaml metadata.yml 同构文本
     * @return 写入行数
     * @throws BusinessException 解析失败（4002）
     */
    private int writeMetadata(String yaml) {
        // 草稿保存只做**结构映射**（YAML 语法 + 字段类型），不做图合法性校验：
        // 悬空引用、ID 重复等语义校验统一留到发布期（4002），
        // 与「草稿可任意编辑、发布才整体拒绝」的既有契约一致。
        MetadataDoc.Parsed parsed = parseMetadataDoc(yaml);
        StructuredSnapshot parents = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        int sort = 0;
        Set<String> javaIds = new LinkedHashSet<>();
        for (MetadataDoc.JavaRow java : parsed.javaVersions()) {
            CfgJavaVersionEntity row = new CfgJavaVersionEntity();
            row.setVersionId(java.id());
            row.setLabel(java.label());
            row.setDeprecated(java.deprecated());
            row.setRecommendedBootVersion(java.recommendedBootVersion());
            row.setSort(sort++);
            parents.javaVersions().add(row);
            javaIds.add(java.id());
        }
        sort = 0;
        Map<String, CfgBootVersionEntity> bootById = new HashMap<>();
        long bootPlaceholderPk = 0L;
        for (MetadataDoc.BootRow boot : parsed.bootVersions()) {
            CfgBootVersionEntity row = new CfgBootVersionEntity();
            row.setId(--bootPlaceholderPk);
            row.setVersionId(boot.id());
            row.setLabel(boot.label());
            row.setOfficial(boot.official());
            if (boot.frameworkParent() != null) {
                row.setFpGroup(boot.frameworkParent().groupId());
                row.setFpArtifact(boot.frameworkParent().artifactId());
                row.setFpVersion(boot.frameworkParent().version());
            }
            row.setSbVersion(boot.springBootVersion());
            row.setSort(sort++);
            parents.bootVersions().add(row);
            bootById.put(boot.id(), row);
        }

        StructuredSnapshot children = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        for (MetadataDoc.BootRow boot : parsed.bootVersions()) {
            CfgBootVersionEntity bootRow = bootById.get(boot.id());
            int childSort = 0;
            for (String javaId : boot.supportedJavaVersions()) {
                // 兼容矩阵存业务 ID：悬空引用（javaIds 不含）也原样落库，
                // 保证草稿态能看到完整输入、发布期能给出精确的 4002 明细
                if (!javaIds.contains(javaId)) {
                    log.debug("metadata 草稿存在悬空 Java 引用（发布期将拒绝）: boot={}, java={}",
                            boot.id(), javaId);
                }
                CfgBootJavaEntity link = new CfgBootJavaEntity();
                link.setBootPk(bootRow.getId());
                link.setJavaVersionId(javaId);
                children.bootJava().add(link);
            }
            for (String starterId : boot.starters()) {
                CfgBootStarterRefEntity ref = new CfgBootStarterRefEntity();
                ref.setBootPk(bootRow.getId());
                ref.setStarterId(starterId);
                ref.setSort(childSort++);
                children.bootStarterRefs().add(ref);
            }
        }
        int rows = writeTwoStage(parents, children);
        log.debug("元数据结构行已写入: java={}, boot={}, rows={}",
                parents.javaVersions().size(), parents.bootVersions().size(), rows);
        return rows;
    }

    /**
     * 解析 metadata 草稿为「结构映射」文档（不含图合法性校验）。
     *
     * <p>与 {@link MetadataCatalog#load} 的差别：本方法**容忍悬空引用与重复 ID**，
     * 因为草稿保存阶段必须允许用户逐步补全配置；语义校验在发布期统一执行（4002 明细）。
     * 字段类型与必需字段仍做检查，避免把结构性垃圾写进库。
     *
     * @param yaml metadata.yml 同构文本
     * @return 结构映射文档
     * @throws BusinessException YAML 语法错误或字段类型非法（4002）
     */
    @SuppressWarnings("unchecked")
    private static MetadataDoc.Parsed parseMetadataDoc(String yaml) {
        Map<String, Object> root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "metadata 配置解析失败: " + e.getMessage(), e);
        }
        if (root == null) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED, "metadata 配置内容为空");
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
                    parseFrameworkParent(map.get("frameworkParent")),
                    stringOrNull(map, "springBootVersion")));
        }
        return new MetadataDoc.Parsed(javaRows, bootRows);
    }

    /**
     * 解析 {@code frameworkParent} 节（可选）：三字段齐全才生效，否则视为未配置。
     *
     * @param node frameworkParent 节点
     * @return 框架父 POM 三字段；未配置返回 {@code null}
     * @throws BusinessException 字段类型非法（4002）
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
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "metadata frameworkParent 字段不完整，需 groupId/artifactId/version 三项齐全");
        }
        return new MetadataDoc.Parent(groupId, artifactId, version);
    }

    /**
     * 必需列表字段断言。
     *
     * @param node  原始节点
     * @param field 字段名（错误明细用）
     * @return 列表视图
     * @throws BusinessException 非列表或为空（4002）
     */
    private static List<Object> requireList(Object node, String field) {
        if (!(node instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "metadata 缺少必需字段或类型非法: " + field);
        }
        return new ArrayList<>(list);
    }

    /**
     * 必需映射字段断言。
     *
     * @param node  原始节点
     * @param field 字段名（错误明细用）
     * @return 映射视图
     * @throws BusinessException 非映射（4002）
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Object node, String field) {
        if (!(node instanceof Map)) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "metadata 字段类型非法: " + field);
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
     * @throws BusinessException 缺失或空白（4002）
     */
    private static String requireString(Map<String, Object> map, String key, String field) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "metadata 缺少必需字段: " + field);
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
     * 字符串列表读取（非列表视为空；元素去空白、过滤空串）。
     *
     * @param node  原始节点
     * @param field 字段名（错误明细用）
     * @return 字符串列表
     * @throws BusinessException 元素类型非法（4002）
     */
    private static List<String> toStringList(Object node, String field) {
        if (node == null) {
            return List.of();
        }
        if (!(node instanceof List<?> list)) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "metadata 字段类型非法: " + field + "（必须为列表）");
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
     * 写入 starter 结构行（父行 + 依赖 / 联动 / Boot 关联 / 依赖分组）。
     *
     * @param yaml       多文档 starter yml（{@code ---} 分隔）
     * @param source     来源通道（{@link ConfigRepository#SOURCE_MANUAL} /
     *                   {@link ConfigRepository#SOURCE_SYNC}）
     * @return 写入行数
     * @throws BusinessException 单文档解析失败（9002 收敛为 4002）
     */
    private int writeStarters(String yaml, String source) {
        List<StarterCatalogEntry> entries = new ArrayList<>();
        for (String document : CatalogRefresher.splitDocuments(yaml)) {
            try (var in = new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8))) {
                entries.add(YamlStarterParser.parse(in));
            } catch (BusinessException e) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "starter 配置非法: " + e.getMessage(), e);
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "starter 配置读取失败: " + e.getMessage(), e);
            }
        }
        StructuredSnapshot parents = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        int sort = 0;
        StarterScopeIndex identities = new StarterScopeIndex();
        for (StarterCatalogEntry entry : entries) {
            try {
                identities.add(entry.id(), entry.bootVersions());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED, e.getMessage(), e);
            }
            CfgStarterEntity row = new CfgStarterEntity();
            row.setStarterId(entry.id());
            row.setCategory(entry.category());
            row.setMaintainer(entry.maintainer() == null ? null : entry.maintainer().name());
            row.setDescription(entry.description());
            row.setRequired(entry.required());
            row.setHidden(entry.hidden());
            row.setConfigPackage(entry.linkages() == null ? null : entry.linkages().configPackage());
            row.setSource(source);
            row.setSort(sort++);
            parents.starters().add(row);
        }
        long placeholderPk = 0L;
        for (CfgStarterEntity row : parents.starters()) {
            // 显式占位主键（负数区间，绝不与库内真实自增主键冲突）：
            // 子行父键据此唯一定位父行，写入期由映射换算为真实主键
            row.setId(--placeholderPk);
        }

        StructuredSnapshot children = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        long groupPlaceholderPk = 0L;
        int entryIndex = 0;
        for (StarterCatalogEntry entry : entries) {
            CfgStarterEntity starterRow = parents.starters().get(entryIndex++);
            int childSort = 0;
            for (DependencyCoord dep : entry.dependencies()) {
                CfgStarterDependencyEntity row = new CfgStarterDependencyEntity();
                row.setStarterPk(starterRow.getId());
                row.setGroupId(dep.groupId());
                row.setArtifactId(dep.artifactId());
                row.setVersion(dep.version());
                row.setSort(childSort++);
                children.starterDependencies().add(row);
            }
            int groupIndex = 0;
            // 分组父行与组内项同时构造：组内项 group_pk 引用分组行对象主键，
            // 由写入期映射统一换算（分组行必须先于组内项落库，顺序由 appendToDraft 保证）
            for (List<String> group : entry.requires()) {
                CfgStarterRequireGroupEntity groupRow = new CfgStarterRequireGroupEntity();
                groupRow.setId(--groupPlaceholderPk);
                groupRow.setStarterPk(starterRow.getId());
                groupRow.setGroupIndex(groupIndex++);
                children.starterRequireGroups().add(groupRow);
                int itemSort = 0;
                for (String requiredStarterId : group) {
                    CfgStarterRequireItemEntity itemRow = new CfgStarterRequireItemEntity();
                    itemRow.setGroupPk(groupRow.getId());
                    itemRow.setRequiredStarterId(requiredStarterId);
                    itemRow.setSort(itemSort++);
                    children.starterRequireItems().add(itemRow);
                }
            }
            for (String bootVersionId : entry.bootVersions()) {
                CfgStarterBootVersionEntity row = new CfgStarterBootVersionEntity();
                row.setStarterPk(starterRow.getId());
                row.setBootVersionId(bootVersionId);
                children.starterBootVersions().add(row);
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
                    children.starterLinkageTemplates().add(row);
                }
                int ymlSort = 0;
                for (YmlFragmentLinkage fragment : linkages.ymlFragments()) {
                    CfgStarterLinkageYmlEntity row = new CfgStarterLinkageYmlEntity();
                    row.setStarterPk(starterRow.getId());
                    row.setSource(fragment.source());
                    row.setMergeStrategy(fragment.mergeStrategy() == null
                            ? "APPEND" : fragment.mergeStrategy().name());
                    row.setSort(ymlSort++);
                    children.starterLinkageYmls().add(row);
                }
                int abstractSort = 0;
                for (AbstractClassLinkage abstractClass : linkages.abstractClasses()) {
                    CfgStarterLinkageAbstractEntity row = new CfgStarterLinkageAbstractEntity();
                    row.setStarterPk(starterRow.getId());
                    row.setFqcn(abstractClass.fqcn());
                    row.setAlias(abstractClass.alias());
                    row.setSort(abstractSort++);
                    children.starterLinkageAbstracts().add(row);
                }
            }
        }
        int rows = writeTwoStage(parents, children);
        log.debug("starter 结构行已写入: source={}, starters={}, rows={}", source, entries.size(), rows);
        return rows;
    }

    /**
     * 写入冲突规则结构行（规则父行 + 成员子行）。
     *
     * @param yaml conflict-rules.yml 同构文本
     * @return 写入行数
     * @throws BusinessException 解析失败（4002）
     */
    private int writeConflictRules(String yaml) {
        ConflictCatalog catalog = ConflictRuleParser.parseText(yaml);
        StructuredSnapshot parents = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        int sort = 0;
        long rulePlaceholderPk = 0L;
        for (ConflictRule rule : catalog.all()) {
            CfgConflictRuleEntity row = new CfgConflictRuleEntity();
            row.setId(--rulePlaceholderPk);
            row.setRuleId(rule.id());
            row.setRuleType(rule.type().name());
            row.setMessage(rule.message());
            row.setSort(sort++);
            parents.conflictRules().add(row);
        }
        StructuredSnapshot children = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        for (ConflictRule rule : catalog.all()) {
            CfgConflictRuleEntity ruleRow = parents.conflictRules().stream()
                    .filter(row -> row.getRuleId().equals(rule.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("冲突规则父行构造缺失: " + rule.id()));
            int memberSort = 0;
            for (String starterId : rule.starters()) {
                CfgConflictRuleStarterEntity row = new CfgConflictRuleStarterEntity();
                row.setRulePk(ruleRow.getId());
                row.setStarterId(starterId);
                row.setSort(memberSort++);
                children.conflictRuleStarters().add(row);
            }
        }
        int rows = writeTwoStage(parents, children);
        log.debug("冲突规则结构行已写入: rules={}, rows={}", catalog.size(), rows);
        return rows;
    }

    /**
     * 写入模板结构行（多文档 {@code {name, content}}）。
     *
     * @param yaml 多文档模板文本
     * @return 写入行数
     * @throws BusinessException 文档结构非法（4002）
     */
    private int writeTemplates(String yaml) {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        int sort = 0;
        for (String document : CatalogRefresher.splitDocuments(yaml)) {
            Object root;
            try {
                root = new Yaml(new SafeConstructor(new LoaderOptions())).load(document);
            } catch (RuntimeException e) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "template 配置解析失败: " + e.getMessage(), e);
            }
            if (!(root instanceof Map<?, ?> map)) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "template 文档顶层必须为映射结构（含 name 与 content 字段）");
            }
            Object name = map.get("name");
            Object content = map.get("content");
            if (name == null || name.toString().isBlank()) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "template 缺少必需字段: name");
            }
            if (content == null || content.toString().isBlank()) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "template " + name + " 缺少必需字段: content");
            }
            CfgTemplateEntity row = new CfgTemplateEntity();
            row.setName(name.toString().trim());
            row.setContent(content.toString());
            row.setSort(sort++);
            row.setUpdatedAt(java.time.Instant.now());
            snapshot.templates().add(row);
        }
        int rows = repository.appendToDraft(snapshot);
        log.debug("模板结构行已写入: templates={}, rows={}", snapshot.templates().size(), rows);
        return rows;
    }

    /**
     * 存量迁移（T1.6）：把旧版 {@code config_draft.content_yaml} 四类 YAML 一次性写入结构行。
     *
     * <p>旧库升级路径：新版启动时若检测到旧 {@code config_draft} 表仍存在且结构行为空，
     * 由运维按文档指引先导出旧 YAML，再经「导入通道（T4）」或本方法回填。
     * 本方法只处理「kind → YAML 文本」的语义转换，连接旧表与事务边界由迁移脚本负责。
     *
     * @param legacyYamlByKind 旧版四类草稿 YAML（kind 枚举名 → 文本；可缺项）
     * @return 各 kind 写入的行数（kind 枚举名 → 行数；跳过的 kind 不出现在结果中）
     * @throws BusinessException YAML 结构非法（4002）
     */
    public Map<String, Integer> migrateLegacyDrafts(Map<String, String> legacyYamlByKind) {
        Map<String, Integer> written = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : legacyYamlByKind.entrySet()) {
            String raw = entry.getValue();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            ConfigKind kind = ConfigKind.parse(entry.getKey());
            repository.clearDraftKind(kind);
            written.put(kind.name(), write(kind, raw));
        }
        log.info("旧版 YAML 草稿迁移完成: {}", written);
        return written;
    }
}
