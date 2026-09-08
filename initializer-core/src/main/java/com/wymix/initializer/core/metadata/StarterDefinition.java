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
 *   <li>{@code requires}：依赖联动，AND-of-OR-groups。分组内任一被选即满足，全部分组满足
 *       才算依赖满足。前端不再自动勾选/锁定依赖引入项，由用户显式选择，生成期校验</li>
 * </ul>
 *
 * <pre>{@code
 * requires: [B, C]          // 兼容：需要 B 和 C
 * requires: [[B, C]]        // 需要 B 或 C
 * requires: [[B, C], [D]]   // (B 或 C) 且 D
 * }</pre>
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record StarterDefinition(String id, String label, MavenDependency dependency,
                                boolean required, List<List<String>> requires) {

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
     * @return 依赖分组（AND-of-OR-groups，不可变，无依赖时为空）
     */
    public List<List<String>> requires() {
        return requires == null ? List.of() : requires;
    }
}
