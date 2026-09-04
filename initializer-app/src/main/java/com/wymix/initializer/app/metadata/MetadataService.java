package com.wymix.initializer.app.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder;
import com.wymix.initializer.core.metadata.BootVersion;
import com.wymix.initializer.core.metadata.JavaVersion;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.metadata.StarterDefinition;
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
 * <p>线程安全性：无状态单例（快照不可变），线程安全。
 */
@Service
public class MetadataService {

    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);

    private final CatalogSnapshotHolder snapshotHolder;
    /** 兜底快照（classpath 内置清单）：快照未发布时使用，行为与 M3.1 前一致 */
    private final MetadataCatalog fallbackCatalog;
    private final StarterCatalog fallbackStarterCatalog;

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
     * 指定 Boot 版本下可用的 starter 清单。
     *
     * @param bootVersionId Boot 版本 ID
     * @return starter DTO 列表
     * @throws BusinessException Boot 版本未知（404 语义，错误码 2002）
     */
    public List<StarterDto> listStarters(String bootVersionId) {
        MetadataCatalog catalog = catalog();
        StarterCatalog starterCatalog = starterCatalog();
        return catalog.starters(bootVersionId).stream()
                .map(id -> {
                    StarterDefinition sd = catalog.starterDefinition(id);
                    return StarterDto.of(sd.id(), sd.label(), maintainerOf(starterCatalog, id));
                })
                .toList();
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

    /** Starter DTO（M2.1 补充维护方标注：官方/内部统一入清单）。 */
    public record StarterDto(String id, String label, String maintainer) {

        static StarterDto of(String id, String label, Maintainer maintainer) {
            return new StarterDto(id, label, maintainer.name().toLowerCase());
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

    /**
     * 查询 starter 维护方：优先 starter 清单（M2.1），未登记的回退为官方。
     *
     * @param starterCatalog 生效 starter 清单
     * @param starterId      starter ID
     * @return 维护方
     */
    private Maintainer maintainerOf(StarterCatalog starterCatalog, String starterId) {
        return starterCatalog.contains(starterId)
                ? starterCatalog.entry(starterId).maintainer()
                : Maintainer.OFFICIAL;
    }
}
