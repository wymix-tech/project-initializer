package com.wymix.initializer.app.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshot;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder;
import com.wymix.initializer.core.conflict.ConflictCatalog;
import com.wymix.initializer.core.metadata.BootVersion;
import com.wymix.initializer.core.metadata.JavaVersion;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.metadata.StarterDefinition;
import com.wymix.initializer.core.search.StarterQueryEngine;
import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.starter.StarterCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 元数据应用服务：包装 {@link MetadataCatalog} 的图遍历，向 HTTP 层输出 DTO。
 *
 * <p>M3.2 清单热刷新：读路径改为从 {@link CatalogSnapshotHolder} 当前快照取数，
 * 管理端发布成功后元数据清单即时热生效，无需重启；快照不可变，请求期间视图一致。
 *
 * <p>M3.5 依赖冲突提示：{@link #listConflictRules()} 返回当前生效快照的冲突规则集，
 * 前端选择依赖时本地即时计算提示；未发布冲突规则时返回空清单（无提示语义）。
 *
 * <p>M3.4 搜索与分类：starter 清单支持 {@code ?q=}（关键字，匹配 ID/展示名）
 * 与 {@code ?category=}（分类过滤），由 {@link StarterQueryEngine} 纯内存计算；q 匹配 ID/展示名/描述；
 * 未传参数时行为与全量清单一致（接口向后兼容）。
 *
 * <p>线程安全性：无状态单例（快照不可变），线程安全。
 */
@Service
public class MetadataService {

    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);

    private final CatalogSnapshotHolder snapshotHolder;
    /** 兜底快照（classpath 内置清单）：快照未发布时使用，行为与 M3.1 前一致 */
    private final MetadataCatalog fallbackCatalog;
    private final StarterCatalog fallbackStarterCatalog;
    /** M3.4：starter 内存检索引擎（无状态） */
    private final StarterQueryEngine queryEngine = new StarterQueryEngine();

    /**
     * M3.2 构造：读路径走快照持有器。
     *
     * @param snapshotHolder        当前快照持有器
     * @param fallbackCatalog       classpath 内置元数据目录（兜底，由 {@link MetadataCatalogConfig} 装配）
     * @param fallbackStarterCatalog classpath 内置 starter 清单（兜底，由 {@link StarterCatalogConfig} 装配）
     */
    public MetadataService(CatalogSnapshotHolder snapshotHolder, MetadataCatalog fallbackCatalog,
                           StarterCatalog fallbackStarterCatalog) {
        this.snapshotHolder = snapshotHolder;
        this.fallbackCatalog = fallbackCatalog;
        this.fallbackStarterCatalog = fallbackStarterCatalog;
    }

    /**
     * 全量 Java 版本清单。
     *
     * @return Java 版本 DTO 列表
     */
    public List<JavaVersionDto> listJavaVersions() {
        return catalog().javaVersions().stream().map(JavaVersionDto::from).toList();
    }

    /**
     * 指定 Java 版本下可用的官方 Boot 版本（兼容矩阵联动过滤）。
     *
     * @param javaVersionId Java 版本 ID
     * @return 兼容的官方 Boot 版本 DTO 列表
     * @throws BusinessException Java 版本未知（404 语义，错误码 2002）
     */
    public List<BootVersionDto> listCompatibleBootVersions(String javaVersionId) {
        List<BootVersion> versions = catalog().compatibleBootVersions(javaVersionId);
        if (versions.isEmpty()) {
            throw new BusinessException(ErrorCode.INCOMPATIBLE_VERSION,
                    "Java 版本 " + javaVersionId + " 无可用的官方 Boot 版本");
        }
        return versions.stream().map(BootVersionDto::from).toList();
    }

    /**
     * 指定 Boot 版本下可用的 starter 清单（无检索条件，M3.4 前兼容入口）。
     *
     * @param bootVersionId Boot 版本 ID
     * @return starter DTO 列表
     * @throws BusinessException Boot 版本未知（404 语义，错误码 2002）
     */
    public List<StarterDto> listStarters(String bootVersionId) {
        return listStarters(bootVersionId, null, null);
    }

    /**
     * 指定 Boot 版本下可用的 starter 清单，支持关键字与分类过滤（M3.4）。
     *
     * @param bootVersionId Boot 版本 ID
     * @param q             关键字（可空）：匹配 starter ID/展示名/描述，忽略大小写
     * @param category      分类（可空）：精确匹配，忽略大小写；未知分类返回空列表
     * @return starter DTO 列表
     * @throws BusinessException Boot 版本未知（404 语义，错误码 2002）
     */
    public List<StarterDto> listStarters(String bootVersionId, String q, String category) {
        MetadataCatalog catalog = catalog();
        StarterCatalog starterCatalog = starterCatalog();
        return queryEngine.search(catalog, starterCatalog, bootVersionId, q, category).stream()
                .map(StarterDto::from)
                .toList();
    }

    /**
     * 当前生效快照的冲突规则清单（M3.5）；快照未发布时回退空清单
     * （classpath 内置清单暂无内置冲突规则，兜底语义为「无提示」）。
     *
     * @return 冲突规则 DTO 列表（不可变）
     */
    public List<ConflictRuleDto> listConflictRules() {
        CatalogSnapshot snapshot = snapshotHolder.current();
        ConflictCatalog catalog = snapshot != null ? snapshot.conflictCatalog() : ConflictCatalog.empty();
        return catalog.all().stream().map(ConflictRuleDto::from).toList();
    }

    /**
     * 冲突规则 DTO（M3.5）：规则 ID + 类型 + 涉及 starter + 提示文案，
     * 供前端本地即时计算冲突提示。
     */
    public record ConflictRuleDto(String id, String type, List<String> starters, String message) {

        static ConflictRuleDto from(com.wymix.initializer.core.conflict.ConflictRule rule) {
            return new ConflictRuleDto(rule.id(), rule.type().name().toLowerCase(),
                    rule.starters(), rule.message());
        }
    }

    /** Java 版本 DTO。 */
    public record JavaVersionDto(String id, String label, boolean deprecated, String recommendedBootVersion) {

        static JavaVersionDto from(JavaVersion jv) {
            return new JavaVersionDto(jv.id(), jv.label(), jv.deprecated(), jv.recommendedBootVersion());
        }
    }

    /** Boot 版本 DTO。 */
    public record BootVersionDto(String id, String label, boolean official) {

        static BootVersionDto from(BootVersion bv) {
            return new BootVersionDto(bv.id(), bv.label(), bv.official());
        }
    }

    /**
     * Starter DTO（M2.1 补充维护方标注；M3.4 补充分类与描述；验收一般项①：
     * 补充基线版本透出——内部 starter 展示清单声明版本，官方 starter 为 null 走 Boot BOM）。
     */
    public record StarterDto(String id, String label, String maintainer, String category,
                             String description, String version) {

        static StarterDto from(StarterQueryEngine.StarterSearchItem item) {
            return new StarterDto(item.id(), item.label(),
                    item.maintainer().name().toLowerCase(),
                    item.category(),
                    item.description(),
                    item.version());
        }

        static StarterDto of(String id, String label, Maintainer maintainer) {
            return new StarterDto(id, label, maintainer.name().toLowerCase(), null, null, null);
        }
    }

    /**
     * 当前生效元数据目录：快照已发布取快照，否则回退 classpath 内置清单。
     *
     * @return 元数据目录
     */
    private MetadataCatalog catalog() {
        var snapshot = snapshotHolder.current();
        return snapshot != null ? snapshot.metadataCatalog() : fallbackCatalog;
    }

    /**
     * 当前生效 starter 清单：快照已发布取快照，否则回退 classpath 内置清单。
     *
     * @return starter 清单
     */
    private StarterCatalog starterCatalog() {
        var snapshot = snapshotHolder.current();
        return snapshot != null ? snapshot.starterCatalog() : fallbackStarterCatalog;
    }
}
