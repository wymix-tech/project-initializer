package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.scanner.ScannerException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Pattern;

/**
 * 单 starter yml 解析器：基于 SnakeYAML 安全构造器映射为 {@link StarterCatalogEntry}。
 *
 * <p>安全约束：拒绝锚点引用与多文档（技术方案风险项）——多文档由 SnakeYAML load 抛出，
 * 锚点/别名由 {@link SafeConstructor} 天然支持但属语义限制，此处显式拒绝含别名节点的文档。
 * 只做结构映射与类型收敛，清单级校验由 {@link StarterCatalog} 负责。
 *
 * <p>线程安全性：无状态，线程安全。
 */
public final class YamlStarterParser {

    private YamlStarterParser() {
    }

    /** YAML 锚点定义（&name）模式：仅匹配空白或流上下文指示符后的锚点声明。 */
    private static final Pattern ANCHOR_PATTERN = Pattern.compile("(^|[\\s,\\[{])&[A-Za-z0-9_-]+");

    /** YAML 别名引用（*name）模式：匹配键指示符或空白后的别名引用。 */
    private static final Pattern ALIAS_PATTERN = Pattern.compile("(:\\s|^\\s*)\\*[A-Za-z0-9_-]+");

    /**
     * 解析单个 starter yml。
     *
     * @param yaml yml 输入流
     * @return 清单条目
     * @throws BusinessException 结构非法 / 缺少必需字段 / 含锚点别名（错误码 {@link ErrorCode#STARTER_CATALOG_INVALID}）
     */
    public static StarterCatalogEntry parse(InputStream yaml) {
        String text = readText(yaml);
        // 语义限制（技术方案风险项）：合并器不支持锚点引用，加载期显式拒绝
        if (ANCHOR_PATTERN.matcher(text).find() || ALIAS_PATTERN.matcher(text).find()) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    "starter yml 不支持锚点引用（&anchor / *alias）");
        }
        Map<String, Object> root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (ScannerException e) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    "starter yml 解析失败（多文档等语法错误）: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    "starter yml 解析失败: " + e.getMessage(), e);
        }
        if (root == null) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID, "starter yml 内容为空");
        }
        if (!(root instanceof Map)) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID, "starter yml 顶层必须为映射结构");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) root;
        return parseEntry(map);
    }

    private static String readText(InputStream yaml) {
        try (Scanner scanner = new Scanner(yaml, StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private static StarterCatalogEntry parseEntry(Map<String, Object> map) {
        String id = requireString(map, "id");
        String category = requireString(map, "category");
        Maintainer maintainer = parseMaintainer(requireString(map, "maintainer"), id);
        String description = stringOrDefault(map, "description", "");
        List<DependencyCoord> dependencies = parseDependencies(map.get("dependencies"), id);
        LinkageSpec linkages = parseLinkages(map.get("linkages"), id);
        boolean required = Boolean.TRUE.equals(map.get("required"));
        List<List<String>> requires = parseRequires(map.get("requires"), id);
        List<String> bootVersions = parseBootVersions(map, id);
        boolean hidden = Boolean.TRUE.equals(map.get("hidden"));
        return new StarterCatalogEntry(id, category, maintainer, description, dependencies, linkages,
                required, requires, bootVersions, hidden);
    }

    /**
     * 解析 Boot 版本关联（可选）：{@code bootVersions: ["3.2.5"]} 或 {@code bootVersion: "3.2.5"}；
     * 未声明表示对全部 Boot 版本可用。
     */
    private static List<String> parseBootVersions(Map<String, Object> map, String starterId) {
        Object node = map.get("bootVersions");
        if (node == null) {
            node = map.get("bootVersion");
        }
        if (node == null) {
            return List.of();
        }
        if (node instanceof String single) {
            return single.isBlank() ? List.of() : List.of(single.trim());
        }
        if (!(node instanceof List<?> list)) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    "starter " + starterId + " 字段类型非法: bootVersions（必须为列表或字符串）");
        }
        return list.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * 解析 {@code requires}（可选）：AND-of-OR-groups 依赖联动。
     *
     * <p>接受三种写法（混合时拒收）：
     * <ul>
     *   <li>扁平字符串列表 {@code [B, C]} — 兼容旧写法，按 AND 解析为 {@code [[B], [C]]}</li>
     *   <li>列表的列表 {@code [[B, C]]} — OR 组：任一被选即满足</li>
     *   <li>单字符串 {@code B} — 等价于 {@code [[B]]}</li>
     * </ul>
     *
     * <p>装载期校验在 {@link StarterCatalog#validateRequires(List)} 中进行（悬空 / 自引用 /
     * 循环依赖 / 跨 Boot 版本作用域），本方法只负责结构归一化。
     */
    @SuppressWarnings("unchecked")
    private static List<List<String>> parseRequires(Object node, String starterId) {
        if (node == null) {
            return List.of();
        }
        if (node instanceof String single) {
            String id = single.trim();
            if (id.isEmpty()) {
                throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                        "starter " + starterId + " requires 条目为空字符串");
            }
            return List.of(List.of(id));
        }
        if (!(node instanceof List<?> list)) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    "starter " + starterId + " requires 字段类型非法（必须为字符串或列表）");
        }
        if (list.isEmpty()) {
            return List.of();
        }
        // 推断是否走扁平（每个元素是 String）还是嵌套（每个元素是 List）模式；混合则拒收
        boolean allStrings = list.stream().allMatch(e -> e instanceof String);
        boolean allLists = list.stream().allMatch(e -> e instanceof List<?>);
        if (!allStrings && !allLists) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    "starter " + starterId + " requires 元素类型必须全部为字符串或全部为列表，不支持混合");
        }
        List<List<String>> groups = new ArrayList<>(list.size());
        if (allStrings) {
            for (Object elem : list) {
                String id = ((String) elem).trim();
                if (id.isEmpty()) {
                    throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                            "starter " + starterId + " requires 条目为空字符串");
                }
                groups.add(List.of(id));
            }
        } else {
            for (Object elem : list) {
                List<?> inner = (List<?>) elem;
                if (inner.isEmpty()) {
                    throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                            "starter " + starterId + " requires 的 OR 组不能为空列表");
                }
                List<String> group = new ArrayList<>(inner.size());
                for (Object i : inner) {
                    if (!(i instanceof String)) {
                        throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                                "starter " + starterId + " requires 的 OR 组内必须全部为字符串");
                    }
                    String id = ((String) i).trim();
                    if (id.isEmpty()) {
                        throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                                "starter " + starterId + " requires 的 OR 组内含空字符串");
                    }
                    group.add(id);
                }
                groups.add(List.copyOf(group));
            }
        }
        return List.copyOf(groups);
    }

    private static Maintainer parseMaintainer(String value, String starterId) {
        try {
            return Maintainer.valueOf(value.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    "starter " + starterId + " maintainer 非法: " + value + "（仅允许 official/internal）", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<DependencyCoord> parseDependencies(Object node, String starterId) {
        requireList(node, "dependencies", starterId);
        List<DependencyCoord> result = new ArrayList<>();
        int i = 0;
        for (Object item : (List<Object>) node) {
            String field = "dependencies[" + i + "]";
            Map<String, Object> map = requireMap(item, field, starterId);
            result.add(new DependencyCoord(
                    requireString(map, "groupId", field),
                    requireString(map, "artifactId", field),
                    stringOrNull(map, "version")));
            i++;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static LinkageSpec parseLinkages(Object node, String starterId) {
        if (node == null) {
            return new LinkageSpec(null, List.of(), List.of(), List.of());
        }
        if (!(node instanceof Map)) {
            throw typeError("linkages", starterId);
        }
        Map<String, Object> map = (Map<String, Object>) node;
        return new LinkageSpec(
                stringOrNull(map, "config-package"),
                parseTemplates(map.get("templates"), starterId),
                parseYmlFragments(map.get("application-yml-fragments"), starterId),
                parseAbstractClasses(map.get("abstract-classes"), starterId));
    }

    @SuppressWarnings("unchecked")
    private static List<TemplateLinkage> parseTemplates(Object node, String starterId) {
        if (node == null) {
            return List.of();
        }
        requireList(node, "linkages.templates", starterId);
        List<TemplateLinkage> result = new ArrayList<>();
        int i = 0;
        for (Object item : (List<Object>) node) {
            String field = "linkages.templates[" + i + "]";
            Map<String, Object> map = requireMap(item, field, starterId);
            result.add(new TemplateLinkage(
                    requireString(map, "source", field), requireString(map, "target", field)));
            i++;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<YmlFragmentLinkage> parseYmlFragments(Object node, String starterId) {
        if (node == null) {
            return List.of();
        }
        requireList(node, "linkages.application-yml-fragments", starterId);
        List<YmlFragmentLinkage> result = new ArrayList<>();
        int i = 0;
        for (Object item : (List<Object>) node) {
            String field = "linkages.application-yml-fragments[" + i + "]";
            Map<String, Object> map = requireMap(item, field, starterId);
            String strategyRaw = stringOrDefault(map, "merge-strategy", "append");
            YmlFragmentLinkage.MergeStrategy strategy;
            try {
                strategy = YmlFragmentLinkage.MergeStrategy.valueOf(strategyRaw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                        "starter " + starterId + " " + field + ".merge-strategy 非法: "
                                + strategyRaw + "（仅允许 append/override）", e);
            }
            result.add(new YmlFragmentLinkage(requireString(map, "source", field), strategy));
            i++;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractClassLinkage> parseAbstractClasses(Object node, String starterId) {
        if (node == null) {
            return List.of();
        }
        requireList(node, "linkages.abstract-classes", starterId);
        List<AbstractClassLinkage> result = new ArrayList<>();
        int i = 0;
        for (Object item : (List<Object>) node) {
            String field = "linkages.abstract-classes[" + i + "]";
            Map<String, Object> map = requireMap(item, field, starterId);
            result.add(new AbstractClassLinkage(
                    requireString(map, "fqcn", field), requireString(map, "alias", field)));
            i++;
        }
        return result;
    }

    private static void requireList(Object node, String field, String starterId) {
        if (!(node instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    "starter " + starterId + " 缺少必需字段: " + field);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Object node, String field, String starterId) {
        if (!(node instanceof Map)) {
            throw typeError(field, starterId);
        }
        return (Map<String, Object>) node;
    }

    private static BusinessException typeError(String field, String starterId) {
        return new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                "starter " + starterId + " 字段类型非法: " + field);
    }

    private static String requireString(Map<String, Object> map, String key) {
        return requireString(map, key, null);
    }

    private static String requireString(Map<String, Object> map, String key, String fieldPrefix) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            String path = fieldPrefix == null ? key : fieldPrefix + "." + key;
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    "starter yml 缺少必需字段: " + path);
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
}
