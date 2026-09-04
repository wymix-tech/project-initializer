package com.wymix.initializer.core.generator;

/**
 * 构建脚本渲染 SPI（M2.4）：Maven / Gradle 写法差异隔离于本接口之下。
 *
 * <p>实现方以 FreeMarker 模板承载脚本内容：{@link MavenRenderer}（{@code pom.xml.ftl}）、
 * {@link GradleRenderer}（{@code build.gradle.ftl} + {@code settings.gradle.ftl}，Groovy DSL）。
 * 新增构建工具仅需新增实现 + 模板文件，生成引擎 {@link ProjectGenerator}
 * 按请求的 {@link BuildTool} 分派，零改动。
 *
 * <p>线程安全性：实现类须为无状态不可变对象，线程安全。
 */
public interface BuildScriptRenderer {

    /**
     * 主构建脚本模板名（相对 templates/ 目录）。
     *
     * @return 如 {@code pom.xml.ftl} / {@code build.gradle.ftl}
     */
    String buildScriptTemplate();

    /**
     * 主构建脚本在产物 zip 中的相对路径（不含 {artifact} 根目录）。
     *
     * @return 如 {@code pom.xml} / {@code build.gradle}
     */
    String buildScriptPath();

    /**
     * 构建工具设置脚本模板名；无对应文件返回 null（不产出文件）。
     *
     * @return 如 {@code settings.gradle.ftl}；Maven 为 null
     */
    String settingsTemplate();

    /**
     * 构建工具设置脚本在产物 zip 中的相对路径；无对应文件返回 null。
     *
     * @return 如 {@code settings.gradle}；Maven 为 null
     */
    String settingsPath();
}
