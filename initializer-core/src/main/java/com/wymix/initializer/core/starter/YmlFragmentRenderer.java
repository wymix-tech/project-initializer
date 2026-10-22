package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAML 片段参数渲染：仅替换模型中的标量参数，保留 Spring 运行时占位符。
 * 参数先以临时标记参与 YAML 解析，再恢复到字符串节点，避免参数值改变 YAML 结构或类型。
 * 反斜杠转义的占位符始终作为字面量输出。无共享可变状态，线程安全。
 */
final class YmlFragmentRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("(\\\\)?\\$\\{([^{}]+)}");

    private YmlFragmentRenderer() {
    }

    static YmlFragmentMerger.Fragment render(String text, String source,
                                             YmlFragmentLinkage.MergeStrategy strategy,
                                             Map<String, Object> model) {
        String prefix = "YML_PARAM_" + UUID.randomUUID().toString().replace("-", "") + "_";
        Map<String, String> replacements = new LinkedHashMap<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            String literal = "${" + matcher.group(2) + "}";
            Object value = model.get(matcher.group(2));
            String replacement = literal;
            if (matcher.group(1) == null && (value instanceof String || value instanceof Number
                    || value instanceof Boolean)) {
                replacement = String.valueOf(value);
            }
            String token = prefix + replacements.size() + "_";
            replacements.put(token, replacement);
            matcher.appendReplacement(masked, token);
        }
        matcher.appendTail(masked);
        YmlFragmentMerger.Fragment parsed = YmlFragmentMerger.Fragment.parse(
                new ByteArrayInputStream(masked.toString().getBytes(StandardCharsets.UTF_8)), source, strategy);
        Pattern tokens = Pattern.compile(Pattern.quote(prefix) + "\\d+_");
        @SuppressWarnings("unchecked")
        Map<String, Object> rendered = (Map<String, Object>) restoreNode(
                parsed.parsed(), tokens, replacements, source);
        return new YmlFragmentMerger.Fragment(rendered, strategy, source);
    }

    private static Object restoreNode(Object node, Pattern tokens, Map<String, String> replacements,
                                      String source) {
        if (node instanceof String text) {
            return tokens.matcher(text).replaceAll(match -> Matcher.quoteReplacement(replacements.get(match.group())));
        }
        if (node instanceof Map<?, ?> map) {
            Map<Object, Object> rendered = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = restoreNode(entry.getKey(), tokens, replacements, source);
                if (rendered.containsKey(key)) {
                    throw new BusinessException(ErrorCode.YML_MERGE_CONFLICT,
                            "yml 参数替换后键冲突: " + key + "（来源: " + source + "）");
                }
                rendered.put(key, restoreNode(entry.getValue(), tokens, replacements, source));
            }
            return rendered;
        }
        if (node instanceof java.util.List<?> list) {
            java.util.List<Object> rendered = new ArrayList<>();
            for (Object item : list) {
                rendered.add(restoreNode(item, tokens, replacements, source));
            }
            return rendered;
        }
        return node;
    }
}
