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

    /** 解析结果载体：Java 版本与 Boot 版本（starter 元信息全部来自 starter 清单，经 merge 联动）。 */
    record ParsedMetadata(List<JavaVersion> javaVersions, List<BootVersion> bootVersions) {
    }

    /**
     * 解析 metadata.yml 输入流。
     *
     * <p>starter 相关信息（展示名/坐标/必选/依赖联动）**全部由 starter 清单提供**，
     * 本文件不再维护 starters 节；bootVersions[].starters 也可省略
     * （省略即该版本对全部清单条目可选，由 {@link MetadataCatalog#merge} 填充）。
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
        return new ParsedMetadata(parseJavaVersions(root.get("javaVersions")),
                parseBootVersions(root.get("bootVersions"), parseFrameworkParent(root.get("frameworkParent"))));
    }

    /**
     * 解析 {@code frameworkParent} 节（可选）：生成 {@code pom.xml} 的 {@code <parent>} 坐标。
     * 声明位置：每个 {@code bootVersions[]} 条目内（不同 Boot 版本可挂不同框架父 POM）；
     * 顶层声明为全局默认，条目内未声明时继承。
     * 三字段任一缺失视为未配置（返回 null，由生成器回退默认 spring-boot-starter-parent）。
     *
     * @param node frameworkParent 节点
     * @return FrameworkParent；未配置返回 null
     */
    @SuppressWarnings("unchecked")
    private static FrameworkParent parseFrameworkParent(Object node) {
        if (node == null) {
            return null;
        }
        if (!(node instanceof Map)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "metadata.yml 字段类型非法: frameworkParent");
        }
        Map<String, Object> map = (Map<String, Object>) node;
        String groupId = stringOrNull(map, "groupId");
        String artifactId = stringOrNull(map, "artifactId");
        String version = stringOrNull(map, "version");
        if (groupId == null || artifactId == null || version == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "metadata.yml frameworkParent 字段不完整，需 groupId/artifactId/version 三项齐全");
        }
        return new FrameworkParent(groupId, artifactId, version);
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

    /**
     * 解析 bootVersions 节：每个条目内可独立声明 {@code frameworkParent}（可选），
     * 未声明时继承全局默认（顶层 frameworkParent，也可为 null）——
     * 不同 Boot 版本可挂接不同框架父 POM。
     *
     * @param node           bootVersions 节点
     * @param globalFallback 顶层 frameworkParent（全局默认，可 null）
     * @return Boot 版本清单
     */
    @SuppressWarnings("unchecked")
    private static List<BootVersion> parseBootVersions(Object node, FrameworkParent globalFallback) {
        requireList(node, "bootVersions");
        List<BootVersion> result = new ArrayList<>();
        for (Object item : (List<Object>) node) {
            Map<String, Object> map = requireMap(item, "bootVersions[]");
            String id = requireString(map, "id", "bootVersions[].id");
            String label = stringOrDefault(map, "label", "Spring Boot " + id);
            boolean official = !Boolean.FALSE.equals(map.get("official"));
            List<String> javaIds = toStringList(map.get("supportedJavaVersions"), "bootVersions[].supportedJavaVersions");
            List<String> starters = toStringList(map.get("starters"), "bootVersions[].starters");
            FrameworkParent parent = parseFrameworkParent(map.get("frameworkParent"));
            if (parent == null) {
                parent = globalFallback;
            }
            result.add(new BootVersion(id, label, official, javaIds, starters, parent));
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
