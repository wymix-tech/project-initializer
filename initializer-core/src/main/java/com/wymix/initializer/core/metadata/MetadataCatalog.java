package com.wymix.initializer.core.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
        return new MetadataCatalog(parsed.javaVersions(), parsed.bootVersions(), List.of());
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
            new MetadataCatalog(List.of(), List.of(), List.of());

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
        // starter 元信息不再由 metadata 提供（全部来自 starter 清单，经 merge 联动），
        // bootVersions.starters 的悬空引用不在此强校验：未知 id 在查询/生成时自然不可见或报错，
        // requires（悬空/自引用/成环）校验由 StarterCatalog 装载期负责
    }

    /**
     * 依赖校验（M3.7）：给定选中清单，校验每个已选 starter 的 requires 分组均被满足，
     * 且限定在指定 Boot 版本的可选清单内（不在清单内的依赖被忽略，避免引入不支持的 starter）。
     *
     * <p>与旧版（M3.6）的关键差异：**不再自动把 requires 依赖拉进选中集合**。
     * requires 为 AND-of-OR-groups——分组内任一被选即满足该分组，全部分组满足才算依赖满足。
     * 前端据此展示「需要 B 或 C」提示并让用户显式选择（不默认勾选、不锁定），
     * 由本方法在生成期做最终校验，未满足即拒绝（错误码
     * {@link ErrorCode#MISSING_DEPENDENCY}），错误明细列出缺失分组。
     *
     * @param bootVersionId Boot 版本 ID
     * @param selected      用户选中的 starter ID 清单（可含重复，保持首次出现顺序）
     * @return 去重后的选中清单（顺序保持，不追加任何隐式依赖，不可变）
     * @throws BusinessException Boot 版本不存在（错误码 {@link ErrorCode#METADATA_NOT_FOUND}）；
     *                           依赖未满足（错误码 {@link ErrorCode#MISSING_DEPENDENCY}）
     */
    public List<String> resolveRequires(String bootVersionId, List<String> selected) {
        Set<String> available = new LinkedHashSet<>(bootVersion(bootVersionId).starters());
        Set<String> chosen = selected == null ? new LinkedHashSet<>() : new LinkedHashSet<>(selected);
        List<String> missing = new ArrayList<>();
        for (String id : chosen) {
            // 不在本 Boot 版本可选清单内的项忽略（与既有语义一致，不引入不支持的 starter）
            if (!available.contains(id)) {
                continue;
            }
            StarterDefinition sd = starterDefinitions.get(id);
            if (sd == null) {
                continue;
            }
            for (List<String> group : sd.requires()) {
                boolean satisfied = group.stream()
                        .anyMatch(dep -> chosen.contains(dep) && available.contains(dep));
                if (!satisfied) {
                    missing.add(id + " 需要 " + String.join(" 或 ", group));
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.MISSING_DEPENDENCY,
                    "依赖未满足：" + String.join("；", missing));
        }
        return List.copyOf(chosen);
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
     * 返回指定 Boot 版本下的必选 starter 清单（M3.6）。
     *
     * <p>必选项由 {@code metadata.yml} 的 {@code starters.<id>.required: true} 声明（全局生效），
     * 但**只在该 starter 属于本 Boot 版本可选清单时**才强制——未登记在本版本清单内的
     * 必选项被忽略，避免生成请求引入该 Boot 版本不支持的 starter。
     *
     * @param bootVersionId Boot 版本 ID
     * @return 必选 starter ID 清单（按可选清单顺序，不可变；无必选项时为空）
     * @throws BusinessException Boot 版本不存在（错误码 {@link ErrorCode#METADATA_NOT_FOUND}）
     */
    public List<String> requiredStarters(String bootVersionId) {
        return bootVersion(bootVersionId).starters().stream()
                .filter(id -> {
                    StarterDefinition sd = starterDefinitions.get(id);
                    return sd != null && sd.required();
                })
                .toList();
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
                    bv.supportedJavaVersions(), starters, bv.frameworkParent()));
        }
        return new MetadataCatalog(List.copyOf(javaVersions.values()), bootList, defs);
    }

    /**
     * starter 清单联动合并：starter 的**全部元信息**（展示名、依赖坐标、必选标记、依赖联动）
     * 以 {@link StarterCatalog}（{@code catalog/starters/*.yml}）为唯一数据源，
     * metadata.yml 不再维护 starters 节，避免两处重复配置。
     *
     * <p>合并语义：
     * <ul>
     *   <li>starter 定义：全部由清单生成（展示名取 description，再兜底 ID；坐标取 dependencies 首条；
     *       {@code required} / {@code requires} 取清单声明，悬空/自引用/成环已在清单装载期拒绝）</li>
     *   <li>{@code bootVersions[].starters}：**省略或为空**即该版本对全部清单条目可选（自动填充）；
     *       显式列出则为白名单，并自动追加清单新增条目（与既有「清单条目自动可选」语义一致）</li>
     * </ul>
     *
     * @param starterCatalog 生效 starter 清单
     * @return 合并后的新元数据目录（原对象不变）
     */
    public MetadataCatalog merge(StarterCatalog starterCatalog) {
        if (starterCatalog == null) {
            return this;
        }
        // starter 定义：跨 Boot 版本按 ID 去重（全版本条目优先），坐标取清单声明
        List<StarterDefinition> defs = new ArrayList<>();
        for (StarterCatalogEntry entry : starterCatalog.all()) {
            defs.add(new StarterDefinition(entry.id(), displayNameOf(entry), primaryCoord(entry),
                    entry.required(), entry.requires()));
        }
        // 清单中登记过的全部 ID（用于识别「声明了 bootVersions 但不含本版本」的条目）
        Set<String> allCatalogIds = starterCatalog.all().stream()
                .map(StarterCatalogEntry::id)
                .collect(Collectors.toSet());
        List<BootVersion> boots = new ArrayList<>();
        for (BootVersion bv : bootVersions.values()) {
            // 该 Boot 版本可用的清单条目：全版本条目 + 声明了该版本的条目（同 ID 版本专属优先）
            List<String> available = starterCatalog.allForBoot(bv.id()).stream()
                    .map(StarterCatalogEntry::id)
                    .toList();
            // 白名单（bootVersions[].starters）+ 清单中关联本版本的条目（自动可选）
            List<String> starters = new ArrayList<>(bv.starters());
            for (String id : available) {
                if (!starters.contains(id)) {
                    starters.add(id);
                }
            }
            // 关联校验：清单条目声明了 bootVersions 但不含本版本 → 本版本不可引用
            starters.removeIf(id -> allCatalogIds.contains(id) && !available.contains(id));
            boots.add(new BootVersion(bv.id(), bv.label(), bv.official(),
                    bv.supportedJavaVersions(), starters, bv.frameworkParent()));
        }
        return new MetadataCatalog(List.copyOf(javaVersions.values()), boots, defs);
    }

    /** starter 清单条目的展示名：description 优先，缺失用 ID。 */
    private static String displayNameOf(StarterCatalogEntry entry) {
        return entry.description() == null || entry.description().isBlank() ? entry.id() : entry.description();
    }

    /** starter 清单条目的主坐标：dependencies 首条的 groupId/artifactId（无依赖时 null）。 */
    private static MavenDependency primaryCoord(StarterCatalogEntry entry) {
        if (entry.dependencies().isEmpty()) {
            return null;
        }
        com.wymix.initializer.core.starter.DependencyCoord primary = entry.dependencies().get(0);
        return new MavenDependency(primary.groupId(), primary.artifactId());
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
