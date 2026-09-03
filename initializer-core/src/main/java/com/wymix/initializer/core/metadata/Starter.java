package com.wymix.initializer.core.metadata;

/**
 * Starter 依赖元数据（配置驱动，来自 {@code catalog/metadata.yml}）。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record Starter(String id, String label, String description) {
}
