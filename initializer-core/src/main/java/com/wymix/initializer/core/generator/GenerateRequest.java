package com.wymix.initializer.core.generator;

import java.util.List;

/**
 * 生成请求：项目元信息 + 版本组合 + starter 清单。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record GenerateRequest(
        /** groupId，如 com.example */
        String group,
        /** artifactId，如 demo-app */
        String artifact,
        /** 基础包名，如 com.example.demo */
        String basePackage,
        /** Java 版本 ID，如 17 */
        String javaVersion,
        /** Boot 版本 ID，如 3.2.5 */
        String bootVersion,
        /** starter ID 清单，如 [web, data-jpa] */
        List<String> starters,
        /** 构建工具（M2.4）：maven / gradle；null/空白回退 maven，非法取值抛 1004 */
        BuildTool buildTool) {

    /**
     * @param group       groupId（必填）
     * @param artifact    artifactId（必填）
     * @param basePackage 基础包名（必填）
     * @param javaVersion Java 版本 ID（必填）
     * @param bootVersion Boot 版本 ID（必填）
     * @param starters    starter 清单，可为空
     */
    public GenerateRequest {
        starters = starters == null ? List.of() : List.copyOf(starters);
        buildTool = buildTool == null ? BuildTool.defaultTool() : buildTool;
    }

    /**
     * String 形态工厂：buildTool 宽松解析（null/空白回退 maven，非法抛 1004）。
     *
     * @param buildTool 构建工具原始取值（maven/gradle，忽略大小写）
     * @return 生成请求
     */
    public static GenerateRequest of(String group, String artifact, String basePackage,
                                     String javaVersion, String bootVersion,
                                     List<String> starters, String buildTool) {
        return new GenerateRequest(group, artifact, basePackage, javaVersion, bootVersion,
                starters, BuildTool.parse(buildTool));
    }

    /**
     * 兼容构造器：未指定构建工具时回退默认值 Maven（M2.4 前存量调用方零改动）。
     *
     * @param group       groupId（必填）
     * @param artifact    artifactId（必填）
     * @param basePackage 基础包名（必填）
     * @param javaVersion Java 版本 ID（必填）
     * @param bootVersion Boot 版本 ID（必填）
     * @param starters    starter 清单，可为空
     */
    public GenerateRequest(String group, String artifact, String basePackage,
                           String javaVersion, String bootVersion, List<String> starters) {
        this(group, artifact, basePackage, javaVersion, bootVersion, starters, null);
    }
}
