package com.wymix.initializer.core.starter;

/**
 * 依赖坐标（GAV），来自 starter yml 的 {@code dependencies} 节。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record DependencyCoord(String groupId, String artifactId, String version) {
    public DependencyCoord {
        version = version == null || version.isBlank() ? null : version.trim();
    }
}
