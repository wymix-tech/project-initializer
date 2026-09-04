package com.wymix.initializer.core.metadata;

/**
 * Maven 依赖坐标（配置驱动，来自 {@code catalog/metadata.yml} 的 starters 节）。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record MavenDependency(String groupId, String artifactId) {
}
