package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * starter 清单：启动时全量加载 {@code catalog/starters/*.yml}，提供联动规则查询。
 *
 * <p>与 {@link com.wymix.initializer.core.metadata.MetadataCatalog} 职责互补：
 * MetadataCatalog 管「版本兼容矩阵 + starter 可用性」，本类管「starter 联动规则」。
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

    private final Map<String, StarterCatalogEntry> entries = new LinkedHashMap<>();

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
            if (entries.putIfAbsent(entry.id(), entry) != null) {
                throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                        "starter 清单 ID 重复: " + entry.id());
            }
        }
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 按 ID 查询 starter 清单条目。
     *
     * @param starterId starter ID
     * @return 清单条目
     * @throws BusinessException 未登记（错误码 {@link ErrorCode#STARTER_CATALOG_INVALID}）
     */
    public StarterCatalogEntry entry(String starterId) {
        StarterCatalogEntry entry = entries.get(starterId);
        if (entry == null) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID, "starter 清单未登记: " + starterId);
        }
        return entry;
    }

    /** @return 是否登记了指定 starter */
    public boolean contains(String starterId) {
        return entries.containsKey(starterId);
    }

    /** @return 全量清单条目（按装载顺序，不可变） */
    public List<StarterCatalogEntry> all() {
        return List.copyOf(entries.values());
    }

    /** @return 已登记 starter ID 数量 */
    public int size() {
        return entries.size();
    }
}
