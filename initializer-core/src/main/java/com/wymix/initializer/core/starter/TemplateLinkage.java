package com.wymix.initializer.core.starter;

/**
 * 模板联动项：starter yml 声明的一个 {@code templates} 条目。
 *
 * <p>{@code source} 为 classpath 模板路径（如 {@code starters/xxx-redis/RedisConfig.java.ftl}），
 * {@code target} 为生成项目内的目标路径，支持统一占位符（M2.1 渲染前由联动管线替换）。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record TemplateLinkage(String source, String target) {
}
