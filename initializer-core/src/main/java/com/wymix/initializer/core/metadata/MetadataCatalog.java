package com.wymix.initializer.core.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据目录：加载 {@code catalog/metadata.yml}，提供版本清单与兼容矩阵查询。
 *
 * <p>兼容矩阵为配置驱动的有向图（Boot → Java ID 引用），本类负责图的装载与遍历过滤：
 * <ul>
 *   <li>{@link #javaVersions()} —— 全量 Java 版本清单</li>
 *   <li>{@link #compatibleBootVersions(String)} —— 按 Java 版本过滤 Boot 版本</li>
 *   <li>{@link #starters(String)} —— 按 Boot 版本列出可用 starter</li>
 *   <li>{@link #starterDefinition(String)} —— starter 完整元信息（展示名 + Maven 依赖坐标）</li>
 * </ul>
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public class MetadataCatalog {

    private final Map<String, JavaVersion> javaVersions = new LinkedHashMap<>();
    private final Map<String, BootVersion> bootVersions = new LinkedHashMap<>();
    private final Map<String, StarterDefinition> starterDefinitions = new LinkedHashMap<>();

    /**
     * 从 YAML 输入流装载元数据目录。
     *
     * @param yaml metadata.yml 输入流
     * @return 元数据目录实例
     * @throws BusinessException YAML 解析失败或结构非法（错误码 {@link ErrorCode#INTERNAL_ERROR}）
     */
    public static MetadataCatalog load(InputStream yaml) {
        YamlMetadataParser.ParsedMetadata parsed = YamlMetadataParser.parse(yaml);
        return new MetadataCatalog(parsed.javaVersions(), parsed.bootVersions(), parsed.starters());
    }

    private MetadataCatalog(List<JavaVersion> javaList, List<BootVersion> bootList, List<StarterDefinition> starterList) {
        for (JavaVersion jv : javaList) {
            if (javaVersions.putIfAbsent(jv.id(), jv) != null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Java 版本 ID 重复: " + jv.id());
            }
        }
        for (BootVersion bv : bootList) {
            if (bootVersions.putIfAbsent(bv.id(), bv) != null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Boot 版本 ID 重复: " + bv.id());
            }
            for (String javaId : bv.supportedJavaVersions()) {
                if (!javaVersions.containsKey(javaId)) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                            "Boot 版本 " + bv.id() + " 引用了未定义的 Java 版本: " + javaId);
                }
            }
        }
        // YAML 映射键天然唯一（重复键由解析层覆盖），此处无需重复 ID 校验
        for (StarterDefinition sd : starterList) {
            starterDefinitions.put(sd.id(), sd);
        }
        // 悬空引用校验：bootVersions.starters 引用的 starter 必须在 starters 节登记
        for (BootVersion bv : bootList) {
            for (String starterId : bv.starters()) {
                if (!starterDefinitions.containsKey(starterId)) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                            "Boot 版本 " + bv.id() + " 引用了未定义的 starter: " + starterId);
                }
            }
        }
    }

    /** @return 全量 Java 版本清单（按配置顺序，不可变） */
    public List<JavaVersion> javaVersions() {
        return List.copyOf(javaVersions.values());
    }

    /** @return 全量 Boot 版本清单（按配置顺序，不可变） */
    public List<BootVersion> bootVersions() {
        return List.copyOf(bootVersions.values());
    }

    /**
     * 按 Java 版本 ID 查询元数据。
     *
     * @param javaVersionId Java 版本 ID，如 {@code "17"}
     * @return Java 版本元数据
     * @throws BusinessException 不存在（错误码 {@link ErrorCode#METADATA_NOT_FOUND}）
     */
    public JavaVersion javaVersion(String javaVersionId) {
        JavaVersion jv = javaVersions.get(javaVersionId);
        if (jv == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND, "未知 Java 版本: " + javaVersionId);
        }
        return jv;
    }

    /**
     * 按 Boot 版本 ID 查询元数据。
     *
     * @param bootVersionId Boot 版本 ID，如 {@code "3.2.5"}
     * @return Boot 版本元数据
     * @throws BusinessException 不存在（错误码 {@link ErrorCode#METADATA_NOT_FOUND}）
     */
    public BootVersion bootVersion(String bootVersionId) {
        BootVersion bv = bootVersions.get(bootVersionId);
        if (bv == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND, "未知 Boot 版本: " + bootVersionId);
        }
        return bv;
    }

    /**
     * 兼容矩阵图遍历：返回指定 Java 版本可用的 Boot 版本（仅官方版本）。
     *
     * <p>遍历方向：Java → Boot 的反向边（Boot.supportedJavaVersions 引用 Java ID）。
     *
     * @param javaVersionId Java 版本 ID
     * @return 兼容的官方 Boot 版本清单（按配置顺序，不可变）
     * @throws BusinessException Java 版本不存在（错误码 {@link ErrorCode#METADATA_NOT_FOUND}）
     */
    public List<BootVersion> compatibleBootVersions(String javaVersionId) {
        javaVersion(javaVersionId);
        return bootVersions.values().stream()
                .filter(BootVersion::official)
                .filter(bv -> bv.supportsJava(javaVersionId))
                .toList();
    }

    /**
     * 返回指定 Boot 版本可用的 starter 清单。
     *
     * @param bootVersionId Boot 版本 ID
     * @return starter ID 清单（不可变）
     * @throws BusinessException Boot 版本不存在（错误码 {@link ErrorCode#METADATA_NOT_FOUND}）
     */
    public List<String> starters(String bootVersionId) {
        return Collections.unmodifiableList(bootVersion(bootVersionId).starters());
    }

    /**
     * 按 ID 查询 starter 完整元信息（展示名 + Maven 依赖坐标）。
     *
     * @param starterId starter ID，如 {@code web}
     * @return starter 定义
     * @throws BusinessException 未登记（错误码 {@link ErrorCode#METADATA_NOT_FOUND}）
     */
    public StarterDefinition starterDefinition(String starterId) {
        StarterDefinition sd = starterDefinitions.get(starterId);
        if (sd == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND, "未知 starter: " + starterId);
        }
        return sd;
    }

    /**
     * 按 ID 查询 starter 元信息（宽松版，M3.1 新增）。
     *
     * @param starterId starter ID
     * @return starter 定义；未登记返回 {@code null}
     */
    public StarterDefinition starterDefinitionOrNull(String starterId) {
        return starterDefinitions.get(starterId);
    }

    /** @return 全部已登记 starter ID 集合（M3.1 交叉校验用，不可变） */
    public java.util.Set<String> startersDefinitionIds() {
        return java.util.Collections.unmodifiableSet(starterDefinitions.keySet());
    }
}
