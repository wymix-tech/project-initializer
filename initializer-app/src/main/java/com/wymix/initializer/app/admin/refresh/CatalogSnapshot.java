package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.core.conflict.ConflictCatalog;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.StarterCatalog;

import java.util.Map;
import java.util.Objects;

/**
 * 配置快照（M3.1/M3.2）：聚合元数据目录、starter 清单与模板覆盖的不可变视图。
 *
 * <p>快照由 {@link StructuredCatalogAssembler}（发布/回滚/启动装表）或
 * {@link CatalogRefresher}（YAML 通道，保留给导入导出）构建：装载期校验全部通过后原子切换，
 * 生成/元数据读路径只依赖快照，刷新失败时旧快照继续服务。
 *
 * <p>模板集（M3.2 → 结构化改造 T2）：快照持有「模板名 → 模板全文」映射。
 * **T2 起语义收敛为「数据库全量模板集」**——发布快照里的模板即全部可用模板，
 * 不再有「覆盖 + classpath 兜底」之分；生成期直接以本映射渲染。
 * 过渡期若映射为空（旧快照 / 外部目录 / {@code --demo} 播种），
 * {@link com.wymix.initializer.core.generator.TemplateRenderer} 仍保留 classpath 兜底，
 * 该兜底在 T5 随「模板改为 DB 唯一数据源」一并摘除。
 *
 * <p>冲突规则（M3.5）：快照持有配置化的 starter 互斥规则目录，前端提示与生成兜底校验
 * 均以快照中的规则为准——发布后即时热生效。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public class CatalogSnapshot {

    private final MetadataCatalog metadataCatalog;
    private final StarterCatalog starterCatalog;
    /**
     * 模板集映射（T2 起语义为「数据库全量模板集」）：键为模板名（相对 templates/），值为模板全文
     */
    private final Map<String, String> templateOverrides;
    /** 冲突规则目录（M3.5）：无规则时为空目录 */
    private final ConflictCatalog conflictCatalog;

    /**
     * M3.1 兼容构造：无模板覆盖。
     *
     * @param metadataCatalog 元数据目录（禁 null）
     * @param starterCatalog  starter 清单（禁 null）
     */
    public CatalogSnapshot(MetadataCatalog metadataCatalog, StarterCatalog starterCatalog) {
        this(metadataCatalog, starterCatalog, Map.of(), ConflictCatalog.empty());
    }

    /**
     * M3.2 兼容构造：无冲突规则。
     *
     * @param metadataCatalog   元数据目录（禁 null）
     * @param starterCatalog    starter 清单（禁 null）
     * @param templateOverrides 模板集映射（禁 null，可为空映射；T2 起语义为 DB 全量模板集）
     */
    public CatalogSnapshot(MetadataCatalog metadataCatalog, StarterCatalog starterCatalog,
                           Map<String, String> templateOverrides) {
        this(metadataCatalog, starterCatalog, templateOverrides, ConflictCatalog.empty());
    }

    /**
     * M3.5 全参构造。
     *
     * @param metadataCatalog   元数据目录（禁 null）
     * @param starterCatalog    starter 清单（禁 null）
     * @param templateOverrides 模板集映射（禁 null，可为空映射；T2 起语义为 DB 全量模板集）
     * @param conflictCatalog   冲突规则目录（禁 null，可为空目录）
     */
    public CatalogSnapshot(MetadataCatalog metadataCatalog, StarterCatalog starterCatalog,
                           Map<String, String> templateOverrides, ConflictCatalog conflictCatalog) {
        this.metadataCatalog = Objects.requireNonNull(metadataCatalog, "metadataCatalog");
        this.starterCatalog = Objects.requireNonNull(starterCatalog, "starterCatalog");
        this.templateOverrides = Map.copyOf(Objects.requireNonNull(templateOverrides, "templateOverrides"));
        this.conflictCatalog = Objects.requireNonNull(conflictCatalog, "conflictCatalog");
    }

    /** @return 元数据目录 */
    public MetadataCatalog metadataCatalog() {
        return metadataCatalog;
    }

    /** @return starter 清单 */
    public StarterCatalog starterCatalog() {
        return starterCatalog;
    }

    /**
     * @return 模板集映射（不可变；T2 起语义为「数据库全量模板集」，为空表示无 DB 模板）
     */
    public Map<String, String> templateOverrides() {
        return templateOverrides;
    }

    /** @return 冲突规则目录（M3.5，不可变，无规则时为空目录） */
    public ConflictCatalog conflictCatalog() {
        return conflictCatalog;
    }
}
