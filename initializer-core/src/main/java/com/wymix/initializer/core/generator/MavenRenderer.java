package com.wymix.initializer.core.generator;

/**
 * Maven 构建脚本渲染实现（M2.4）：{@code pom.xml.ftl} → {@code pom.xml}。
 *
 * <p>行为与 M2.4 前的 pom 产物完全一致（存量请求默认 buildTool=maven，快照不回归）。
 *
 * <p>线程安全性：无状态，线程安全。
 */
public class MavenRenderer implements BuildScriptRenderer {

    /** Maven 主脚本模板名。 */
    public static final String BUILD_TEMPLATE = "pom.xml.ftl";
    /** Maven 主脚本产物路径。 */
    public static final String BUILD_PATH = "pom.xml";

    /** @return {@code pom.xml.ftl} */
    @Override
    public String buildScriptTemplate() {
        return BUILD_TEMPLATE;
    }

    /** @return {@code pom.xml} */
    @Override
    public String buildScriptPath() {
        return BUILD_PATH;
    }

    /** Maven 无设置脚本模板。 */
    @Override
    public String settingsTemplate() {
        return null;
    }

    /** Maven 无设置脚本产物。 */
    @Override
    public String settingsPath() {
        return null;
    }
}
