package com.wymix.initializer.core.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * 框架父 POM 配置（顶层 metadata 可选字段）：生成 {@code pom.xml} 的 {@code <parent>} 坐标。
     * null 时生成器回退默认 {@code org.springframework.boot:spring-boot-starter-parent:&lt;bootVersion&gt;}。
     */
    private final FrameworkParent frameworkParent;

    /**
     * 从 YAML 输入流装载元数据目录。
     *
     * @param yaml metadata.yml 输入流
     * @return 元数据目录实例
     * @throws BusinessException YAML 解析失败或结构非法（错误码 {@link ErrorCode#INTERNAL_ERROR}）
     */
    public static MetadataCatalog load(InputStream yaml) {
        YamlMetadataParser.ParsedMetadata parsed = YamlMetadataParser.parse(yaml);
        return new MetadataCatalog(parsed.javaVersions(), parsed.bootVersions(), parsed.starters(), parsed.frameworkParent());
    }

    /**
     * 空元数据目录（无 Java 版本 / Boot 版本 / starter 定义）。
     *
     * <p>用于「未启用内置清单」场景（如生产部署未携带 {@code --demo}）：
     * 读路径回退到空目录而非失败，清单数据等待管理端发布配置后填充。
     *
     * @return 空目录实例（不可变，线程安全）
     */
    public static MetadataCatalog empty() {
        return EMPTY;
    }

    /** 空目录单例：三个维度均为空表，无悬空引用可校验。 */
    private static final MetadataCatalog EMPTY =
            new MetadataCatalog(List.of(), List.of(), List.of(), null);

    private MetadataCatalog(List<JavaVersion> javaList, List<BootVersion> bootList, List<StarterDefinition> starterList,
                            FrameworkParent frameworkParent) {
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
        this.frameworkParent = frameworkParent;
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

/**
     * M3.3：starter 清单条目自动补位——将未在 metadata 登记的 starter 追加进清单
     * 返回扩展后的新目录（原对象不变）。
     *
     * <p>用途：制品库同步条目（及任何仅在 starter yml 登记的条目）默认对全部 Boot
     * 版本可选，无需手工维护 metadata 联动；调用方（发布链路自动补位）保证只传未被
     * 手工配置占用的条目。重建过程复用私有构造的悬空引用校验，合法性与装载期一致。
     *
     * @param added      追加的 starter 元信息（展示名 + 坐标）
     * @param starterIds 追加进全部 Boot 版本 starters 节的 ID 清单
     * @return 扩展后的新元数据目录
     */
    public MetadataCatalog withAdditionalStarters(List<StarterDefinition> added, List<String> starterIds) {
        List<StarterDefinition> defs = new ArrayList<>(starterDefinitions.values());
        defs.addAll(added);
        List<BootVersion> bootList = new ArrayList<>();
        for (BootVersion bv : bootVersions.values()) {
            List<String> starters = new ArrayList<>(bv.starters());
            for (String starterId : starterIds) {
                if (!starters.contains(starterId)) {
                    starters.add(starterId);
                }
            }
            bootList.add(new BootVersion(bv.id(), bv.label(), bv.official(),
                    bv.supportedJavaVersions(), starters));
        }
        return new MetadataCatalog(List.copyOf(javaVersions.values()), bootList, defs, frameworkParent);
    }

    /**
     * 框架父 POM 坐标（生成 {@code pom.xml} 的 {@code <parent>}）。null 时由生成器回退默认
     * {@code org.springframework.boot:spring-boot-starter-parent:&lt;bootVersion&gt;}。
     */
    public FrameworkParent frameworkParent() {
        return frameworkParent;
    }

    /** @return 全部已登记 starter ID 集合（M3.3 同步保留集计算用，不可变） */
    public Set<String> starterDefinitionIds() {
        return Collections.unmodifiableSet(starterDefinitions.keySet());
    }

    /** @return 全部已登记 starter ID 集合（M3.1 交叉校验用，{@link #starterDefinitionIds()} 别名） */
    public java.util.Set<String> startersDefinitionIds() {
        return starterDefinitionIds();
    }
}
