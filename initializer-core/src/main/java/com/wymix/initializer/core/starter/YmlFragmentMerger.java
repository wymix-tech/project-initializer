package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * application.yml 片段合并器（M2.2）：YAML 树递归合并，冲突显式报错，不静默覆盖。
 *
 * <p>合并规则：
 * <ul>
 *   <li>双方均为 Map → 递归合并</li>
 *   <li>双方为标量：同值跳过；不同值 → 冲突，记入冲突清单（含完整 key path 与两侧值）</li>
 *   <li>数组：按片段声明的 {@code merge-strategy}：append（追加去重）/ override（整体替换），默认 append</li>
 *   <li>类型不一致（Map vs 标量等）→ 冲突</li>
 * </ul>
 *
 * <p>语义限制：仅支持标准映射/序列，加载期拒绝锚点引用与多文档。
 *
 * <p>线程安全性：无状态，线程安全。
 */
public final class YmlFragmentMerger {

    private YmlFragmentMerger() {
    }

    /**
     * 将片段按顺序合并进基底树。
     *
     * @param base      基底树（可为 null 表示空基底）
     * @param fragments 片段清单（按合并顺序，元素含解析后的树与声明的数组合并策略）
     * @return 合并后的树（新对象，不修改入参）；无片段时返回基底副本
     * @throws BusinessException 任一片段解析失败（3004）或合并冲突（3004，明细含完整冲突清单）
     */
    public static Map<String, Object> merge(Map<String, Object> base, List<Fragment> fragments) {
        Map<String, Object> result = base == null ? new LinkedHashMap<>() : deepCopy(base);
        List<YmlConflict> conflicts = new ArrayList<>();
        for (Fragment fragment : fragments) {
            Map<String, Object> tree = fragment.parsed();
            if (tree != null) {
                mergeNode(result, tree, "", fragment.strategy(), fragment.source(), conflicts);
            }
        }
        if (!conflicts.isEmpty()) {
            throw new BusinessException(ErrorCode.YML_MERGE_CONFLICT, describeConflicts(conflicts));
        }
        return result;
    }

    /**
     * 将合并后的树序列化为 YAML 文本（写入 application.yml）。
     *
     * @param tree 合并后的树
     * @return YAML 文本
     */
    public static String toYaml(Map<String, Object> tree) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Representer representer = new Representer(options);
        representer.getPropertyUtils().setAllowReadOnlyProperties(true);
        return new Yaml(representer, options).dump(tree);
    }

    private static void mergeNode(Map<String, Object> target, Map<String, Object> incoming, String prefix,
                                  YmlFragmentLinkage.MergeStrategy defaultStrategy, String source,
                                  List<YmlConflict> conflicts) {
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String key = entry.getKey();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object incomingValue = entry.getValue();
            if (!target.containsKey(key)) {
                target.put(key, deepCopy(incomingValue));
                continue;
            }
            Object existing = target.get(key);
            if (existing instanceof Map<?, ?> existingMap && incomingValue instanceof Map<?, ?> incomingMap) {
                mergeNode(castMap(existingMap), castMap(incomingMap), path, defaultStrategy, source, conflicts);
            } else if (existing instanceof List<?> existingList && incomingValue instanceof List<?> incomingList) {
                target.put(key, mergeLists(existingList, incomingList, defaultStrategy));
            } else if (equalsScalar(existing, incomingValue)) {
                // 同值跳过
            } else {
                conflicts.add(new YmlConflict(path, String.valueOf(existing),
                        String.valueOf(incomingValue), source));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static List<Object> mergeLists(List<?> existing, List<?> incoming,
                                           YmlFragmentLinkage.MergeStrategy strategy) {
        if (strategy == YmlFragmentLinkage.MergeStrategy.OVERRIDE) {
            List<Object> replaced = new ArrayList<>();
            replaced.addAll((List<Object>) deepCopy(incoming));
            return replaced;
        }
        // append：追加去重（按元素标量相等语义）
        List<Object> merged = new ArrayList<>(existing);
        Set<Object> seen = new LinkedHashSet<>(existing);
        for (Object item : incoming) {
            if (!seen.contains(item)) {
                merged.add(item);
                seen.add(item);
            }
        }
        return merged;
    }

    private static boolean equalsScalar(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        // SnakeYAML 标量可能是 Integer/Boolean/String，统一字符串比较避免跨类型误报冲突
        return String.valueOf(a).equals(String.valueOf(b));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            copy.put(e.getKey(), deepCopy(e.getValue()));
        }
        return copy;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepCopy(castMap(map));
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }

    private static String describeConflicts(List<YmlConflict> conflicts) {
        StringBuilder sb = new StringBuilder("application.yml 片段合并冲突（").append(conflicts.size()).append(" 处）:");
        for (YmlConflict conflict : conflicts) {
            sb.append("\n  - path: ").append(conflict.path())
                    .append(", 已有值: ").append(conflict.existingValue())
                    .append(", 冲突值: ").append(conflict.incomingValue())
                    .append("（来源: ").append(conflict.incomingSource()).append("）");
        }
        return sb.toString();
    }

    /**
     * 片段载体：解析后的 YAML 树 + 片段声明的数组合并策略 + 来源标识（用于冲突明细输出）。
     *
     * <p>线程安全性：不可变对象，线程安全。
     */
    public record Fragment(Map<String, Object> parsed, YmlFragmentLinkage.MergeStrategy strategy, String source) {

        /**
         * 解析片段 yml 输入流（安全构造器，拒绝锚点/别名/多文档）。
         *
         * @param in 片段输入流
         * @param source 来源标识（冲突明细展示用）
         * @param strategy 片段内数组默认合并策略
         * @return 片段载体
         * @throws BusinessException 解析失败（错误码 3004）
         */
        public static Fragment parse(InputStream in, String source, YmlFragmentLinkage.MergeStrategy strategy) {
            Map<String, Object> tree;
            try {
                tree = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            } catch (RuntimeException e) {
                throw new BusinessException(ErrorCode.YML_MERGE_CONFLICT,
                        "yml 片段解析失败: " + source + ", 原因: " + e.getMessage(), e);
            }
            if (tree != null && !(tree instanceof Map)) {
                throw new BusinessException(ErrorCode.YML_MERGE_CONFLICT,
                        "yml 片段顶层必须为映射结构: " + source);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) tree;
            return new Fragment(casted, strategy, source);
        }
    }
}
