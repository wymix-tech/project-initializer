package com.wymix.initializer.core.starter;

import java.util.List;

/**
 * 联动规则集合：starter yml 的 {@code linkages} 节。
 *
 * <p>选中 starter 后，联动渲染管线（M2.3）按本规则自动生成配置代码与 yml 片段合并。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record LinkageSpec(
        /** 配置类目标包名，支持统一占位符，如 {@code {basePackage}.config}；可为空 */
        String configPackage,
        /** 模板联动清单（可为空） */
        List<TemplateLinkage> templates,
        /** application.yml 片段联动清单（可为空） */
        List<YmlFragmentLinkage> ymlFragments,
        /** 集成抽象类联动清单（可为空） */
        List<AbstractClassLinkage> abstractClasses) {

    public LinkageSpec {
        templates = templates == null ? List.of() : List.copyOf(templates);
        ymlFragments = ymlFragments == null ? List.of() : List.copyOf(ymlFragments);
        abstractClasses = abstractClasses == null ? List.of() : List.copyOf(abstractClasses);
    }

    /** @return 是否声明了任何联动规则 */
    public boolean isEmpty() {
        return configPackage == null
                && templates.isEmpty()
                && ymlFragments.isEmpty()
                && abstractClasses.isEmpty();
    }
}
