package com.wymix.initializer.core.metadata;

/**
 * Starter 完整元数据：展示名 + Maven 依赖坐标映射（配置驱动）+ 是否必选。
 *
 * <p>新增 starter 仅需在 {@code catalog/metadata.yml} 的 {@code starters} 节登记，
 * 生成器与 API 层无需改动代码（技术方案 §2.2）。
 *
 * <p>{@code required = true} 表示该 starter 为必选项：前端默认勾选且禁止取消，
 * 后端生成时兜底自动补齐（请求未携带也会加入产物），用户无法生成不含必选项的项目。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record StarterDefinition(String id, String label, MavenDependency dependency, boolean required) {

    /**
     * 兼容构造：未声明 required 时视为可选（默认 false）。
     *
     * @param id         starter ID
     * @param label      展示名
     * @param dependency 依赖坐标
     */
    public StarterDefinition(String id, String label, MavenDependency dependency) {
        this(id, label, dependency, false);
    }
}
