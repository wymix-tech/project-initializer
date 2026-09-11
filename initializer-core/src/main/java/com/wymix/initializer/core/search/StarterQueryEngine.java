package com.wymix.initializer.core.search;

import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.metadata.StarterDefinition;
import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.starter.StarterCatalog;

import java.util.List;
import java.util.Locale;

/**
 * starter 清单内存检索引擎（M3.4）：按关键字与分类过滤。
 *
 * <p>纯内存计算，无 IO 与外部依赖：输入生效快照（{@link MetadataCatalog} 提供
 * 可用 starter 的 ID 与展示名，{@link StarterCatalog} 提供分类/描述/维护方），
 * 输出过滤后的检索结果条目。
 *
 * <p>过滤语义：
 * <ul>
 *   <li>{@code q}：对 starter ID、展示名与描述做忽略大小写的 contains 匹配；空/空白视为不过滤</li>
 *   <li>{@code category}：与 starter 清单条目的 category 精确匹配（忽略大小写）；
 *       清单未登记的条目不参与分类过滤（未登记即视为无分类信息）；未知分类自然返回空结果，不报错</li>
 * </ul>
 *
 * <p>线程安全性：无状态（输入快照不可变），线程安全。
 */
public class StarterQueryEngine {

    /**
     * 检索结果条目：starter 展示信息 + 分类/维护方标注。
     *
     * @param id          starter ID
     * @param label       展示名
     * @param maintainer  维护方（official / internal）
     * @param category    分类；清单未登记时为 {@code null}
     * @param description 描述；清单未登记时为 {@code null}
     * @param version     基线版本：清单登记依赖坐标首个声明的 version；未登记或未声明版本时为 {@code null}
     *                    （官方 starter 走 Boot BOM 不声明版本，内部 starter 声明基线版本如 1.2.0）
     * @param requires    依赖分组（AND-of-OR-groups）：分组内任一被选即满足；
     *                    单元素分组视为强制依赖（前端自动选中并锁定），多元素分组仅提示不自动勾选
     * @param hidden      是否在前端依赖选择列表中默认隐藏（被依赖引用时强制展示）；清单未登记时为 {@code false}
     */
    public record StarterSearchItem(
            String id,
            String label,
            Maintainer maintainer,
            String category,
            String description,
            String version,
            boolean required,
            List<List<String>> requires,
            boolean hidden) {

        /**
         * 兼容构造：未声明约束时视为可选、无依赖、不隐藏。
         */
        public StarterSearchItem(String id, String label, Maintainer maintainer, String category,
                                 String description, String version) {
            this(id, label, maintainer, category, description, version, false, List.of(), false);
        }

        /**
         * 兼容构造：仅声明必选标记，无依赖联动、不隐藏。
         */
        public StarterSearchItem(String id, String label, Maintainer maintainer, String category,
                                 String description, String version, boolean required) {
            this(id, label, maintainer, category, description, version, required, List.of(), false);
        }
    }

    /**
     * 按 Boot 版本可用清单 + 检索条件过滤。
     *
     * @param catalog       生效元数据目录（提供 Boot 版本下可用 starter 及展示名）
     * @param starterCatalog 生效 starter 清单（提供分类/描述/维护方）
     * @param bootVersionId Boot 版本 ID（可用性过滤基准）
     * @param q             关键字（可空）：匹配 ID/展示名，忽略大小写
     * @param category      分类（可空）：精确匹配，忽略大小写
     * @return 过滤后的结果清单（按可用清单顺序，不可变）
     * @throws com.wymix.initializer.api.error.BusinessException Boot 版本未知（错误码 2002，由目录抛出）
     */
    public List<StarterSearchItem> search(MetadataCatalog catalog, StarterCatalog starterCatalog,
                                          String bootVersionId, String q, String category) {
        String keyword = normalize(q);
        String categoryFilter = normalize(category);
        return catalog.starters(bootVersionId).stream()
                .map(id -> toItem(catalog, starterCatalog, bootVersionId, id))
                .filter(item -> item != null)
                .filter(item -> matchesKeyword(item, keyword))
                .filter(item -> matchesCategory(item, categoryFilter))
                .toList();
    }

    /**
     * 构造检索条目；starter 无定义（清单未登记的遗留 id）时返回 {@code null} 并被过滤——
     * starter 元信息全部来自 starter 清单，未登记即在可选清单中不可见。
     */
    private StarterSearchItem toItem(MetadataCatalog catalog, StarterCatalog starterCatalog,
                                     String bootVersionId, String starterId) {
        StarterDefinition sd = catalog.starterDefinitionOrNull(starterId);
        if (sd == null) {
            return null;
        }
        // 按 Boot 版本关联解析清单条目（同 ID 在不同版本可有不同坐标/版本）
        boolean known = starterCatalog.contains(bootVersionId, starterId);
        Maintainer maintainer = known
                ? starterCatalog.entry(bootVersionId, starterId).maintainer()
                : Maintainer.OFFICIAL;
        String category = known
                ? starterCatalog.entry(bootVersionId, starterId).category()
                : null;
        String description = known
                ? starterCatalog.entry(bootVersionId, starterId).description()
                : null;
        String version = known
                ? baselineVersion(starterCatalog.entry(bootVersionId, starterId).dependencies())
                : null;
        boolean hidden = known && starterCatalog.entry(bootVersionId, starterId).hidden();
        return new StarterSearchItem(starterId, sd.label(), maintainer, category, description, version,
                sd.required(), List.copyOf(sd.requires()), hidden);
    }

    /**
     * 依赖坐标基线版本：取首个声明了 version 的坐标；全部未声明返回 {@code null}。
     *
     * @param deps 清单登记的依赖坐标清单（非空）
     * @return 基线版本或 {@code null}
     */
    private static String baselineVersion(List<com.wymix.initializer.core.starter.DependencyCoord> deps) {
        return deps.stream()
                .map(com.wymix.initializer.core.starter.DependencyCoord::version)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    private boolean matchesKeyword(StarterSearchItem item, String keyword) {
        if (keyword == null) {
            return true;
        }
        return containsIgnoreCase(item.id(), keyword)
                || containsIgnoreCase(item.label(), keyword)
                || containsIgnoreCase(item.description(), keyword);
    }

    private boolean matchesCategory(StarterSearchItem item, String categoryFilter) {
        if (categoryFilter == null) {
            return true;
        }
        return item.category() != null && item.category().toLowerCase(Locale.ROOT).equals(categoryFilter);
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /** 归一化检索条件：null/空白返回 null（不过滤），否则小写化。 */
    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
