package com.wymix.initializer.core.metadata;

import java.util.List;

/**
 * Spring Boot 版本元数据（配置驱动，来自 {@code catalog/metadata.yml}）。
 *
 * <p>仅收录官方版本；兼容矩阵通过 {@code supportedJavaVersions} 表达 ID 引用。
 *
 * <p>框架父 POM（{@code frameworkParent}，可选）：每个 Boot 版本可独立声明——
 * 不同 Boot 版本挂接的框架父 POM 可能不同；未声明时生成器回退
 * {@code org.springframework.boot:spring-boot-starter-parent:&lt;bootVersion&gt;}。
 *
 * <p>线程安全性：不可变对象，线程安全。
 *
 * @param id                   版本 ID
 * @param label                展示名
 * @param official             是否官方发布版本
 * @param supportedJavaVersions 兼容 Java 版本 ID
 * <p>Spring Boot 版本（{@code springBootVersion}，可选）：版本 ID 可能是内部框架的版本号
 * （如 {@code 3.0.0-SNAPSHOT}），与真实的 Spring Boot 版本不一致；此时用该字段单独声明
 * Gradle 插件与 BOM 解析所用的 Spring Boot 版本（如 {@code 3.2.5}）。未声明时回退版本 ID。
 *
 * <p>线程安全性：不可变对象，线程安全。
 *
 * @param id                   版本 ID
 * @param label                展示名
 * @param official             是否官方发布版本
 * @param supportedJavaVersions 兼容 Java 版本 ID
 * @param starters             可选 starter ID
 * @param frameworkParent      该版本生成项目挂接的框架父 POM（可 null，null 回退官方父 POM）
 * @param springBootVersion    真实的 Spring Boot 版本（可 null，null 回退 id）
 */
public record BootVersion(String id, String label, boolean official, List<String> supportedJavaVersions,
                          List<String> starters, FrameworkParent frameworkParent, String springBootVersion) {

    public BootVersion {
        supportedJavaVersions = List.copyOf(supportedJavaVersions);
        starters = List.copyOf(starters);
    }

    /**
     * 兼容构造：未声明 Spring Boot 版本（等价 {@code springBootVersion = null}）。
     */
    public BootVersion(String id, String label, boolean official, List<String> supportedJavaVersions,
                       List<String> starters, FrameworkParent frameworkParent) {
        this(id, label, official, supportedJavaVersions, starters, frameworkParent, null);
    }

    /**
     * 兼容构造：无框架父 POM、未声明 Spring Boot 版本（等价两者均为 null）。
     */
    public BootVersion(String id, String label, boolean official, List<String> supportedJavaVersions,
                       List<String> starters) {
        this(id, label, official, supportedJavaVersions, starters, null, null);
    }

    /**
     * 真实的 Spring Boot 版本：Gradle 的 {@code org.springframework.boot} 插件版本与
     * 依赖版本解析以它为准，避免与内部框架父 POM 的版本号混淆。
     *
     * @return 声明的 Spring Boot 版本；未声明时回退版本 ID
     */
    @Override
    public String springBootVersion() {
        return springBootVersion == null || springBootVersion.isBlank() ? id : springBootVersion.trim();
    }

    /**
     * 该 Boot 版本支持的构建工具。
     *
     * <p>Maven 走 {@code <parent>} 继承；Gradle 无 parent 概念，由 Spring Boot 插件 +
     * dependency-management 插件复刻版本管理，自定义框架父 POM 时额外导入其 BOM（见 build.gradle.ftl）。
     *
     * @return 受支持的构建工具 id（{@code maven} / {@code gradle}）
     */
    public List<String> supportedBuildTools() {
        return List.of("maven", "gradle");
    }

    /** @return 该 Boot 版本是否支持指定 Java 版本 */
    public boolean supportsJava(String javaVersionId) {
        return supportedJavaVersions.contains(javaVersionId);
    }
}
