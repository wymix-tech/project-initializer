package com.wymix.initializer.app.validation;

import com.wymix.initializer.core.PackageNamer;
import com.wymix.initializer.core.validation.ValidationRules;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校验应用服务：基于配置规则集做 Group / Artifact / Package 即时校验与包名推导。
 *
 * <p>线程安全性：无状态单例（规则集不可变），线程安全。
 */
@Service
public class ValidationService {

    private final ValidationRules rules;

    /**
     * @param rules 校验规则集（由 {@link ValidationRulesConfig} 装配）
     */
    public ValidationService(ValidationRules rules) {
        this.rules = rules;
    }

    /**
     * 即时校验元信息三项字段。
     *
     * @param group    groupId，可为空（空值也返回对应错误项）
     * @param artifact artifactId，可为空
     * @param packageName 包名，可为空
     * @return 校验结果：errors 为空即全部通过
     */
    public ValidationResult validate(String group, String artifact, String packageName) {
        Map<String, String> errors = new LinkedHashMap<>();
        collect(errors, "group", rules.validateGroup(group));
        collect(errors, "artifact", rules.validateArtifact(artifact));
        collect(errors, "package", rules.validatePackage(packageName));
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors.values()));
    }

    /**
     * 由 Group + Artifact 推导默认包名（复用 core 层 {@link PackageNamer}）。
     *
     * @param group    groupId
     * @param artifact artifactId
     * @return 推导出的包名
     */
    public String derivePackage(String group, String artifact) {
        return PackageNamer.derive(group, artifact);
    }

    private static void collect(Map<String, String> errors, String field, String message) {
        if (message != null) {
            errors.put(field, message);
        }
    }

    /** 校验结果 DTO：{@code valid} + 错误明细列表。 */
    public record ValidationResult(boolean valid, List<String> errors) {

        /**
         * @param valid  是否全部通过
         * @param errors 错误文案列表（保持 group → artifact → package 顺序）
         */
        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        /** @return 空错误结果（全部通过） */
        public static ValidationResult ok() {
            return new ValidationResult(true, new ArrayList<>());
        }
    }
}
