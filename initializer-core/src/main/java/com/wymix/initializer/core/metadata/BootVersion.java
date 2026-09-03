package com.wymix.initializer.core.metadata;

import java.util.List;

/**
 * Spring Boot 版本元数据（配置驱动，来自 {@code catalog/metadata.yml}）。
 *
 * <p>仅收录官方版本；兼容矩阵通过 {@code supportedJavaVersions} 表达 ID 引用。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record BootVersion(String id, String label, boolean official, List<String> supportedJavaVersions, List<String> starters) {

    public BootVersion {
        supportedJavaVersions = List.copyOf(supportedJavaVersions);
        starters = List.copyOf(starters);
    }

    /** @return 该 Boot 版本是否支持指定 Java 版本 */
    public boolean supportsJava(String javaVersionId) {
        return supportedJavaVersions.contains(javaVersionId);
    }
}
