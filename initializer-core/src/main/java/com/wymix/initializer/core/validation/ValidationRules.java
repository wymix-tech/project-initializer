package com.wymix.initializer.core.validation;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 校验规则集：装载 {@code catalog/validation.yml}，提供 Group / Artifact / Package 整串匹配校验。
 *
 * <p>规则全部来源于配置文件，无硬编码；正则在装载期预编译并做合法性校验。
 *
 * <p>线程安全性：不可变对象（{@link Pattern} 线程安全），线程安全。
 */
public class ValidationRules {

    /** 装载期固定必需字段：group / artifact / package。 */
    private static final String FIELD_GROUP = "group";
    private static final String FIELD_ARTIFACT = "artifact";
    private static final String FIELD_PACKAGE = "package";

    private final Map<String, CompiledRule> rules = new LinkedHashMap<>();

    /**
     * 从 YAML 输入流装载校验规则集。
     *
     * @param yaml validation.yml 输入流
     * @return 校验规则集实例
     * @throws BusinessException YAML 解析失败、正则非法或结构不完整（错误码 {@link ErrorCode#INTERNAL_ERROR}）
     */
    public static ValidationRules load(InputStream yaml) {
        YamlValidationParser.ParsedRules parsed = YamlValidationParser.parse(yaml);
        return new ValidationRules(parsed.rules());
    }

    private ValidationRules(java.util.List<ValidationRule> ruleList) {
        for (ValidationRule rule : ruleList) {
            Pattern compiled;
            try {
                compiled = Pattern.compile(rule.pattern());
            } catch (PatternSyntaxException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "validation.yml 正则非法: rules." + rule.field() + " -> " + rule.pattern(), e);
            }
            rules.put(rule.field(), new CompiledRule(rule, compiled));
        }
        for (String field : new String[]{FIELD_GROUP, FIELD_ARTIFACT, FIELD_PACKAGE}) {
            if (!rules.containsKey(field)) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "validation.yml 缺少必需字段: rules." + field);
            }
        }
    }

    /**
     * 校验字段值是否满足对应规则。
     *
     * @param field 字段名（group / artifact / package）
     * @param value 待校验值
     * @return 校验通过返回 {@code null}；失败返回具体错误文案（空值提示或默认文案）
     * @throws BusinessException 字段名未知（配置与代码不一致，错误码 {@link ErrorCode#INTERNAL_ERROR}）
     */
    public String validate(String field, String value) {
        CompiledRule rule = rules.get(field);
        if (rule == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "未配置的校验字段: " + field);
        }
        if (value == null || value.isBlank()) {
            return rule.definition().emptyMessage();
        }
        return rule.compiled().matcher(value).matches() ? null : rule.definition().defaultMessage();
    }

    /**
     * 校验 groupId。
     *
     * @param value 待校验值
     * @return 通过返回 {@code null}，失败返回错误文案
     */
    public String validateGroup(String value) {
        return validate(FIELD_GROUP, value);
    }

    /**
     * 校验 artifactId。
     *
     * @param value 待校验值
     * @return 通过返回 {@code null}，失败返回错误文案
     */
    public String validateArtifact(String value) {
        return validate(FIELD_ARTIFACT, value);
    }

    /**
     * 校验包名。
     *
     * @param value 待校验值
     * @return 通过返回 {@code null}，失败返回错误文案
     */
    public String validatePackage(String value) {
        return validate(FIELD_PACKAGE, value);
    }

    /** 编译后的规则：原始定义 + 预编译正则。 */
    private record CompiledRule(ValidationRule definition, Pattern compiled) {
    }
}
