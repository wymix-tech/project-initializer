package com.wymix.initializer.core.starter;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一占位符白名单规范（技术方案 #3 §3.4）。
 *
 * <p>starter yml 中仅允许以下占位符（target 路径与 config-package 渲染前替换，
 * 模板内部由 FreeMarker context 处理）：
 * {@code {basePackage} {packagePath} {artifact} {group} {bootVersion} {javaVersion}}。
 * 越界占位符在清单装载期拒绝（错误码 9002）。
 *
 * <p>线程安全性：无状态，线程安全。
 */
public final class PlaceholderPolicy {

    /** 占位符语法：{@code {name}}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    /** 允许的占位符白名单。 */
    private static final Set<String> ALLOWED = Set.of(
            "basePackage", "packagePath", "artifact", "group", "bootVersion", "javaVersion");

    private PlaceholderPolicy() {
    }

    /**
     * 提取值中不在白名单内的占位符名。
     *
     * @param value 待校验字符串
     * @return 越界占位符名（保持出现顺序去重）；全部合法时为空
     */
    public static Set<String> unknownPlaceholders(String value) {
        Set<String> unknown = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(value);
        while (m.find()) {
            String name = m.group(1);
            if (!ALLOWED.contains(name)) {
                unknown.add("{" + name + "}");
            }
        }
        return unknown;
    }

    /** @return 白名单占位符的可读清单 */
    public static String allowed() {
        return String.join(", ", ALLOWED.stream().sorted().map(n -> "{" + n + "}").toList());
    }

    /**
     * 白名单占位符替换：按取值表替换 {@code {name}}。
     *
     * <p>白名单内但取值表缺失的占位符视为配置错误（如 target 路径写了 {@code {bootVersion}}
     * 而模型未提供取值），立即抛 {@link IllegalArgumentException} 暴露问题，
     * 避免生成带 {@code {name}} 字面量的文件路径。
     *
     * @param value 含占位符的原始值
     * @param values 占位符取值表（键为占位符名，如 basePackage）
     * @return 替换后的值
     * @throws IllegalArgumentException 值中含越界占位符（清单装载期应已拦截，此处兜底），
     *                                  或白名单占位符缺少取值
     */
    public static String replace(String value, Map<String, ?> values) {
        Set<String> unknown = unknownPlaceholders(value);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("存在越界占位符 " + String.join(", ", unknown)
                    + "（仅允许 " + allowed() + "）");
        }
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            Object replacement = values.get(name);
            if (replacement == null) {
                throw new IllegalArgumentException("占位符 {" + name + "} 缺少取值（value=" + value + "）");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(replacement)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
