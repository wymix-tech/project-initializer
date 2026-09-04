package com.wymix.initializer.core.generator;

/**
 * Gradle 构建脚本渲染实现（M2.4，Groovy DSL）：
 * {@code build.gradle.ftl} → {@code build.gradle}、{@code settings.gradle.ftl} → {@code settings.gradle}。
 *
 * <p>依赖写法：选中 starter 的坐标由数据模型 {@code starterDependencies} 驱动
 * （{@code implementation "group:artifact:version"}，version 为空走 Boot BOM 不写版本），
 * 与 Maven 共用同一依赖汇总来源，双轨产物依赖一致。
 *
 * <p>线程安全性：无状态，线程安全。
 */
public class GradleRenderer implements BuildScriptRenderer {

    /** Gradle 主脚本模板名。 */
    public static final String BUILD_TEMPLATE = "build.gradle.ftl";
    /** Gradle 主脚本产物路径。 */
    public static final String BUILD_PATH = "build.gradle";
    /** Gradle 设置脚本模板名。 */
    public static final String SETTINGS_TEMPLATE = "settings.gradle.ftl";
    /** Gradle 设置脚本产物路径。 */
    public static final String SETTINGS_PATH = "settings.gradle";

    /** @return {@code build.gradle.ftl} */
    @Override
    public String buildScriptTemplate() {
        return BUILD_TEMPLATE;
    }

    /** @return {@code build.gradle} */
    @Override
    public String buildScriptPath() {
        return BUILD_PATH;
    }

    /** @return {@code settings.gradle.ftl} */
    @Override
    public String settingsTemplate() {
        return SETTINGS_TEMPLATE;
    }

    /** @return {@code settings.gradle} */
    @Override
    public String settingsPath() {
        return SETTINGS_PATH;
    }
}
