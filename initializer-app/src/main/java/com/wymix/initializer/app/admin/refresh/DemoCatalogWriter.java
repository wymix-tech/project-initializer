package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.app.admin.entity.CfgBootJavaEntity;
import com.wymix.initializer.app.admin.entity.CfgBootStarterRefEntity;
import com.wymix.initializer.app.admin.entity.CfgBootVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgJavaVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterBootVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterDependencyEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageAbstractEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageTemplateEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageYmlEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterRequireGroupEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterRequireItemEntity;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.repository.StructuredSnapshot;
import com.wymix.initializer.core.metadata.BootVersion;
import com.wymix.initializer.core.metadata.FrameworkParent;
import com.wymix.initializer.core.metadata.JavaVersion;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.AbstractClassLinkage;
import com.wymix.initializer.core.starter.DependencyCoord;
import com.wymix.initializer.core.starter.LinkageSpec;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import com.wymix.initializer.core.starter.TemplateLinkage;
import com.wymix.initializer.core.starter.YmlFragmentLinkage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内置清单 → 结构行映射工具（T5.3）：把 classpath 内置模型（{@link MetadataCatalog} /
 * {@link StarterCatalogEntry}）反写为 {@code cfg_*} 结构行，供 {@code --demo} 种子入库。
 *
 * <p>与 {@code StructuredDraftWriter} 的区别：后者走「YAML 文本 → 结构行」解析通道，
 * 本类走「core 模型 → 结构行」直写，**不经过任何 YAML 文本**（内置清单已是解析后的模型）。
 *
 * <p>占位主键约定与 {@code StructuredDraftWriter} 一致：父行显式分配负数占位主键，
 * 子行据此引用，写入期由 {@link StructuredSnapshot} 的主键映射换算为真实自增主键。
 *
 * <p>线程安全性：仅静态纯函数，线程安全。
 */
final class DemoCatalogWriter {

    private DemoCatalogWriter() {
        throw new AssertionError("工具类不可实例化");
    }

    /**
     * 构造 starter 主行（不含子行）。
     *
     * @param entry starter 清单条目
     * @param sort  排序键
     * @return 未分配占位主键的 starter 主行（由 {@link #appendStarterChildren} 前的调用方统一分配）
     */
    static CfgStarterEntity starterRow(StarterCatalogEntry entry, int sort) {
        CfgStarterEntity row = new CfgStarterEntity();
        row.setStarterId(entry.id());
        row.setCategory(entry.category());
        row.setMaintainer(entry.maintainer() == null ? null : entry.maintainer().name());
        row.setDescription(entry.description());
        row.setRequired(entry.required());
        row.setHidden(entry.hidden());
        row.setConfigPackage(entry.linkages() == null ? null : entry.linkages().configPackage());
        row.setSource(ConfigRepository.SOURCE_MANUAL);
        row.setSort(sort);
        return row;
    }

    /**
     * 把内置清单条目写入草稿结构行（主行 + 依赖/联动/Boot 关联/requires 子行一次成型）。
     *
     * <p>父行占位主键在方法内显式分配（负数区间），子行引用同一对象主键，
     * 由 {@code appendToDraft} 写入期换算为真实自增主键。
     *
     * @param snapshot 目标草稿结构行（{@code releaseId} 强制为草稿作用域）
     * @param starters 内置 starter 清单（全部条目，按清单顺序）
     */
    static void writeStarterRows(StructuredSnapshot snapshot, List<StarterCatalogEntry> starters) {
        Map<String, CfgStarterEntity> byId = new HashMap<>();
        long placeholderPk = 0L;
        int sort = 0;
        for (StarterCatalogEntry entry : starters) {
            CfgStarterEntity row = starterRow(entry, sort++);
            row.setId(--placeholderPk);
            byId.put(entry.id(), row);
            snapshot.starters().add(row);
        }
        for (StarterCatalogEntry entry : starters) {
            CfgStarterEntity parent = byId.get(entry.id());
            appendChildren(snapshot, parent, entry);
        }
    }

    /**
     * 追加单个 starter 的子行（依赖 / requires 分组 / Boot 关联 / 三类联动）。
     *
     * <p>父行必须已分配占位主键（负数区间），否则子行父键无法换算。
     *
     * @param snapshot 目标草稿结构行
     * @param parent   starter 主行（已分配占位主键）
     * @param entry    内置清单条目
     */
    static void appendChildren(StructuredSnapshot snapshot, CfgStarterEntity parent,
                               StarterCatalogEntry entry) {
        int sort = 0;
        for (DependencyCoord dep : entry.dependencies()) {
            CfgStarterDependencyEntity row = new CfgStarterDependencyEntity();
            row.setStarterPk(parent.getId());
            row.setGroupId(dep.groupId());
            row.setArtifactId(dep.artifactId());
            row.setVersion(dep.version());
            row.setSort(sort++);
            snapshot.starterDependencies().add(row);
        }
        long groupPlaceholder = 0L;
        int groupIndex = 0;
        for (List<String> group : entry.requires()) {
            CfgStarterRequireGroupEntity groupRow = new CfgStarterRequireGroupEntity();
            groupRow.setId(--groupPlaceholder);
            groupRow.setStarterPk(parent.getId());
            groupRow.setGroupIndex(groupIndex++);
            snapshot.starterRequireGroups().add(groupRow);
            int itemSort = 0;
            for (String requiredId : group) {
                CfgStarterRequireItemEntity itemRow = new CfgStarterRequireItemEntity();
                itemRow.setGroupPk(groupRow.getId());
                itemRow.setRequiredStarterId(requiredId);
                itemRow.setSort(itemSort++);
                snapshot.starterRequireItems().add(itemRow);
            }
        }
        for (String bootVersionId : entry.bootVersions()) {
            CfgStarterBootVersionEntity row = new CfgStarterBootVersionEntity();
            row.setStarterPk(parent.getId());
            row.setBootVersionId(bootVersionId);
            snapshot.starterBootVersions().add(row);
        }
        LinkageSpec linkages = entry.linkages();
        if (linkages == null) {
            return;
        }
        int templateSort = 0;
        for (TemplateLinkage template : linkages.templates()) {
            CfgStarterLinkageTemplateEntity row = new CfgStarterLinkageTemplateEntity();
            row.setStarterPk(parent.getId());
            row.setSource(template.source());
            row.setTarget(template.target());
            row.setSort(templateSort++);
            snapshot.starterLinkageTemplates().add(row);
        }
        int ymlSort = 0;
        for (YmlFragmentLinkage fragment : linkages.ymlFragments()) {
            CfgStarterLinkageYmlEntity row = new CfgStarterLinkageYmlEntity();
            row.setStarterPk(parent.getId());
            row.setSource(fragment.source());
            row.setMergeStrategy(fragment.mergeStrategy() == null ? "APPEND" : fragment.mergeStrategy().name());
            row.setSort(ymlSort++);
            snapshot.starterLinkageYmls().add(row);
        }
        int abstractSort = 0;
        for (AbstractClassLinkage abstractClass : linkages.abstractClasses()) {
            CfgStarterLinkageAbstractEntity row = new CfgStarterLinkageAbstractEntity();
            row.setStarterPk(parent.getId());
            row.setFqcn(abstractClass.fqcn());
            row.setAlias(abstractClass.alias());
            row.setSort(abstractSort++);
            snapshot.starterLinkageAbstracts().add(row);
        }
    }

    /**
     * 写元数据结构行（Java 版本 / Boot 版本 / 兼容矩阵 / starter 白名单）。
     *
     * <p>兼容矩阵与白名单以 Boot 父行占位主键表达 ID 引用，父行先分配占位主键再写子行。
     *
     * @param snapshot          目标草稿结构行
     * @param metadata          内置元数据目录
     * @param declaredStarters  生效的 starter ID 集合（写入 Boot 白名单）
     * @param javaSort          起始排序键（调用方预留，可为 0）
     */
    static void writeMetadataRows(StructuredSnapshot snapshot, MetadataCatalog metadata,
                                  Set<String> declaredStarters, int javaSort) {
        int sort = javaSort;
        for (JavaVersion java : metadata.javaVersions()) {
            CfgJavaVersionEntity row = new CfgJavaVersionEntity();
            row.setVersionId(java.id());
            row.setLabel(java.label());
            row.setDeprecated(java.deprecated());
            row.setRecommendedBootVersion(java.recommendedBootVersion());
            row.setSort(sort++);
            snapshot.javaVersions().add(row);
        }
        sort = 0;
        Map<String, CfgBootVersionEntity> bootById = new HashMap<>();
        long placeholderPk = 0L;
        for (BootVersion boot : metadata.bootVersions()) {
            CfgBootVersionEntity row = new CfgBootVersionEntity();
            row.setId(--placeholderPk);
            row.setVersionId(boot.id());
            row.setLabel(boot.label());
            row.setOfficial(boot.official());
            FrameworkParent parent = boot.frameworkParent();
            if (parent != null) {
                row.setFpGroup(parent.groupId());
                row.setFpArtifact(parent.artifactId());
                row.setFpVersion(parent.version());
            }
            row.setSort(sort++);
            snapshot.bootVersions().add(row);
            bootById.put(boot.id(), row);
        }
        for (BootVersion boot : metadata.bootVersions()) {
            CfgBootVersionEntity parent = bootById.get(boot.id());
            for (String javaId : boot.supportedJavaVersions()) {
                CfgBootJavaEntity link = new CfgBootJavaEntity();
                link.setBootPk(parent.getId());
                link.setJavaVersionId(javaId);
                snapshot.bootJava().add(link);
            }
            List<String> whitelist = boot.starters().isEmpty()
                    ? List.copyOf(declaredStarters) : boot.starters();
            int refSort = 0;
            for (String starterId : whitelist) {
                CfgBootStarterRefEntity ref = new CfgBootStarterRefEntity();
                ref.setBootPk(parent.getId());
                ref.setStarterId(starterId);
                ref.setSort(refSort++);
                snapshot.bootStarterRefs().add(ref);
            }
        }
    }
}
