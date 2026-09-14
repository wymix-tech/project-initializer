package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.app.admin.entity.CfgTemplateEntity;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.repository.StructuredSnapshot;
import com.wymix.initializer.core.generator.TemplateRenderer;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import com.wymix.initializer.core.starter.TemplateLinkage;
import com.wymix.initializer.core.starter.YmlFragmentLinkage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code --demo} 内置种子装载器（T5：模板改为 DB 唯一数据源）。
 *
 * <p>职责：把 **classpath 内置清单与内置模板** 一次性灌入数据库草稿作用域
 * （{@code release_id = 0}），供启动链自动发布一版；此后所有读路径
 * （生成 / 元数据 / 管理端）统一读 DB，不再回退 classpath 模板。
 *
 * <p>入库范围（T5.3）：
 * <ol>
 *   <li>{@code cfg_template}：classpath {@code templates/**} 下的全部文件（含 {@code .yml} 片段），
 *       键为相对 {@code templates/} 的路径，与 starter 清单 {@code linkages.*.source} 对得上</li>
 *   <li>{@code STARTER}（MANUAL 通道）：classpath {@code catalog/starters/*.yml} 全部条目</li>
 *   <li>{@code METADATA}：由 {@link MetadataCatalog} + {@link StarterCatalog} 反写为结构行
 *       （Java 版本 / Boot 版本 / 兼容矩阵 / starter 白名单），无需重复维护 {@code metadata.yml}</li>
 * </ol>
 *
 * <p>线程安全性：无状态单例（全部状态在入参与返回值中），线程安全。
 */
@Component
public class DemoCatalogSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoCatalogSeeder.class);

    /** classpath 内置模板集扫描模式（覆盖 jar 内外全部 {@code templates/} 副本）。 */
    static final String TEMPLATE_PATTERN = "classpath*:" + TemplateRenderer.TEMPLATE_BASE + "**";

    private final ConfigRepository repository;

    /**
     * @param repository 配置存储仓库（把内置种子写入草稿作用域）
     */
    public DemoCatalogSeeder(ConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * 把内置清单与内置模板写入草稿作用域并发布一版。
     *
     * <p>写入顺序：模板 → starter（MANUAL 通道）→ metadata（各自整体替换），
     * 随后 {@code copyAsRelease} 固化发布快照。
     * 三个模块都是**确定性全量替换**（不读库内既有状态），保证 {@code --demo} 的启动结果
     * 与库内残留草稿无关；SYNC 通道与冲突规则草稿不在种子范围内，保持库内既有内容。
     *
     * @param metadata 内置元数据目录（已与 starter 清单联动合并）
     * @param starters 内置 starter 清单
     * @param operator 操作人（写入发布记录）
     * @return 自动发布的发布记录；无可入库内容时返回 {@code null}（不改动 DB）
     */
    public ConfigReleaseEntity seedAndPublish(MetadataCatalog metadata, StarterCatalog starters, String operator) {
        verifyTemplateReachability(starters);
        // 内置种子是**确定性全量**：模板集 + starter 清单（MANUAL 通道）+ 元数据，
        // 一律整体替换草稿作用域内的对应行。这样 --demo 的启动结果与「管理端从未发布过」
        // 的库状态无关，也不受同进程内其他测试/实例的残留草稿影响。
        // SYNC 通道与冲突规则不在内置种子范围内（由同步链路与元数据派生），保持库内既有内容。
        int templates = writeTemplates();
        if (templates == 0) {
            log.warn("--demo 内置模板集为空：跳过自动发布，生成将因模板缺失而失败（DB 模板集为唯一数据源）");
            return null;
        }
        int starterRows = writeStarters(starters);
        int metaRows = writeMetadata(metadata, starters);
        ConfigReleaseEntity release = repository.insertRelease(operator, "--demo 内置清单与模板入库自动发布");
        int rows = repository.copyAsRelease(ConfigRepository.DRAFT_RELEASE_ID, release.getId());
        log.info("--demo 内置种子已入库并自动发布: releaseId={}, 复制 {} 行（模板 {} / starter 结构行 {} / 元数据结构行 {}）",
                release.getId(), rows, templates, starterRows, metaRows);
        return release;
    }

    /**
     * 把 classpath 内置模板写入 {@code cfg_template} 草稿行（整体替换）。
     *
     * @return 入库模板数
     */
    private int writeTemplates() {
        java.util.Map<String, String> templates = loadClasspathTemplates();
        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        int sort = 0;
        for (java.util.Map.Entry<String, String> entry : templates.entrySet()) {
            CfgTemplateEntity row = new CfgTemplateEntity();
            row.setName(entry.getKey());
            row.setContent(entry.getValue());
            row.setSort(sort++);
            row.setUpdatedAt(java.time.Instant.now());
            snapshot.templates().add(row);
        }
        if (snapshot.templates().isEmpty()) {
            return 0;
        }
        repository.replaceDraftTemplates(snapshot);
        return snapshot.templates().size();
    }

    /**
     * 装载 classpath 内置模板集（{@code templates/**} 全部文件）。
     *
     * <p>同名条目以**首个命中**为准（{@code classpath*} 可能命中测试资源与主资源两份副本），
     * 保证装载结果确定性。
     *
     * @return 模板名（相对 {@code templates/}，{@code /} 分隔）→ 模板全文
     * @throws BusinessException 内置模板读取失败（启动即失败，避免半套种子）
     */
    java.util.Map<String, String> loadClasspathTemplates() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(TEMPLATE_PATTERN);
        } catch (IOException e) {
            throw new BusinessException(com.wymix.initializer.api.error.ErrorCode.INTERNAL_ERROR,
                    "内置模板集扫描失败: " + e.getMessage(), e);
        }
        java.util.Map<String, String> templates = new java.util.LinkedHashMap<>();
        for (Resource resource : sortByUri(resources)) {
            String name = templateName(resource);
            if (name == null) {
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                templates.putIfAbsent(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new BusinessException(com.wymix.initializer.api.error.ErrorCode.INTERNAL_ERROR,
                        "内置模板读取失败: " + resource, e);
            }
        }
        return templates;
    }

    /**
     * 由资源定位反推模板名（相对 {@code templates/} 的路径）；非模板文件返回 null。
     *
     * <p>目录型资源（{@code templates/} / {@code templates/starters/}）必须以「文件名末段带
     * 扩展名」判定排除——jar 与目录两种协议下目录 URL 都**不带**尾斜杠，
     * 只能靠 {@link Resource#getFilename()} 的扩展名识别，否则目录会被当成模板入库
     * （表现为「template 缺少必需字段: content」）。
     */
    private static String templateName(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null || !filename.contains(".")) {
            return null;
        }
        try {
            String uri = resource.getURI().toString().replace('\\', '/');
            int index = uri.indexOf(TemplateRenderer.TEMPLATE_BASE);
            if (index < 0) {
                return null;
            }
            String name = uri.substring(index + TemplateRenderer.TEMPLATE_BASE.length());
            return name.isBlank() || name.endsWith("/") ? null : name;
        } catch (IOException e) {
            return null;
        }
    }

    /** 按资源 URI 排序，保证装载顺序确定性（跨 jar 场景 Resource 顺序不保证）。 */
    private static List<Resource> sortByUri(Resource[] resources) {
        List<Resource> list = new ArrayList<>(List.of(resources));
        list.sort((a, b) -> String.valueOf(a).compareTo(String.valueOf(b)));
        return list;
    }

    /**
     * 写 starter 清单草稿行（MANUAL 通道，整体替换）。
     *
     * @param starters 内置 starter 清单
     */
    private int writeStarters(StarterCatalog starters) {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        DemoCatalogWriter.writeStarterRows(snapshot, starters.all());
        return repository.replaceDraftStartersBySource(ConfigRepository.SOURCE_MANUAL, snapshot);
    }

    /**
     * 写元数据草稿行（Java 版本 / Boot 版本 / 兼容矩阵 / starter 白名单，整体替换）。
     *
     * <p>白名单以「starter 清单全部条目」为准——内置 metadata.yml 已显式登记，
     * 这里再取并集，保证清单新增条目自动成为全部 Boot 版本可选。
     *
     * @param metadata 内置元数据目录
     * @param starters 内置 starter 清单
     */
    private int writeMetadata(MetadataCatalog metadata, StarterCatalog starters) {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        int javaSort = 0;
        Set<String> declaredStarters = new LinkedHashSet<>(metadata.starterDefinitionIds());
        for (StarterCatalogEntry entry : starters.all()) {
            declaredStarters.add(entry.id());
        }
        DemoCatalogWriter.writeMetadataRows(snapshot, metadata, declaredStarters, javaSort);
        return repository.replaceDraftMetadata(snapshot);
    }

    /**
     * 内置模板集「模板可达性」自检（供启动链使用）：starter 清单声明引用的全部模板与
     * yml 片段必须能在内置模板集中找到，否则 {@code --demo} 种子必然产出坏快照。
     *
     * @param starters 内置 starter 清单
     * @throws BusinessException 存在缺失引用（启动即失败，明细列出缺失项）
     */
    public void verifyTemplateReachability(StarterCatalog starters) {
        Set<String> names = loadClasspathTemplates().keySet();
        List<String> missing = new ArrayList<>();
        for (StarterCatalogEntry entry : starters.all()) {
            for (TemplateLinkage linkage : entry.linkages().templates()) {
                if (!names.contains(linkage.source())) {
                    missing.add("starter " + entry.id() + " 模板缺失: " + linkage.source());
                }
            }
            for (YmlFragmentLinkage linkage : entry.linkages().ymlFragments()) {
                if (!names.contains(linkage.source())) {
                    missing.add("starter " + entry.id() + " yml 片段缺失: " + linkage.source());
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(com.wymix.initializer.api.error.ErrorCode.STARTER_CATALOG_INVALID,
                    "内置模板集不完整（模板可达性校验失败）: " + String.join("; ", missing));
        }
    }

}
