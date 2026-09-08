package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * starter 清单：启动时全量加载 {@code catalog/starters/*.yml}，提供联动规则查询。
 *
 * <p>与 {@link com.wymix.initializer.core.metadata.MetadataCatalog} 职责互补：
 * MetadataCatalog 管「版本兼容矩阵 + starter 可用性」，本类管「starter 联动规则」。
 *
 * <p>**Boot 版本关联与唯一性**（与 metadata 的 {@code bootVersions[].id} 关联）：
 * <ul>
 *   <li>条目可声明 {@code bootVersions}（不声明即对全部版本可用）</li>
 *   <li>**同一 Boot 版本内 starter ID 不允许重复**（重复即 9002）</li>
 *   <li>**不同 Boot 版本之间允许重复**——同一 starter 在不同 Boot 版本可有不同坐标/版本/联动</li>
 * </ul>
 *
 * <p>装载期校验（任一失败抛 9002 启动失败）：
 * <ul>
 *   <li>ID 重复 / ID 为空</li>
 *   <li>占位符越界（仅允许 {@link PlaceholderPolicy} 白名单）</li>
 *   <li>category 非空、maintainer 枚举合法、dependencies 非空</li>
 * </ul>
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public class StarterCatalog {

    /** 未声明 bootVersions 的条目：对全部 Boot 版本可用，按 ID 索引。 */
    private final Map<String, StarterCatalogEntry> globalEntries = new LinkedHashMap<>();
    /** 声明了 bootVersions 的条目：Boot 版本 ID →（ID → 条目）。 */
    private final Map<String, Map<String, StarterCatalogEntry>> byBootVersion = new LinkedHashMap<>();

    /**
     * 装载 starter 清单。
     *
     * @param parsed 解析结果（按配置文件顺序）
     * @return starter 清单实例
     * @throws BusinessException 装载校验失败（错误码 {@link ErrorCode#STARTER_CATALOG_INVALID}）
     */
    public static StarterCatalog of(List<StarterCatalogEntry> parsed) {
        return new StarterCatalog(parsed);
    }

    private StarterCatalog(List<StarterCatalogEntry> parsed) {
        for (StarterCatalogEntry entry : parsed) {
            validateEntry(entry);
            if (entry.bootVersions().isEmpty()) {
                // 全版本条目：全局唯一（对任一 Boot 版本都会生效，同 ID 即冲突）
                if (globalEntries.putIfAbsent(entry.id(), entry) != null) {
                    throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                            "starter 清单 ID 重复: " + entry.id());
                }
                continue;
            }
            for (String bootVersionId : entry.bootVersions()) {
                Map<String, StarterCatalogEntry> scoped = byBootVersion
                        .computeIfAbsent(bootVersionId, k -> new LinkedHashMap<>());
                // 同一 Boot 版本内 ID 必须唯一；不同 Boot 版本允许重复
                if (scoped.putIfAbsent(entry.id(), entry) != null) {
                    throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                            "Boot 版本 " + bootVersionId + " 下 starter 清单 ID 重复: " + entry.id());
                }
            }
        }
        validateRequires(parsed);
    }

    private void validateEntry(StarterCatalogEntry entry) {
        if (entry.id() == null || entry.id().isBlank()) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID, "starter 清单缺少必需字段: id");
        }
        String prefix = "starters/" + entry.id();
        if (entry.category() == null || entry.category().isBlank()) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    prefix + " 缺少必需字段: category");
        }
        if (entry.maintainer() == null) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    prefix + " 缺少必需字段: maintainer（official/internal）");
        }
        if (entry.dependencies().isEmpty()) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    prefix + " 缺少必需字段: dependencies（至少声明一个依赖坐标）");
        }
        for (DependencyCoord dep : entry.dependencies()) {
            if (isBlank(dep.groupId()) || isBlank(dep.artifactId())) {
                throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                        prefix + " 依赖坐标缺少 groupId/artifactId");
            }
        }
        LinkageSpec linkages = entry.linkages();
        if (linkages == null) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID, prefix + " 缺少必需字段: linkages");
        }
        validatePlaceholders(prefix + ".linkages.config-package", linkages.configPackage());
        for (TemplateLinkage t : linkages.templates()) {
            if (isBlank(t.source()) || isBlank(t.target())) {
                throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                        prefix + ".linkages.templates 条目缺少 source/target");
            }
            validatePlaceholders(prefix + ".linkages.templates[].target", t.target());
        }
    }

    private void validatePlaceholders(String field, String value) {
        if (value == null) {
            return;
        }
        String unknown = String.join(", ", PlaceholderPolicy.unknownPlaceholders(value));
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    field + " 存在越界占位符 " + unknown + "（仅允许 " + PlaceholderPolicy.allowed() + "）");
        }
    }

    /**
     * 依赖联动（requires）合法性校验：悬空引用、自引用、循环依赖一律拒绝（配置错误早暴露）。
     *
     * @param parsed 全部清单条目
     * @throws BusinessException 引用未定义 / 自引用 / 存在环（9002）
     */
    private void validateRequires(List<StarterCatalogEntry> parsed) {
        List<String> bootVersionIds = List.copyOf(byBootVersion.keySet());
        for (StarterCatalogEntry entry : parsed) {
            for (String dep : entry.requires()) {
                if (dep.equals(entry.id())) {
                    throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                            "starter " + entry.id() + " 的 requires 不能引用自身: " + dep);
                }
                if (!contains(dep)) {
                    throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                            "starter " + entry.id() + " 的 requires 引用了未登记的 starter: " + dep);
                }
                // 作用域校验：依赖必须在本条目可见的每个 Boot 版本维度内都可解析。
                // 仅做全局 contains 校验会留下「装载通过但生成期按 Boot 版本取不到」的缺口
                // （entry(bootVersionId, id) 只在该版本专属条目或全版本条目中查找）。
                for (String scope : visibleScopes(entry, bootVersionIds)) {
                    if (!resolvableIn(scope, dep)) {
                        throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                                "starter " + entry.id() + " 的 requires 引用了 " + dep
                                        + "，但后者在 Boot 版本 " + scope + " 下未登记");
                    }
                }
            }
        }
        Map<String, Integer> state = new LinkedHashMap<>();
        for (StarterCatalogEntry entry : parsed) {
            visitRequires(entry.id(), state, new LinkedHashSet<>());
        }
    }

    /** 条目可见的 Boot 版本维度：声明了版本即这些版本，未声明（全版本条目）即全部已声明版本。 */
    private static List<String> visibleScopes(StarterCatalogEntry entry, List<String> bootVersionIds) {
        return entry.bootVersions().isEmpty() ? bootVersionIds : entry.bootVersions();
    }

    /** 指定 Boot 版本维度内能否解析出该 starter（版本专属条目或全版本条目）。 */
    private boolean resolvableIn(String bootVersionId, String starterId) {
        Map<String, StarterCatalogEntry> scoped = byBootVersion.get(bootVersionId);
        return (scoped != null && scoped.containsKey(starterId)) || globalEntries.containsKey(starterId);
    }

    /** requires 有向图 DFS：0/未访问 1/访问中 2/已完成，回边即环。 */
    private void visitRequires(String id, Map<String, Integer> state, LinkedHashSet<String> path) {
        Integer visited = state.get(id);
        if (visited != null && visited == 2) {
            return;
        }
        if (visited != null && visited == 1) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    "starter requires 存在循环依赖: " + String.join(" -> ", path) + " -> " + id);
        }
        state.put(id, 1);
        path.add(id);
        StarterCatalogEntry entry = entry(id);
        if (entry != null) {
            for (String dep : entry.requires()) {
                visitRequires(dep, state, path);
            }
        }
        path.remove(id);
        state.put(id, 2);
    }

    /**
     * 按 ID + Boot 版本查询清单条目（版本专属条目优先于全版本条目）。
     *
     * @param bootVersionId Boot 版本 ID
     * @param starterId     starter ID
     * @return 清单条目
     * @throws BusinessException 未登记（错误码 {@link ErrorCode#STARTER_CATALOG_INVALID}）
     */
    public StarterCatalogEntry entry(String bootVersionId, String starterId) {
        Map<String, StarterCatalogEntry> scoped = byBootVersion.get(bootVersionId);
        if (scoped != null && scoped.containsKey(starterId)) {
            return scoped.get(starterId);
        }
        return entry(starterId);
    }

    /**
     * 按 ID 查询清单条目（忽略 Boot 版本：优先全版本条目，其次任一版本条目）。
     *
     * @param starterId starter ID
     * @return 清单条目
     * @throws BusinessException 未登记（错误码 {@link ErrorCode#STARTER_CATALOG_INVALID}）
     */
    public StarterCatalogEntry entry(String starterId) {
        StarterCatalogEntry global = globalEntries.get(starterId);
        if (global != null) {
            return global;
        }
        for (Map<String, StarterCatalogEntry> scoped : byBootVersion.values()) {
            StarterCatalogEntry candidate = scoped.get(starterId);
            if (candidate != null) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID, "starter 清单未登记: " + starterId);
    }

    /** @return 指定 Boot 版本下是否登记了该 starter */
    public boolean contains(String bootVersionId, String starterId) {
        Map<String, StarterCatalogEntry> scoped = byBootVersion.get(bootVersionId);
        if (scoped != null && scoped.containsKey(starterId)) {
            return true;
        }
        return globalEntries.containsKey(starterId);
    }

    /** @return 是否登记了指定 starter（任一 Boot 版本或全版本条目） */
    public boolean contains(String starterId) {
        return globalEntries.containsKey(starterId)
                || byBootVersion.values().stream().anyMatch(scoped -> scoped.containsKey(starterId));
    }

    /**
     * 指定 Boot 版本下可用的全部条目（全版本条目 + 该版本专属条目；同 ID 以版本专属优先）。
     *
     * @param bootVersionId Boot 版本 ID
     * @return 条目清单（按装载顺序，不可变）
     */
    public List<StarterCatalogEntry> allForBoot(String bootVersionId) {
        Map<String, StarterCatalogEntry> scoped = byBootVersion.getOrDefault(bootVersionId, Map.of());
        List<StarterCatalogEntry> result = new ArrayList<>();
        for (Map.Entry<String, StarterCatalogEntry> e : scoped.entrySet()) {
            result.add(e.getValue());
        }
        for (StarterCatalogEntry global : globalEntries.values()) {
            if (!scoped.containsKey(global.id())) {
                result.add(global);
            }
        }
        return List.copyOf(result);
    }

    /** @return 全量清单条目（跨 Boot 版本按 ID 去重，全版本条目优先；按装载顺序，不可变） */
    public List<StarterCatalogEntry> all() {
        Map<String, StarterCatalogEntry> merged = new LinkedHashMap<>(globalEntries);
        for (Map<String, StarterCatalogEntry> scoped : byBootVersion.values()) {
            for (Map.Entry<String, StarterCatalogEntry> e : scoped.entrySet()) {
                merged.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return List.copyOf(merged.values());
    }

    /** @return 已登记 starter ID 数量（跨 Boot 版本去重） */
    public int size() {
        return all().size();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
