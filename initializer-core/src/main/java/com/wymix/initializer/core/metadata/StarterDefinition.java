package com.wymix.initializer.core.metadata;

import java.util.List;

/**
 * Starter 完整元数据：展示名 + Maven 依赖坐标映射（配置驱动）+ 选择约束。
 *
 * <p>新增 starter 仅需在 {@code catalog/metadata.yml} 的 {@code starters} 节登记，
 * 生成器与 API 层无需改动代码（技术方案 §2.2）。
 *
 * <p>两类选择约束（均由发布期校验，配置错误早暴露）：
 * <ul>
 *   <li>{@code required = true}：必选。前端默认勾选且禁止取消，后端生成时兜底自动补齐</li>
 *   <li>{@code requires = [id...]}：依赖联动。选中本 starter 时自动带上所列 starter（传递闭包），
 *       取消本 starter 后，不再被其他选中项需要的连带项自动释放</li>
 * </ul>
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record StarterDefinition(String id, String label, MavenDependency dependency,
                                boolean required, List<String> requires) {

    /**
     * 兼容构造：未声明约束时视为可选、无依赖。
     *
     * @param id         starter ID
     * @param label      展示名
     * @param dependency 依赖坐标
     */
    public StarterDefinition(String id, String label, MavenDependency dependency) {
        this(id, label, dependency, false, List.of());
    }

    /**
     * 兼容构造：仅声明必选标记，无依赖联动。
     *
     * @param id         starter ID
     * @param label      展示名
     * @param dependency 依赖坐标
     * @param required   是否必选
     */
    public StarterDefinition(String id, String label, MavenDependency dependency, boolean required) {
        this(id, label, dependency, required, List.of());
    }

    /**
     * @return 依赖的 starter ID 清单（不可变，无依赖时为空）
     */
    public List<String> requires() {
        return requires == null ? List.of() : requires;
    }
}
