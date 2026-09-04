package com.wymix.initializer.core.metadata;

/**
 * Starter 完整元数据：展示名 + Maven 依赖坐标映射（配置驱动）。
 *
 * <p>新增 starter 仅需在 {@code catalog/metadata.yml} 的 {@code starters} 节登记，
 * 生成器与 API 层无需改动代码（技术方案 §2.2）。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record StarterDefinition(String id, String label, MavenDependency dependency) {
}
