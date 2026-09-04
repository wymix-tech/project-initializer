package com.wymix.initializer.core.starter;

import java.util.List;

/**
 * starter 清单条目：单个 starter yml（{@code catalog/starters/*.yml}）的完整模型。
 *
 * <p>官方 starter 与内部 starter 统一入清单，以 {@link Maintainer} 区分维护方。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record StarterCatalogEntry(
        /** starter ID，全局唯一，如 {@code xxx-redis-spring-boot-starter} */
        String id,
        /** 分类：web / data / middleware / tool */
        String category,
        /** 维护方 */
        Maintainer maintainer,
        /** 展示描述 */
        String description,
        /** 写入构建脚本的依赖坐标清单 */
        List<DependencyCoord> dependencies,
        /** 联动规则 */
        LinkageSpec linkages) {

    public StarterCatalogEntry {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }
}
