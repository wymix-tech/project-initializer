package com.wymix.initializer.core.conflict;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 冲突规则 YAML 解析器（M3.5）：解析 {@code conflict-rules.yml} 同构配置为 {@link ConflictCatalog}。
 *
 * <p>配置格式（与概要设计 M3.5 一致）：
 * <pre>
 * rules:
 *   - id: redis-vs-cache-impl
 *     type: pair                 # pair（id 对）/ group（组内互斥）
 *     starters: [a, b]
 *     message: 两者均注册缓存管理器
 * </pre>
 *
 * <p>安全语义：SnakeYAML SafeConstructor（拒绝任意类实例化），单文档。
 * 结构非法（4002 语义收敛由发布层做，此处抛 {@link ErrorCode#CONFIG_VALIDATION_FAILED}）。
 *
 * <p>线程安全性：无状态，线程安全。
 */
public final class ConflictRuleParser {

    private ConflictRuleParser() {
    }

    /**
     * 从 YAML 输入流解析冲突规则目录。
     *
     * @param yaml conflict-rules 配置输入流（可空流视作空目录）
     * @return 冲突规则目录
     * @throws BusinessException 解析失败或结构非法（错误码 {@link ErrorCode#CONFIG_VALIDATION_FAILED}）
     */
    public static ConflictCatalog parse(InputStream yaml) {
        if (yaml == null) {
            return ConflictCatalog.empty();
        }
        String text;
        try {
            text = new String(yaml.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "冲突规则配置读取失败: " + e.getMessage(), e);
        }
        return parseText(text);
    }

    /**
     * 从 YAML 文本解析冲突规则目录（null/空白视为空目录）。
     *
     * @param text 配置全文（可空）
     * @return 冲突规则目录
     * @throws BusinessException 解析失败或结构非法
     */
    public static ConflictCatalog parseText(String text) {
        if (text == null || text.isBlank()) {
            return ConflictCatalog.empty();
        }
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "冲突规则配置解析失败: " + e.getMessage(), e);
        }
        if (root == null) {
            return ConflictCatalog.empty();
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "冲突规则配置顶层必须为映射结构（含 rules 节）");
        }
        Object rulesNode = map.get("rules");
        if (rulesNode == null) {
            return ConflictCatalog.empty();
        }
        if (!(rulesNode instanceof List<?> ruleList)) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "冲突规则 rules 节必须为列表结构");
        }
        List<ConflictRule> rules = new ArrayList<>();
        for (Object item : ruleList) {
            rules.add(toRule(item));
        }
        try {
            return new ConflictCatalog(rules);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "冲突规则配置非法: " + e.getMessage(), e);
        }
    }

    /** 单条规则节点 → ConflictRule（缺字段/类型非法统一 4002）。 */
    private static ConflictRule toRule(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "冲突规则条目必须为映射结构（含 id/type/starters/message）");
        }
        Object id = map.get("id");
        if (id == null || id.toString().isBlank()) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "冲突规则缺少必需字段: id");
        }
        Object type = map.get("type");
        if (type == null || type.toString().isBlank()) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "冲突规则 " + id + " 缺少必需字段: type");
        }
        ConflictRule.Type ruleType;
        try {
            ruleType = ConflictRule.Type.parse(type.toString());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "冲突规则 " + id + " 的 type 非法: " + type + "（仅允许 pair / group）");
        }
        Object starters = map.get("starters");
        if (!(starters instanceof List<?> starterList) || starterList.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "冲突规则 " + id + " 缺少必需字段或 starters 非列表: starters");
        }
        List<String> starterIds = starterList.stream()
                .map(s -> {
                    if (s == null || s.toString().isBlank()) {
                        throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                                "冲突规则 " + id + " 的 starters 含空白项");
                    }
                    return s.toString().trim();
                })
                .toList();
        Object message = map.get("message");
        if (message == null || message.toString().isBlank()) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "冲突规则 " + id + " 缺少必需字段: message");
        }
        try {
            return new ConflictRule(id.toString().trim(), ruleType, starterIds, message.toString());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "冲突规则配置非法: " + e.getMessage(), e);
        }
    }
}
