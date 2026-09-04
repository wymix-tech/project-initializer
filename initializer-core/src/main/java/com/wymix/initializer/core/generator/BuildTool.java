package com.wymix.initializer.core.generator;

import java.util.Locale;

/**
 * 构建工具枚举（M2.4）：Maven / Gradle 双轨支持。
 *
 * <p>构建脚本写法差异全部隔离在 {@link BuildScriptRenderer} 接口下，本枚举仅做
 * 请求侧的取值标识与反序列化归一化。
 *
 * <p>线程安全性：枚举实例天然线程安全。
 */
public enum BuildTool {

    /** Maven：渲染 pom.xml */
    MAVEN,

    /** Gradle（Groovy DSL）：渲染 build.gradle + settings.gradle */
    GRADLE;

    /**
     * 默认构建工具：Maven（存量请求与前端默认值保持不变）。
     *
     * @return 默认值 {@link #MAVEN}
     */
    public static BuildTool defaultTool() {
        return MAVEN;
    }

    /**
     * 宽松解析构建工具：null/空白回退默认值 Maven；大小写不敏感；
     * 非法取值抛 1004。
     *
     * @param raw 原始取值，如 {@code "gradle"}、{@code null}
     * @return 构建工具枚举
     * @throws com.wymix.initializer.api.error.BusinessException 取值非法（1004 INVALID_BUILD_TOOL）
     */
    public static BuildTool parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultTool();
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new com.wymix.initializer.api.error.BusinessException(
                    com.wymix.initializer.api.error.ErrorCode.INVALID_BUILD_TOOL,
                    "不支持的构建工具: " + raw + "（仅允许 maven / gradle）");
        }
    }
}
