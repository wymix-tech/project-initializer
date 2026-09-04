package com.wymix.initializer.core.metadata;

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
 * metadata.yml 解析器：基于 SnakeYAML 安全构造器，把 YAML 结构映射为元数据 record。
 *
 * <p>只做结构映射与基础类型收敛，图合法性校验由 {@link MetadataCatalog} 负责。
 *
 * <p>线程安全性：无状态，线程安全。
 */
final class YamlMetadataParser {

    private YamlMetadataParser() {
    }

    /** 解析结果载体：Java 版本、Boot 版本与 starter 元信息清单。 */
    record ParsedMetadata(List<JavaVersion> javaVersions, List<BootVersion> bootVersions,
                          List<StarterDefinition> starters) {
    }

    /**
     * 解析 metadata.yml 输入流。
     *
     * @param yaml 输入流
     * @return 解析结果
     * @throws BusinessException 结构缺失/类型非法（错误码 {@link ErrorCode#INTERNAL_ERROR}）
     */
    static ParsedMetadata parse(InputStream yaml) {
        Map<String, Object> root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "metadata.yml 解析失败: " + e.getMessage(), e);
        }
        if (root == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "metadata.yml 内容为空");
        }
        return new ParsedMetadata(parseJavaVersions(root.get("javaVersions")), parseBootVersions(root.get("bootVersions")),
                parseStarters(root.get("starters")));
    }

    @SuppressWarnings("unchecked")
    private static List<JavaVersion> parseJavaVersions(Object node) {
        requireList(node, "javaVersions");
        List<JavaVersion> result = new ArrayList<>();
        for (Object item : (List<Object>) node) {
            Map<String, Object> map = requireMap(item, "javaVersions[]");
            String id = requireString(map, "id", "javaVersions[].id");
            String label = stringOrDefault(map, "label", "Java " + id);
            boolean deprecated = Boolean.TRUE.equals(map.get("deprecated"));
            String recommended = stringOrNull(map, "recommendedBootVersion");
            result.add(new JavaVersion(id, label, deprecated, recommended));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<BootVersion> parseBootVersions(Object node) {
        requireList(node, "bootVersions");
        List<BootVersion> result = new ArrayList<>();
        for (Object item : (List<Object>) node) {
            Map<String, Object> map = requireMap(item, "bootVersions[]");
            String id = requireString(map, "id", "bootVersions[].id");
            String label = stringOrDefault(map, "label", "Spring Boot " + id);
            boolean official = !Boolean.FALSE.equals(map.get("official"));
            List<String> javaIds = toStringList(map.get("supportedJavaVersions"), "bootVersions[].supportedJavaVersions");
            List<String> starters = toStringList(map.get("starters"), "bootVersions[].starters");
            result.add(new BootVersion(id, label, official, javaIds, starters));
        }
        return result;
    }

    /**
     * 解析 starters 节：starter ID → {label, dependency{groupId, artifactId}}。
     *
     * @param node starters 节点（Map 结构；缺失时为空清单）
     * @return starter 定义清单（按配置顺序）
     * @throws BusinessException 结构非法或缺少必需字段（错误码 {@link ErrorCode#INTERNAL_ERROR}）
     */
    @SuppressWarnings("unchecked")
    private static List<StarterDefinition> parseStarters(Object node) {
        if (node == null) {
            return List.of();
        }
        if (!(node instanceof Map)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "metadata.yml 字段类型非法: starters");
        }
        List<StarterDefinition> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) node).entrySet()) {
            String id = entry.getKey();
            if (!(entry.getValue() instanceof Map)) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "metadata.yml 字段类型非法: starters." + id);
            }
            Map<String, Object> map = (Map<String, Object>) entry.getValue();
            String label = requireString(map, "label", "starters." + id + ".label");
            if (!(map.get("dependency") instanceof Map)) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "metadata.yml 缺少必需字段: starters." + id + ".dependency");
            }
            Map<String, Object> dep = (Map<String, Object>) map.get("dependency");
            String groupId = requireString(dep, "groupId", "starters." + id + ".dependency.groupId");
            String artifactId = requireString(dep, "artifactId", "starters." + id + ".dependency.artifactId");
            result.add(new StarterDefinition(id, label, new MavenDependency(groupId, artifactId)));
        }
        return result;
    }

    private static void requireList(Object node, String field) {
        if (!(node instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "metadata.yml 缺少必需字段: " + field);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Object node, String field) {
        if (!(node instanceof Map)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "metadata.yml 字段类型非法: " + field);
        }
        return (Map<String, Object>) node;
    }

    private static String requireString(Map<String, Object> map, String key, String field) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "metadata.yml 缺少必需字段: " + field);
        }
        return value.toString();
    }

    private static String stringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

    private static String stringOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static List<String> toStringList(Object node, String field) {
        if (node == null) {
            return List.of();
        }
        if (!(node instanceof List<?> list)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "metadata.yml 字段类型非法: " + field);
        }
        return list.stream().map(String::valueOf).toList();
    }
}
