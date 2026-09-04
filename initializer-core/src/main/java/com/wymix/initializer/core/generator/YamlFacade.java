package com.wymix.initializer.core.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.Map;

/**
 * YAML 解析门面：生成引擎内 application.yml 基底文本 → 树（供片段合并）。
 *
 * <p>线程安全性：无状态，线程安全。
 */
final class YamlFacade {

    private YamlFacade() {
    }

    /**
     * 解析 YAML 文本为映射树。
     *
     * @param text YAML 文本
     * @return 映射树（文本为空时为空映射）
     * @throws BusinessException 解析失败（错误码 3004）
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String text) {
        Object tree;
        try {
            tree = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.YML_MERGE_CONFLICT,
                    "application.yml 基底解析失败: " + e.getMessage(), e);
        }
        if (tree == null) {
            return new java.util.LinkedHashMap<>();
        }
        if (!(tree instanceof Map)) {
            throw new BusinessException(ErrorCode.YML_MERGE_CONFLICT, "application.yml 基底顶层必须为映射结构");
        }
        return (Map<String, Object>) tree;
    }
}
