package com.wymix.initializer.app.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.metadata.BootVersion;
import com.wymix.initializer.core.metadata.JavaVersion;
import com.wymix.initializer.core.metadata.MetadataCatalog;
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

    /**
     * @param catalog 元数据目录（由 {@link MetadataCatalogConfig} 装配）
     */
    public MetadataService(MetadataCatalog catalog) {
        this.catalog = catalog;
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
                .map(id -> new StarterDto(id, starterLabel(id)))
                .toList();
    }

    /**
     * starter 展示名映射：当前配置仅含 starter ID，展示名按约定规则推导，后续可配置化。
     *
     * @param id starter ID，如 {@code web}
     * @return 展示名，如 {@code Spring Web}
     */
    private static String starterLabel(String id) {
        return switch (id) {
            case "web" -> "Spring Web";
            case "data-jpa" -> "Spring Data JPA";
            case "data-redis" -> "Spring Data Redis";
            case "actuator" -> "Spring Boot Actuator";
            default -> id;
        };
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

    /** Starter DTO。 */
    public record StarterDto(String id, String label) {
    }
}
