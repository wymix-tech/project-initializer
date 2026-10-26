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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构行 ↔ 语义视图码编器（T1 过渡层）：把 {@code cfg_*} 关系表行还原为
 * {@code metadata.yml} / {@code starters/*.yml} / {@code conflict-rules.yml} 同构的
 * 语义对象视图，供尚未结构化改造的服务层（发布校验、制品库同步）继续复用既有
 * YAML 解析/校验通道。
 *
 * <p><b>定位与生命周期</b>：T1 只交付持久层，服务层仍按 YAML 文本工作；
 * 本类作为「结构行 → YAML 语义」的一次性桥接，保证 T1 单独可发布、既有测试可继续验证持久层。
 * T2 引入 {@code StructuredCatalogAssembler} 后，发布/启动链路直接由表行装配
 * {@code CatalogSnapshot}，本类的 YAML 渲染职责随之退化为「导入导出（T4）」所需的
 * 旁路工具；届时保留本类但调用点会收缩，签名不变以免二次改动测试。
 *
 * <p><b>与导入导出的关系</b>：本类只做「结构行 → YAML 文本」单向渲染；
 * 反向的「YAML 文本 → 结构行」由 T4 的导入链路实现（其需处理路径穿越、条目上限等安全约束），
 * 本类不重复实现，避免两套解析语义漂移。
 *
 * <p>线程安全性：无状态单例（全部状态在入参与返回值中），线程安全。
 */
@Component
public class StructuredYamlCodec {

    /**
     * 把元数据结构行渲染为 {@code metadata.yml} 同构文本。
     *
     * <p>输出节：{@code javaVersions} / {@code bootVersions}（含 {@code supportedJavaVersions}
     * 与 {@code starters} 白名单、可选 {@code frameworkParent}）；无行时返回 {@code null}
     * （语义 = 未配置 metadata，发布链路会以 4002 拒绝首发）。
     *
     * @param snapshot 草稿作用域内的元数据结构行（子表主键为源作用域真实主键）
     * @return YAML 文本；无任何行时返回 {@code null}
     */
    public String renderMetadata(StructuredSnapshot snapshot) {
        if (snapshot.javaVersions().isEmpty() && snapshot.bootVersions().isEmpty()) {
            return null;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("javaVersions", renderJavaVersions(snapshot.javaVersions()));
        root.put("bootVersions", renderBootVersions(snapshot));
        return YamlTexts.dump(root);
    }

    /**
     * 把 starter 结构行渲染为多文档 starter yml 文本（每 starter 一个文档，{@code ---} 分隔）。
     *
     * @param snapshot 草稿作用域内（或已按来源通道过滤的）starter 结构行
     * @return 多文档 YAML 文本；无 starter 行时返回 {@code null}
     */
    public String renderStarters(StructuredSnapshot snapshot) {
        if (snapshot.starters().isEmpty()) {
            return null;
        }
        Map<Long, List<CfgStarterDependencyEntity>> deps = groupBy(snapshot.starterDependencies(),
                CfgStarterDependencyEntity::getStarterPk);
        Map<Long, List<CfgStarterRequireGroupEntity>> groups = groupBy(snapshot.starterRequireGroups(),
                CfgStarterRequireGroupEntity::getStarterPk);
        Map<Long, List<CfgStarterRequireItemEntity>> items = groupBy(snapshot.starterRequireItems(),
                CfgStarterRequireItemEntity::getGroupPk);
        Map<Long, List<CfgStarterBootVersionEntity>> boots = groupBy(snapshot.starterBootVersions(),
                CfgStarterBootVersionEntity::getStarterPk);
        Map<Long, List<CfgStarterLinkageTemplateEntity>> templates = groupBy(
                snapshot.starterLinkageTemplates(), CfgStarterLinkageTemplateEntity::getStarterPk);
        Map<Long, List<CfgStarterLinkageYmlEntity>> ymls = groupBy(snapshot.starterLinkageYmls(),
                CfgStarterLinkageYmlEntity::getStarterPk);
        Map<Long, List<CfgStarterLinkageAbstractEntity>> abstracts = groupBy(
                snapshot.starterLinkageAbstracts(), CfgStarterLinkageAbstractEntity::getStarterPk);

        List<String> documents = new ArrayList<>();
        for (CfgStarterEntity starter : snapshot.starters()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", starter.getStarterId());
            doc.put("category", starter.getCategory());
            doc.put("maintainer", starter.getMaintainer() == null
                    ? null : starter.getMaintainer().toLowerCase(java.util.Locale.ROOT));
            doc.put("description", starter.getDescription());
            if (Boolean.TRUE.equals(starter.getRequired())) {
                doc.put("required", true);
            }
            if (Boolean.TRUE.equals(starter.getHidden())) {
                doc.put("hidden", true);
            }
            doc.put("dependencies", renderDependencies(deps.getOrDefault(starter.getId(), List.of())));
            List<List<String>> requires = renderRequires(groups.getOrDefault(starter.getId(), List.of()), items);
            if (!requires.isEmpty()) {
                doc.put("requires", requires);
            }
            List<String> bootVersions = boots.getOrDefault(starter.getId(), List.of()).stream()
                    .map(CfgStarterBootVersionEntity::getBootVersionId)
                    .toList();
            if (!bootVersions.isEmpty()) {
                doc.put("bootVersions", bootVersions);
            }
            doc.put("linkages", renderLinkages(starter, templates, ymls, abstracts));
            documents.add(YamlTexts.dump(doc));
        }
        // 多文档必须以行首 "---" 分隔：粘连为单文档会导致重复键覆盖、除首个外条目全部丢失
        return String.join("---\n", documents);
    }

    /**
     * 把冲突规则结构行渲染为 {@code conflict-rules.yml} 同构文本。
     *
     * @param snapshot 草稿作用域内的冲突规则结构行
     * @return YAML 文本；无规则时返回 {@code null}
     */
    public String renderConflictRules(StructuredSnapshot snapshot) {
        if (snapshot.conflictRules().isEmpty()) {
            return null;
        }
        Map<Long, List<CfgConflictRuleStarterEntity>> members = groupBy(
                snapshot.conflictRuleStarters(), CfgConflictRuleStarterEntity::getRulePk);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (CfgConflictRuleEntity rule : snapshot.conflictRules()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rule.getRuleId());
            item.put("type", rule.getRuleType() == null
                    ? null : rule.getRuleType().toLowerCase(java.util.Locale.ROOT));
            item.put("starters", members.getOrDefault(rule.getId(), List.of()).stream()
                    .map(CfgConflictRuleStarterEntity::getStarterId)
                    .toList());
            item.put("message", rule.getMessage());
            rules.add(item);
        }
        return YamlTexts.dump(Map.of("rules", rules));
    }

    /**
     * 把模板结构行渲染为多文档 {@code {name, content}} 文本（TEMPLATE 草稿原格式）。
     *
     * @param snapshot 草稿作用域内的模板结构行（需已加载 {@code content}）
     * @return 多文档 YAML 文本；无模板时返回 {@code null}
     */
    public String renderTemplates(StructuredSnapshot snapshot) {
        if (snapshot.templates().isEmpty()) {
            return null;
        }
        List<String> documents = new ArrayList<>();
        for (CfgTemplateEntity template : snapshot.templates()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("name", template.getName());
            doc.put("content", template.getContent());
            documents.add(YamlTexts.dump(doc));
        }
        return String.join("---\n", documents);
    }

    // ==================== 内部渲染 ====================

    /**
     * 渲染 Java 版本节。
     *
     * @param rows Java 版本行（按 sort 升序）
     * @return YAML 友好结构
     */
    private static List<Map<String, Object>> renderJavaVersions(List<CfgJavaVersionEntity> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CfgJavaVersionEntity row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getVersionId());
            item.put("label", row.getLabel());
            item.put("deprecated", Boolean.TRUE.equals(row.getDeprecated()));
            if (row.getRecommendedBootVersion() != null) {
                item.put("recommendedBootVersion", row.getRecommendedBootVersion());
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 渲染 Boot 版本节（含兼容 Java 清单、starter 白名单与可选框架父 POM）。
     *
     * @param snapshot 元数据结构行（需用到 Java 行主键以还原 {@code supportedJavaVersions} 的 ID 引用）
     * @return YAML 友好结构
     */
    private static List<Map<String, Object>> renderBootVersions(StructuredSnapshot snapshot) {
        // 兼容矩阵按业务 ID 存储：直接按 Boot 行主键聚合即可，无需再查 Java 主键映射
        Map<Long, List<String>> javaRefs = new LinkedHashMap<>();
        for (CfgBootJavaEntity link : snapshot.bootJava()) {
            javaRefs.computeIfAbsent(link.getBootPk(), k -> new ArrayList<>())
                    .add(link.getJavaVersionId());
        }
        Map<Long, List<String>> starterRefs = new LinkedHashMap<>();
        for (CfgBootStarterRefEntity ref : snapshot.bootStarterRefs()) {
            starterRefs.computeIfAbsent(ref.getBootPk(), k -> new ArrayList<>()).add(ref.getStarterId());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (CfgBootVersionEntity boot : snapshot.bootVersions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", boot.getVersionId());
            item.put("label", boot.getLabel());
            item.put("official", !Boolean.FALSE.equals(boot.getOfficial()));
            item.put("supportedJavaVersions", javaRefs.getOrDefault(boot.getId(), List.of()));
            item.put("starters", starterRefs.getOrDefault(boot.getId(), List.of()));
            if (boot.getFpGroup() != null && boot.getFpArtifact() != null && boot.getFpVersion() != null) {
                Map<String, Object> parent = new LinkedHashMap<>();
                parent.put("groupId", boot.getFpGroup());
                parent.put("artifactId", boot.getFpArtifact());
                parent.put("version", boot.getFpVersion());
                item.put("frameworkParent", parent);
            }
            if (boot.getSbVersion() != null && !boot.getSbVersion().isBlank()) {
                item.put("springBootVersion", boot.getSbVersion());
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 渲染 starter 依赖节。
     *
     * @param rows 依赖坐标行
     * @return YAML 友好结构
     */
    private static List<Map<String, Object>> renderDependencies(List<CfgStarterDependencyEntity> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CfgStarterDependencyEntity row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("groupId", row.getGroupId());
            item.put("artifactId", row.getArtifactId());
            if (row.getVersion() != null && !row.getVersion().isBlank()) {
                item.put("version", row.getVersion());
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 渲染依赖联动分组（AND-of-OR）：分组行按 {@code groupIndex} 定序，组内项按 {@code sort} 定序。
     *
     * @param groups 该 starter 的分组行
     * @param items  组主键 → 组内项行
     * @return 分组列表；无分组时返回空列表（调用方据此省略 {@code requires} 节）
     */
    private static List<List<String>> renderRequires(List<CfgStarterRequireGroupEntity> groups,
                                                     Map<Long, List<CfgStarterRequireItemEntity>> items) {
        List<CfgStarterRequireGroupEntity> ordered = new ArrayList<>(groups);
        ordered.sort(java.util.Comparator.comparingInt(
                g -> g.getGroupIndex() == null ? 0 : g.getGroupIndex()));
        List<List<String>> result = new ArrayList<>();
        for (CfgStarterRequireGroupEntity group : ordered) {
            List<String> members = items.getOrDefault(group.getId(), List.of()).stream()
                    .map(CfgStarterRequireItemEntity::getRequiredStarterId)
                    .toList();
            if (!members.isEmpty()) {
                result.add(members);
            }
        }
        return result;
    }

    /**
     * 渲染 starter 联动节（{@code config-package} / templates / yml 片段 / 抽象类）。
     *
     * @param starter   starter 主行（提供 {@code configPackage}）
     * @param templates 模板联动行分组
     * @param ymls      yml 片段联动行分组
     * @param abstracts 抽象类联动行分组
     * @return 联动节结构；无任何联动时返回空映射
     */
    private static Map<String, Object> renderLinkages(CfgStarterEntity starter,
                                                      Map<Long, List<CfgStarterLinkageTemplateEntity>> templates,
                                                      Map<Long, List<CfgStarterLinkageYmlEntity>> ymls,
                                                      Map<Long, List<CfgStarterLinkageAbstractEntity>> abstracts) {
        Map<String, Object> linkages = new LinkedHashMap<>();
        if (starter.getConfigPackage() != null && !starter.getConfigPackage().isBlank()) {
            linkages.put("config-package", starter.getConfigPackage());
        }
        List<Map<String, Object>> templateNodes = new ArrayList<>();
        for (CfgStarterLinkageTemplateEntity row : templates.getOrDefault(starter.getId(), List.of())) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("source", row.getSource());
            node.put("target", row.getTarget());
            templateNodes.add(node);
        }
        if (!templateNodes.isEmpty()) {
            linkages.put("templates", templateNodes);
        }
        List<Map<String, Object>> ymlNodes = new ArrayList<>();
        for (CfgStarterLinkageYmlEntity row : ymls.getOrDefault(starter.getId(), List.of())) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("source", row.getSource());
            node.put("merge-strategy", row.getMergeStrategy() == null
                    ? "append" : row.getMergeStrategy().toLowerCase(java.util.Locale.ROOT));
            ymlNodes.add(node);
        }
        if (!ymlNodes.isEmpty()) {
            linkages.put("application-yml-fragments", ymlNodes);
        }
        List<Map<String, Object>> abstractNodes = new ArrayList<>();
        for (CfgStarterLinkageAbstractEntity row : abstracts.getOrDefault(starter.getId(), List.of())) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("fqcn", row.getFqcn());
            node.put("alias", row.getAlias());
            abstractNodes.add(node);
        }
        if (!abstractNodes.isEmpty()) {
            linkages.put("abstract-classes", abstractNodes);
        }
        return linkages;
    }

    /**
     * 通用按父键分组（保持查询顺序）。
     *
     * @param rows  待分组的行
     * @param keyOf 父键提取函数
     * @param <T>   行类型
     * @return 父键 → 行列表
     */
    private static <T> Map<Long, List<T>> groupBy(List<T> rows, java.util.function.Function<T, Long> keyOf) {
        Map<Long, List<T>> grouped = new LinkedHashMap<>();
        for (T row : rows) {
            Long key = keyOf.apply(row);
            if (key != null) {
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
        return grouped;
    }

    /**
     * starter 主行按来源通道过滤（{@code MANUAL} / {@code SYNC}）。
     *
     * <p>供同步链路把两种通道分别渲染为独立多文档 yml（发布期再拼接归并）。
     *
     * @param snapshot 草稿作用域内的 starter 结构行
     * @param source   目标来源通道
     * @return 只含目标通道的 starter 集合（保持原顺序）
     */
    public static List<CfgStarterEntity> startersOfSource(StructuredSnapshot snapshot, String source) {
        List<CfgStarterEntity> result = new ArrayList<>();
        for (CfgStarterEntity row : snapshot.starters()) {
            if (source.equals(row.getSource())) {
                result.add(row);
            }
        }
        return result;
    }

    /**
     * 提取结构行快照中的 starter ID 集合（保留集计算用）。
     *
     * @param snapshot 草稿作用域内的 starter 结构行
     * @return starter ID 集合（去重保序）
     */
    public static Set<String> starterIds(StructuredSnapshot snapshot) {
        Set<String> ids = new LinkedHashSet<>();
        for (CfgStarterEntity row : snapshot.starters()) {
            ids.add(row.getStarterId());
        }
        return ids;
    }

    /**
     * 校验结构行快照自洽（T1 边界防护）：保留给调用方的显式前置断言。
     *
     * @param snapshot 结构行快照
     * @throws BusinessException 行引用不自洽（错误码 {@link ErrorCode#CONFIG_VALIDATION_FAILED}）
     */
    public void sanityCheck(StructuredSnapshot snapshot) {
        Set<Long> starterPks = new LinkedHashSet<>();
        snapshot.starters().forEach(row -> starterPks.add(row.getId()));
        for (CfgStarterDependencyEntity row : snapshot.starterDependencies()) {
            requireRef(starterPks.contains(row.getStarterPk()), "依赖坐标行引用了不存在的 starter 行");
        }
        for (CfgStarterRequireGroupEntity row : snapshot.starterRequireGroups()) {
            requireRef(starterPks.contains(row.getStarterPk()), "依赖分组行引用了不存在的 starter 行");
        }
        Set<Long> rulePks = new LinkedHashSet<>();
        snapshot.conflictRules().forEach(row -> rulePks.add(row.getId()));
        for (CfgConflictRuleStarterEntity row : snapshot.conflictRuleStarters()) {
            requireRef(rulePks.contains(row.getRulePk()), "规则成员行引用了不存在的冲突规则行");
        }
    }

    /**
     * 引用自洽断言辅助。
     *
     * @param ok      是否通过
     * @param message 失败说明
     * @throws BusinessException 断言失败
     */
    private static void requireRef(boolean ok, String message) {
        if (!ok) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED, message);
        }
    }
}
