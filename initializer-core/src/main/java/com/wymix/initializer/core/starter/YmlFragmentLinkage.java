package com.wymix.initializer.core.starter;

/**
 * application.yml 片段联动项：starter yml 声明的一个 {@code application-yml-fragments} 条目。
 *
 * <p>{@code source} 为 classpath 片段路径，{@code mergeStrategy} 为该片段内数组的默认合并策略，
 * 可被片段内 key 级声明覆盖（M2.2 合并器消费）。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record YmlFragmentLinkage(String source, MergeStrategy mergeStrategy) {

    /** 数组合并策略：append 追加去重 / override 整体替换。 */
    public enum MergeStrategy {
        /** 追加去重（默认） */
        APPEND,
        /** 整体替换 */
        OVERRIDE
    }
}
