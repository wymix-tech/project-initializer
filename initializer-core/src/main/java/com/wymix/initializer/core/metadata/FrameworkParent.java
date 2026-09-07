package com.wymix.initializer.core.metadata;

/**
 * 框架父 POM 配置（顶层 metadata 可选字段）：生成 {@code pom.xml} 的 {@code <parent>} 坐标。
 *
 * <p>无该字段时生成器回退默认 {@code org.springframework.boot:spring-boot-starter-parent}，
 * 适用于 Spring Boot 官方默认脚手架场景；
 * 内部框架可在 metadata.yml 顶层声明本节，将生成项目统一挂到公司框架父 POM 下，
 * 由父 POM 统一管理 Spring Boot BOM 与内部依赖版本。
 *
 * <p>例：{@code travelsky_dev_parent3}（内部框架，继承 spring-boot-starter-parent 并维护内部依赖版本）。
 *
 * <p>线程安全性：不可变 record，线程安全。
 *
 * @param groupId    父 POM groupId
 * @param artifactId 父 POM artifactId
 * @param version    父 POM version（支持 {@code ${revision}} 等占位符，与生成项目 properties 对齐）
 */
public record FrameworkParent(String groupId, String artifactId, String version) {

    /**
     * 默认 Spring Boot 父 POM（未配置 frameworkParent 时兜底）。
     */
    public static FrameworkParent defaultSpringBootParent(String bootVersion) {
        return new FrameworkParent("org.springframework.boot", "spring-boot-starter-parent", bootVersion);
    }
}
