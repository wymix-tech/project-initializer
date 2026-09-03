package com.wymix.initializer.core.validation;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * validation.yml 解析器：基于 SnakeYAML 安全构造器，把 YAML 结构映射为 {@link ValidationRule}。
 *
 * <p>只做结构映射，规则合法性与否由 {@link ValidationRules} 装载期校验。
 *
 * <p>线程安全性：无状态，线程安全。
 */
final class YamlValidationParser {

    private YamlValidationParser() {
    }

    /** 解析结果载体：命名校验规则清单。 */
    record ParsedRules(List<ValidationRule> rules) {
    }

    /** 校验规则清单必需覆盖的字段（缺一即配置不完整）。 */
    private static final List<String> REQUIRED_FIELDS = List.of("group", "artifact", "package");

    /**
     * 解析 validation.yml 输入流。
     *
     * @param yaml 输入流
     * @return 解析结果
     * @throws BusinessException 结构缺失/类型非法（错误码 {@link ErrorCode#INTERNAL_ERROR}）
     */
    static ParsedRules parse(InputStream yaml) {
        Map<String, Object> root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "validation.yml 解析失败: " + e.getMessage(), e);
        }
        if (root == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "validation.yml 内容为空");
        }
        Object rulesNode = root.get("rules");
        if (!(rulesNode instanceof Map)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "validation.yml 缺少必需字段: rules");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> rules = (Map<String, Object>) rulesNode;
        List<ValidationRule> result = new ArrayList<>();
        for (String field : REQUIRED_FIELDS) {
            Object node = rules.get(field);
            if (!(node instanceof Map)) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "validation.yml 缺少必需字段: rules." + field);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) node;
            result.add(new ValidationRule(
                    field,
                    requireString(map, "pattern", "rules." + field + ".pattern"),
                    stringOrDefault(map, "tooShortMessage", field + " 不能为空"),
                    stringOrDefault(map, "defaultMessage", field + " 不符合命名规范")));
        }
        return new ParsedRules(result);
    }

    private static String requireString(Map<String, Object> map, String key, String field) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "validation.yml 缺少必需字段: " + field);
        }
        return value.toString();
    }

    private static String stringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }
}
