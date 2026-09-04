package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.StarterCatalog;

import java.util.Map;
import java.util.Objects;

/**
 * 配置快照（M3.1/M3.2）：聚合元数据目录、starter 清单与模板覆盖的不可变视图。
 *
 * <p>快照由 {@link CatalogRefresher} 在发布期构建：装载期校验全部通过后原子切换，
 * 生成/元数据读路径只依赖快照，刷新失败时旧快照继续服务。
 *
 * <p>模板覆盖（M3.2）：快照持有「模板名 → 模板全文」映射，生成期以覆盖模板优先、
 * classpath 内置模板兜底（未覆盖的模板照常从 {@code templates/} 加载）。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public class CatalogSnapshot {

    private final MetadataCatalog metadataCatalog;
    private final StarterCatalog starterCatalog;
    /** 模板覆盖映射（M3.2）：键为模板名（相对 templates/），值为模板全文；无覆盖时为空映射 */
    private final Map<String, String> templateOverrides;

    /**
     * M3.1 兼容构造：无模板覆盖。
     *
     * @param metadataCatalog 元数据目录（禁 null）
     * @param starterCatalog  starter 清单（禁 null）
     */
    public CatalogSnapshot(MetadataCatalog metadataCatalog, StarterCatalog starterCatalog) {
        this(metadataCatalog, starterCatalog, Map.of());
    }

    /**
     * M3.2 全参构造。
     *
     * @param metadataCatalog   元数据目录（禁 null）
     * @param starterCatalog    starter 清单（禁 null）
     * @param templateOverrides 模板覆盖映射（禁 null，可为空映射）
     */
    public CatalogSnapshot(MetadataCatalog metadataCatalog, StarterCatalog starterCatalog,
                           Map<String, String> templateOverrides) {
        this.metadataCatalog = Objects.requireNonNull(metadataCatalog, "metadataCatalog");
        this.starterCatalog = Objects.requireNonNull(starterCatalog, "starterCatalog");
        this.templateOverrides = Map.copyOf(Objects.requireNonNull(templateOverrides, "templateOverrides"));
    }

    /** @return 元数据目录 */
    public MetadataCatalog metadataCatalog() {
        return metadataCatalog;
    }

    /** @return starter 清单 */
    public StarterCatalog starterCatalog() {
        return starterCatalog;
    }

    /** @return 模板覆盖映射（不可变，无覆盖时为空映射） */
    public Map<String, String> templateOverrides() {
        return templateOverrides;
    }
}
