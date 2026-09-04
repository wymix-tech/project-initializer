package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.StarterCatalog;

import java.util.Objects;

/**
 * 配置快照（M3.1/M3.2）：聚合元数据目录与 starter 清单的不可变视图。
 *
 * <p>快照由 {@link CatalogRefresher} 在发布期构建：装载期校验全部通过后原子切换，
 * 生成/元数据读路径只依赖快照，刷新失败时旧快照继续服务。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public class CatalogSnapshot {

    private final MetadataCatalog metadataCatalog;
    private final StarterCatalog starterCatalog;

    /**
     * @param metadataCatalog 元数据目录（禁 null）
     * @param starterCatalog  starter 清单（禁 null）
     */
    public CatalogSnapshot(MetadataCatalog metadataCatalog, StarterCatalog starterCatalog) {
        this.metadataCatalog = Objects.requireNonNull(metadataCatalog, "metadataCatalog");
        this.starterCatalog = Objects.requireNonNull(starterCatalog, "starterCatalog");
    }

    /** @return 元数据目录 */
    public MetadataCatalog metadataCatalog() {
        return metadataCatalog;
    }

    /** @return starter 清单 */
    public StarterCatalog starterCatalog() {
        return starterCatalog;
    }
}
