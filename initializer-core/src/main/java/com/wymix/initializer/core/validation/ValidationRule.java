package com.wymix.initializer.core.validation;

/**
 * 单项命名校验规则（配置驱动，来自 {@code catalog/validation.yml}）。
 *
 * <p>线程安全性：不可变对象，线程安全。
 *
 * @param field             字段名，如 {@code group}
 * @param pattern           正则表达式（整串匹配）
 * @param emptyMessage      空值提示文案
 * @param defaultMessage    不匹配时的默认提示文案
 */
public record ValidationRule(String field, String pattern, String emptyMessage, String defaultMessage) {
}
