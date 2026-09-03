package com.wymix.initializer.core.metadata;

/**
 * Java 版本元数据（配置驱动，来自 {@code catalog/metadata.yml}）。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record JavaVersion(String id, String label, boolean deprecated, String recommendedBootVersion) {

    /** @return 该 Java 版本的推荐 Boot 版本，未配置时为 {@code null} */
    public String recommendedBootVersion() {
        return recommendedBootVersion;
    }
}
