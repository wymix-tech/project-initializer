package com.wymix.initializer.app.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
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
 * <p>线程安全性：无状态单例（Catalog 不可变），线程安全。
 */
@Service
public class MetadataService {

    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);

    private final MetadataCatalog catalog;
    private final StarterCatalog starterCatalog;

    /**
     * @param catalog        元数据目录（由 {@link MetadataCatalogConfig} 装配）
     * @param starterCatalog starter 清单（由 {@link StarterCatalogConfig} 装配，M2.1 新增）
     */
    public MetadataService(MetadataCatalog catalog, StarterCatalog starterCatalog) {
        this.catalog = catalog;
        this.starterCatalog = starterCatalog;
    }

    /**
     * 全量 Java 版本清单。
     *
     * @return Java 版本 DTO 列表
     */
    public List<JavaVersionDto> listJavaVersions() {
        return catalog.javaVersions().stream().map(JavaVersionDto::from).toList();
    }

    /**
     * 指定 Java 版本下可用的官方 Boot 版本（兼容矩阵联动过滤）。
     *
     * @param javaVersionId Java 版本 ID
     * @return 兼容的官方 Boot 版本 DTO 列表
     * @throws BusinessException Java 版本未知（404 语义，错误码 2002）
     */
    public List<BootVersionDto> listCompatibleBootVersions(String javaVersionId) {
        List<BootVersion> versions = catalog.compatibleBootVersions(javaVersionId);
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
        return catalog.starters(bootVersionId).stream()
                .map(id -> {
                    StarterDefinition sd = catalog.starterDefinition(id);
                    return StarterDto.of(sd.id(), sd.label(), maintainerOf(id));
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
     * 查询 starter 维护方：优先 starter 清单（M2.1），未登记的回退为官方。
     *
     * @param starterId starter ID
     * @return 维护方
     */
    private Maintainer maintainerOf(String starterId) {
        return starterCatalog.contains(starterId)
                ? starterCatalog.entry(starterId).maintainer()
                : Maintainer.OFFICIAL;
    }
}
