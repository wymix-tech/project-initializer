package com.wymix.initializer.core.starter;

/**
 * 集成抽象类联动项：starter yml 声明的一个 {@code abstract-classes} 条目。
 *
 * <p>{@code fqcn} 为 starter 提供的集成抽象类全限定名，
 * {@code alias} 为模板 context 引用名（配置类模板 import/extends 时使用）。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record AbstractClassLinkage(String fqcn, String alias) {
}
