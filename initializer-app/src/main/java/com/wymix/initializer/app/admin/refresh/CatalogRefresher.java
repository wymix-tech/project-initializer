package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.conflict.ConflictCatalog;
import com.wymix.initializer.core.conflict.ConflictRuleParser;
import com.wymix.initializer.core.generator.TemplateRenderer;
import com.wymix.initializer.core.metadata.MavenDependency;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.metadata.StarterDefinition;
import com.wymix.initializer.core.starter.DependencyCoord;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import com.wymix.initializer.core.starter.YamlStarterParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 清单热刷新器（M3.1/M3.2）：草稿 YAML → 装载期校验 → 快照构建 → 原子切换。
 *
 * <p>失败语义：任一步失败抛 {@link BusinessException}（4002/9002/3001 收敛），
 * 快照引用保持不变，生成服务无感；草稿保留可继续编辑。
 *
 * <p>冲突规则草稿格式（M3.5）：{@code conflict-rules.yml} 同构（rules 节），随发布进快照；
 * 悬空引用（规则引用清单中不存在的 starter id）发布即拒绝。
 *
 * <p>starter 草稿格式：与 {@code catalog/starters/*.yml} 同构的配置流，
 * 多 starter 以 YAML 多文档（--- 分隔）表达；METADATA 草稿与 {@code metadata.yml} 同构；
 * TEMPLATE 草稿（M3.2）为多文档映射 {@code {name, content}}，name 相对 templates/ 目录。
 *
 * <p>线程安全性：无状态单例（校验逻辑纯函数式），线程安全。
 */
@Component
public class CatalogRefresher {

    private static final Logger log = LoggerFactory.getLogger(CatalogRefresher.class);

    /** StarterCatalog 装载失败错误码（9002，与 classpath 装载口径一致）。 */
    private static final ErrorCode STARTER_INVALID = ErrorCode.STARTER_CATALOG_INVALID;

    /** 发布期模板语法校验器（classpath 兜底模板集上下文）。 */
    private final TemplateRenderer templateValidator = new TemplateRenderer();

    /**
     * 校验并构建快照（不切换，切换由 {@link CatalogSnapshotHolder} 负责）。
     *
     * @param metadataYaml   metadata 草稿全文（null 时拒绝——首发快照必须显式提供）
     * @param starterYaml    starter 草稿全文（多文档），null 时视为空清单
     * @param templateYaml   TEMPLATE 草稿全文（多文档 {name, content}），null 时视为无模板覆盖
     * @param conflictYamlYaml CONFLICT_RULE 草稿全文（conflict-rules.yml 同构），null 时视为无规则
     * @return 校验通过的新快照
     * @throws BusinessException 解析/装载校验失败（4002：metadata 缺失或 starter/模板/冲突规则非法）
     */
    public CatalogSnapshot validateAndBuild(String metadataYaml, String starterYaml, String templateYaml,
                                            String conflictYamlYaml) {
        if (metadataYaml == null || metadataYaml.isBlank()) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "发布校验失败: metadata 配置缺失，首发快照必须显式提供 metadata.yml 同构配置");
        }
        MetadataCatalog metadataCatalog = parseMetadata(metadataYaml);
        StarterCatalog starterCatalog = parseStarters(starterYaml);
        Map<String, String> templateOverrides = parseTemplates(templateYaml);
        ConflictCatalog conflictCatalog = ConflictRuleParser.parseText(conflictYamlYaml);
        validateConflictReferences(conflictCatalog, metadataCatalog, starterCatalog);
        // M3.3 自动补位：starter 清单条目未在 metadata 登记时，追加为全部 Boot 版本可选
        // （制品库同步条目可见性；手工 yml 条目原本也可只登记清单——行为升级为自动可选）
        metadataCatalog = extendWithUnregistered(metadataCatalog, starterCatalog);
        // 交叉校验：metadata.bootVersions.starters 引用的 starter 必须在 starter 清单登记
        validateCrossReferences(metadataCatalog, starterCatalog);
        return new CatalogSnapshot(metadataCatalog, starterCatalog, templateOverrides, conflictCatalog);
    }

    /**
     * M3.1 兼容重载：无模板覆盖。
     *
     * @param metadataYaml metadata 草稿全文
     * @param starterYaml  starter 草稿全文（多文档）
     * @return 校验通过的新快照
     * @throws BusinessException 解析/装载校验失败
     */
    public CatalogSnapshot validateAndBuild(String metadataYaml, String starterYaml) {
        return validateAndBuild(metadataYaml, starterYaml, null, null);
    }

    /**
     * M3.2 兼容重载：无冲突规则。
     *
     * @param metadataYaml metadata 草稿全文
     * @param starterYaml  starter 草稿全文（多文档）
     * @param templateYaml 模板草稿全文（多文档 {name, content}）
     * @return 校验通过的新快照
     * @throws BusinessException 解析/装载校验失败
     */
    public CatalogSnapshot validateAndBuild(String metadataYaml, String starterYaml, String templateYaml) {
        return validateAndBuild(metadataYaml, starterYaml, templateYaml, null);
    }


/**
     * M3.3：解析 starter 清单 yml（多文档）中登记的全部条目 ID（不做清单级校验）。
     *
     * <p>供同步链路构建「手工配置保留集」与识别同步来源条目；解析失败视为空清单
     * （手工草稿在发布期已被校验拦截，此处宽松读取以同步链路可用性优先）。
     *
     * @param starterYaml starter 配置全文（多文档，可空）
     * @return 条目 ID 集合（保持配置顺序，去重）
     */
    public java.util.Set<String> parseStarterIds(String starterYaml) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        if (starterYaml == null || starterYaml.isBlank()) {
            return ids;
        }
        for (String document : splitDocuments(starterYaml)) {
            try {
                Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(document);
                if (root instanceof Map<?, ?> map && map.get("id") != null) {
                    ids.add(map.get("id").toString());
                }
            } catch (RuntimeException e) {
                log.warn("starter 清单 ID 解析跳过（宽松读取）: {}", e.getMessage());
            }
        }
        return ids;
    }

        /**
     * M3.3：metadata 自动补位——将 starter 清单中未登记 metadata 的条目追加为
     * 全部 Boot 版本可选（展示名取描述，坐标取首条依赖）。
     *
     * @param metadataCatalog 解析后的元数据目录
     * @param starterCatalog  解析后的 starter 清单
     * @return 原目录（全部已登记）或补位后的新目录
     */
    private MetadataCatalog extendWithUnregistered(MetadataCatalog metadataCatalog, StarterCatalog starterCatalog) {
        List<String> missingIds = starterCatalog.all().stream()
                .filter(entry -> !metadataCatalog.starterDefinitionIds().contains(entry.id()))
                .map(StarterCatalogEntry::id)
                .toList();
        if (missingIds.isEmpty()) {
            return metadataCatalog;
        }
        List<StarterDefinition> addedDefs = starterCatalog.all().stream()
                .filter(entry -> missingIds.contains(entry.id()))
                .map(entry -> {
                    String label = entry.description() == null || entry.description().isBlank()
                            ? entry.id() : entry.description();
                    DependencyCoord primary = entry.dependencies().get(0);
                    return new StarterDefinition(entry.id(), label,
                            new MavenDependency(primary.groupId(), primary.artifactId()));
                })
                .toList();
        log.info("starter 清单自动补位 metadata: {} 个条目（全部 Boot 版本可选）", missingIds.size());
        return metadataCatalog.withAdditionalStarters(addedDefs, missingIds);
    }

    /**
     * 解析 metadata 草稿。MetadataCatalog.load 的悬空引用/ID 重复校验直接复用，
     * 异常统一收敛为 4002（发布校验失败）。
     */
    private MetadataCatalog parseMetadata(String yaml) {
        try {
            return MetadataCatalog.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "发布校验失败: metadata 配置非法: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 starter 草稿（YAML 多文档，每文档一个 starter）。
     * YamlStarterParser / StarterCatalog 的全部装载期校验直接复用，零重复代码。
     */
    private StarterCatalog parseStarters(String starterYaml) {
        List<StarterCatalogEntry> entries = new ArrayList<>();
        if (starterYaml != null && !starterYaml.isBlank()) {
            for (String document : splitDocuments(starterYaml)) {
                try (var in = new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8))) {
                    entries.add(YamlStarterParser.parse(in));
                } catch (BusinessException e) {
                    throw new BusinessException(STARTER_INVALID,
                            "发布校验失败: starter 配置非法: " + e.getMessage(), e);
                } catch (Exception e) {
                    throw new BusinessException(STARTER_INVALID,
                            "发布校验失败: starter 配置读取失败: " + e.getMessage(), e);
                }
            }
        }
        try {
            return StarterCatalog.of(entries);
        } catch (BusinessException e) {
            throw new BusinessException(STARTER_INVALID,
                    "发布校验失败: starter 清单非法: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 TEMPLATE 草稿（M3.2）：多文档映射 {@code {name, content}}，构建模板覆盖映射。
     *
     * <p>装载期校验：name/content 必填、模板名不得重复、FreeMarker 语法预校验
     * （语法非法发布即拒绝，绝不让坏模板进入快照）。
     *
     * @param templateYaml 模板草稿全文（可空）
     * @return 模板名 → 模板全文映射（无覆盖时为空映射）
     * @throws BusinessException 模板草稿非法（4002）
     */
    private Map<String, String> parseTemplates(String templateYaml) {
        if (templateYaml == null || templateYaml.isBlank()) {
            return Map.of();
        }
        Map<String, String> overrides = new LinkedHashMap<>();
        for (String document : splitDocuments(templateYaml)) {
            Object root;
            try {
                root = new Yaml(new SafeConstructor(new LoaderOptions())).load(document);
            } catch (RuntimeException e) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "发布校验失败: template 配置解析失败: " + e.getMessage(), e);
            }
            if (!(root instanceof Map<?, ?> map)) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "发布校验失败: template 文档顶层必须为映射结构（含 name 与 content 字段）");
            }
            Object name = map.get("name");
            Object content = map.get("content");
            if (name == null || name.toString().isBlank()) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "发布校验失败: template 缺少必需字段: name");
            }
            if (content == null || content.toString().isBlank()) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "发布校验失败: template " + name + " 缺少必需字段: content");
            }
            String templateName = name.toString().trim();
            if (overrides.putIfAbsent(templateName, content.toString()) != null) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "发布校验失败: template 名称重复: " + templateName);
            }
            // FreeMarker 语法预校验：坏模板发布期即拒绝，不进入快照
            try {
                templateValidator.validate(templateName, content.toString());
            } catch (BusinessException e) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED, e.getMessage(), e);
            }
        }
        log.info("模板覆盖装载完成: {} 个模板", overrides.size());
        return overrides;
    }

    /**
     * 冲突规则悬空引用校验（M3.5）：规则引用的 starter id 必须在生效清单内
     * （metadata starters 节或 starter 清单任一登记即可，与 M3.3 自动补位语义一致——
     * starter 清单条目自动补位后必然可选中）。
     *
     * @param conflictCatalog 解析后的冲突规则目录
     * @param metadataCatalog 解析后的元数据目录
     * @param starterCatalog  解析后的 starter 清单
     * @throws BusinessException 规则引用了双边均未登记的 starter id（4002）
     */
    private void validateConflictReferences(ConflictCatalog conflictCatalog, MetadataCatalog metadataCatalog,
                                            StarterCatalog starterCatalog) {
        for (com.wymix.initializer.core.conflict.ConflictRule rule : conflictCatalog.all()) {
            for (String starterId : rule.starters()) {
                boolean known = metadataCatalog.starterDefinitionIds().contains(starterId)
                        || starterCatalog.contains(starterId);
                if (!known) {
                    throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                            "发布校验失败: 冲突规则 " + rule.id() + " 引用了清单中不存在的 starter: " + starterId);
                }
            }
        }
    }

    /**
     * 交叉悬空引用校验：M3.1 发布期兜底（metadata 装载期无法感知 starter 草稿清单）。
     *
     * <p>注意：web 等官方 starter 允许只在 metadata.yml 的 starters 节登记（无 starter 清单
     * yml，无联动规则）；此处校验的是 bootVersions.starters 引用的 ID 至少在 metadata
     * starters 节存在——该检查已由 MetadataCatalog 悬空引用校验覆盖，本方法额外校验
     * starter 清单与 metadata 双边都未登记的 ID 不可能存在（双边独立装载后天然保证）。
     */
    private void validateCrossReferences(MetadataCatalog metadataCatalog, StarterCatalog starterCatalog) {
        starterCatalog.all().forEach(entry -> {
            if (metadataCatalog.starterDefinitionOrNull(entry.id()) == null) {
                log.warn("starter 清单条目 {} 未在 metadata.yml starters 节登记（允许：仅清单维护，无展示名）", entry.id());
            }
        });
    }


    /**
     * 按 YAML 文档分隔符（行首 --- ）切分多文档；单文档原样返回。
     */
    static List<String> splitDocuments(String yaml) {
        List<String> documents = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : yaml.split("\n", -1)) {
            if (line.trim().equals("---")) {
                if (!current.toString().isBlank()) {
                    documents.add(current.toString());
                }
                current.setLength(0);
            } else {
                current.append(line).append('\n');
            }
        }
        if (!current.toString().isBlank()) {
            documents.add(current.toString());
        }
        return documents;
    }
}
