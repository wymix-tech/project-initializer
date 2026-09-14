package com.wymix.initializer.app.admin.repository;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化配置快照载体（结构化改造 T1）：一次 {@code release_id} 作用域内 15 张
 * {@code cfg_*} 表全部行的打包，供装配器（T2）消费，以及仓库的「整体替换草稿」
 * 「草稿复制为发布快照」两类写入使用。
 *
 * <p><b>可变性边界</b>：集合字段在构造期做**可变副本**（{@code new ArrayList<>(...)}），
 * 写入期可逐条 {@code add} 组装待写行；<b>行实体本身也可变</b>——写库前会就地重置
 * 行主键与作用域。因此本载体只作为「一次读取的结果」或「一次写入的输入」使用，
 * 不做跨线程共享（并发场景各自 {@link ConfigRepository#readScope(long)} 取独立实例）。
 *
 * <p><b>父主键映射</b>：{@code bootPkMapping} / {@code starterPkMapping} /
 * {@code requireGroupPkMapping} / {@code conflictRulePkMapping} 在写父行时登记，
 * 写子行时据此改写外键列——这是「整行复制到新 release」一次事务完成的关键：
 * 自增主键在目标作用域被重新分配，旧引用必须同步换算。
 *
 * <p>注意 {@code cfg_boot_java} 的兼容 Java 引用存**业务 ID**（{@code java_version_id}）
 * 而非主键，故不参与主键换算：非法引用要原样保留到发布期才能给出精确校验明细。
 *
 * <p><b>CLOB 说明</b>：{@code templates} 含模板全文（可能较大），
 * 列表展示等场景请走「只查 name/更新时间」的专门查询，不要构造本载体。
 *
 * <p>线程安全性：非共享的可变数据载体，无内部并发保护。
 */
@SuppressWarnings("checkstyle:ParameterNumber")
public class StructuredSnapshot {

    /** 本快照的来源版本作用域：0 = 草稿工作区，> 0 = 发布快照 ID。 */
    private final long releaseId;

    private final List<CfgJavaVersionEntity> javaVersions;
    private final List<CfgBootVersionEntity> bootVersions;
    private final List<CfgBootJavaEntity> bootJava;
    private final List<CfgBootStarterRefEntity> bootStarterRefs;
    private final List<CfgStarterEntity> starters;
    private final List<CfgStarterDependencyEntity> starterDependencies;
    private final List<CfgStarterRequireGroupEntity> starterRequireGroups;
    private final List<CfgStarterRequireItemEntity> starterRequireItems;
    private final List<CfgStarterBootVersionEntity> starterBootVersions;
    private final List<CfgStarterLinkageTemplateEntity> starterLinkageTemplates;
    private final List<CfgStarterLinkageYmlEntity> starterLinkageYmls;
    private final List<CfgStarterLinkageAbstractEntity> starterLinkageAbstracts;
    private final List<CfgConflictRuleEntity> conflictRules;
    private final List<CfgConflictRuleStarterEntity> conflictRuleStarters;
    private final List<CfgTemplateEntity> templates;

    /**
     * 全参构造。
     *
     * @param releaseId               本快照的来源版本作用域
     * @param javaVersions            Java 版本行
     * @param bootVersions            Boot 版本行
     * @param bootJava                兼容矩阵行
     * @param bootStarterRefs         Boot 可选 starter 白名单行
     * @param starters                starter 主行
     * @param starterDependencies     starter 依赖坐标行
     * @param starterRequireGroups    依赖联动 OR 分组行
     * @param starterRequireItems     依赖联动 OR 组内项行
     * @param starterBootVersions     starter ↔ Boot 版本关联行
     * @param starterLinkageTemplates 模板联动行
     * @param starterLinkageYmls      yml 片段联动行
     * @param starterLinkageAbstracts 抽象类联动行
     * @param conflictRules           冲突规则主行
     * @param conflictRuleStarters    冲突规则涉及 starter 行
     * @param templates               模板行（含 CLOB content）
     */
    public StructuredSnapshot(long releaseId,
                              List<CfgJavaVersionEntity> javaVersions,
                              List<CfgBootVersionEntity> bootVersions,
                              List<CfgBootJavaEntity> bootJava,
                              List<CfgBootStarterRefEntity> bootStarterRefs,
                              List<CfgStarterEntity> starters,
                              List<CfgStarterDependencyEntity> starterDependencies,
                              List<CfgStarterRequireGroupEntity> starterRequireGroups,
                              List<CfgStarterRequireItemEntity> starterRequireItems,
                              List<CfgStarterBootVersionEntity> starterBootVersions,
                              List<CfgStarterLinkageTemplateEntity> starterLinkageTemplates,
                              List<CfgStarterLinkageYmlEntity> starterLinkageYmls,
                              List<CfgStarterLinkageAbstractEntity> starterLinkageAbstracts,
                              List<CfgConflictRuleEntity> conflictRules,
                              List<CfgConflictRuleStarterEntity> conflictRuleStarters,
                              List<CfgTemplateEntity> templates) {
        this.releaseId = releaseId;
        this.javaVersions = copy(javaVersions);
        this.bootVersions = copy(bootVersions);
        this.bootJava = copy(bootJava);
        this.bootStarterRefs = copy(bootStarterRefs);
        this.starters = copy(starters);
        this.starterDependencies = copy(starterDependencies);
        this.starterRequireGroups = copy(starterRequireGroups);
        this.starterRequireItems = copy(starterRequireItems);
        this.starterBootVersions = copy(starterBootVersions);
        this.starterLinkageTemplates = copy(starterLinkageTemplates);
        this.starterLinkageYmls = copy(starterLinkageYmls);
        this.starterLinkageAbstracts = copy(starterLinkageAbstracts);
        this.conflictRules = copy(conflictRules);
        this.conflictRuleStarters = copy(conflictRuleStarters);
        this.templates = copy(templates);
    }

    /**
     * 可变副本：构造期拷贝入参集合，返回的集合可增删（写入期构造待写行用）。
     *
     * <p>之所以不返回 {@link List#of} 的不可变视图：T1 过渡层的
     * {@code StructuredDraftWriter} 需要「逐条 add 组装待写行」，若返回不可变集合
     * 会强迫调用方先建临时 List 再重建整个快照，代码噪音远大于收益。
     *
     * @param rows 原始行集合
     * @param <T>  行类型
     * @return 可变副本
     */
    private static <T> List<T> copy(List<T> rows) {
        return rows == null ? new ArrayList<>() : new ArrayList<>(rows);
    }

    /**
     * 空快照（某作用域尚无任何结构行）。
     *
     * @param releaseId 版本作用域
     * @return 全集合为空的快照
     */
    public static StructuredSnapshot empty(long releaseId) {
        return new StructuredSnapshot(releaseId, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 派生快照：只把依赖联动分组行替换为入参（其余集合清空）。
     *
     * <p>用于「先写分组父行拿到自增主键、再写组内项」的两阶段写入；
     * 其余集合清空是刻意的——调用方本就把该派生快照当单表写使用，语义更明确。
     *
     * @param requireGroups 待写入的依赖联动分组行
     * @return 只含入参分组行的新快照（同一 {@code releaseId}）
     */
    public StructuredSnapshot withRequireGroups(List<CfgStarterRequireGroupEntity> requireGroups) {
        return new StructuredSnapshot(releaseId, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), requireGroups, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    /** @return 来源版本作用域 */
    public long releaseId() {
        return releaseId;
    }

    /** @return Java 版本行（可变副本） */
    public List<CfgJavaVersionEntity> javaVersions() {
        return javaVersions;
    }

    /** @return Boot 版本行（可变副本） */
    public List<CfgBootVersionEntity> bootVersions() {
        return bootVersions;
    }

    /** @return Boot ↔ Java 兼容矩阵行（可变副本） */
    public List<CfgBootJavaEntity> bootJava() {
        return bootJava;
    }

    /** @return Boot 可选 starter 白名单行（可变副本） */
    public List<CfgBootStarterRefEntity> bootStarterRefs() {
        return bootStarterRefs;
    }

    /** @return starter 主行（可变副本） */
    public List<CfgStarterEntity> starters() {
        return starters;
    }

    /** @return starter 依赖坐标行（可变副本） */
    public List<CfgStarterDependencyEntity> starterDependencies() {
        return starterDependencies;
    }

    /** @return 依赖联动 OR 分组行（可变副本） */
    public List<CfgStarterRequireGroupEntity> starterRequireGroups() {
        return starterRequireGroups;
    }

    /** @return 依赖联动 OR 组内项行（可变副本） */
    public List<CfgStarterRequireItemEntity> starterRequireItems() {
        return starterRequireItems;
    }

    /** @return starter ↔ Boot 版本关联行（可变副本） */
    public List<CfgStarterBootVersionEntity> starterBootVersions() {
        return starterBootVersions;
    }

    /** @return 模板联动行（可变副本） */
    public List<CfgStarterLinkageTemplateEntity> starterLinkageTemplates() {
        return starterLinkageTemplates;
    }

    /** @return yml 片段联动行（可变副本） */
    public List<CfgStarterLinkageYmlEntity> starterLinkageYmls() {
        return starterLinkageYmls;
    }

    /** @return 抽象类联动行（可变副本） */
    public List<CfgStarterLinkageAbstractEntity> starterLinkageAbstracts() {
        return starterLinkageAbstracts;
    }

    /** @return 冲突规则主行（可变副本） */
    public List<CfgConflictRuleEntity> conflictRules() {
        return conflictRules;
    }

    /** @return 冲突规则涉及 starter 行（可变副本） */
    public List<CfgConflictRuleStarterEntity> conflictRuleStarters() {
        return conflictRuleStarters;
    }

    /** @return 模板行（含 CLOB content，不可变视图） */
    public List<CfgTemplateEntity> templates() {
        return templates;
    }

    /** @return 结构行总数（审计与断言用） */
    public int rowCount() {
        return javaVersions.size() + bootVersions.size() + bootJava.size() + bootStarterRefs.size()
                + starters.size() + starterDependencies.size() + starterRequireGroups.size()
                + starterRequireItems.size() + starterBootVersions.size()
                + starterLinkageTemplates.size() + starterLinkageYmls.size()
                + starterLinkageAbstracts.size() + conflictRules.size()
                + conflictRuleStarters.size() + templates.size();
    }

    /** @return 是否无任何结构行（空作用域） */
    public boolean isEmpty() {
        return rowCount() == 0;
    }

    /**
     * Boot 版本父行「旧主键 → 新主键」映射（{@code cfg_boot_java.boot_pk} 与
     * {@code cfg_boot_starter_ref.boot_pk} 重映射用）。
     *
     * @return 可变映射（仅写入期有值）
     */
    public Map<Long, Long> bootPkMapping() {
        return bootPkMapping;
    }

    /**
     * starter 父行「旧主键 → 新主键」映射（starter 各子表 {@code starter_pk} 重映射用）。
     *
     * @return 可变映射（仅写入期有值）
     */
    public Map<Long, Long> starterPkMapping() {
        return starterPkMapping;
    }

    /**
     * 依赖联动分组父行「旧主键 → 新主键」映射（{@code cfg_starter_require_item.group_pk} 重映射用）。
     *
     * @return 可变映射（仅写入期有值）
     */
    public Map<Long, Long> requireGroupPkMapping() {
        return requireGroupPkMapping;
    }

    /**
     * 冲突规则父行「旧主键 → 新主键」映射（{@code cfg_conflict_rule_starter.rule_pk} 重映射用）。
     *
     * @return 可变映射（仅写入期有值）
     */
    public Map<Long, Long> conflictRulePkMapping() {
        return conflictRulePkMapping;
    }

    /** Boot 版本父主键映射（写父行时登记）。 */
    private final Map<Long, Long> bootPkMapping = new HashMap<>();
    /** starter 父主键映射（写父行时登记）。 */
    private final Map<Long, Long> starterPkMapping = new HashMap<>();
    /** 依赖联动分组父主键映射（写父行时登记）。 */
    private final Map<Long, Long> requireGroupPkMapping = new HashMap<>();
    /** 冲突规则父主键映射（写父行时登记）。 */
    private final Map<Long, Long> conflictRulePkMapping = new HashMap<>();
}
