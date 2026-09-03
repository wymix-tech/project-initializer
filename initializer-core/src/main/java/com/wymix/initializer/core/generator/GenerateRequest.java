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
        List<String> starters) {

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
    }
}
