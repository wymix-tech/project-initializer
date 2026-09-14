package com.wymix.initializer.core.starter;

import java.util.ArrayList;
import java.util.List;

/**
 * starter 清单条目：单个 starter yml（{@code catalog/starters/*.yml}）的完整模型。
 *
 * <p>starter 的**全部元信息**（展示名、依赖坐标、分类、维护方、联动规则、
 * 必选标记、依赖联动）都以本清单为唯一数据源；metadata.yml 不再重复登记。
 *
 * <p>**Boot 版本关联**（{@code bootVersions}）：条目可声明自己属于哪些 Boot 版本，
 * 与 metadata 的 {@code bootVersions[].id} 关联。约束由 {@link StarterCatalog} 装载期保证：
 * <ul>
 *   <li>**同一 Boot 版本内 starter ID 不允许重复**（重复即 9002）</li>
 *   <li>**不同 Boot 版本之间允许重复**——同一 starter 在不同 Boot 版本可声明不同坐标/版本/联动</li>
 *   <li>不声明 {@code bootVersions}（或为空）表示对**全部 Boot 版本**可用</li>
 * </ul>
 *
 * <p>官方 starter 与内部 starter 统一入清单，以 {@link Maintainer} 区分维护方。
 *
 * <p>**依赖联动**（{@code requires}）：AND-of-OR-groups。每一项为一个分组，分组内任一 starter
 * 被选中即满足本分组；所有分组均被满足才算本 starter 的依赖被满足。
 *
 * <pre>{@code
 * requires: [B, C]              // 兼容：AND，需要 B 和 C
 * requires: [[B, C]]            // OR：需要 B 或 C
 * requires: [[B, C], [D]]       // (B 或 C) 且 D
 * }</pre>
 *
 * <p>前端依赖联动（发布期由冲突规则 {@code catalog/conflict-rules.yml} 协同）：
 * <ul>
 *   <li>单元素分组（无 OR）视为**强制依赖**：选中本 starter 时其依赖自动被选中并锁定，
 *       取消本 starter 后若该依赖不再被其他已选 starter 需要则自动移除</li>
 *   <li>多元素分组（OR）仍不自动勾选，仅提示「需要 B 或 C」，由用户显式选择</li>
 *   <li>若强制依赖与已选组件互斥，则互斥双方都不锁定，前端标记互斥供用户二选一切换</li>
 * </ul>
 *
 * <p>**hidden**：为 {@code true} 时前端依赖选择列表默认不展示该条目；但若被已选组件的
 * {@code requires} 引用则强制展示。隐藏仅影响前端展示，后端检索/生成逻辑不受影响。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record StarterCatalogEntry(
        /** starter ID（同一 Boot 版本内唯一） */
        String id,
        /** 分类：web / data / middleware / tool */
        String category,
        /** 维护方 */
        Maintainer maintainer,
        /** 展示描述（作为前端展示名） */
        String description,
        /** 写入构建脚本的依赖坐标清单 */
        List<DependencyCoord> dependencies,
        /** 联动规则 */
        LinkageSpec linkages,
        /** 是否必选：true 时前端默认勾选且禁止取消，生成时后端兜底补齐 */
        boolean required,
        /** 依赖联动分组：每项为 OR-group，组之间为 AND（详见类 Javadoc） */
        List<List<String>> requires,
        /** 关联的 Boot 版本 ID 清单（空 = 全部版本可用） */
        List<String> bootVersions,
        /** 是否在前端依赖选择列表中默认隐藏（依赖引用时强制展示） */
        boolean hidden) {

    public StarterCatalogEntry {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        requires = normalizeRequires(requires);
        bootVersions = bootVersions == null ? List.of() : List.copyOf(bootVersions);
    }

    /** 浅复制每层不可变视图，过滤空组。 */
    private static List<List<String>> normalizeRequires(List<List<String>> groups) {
        if (groups == null || groups.isEmpty()) return List.of();
        List<List<String>> copy = new ArrayList<>(groups.size());
        for (List<String> g : groups) {
            if (g == null || g.isEmpty()) continue;
            copy.add(List.copyOf(g));
        }
        return List.copyOf(copy);
    }

    /**
     * 兼容构造（旧 canonical 签名，无 hidden 组件）：默认不隐藏。
     *
     * @param id           starter ID
     * @param category     分类
     * @param maintainer   维护方
     * @param description  展示描述
     * @param dependencies 依赖坐标清单
     * @param linkages     联动规则
     * @param required     是否必选
     * @param requires     依赖联动分组（AND-of-OR-groups）
     * @param bootVersions 关联的 Boot 版本 ID 清单
     */
    public StarterCatalogEntry(String id, String category, Maintainer maintainer, String description,
                               List<DependencyCoord> dependencies, LinkageSpec linkages,
                               boolean required, List<List<String>> requires, List<String> bootVersions) {
        this(id, category, maintainer, description, dependencies, linkages, required, requires, bootVersions, false);
    }

    /**
     * 兼容构造（扁平 requires，语义 AND）：对全部 Boot 版本可用。
     *
     * <p>扁平列表按**每个元素一个分组**展开，即 {@code [B, C]} → {@code [[B], [C]]}（需要 B 和 C），
     * 与历史语义一致；表达「B 或 C」请使用嵌套写法 {@code List.of(List.of("B", "C"))}。
     */
    public StarterCatalogEntry(String id, String category, Maintainer maintainer, String description,
                               List<DependencyCoord> dependencies, LinkageSpec linkages,
                               boolean required, List<String> flatRequires) {
        this(id, category, maintainer, description, dependencies, linkages, required,
                asAndGroups(flatRequires), List.of());
    }

    /** 兼容构造（无必选标记与依赖联动）：对全部 Boot 版本可用。 */
    public StarterCatalogEntry(String id, String category, Maintainer maintainer, String description,
                               List<DependencyCoord> dependencies, LinkageSpec linkages) {
        this(id, category, maintainer, description, dependencies, linkages, false, List.of(), List.of());
    }

    /** 扁平列表 → AND 分组（每元素一组的不可变视图）。 */
    private static List<List<String>> asAndGroups(List<String> flat) {
        if (flat == null || flat.isEmpty()) return List.of();
        List<List<String>> groups = new ArrayList<>(flat.size());
        for (String id : flat) {
            groups.add(List.of(id));
        }
        return List.copyOf(groups);
    }
}
