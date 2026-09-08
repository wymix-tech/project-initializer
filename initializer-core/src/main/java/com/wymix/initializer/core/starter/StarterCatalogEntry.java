package com.wymix.initializer.core.starter;

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
        /** 依赖联动：选中本 starter 自动带上所列 starter（传递闭包） */
        List<String> requires,
        /** 关联的 Boot 版本 ID 清单（空 = 全部版本可用） */
        List<String> bootVersions) {

    public StarterCatalogEntry {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        requires = requires == null ? List.of() : List.copyOf(requires);
        bootVersions = bootVersions == null ? List.of() : List.copyOf(bootVersions);
    }

    /** 兼容构造：对全部 Boot 版本可用。 */
    public StarterCatalogEntry(String id, String category, Maintainer maintainer, String description,
                               List<DependencyCoord> dependencies, LinkageSpec linkages,
                               boolean required, List<String> requires) {
        this(id, category, maintainer, description, dependencies, linkages, required, requires, List.of());
    }

    /** 兼容构造：无必选标记与依赖联动，对全部 Boot 版本可用。 */
    public StarterCatalogEntry(String id, String category, Maintainer maintainer, String description,
                               List<DependencyCoord> dependencies, LinkageSpec linkages) {
        this(id, category, maintainer, description, dependencies, linkages, false, List.of(), List.of());
    }
}
