package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.generator.TemplateRenderer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联动渲染管线（M2.3）：选中 starter 后按 linkages 自动生成配置代码与 yml 片段合并。
 *
 * <ol>
 *   <li>逐 starter 渲染 linkages.templates（占位符先替换 target/config-package）</li>
 *   <li>target 路径冲突检测：多 starter 模板写同一路径 → 3002 + 冲突清单</li>
 *   <li>收集 application-yml-fragments → 委托 {@link YmlFragmentMerger} 树合并</li>
 *   <li>abstract-classes 以 {@code alias → fqcn} 注入模板 context，供配置类 import/extends</li>
 * </ol>
 *
 * <p>新增 starter 仅需新增 yml + 模板文件，本类代码零改动。
 *
 * <p><b>T5 模板改为 DB 唯一数据源</b>：模板覆盖映射即**数据库全量模板集**，
 * 未命中即失败（3001/3004），<b>不再回退 classpath</b>。类路径资源访问已全部摘除。
 *
 * <p>线程安全性：无状态（依赖的 Renderer/Catalog 均不可变），线程安全。
 */
public class LinkageRenderer {

    private final StarterCatalog starterCatalog;
    private final TemplateRenderer renderer;
    /**
     * 模板覆盖映射（M3.2 扩展：M3.5 起同时驱动 .ftl 渲染覆盖与 yml 片段合并覆盖）。
     * 键为模板名（相对 templates/ 目录），值为模板全文。
     */
    private final Map<String, String> overrides;

    /**
     * 默认构造：模板集为空（渲染必失败），仅服务于纯依赖坐标解析等不需要模板的场景。
     *
     * @param starterCatalog starter 清单（联动规则来源）
     * @param renderer       模板渲染器（空模板集）
     */
    public LinkageRenderer(StarterCatalog starterCatalog, TemplateRenderer renderer) {
        this(starterCatalog, renderer, Map.of());
    }

    /**
     * 传入库级模板集映射（T5：DB 全量模板集，唯一数据源）。
     * <p>作用：
     * <ul>
     *   <li>.ftl 渲染由 {@link TemplateRenderer} 的内存模板加载器直接取用（未命中即 3001，无兜底）</li>
     *   <li>application.yml 片段合并：键命中时直接用模板集文本（处理 {@code \${} → ${} 还原），未命中即 3004</li>
     * </ul>
     * 管理端 TEMPLATE 草稿/模板集对 starter 的模板与 application-*.yml 片段即时维护，发布即生效。
     *
     * @param starterCatalog starter 清单
     * @param renderer       模板渲染器（装载同一份模板集）
     * @param overrides      模板集映射（null 视为空映射 = 无模板可用）
     */
    public LinkageRenderer(StarterCatalog starterCatalog, TemplateRenderer renderer, Map<String, String> overrides) {
        this.starterCatalog = starterCatalog;
        this.renderer = renderer;
        this.overrides = overrides == null ? Map.of() : overrides;
    }

    /**
     * 联动渲染结果：模板产物文件树 + 合并后的 yml 树（可空，无片段时为 null）。
     *
     * <p>线程安全性：不可变对象，线程安全。
     */
    public record LinkageResult(Map<String, byte[]> templateFiles, Map<String, Object> mergedYml) {
    }

    /**
     * 执行联动渲染。
     *
     * @param starterIds 请求中选中的 starter ID 清单（按选择顺序）
     * @param model      基础模板数据模型（含 basePackage/packagePath 等占位符取值）
     * @return 联动渲染结果；选中的 starter 均无联动规则时返回空产物
     * @throws BusinessException 模板 target 冲突（3002）、yml 合并冲突（3004）、渲染失败（3001）
     */
    /**
     * 执行联动渲染（忽略 Boot 版本关联，兼容旧调用方）。
     *
     * @param starterIds 请求中选中的 starter ID 清单（按选择顺序）
     * @param model      基础模板数据模型
     * @return 联动渲染结果
     */
    public LinkageResult render(List<String> starterIds, Map<String, Object> model) {
        return render(null, starterIds, model);
    }

    /**
     * 执行联动渲染（按 Boot 版本关联解析清单条目）。
     *
     * @param bootVersionId 当前 Boot 版本 ID（null 表示忽略版本关联）
     * @param starterIds    请求中选中的 starter ID 清单（按选择顺序）
     * @param model         基础模板数据模型（含 basePackage/packagePath 等占位符取值）
     * @return 联动渲染结果；选中的 starter 均无联动规则时返回空产物
     * @throws BusinessException 模板 target 冲突（3002）、yml 合并冲突（3004）、渲染失败（3001）
     */
    public LinkageResult render(String bootVersionId, List<String> starterIds, Map<String, Object> model) {
        Map<String, byte[]> templateFiles = renderTemplates(bootVersionId, starterIds, model);
        Map<String, Object> mergedYml = mergeYmlFragments(bootVersionId, starterIds);
        return new LinkageResult(templateFiles, mergedYml);
    }

    /** 按 Boot 版本关联取条目（bootVersionId 为 null 时忽略版本关联）；未登记返回 null。 */
    private StarterCatalogEntry entryFor(String bootVersionId, String starterId) {
        boolean known = bootVersionId == null
                ? starterCatalog.contains(starterId)
                : starterCatalog.contains(bootVersionId, starterId);
        if (!known) {
            return null;
        }
        return bootVersionId == null
                ? starterCatalog.entry(starterId)
                : starterCatalog.entry(bootVersionId, starterId);
    }

    /**
     * 抽象类别名跨 starter 冲突校验（评审修复）：
     * 多个 starter 声明同 alias 不同 fqcn 时显式报错（不静默覆盖），
     * 与「冲突显式失败」的整体原则保持一致。同 alias 同 fqcn 视为合法重复。
     */
    private void validateAliasConflicts(String bootVersionId, List<String> starterIds) {
        Map<String, String> owner = new LinkedHashMap<>();
        for (String starterId : starterIds) {
            StarterCatalogEntry entry = entryFor(bootVersionId, starterId);
            if (entry == null) {
                continue;
            }
            for (AbstractClassLinkage linkage : entry.linkages().abstractClasses()) {
                String previous = owner.putIfAbsent(linkage.alias(), linkage.fqcn());
                if (previous != null && !previous.equals(linkage.fqcn())) {
                    throw new BusinessException(ErrorCode.TEMPLATE_TARGET_CONFLICT,
                            "抽象类别名冲突: " + linkage.alias() + "（" + previous
                                    + " 与 " + linkage.fqcn() + "，starter " + starterId + "）");
                }
            }
        }
    }

    /**
     * 逐 starter 渲染模板联动，并做 target 路径冲突检测与「模板可达性」前置校验。
     *
     * <p>T5：模板集即 DB 全量模板集，无 classpath 兜底 —— 渲染前先把本轮需要的全部模板
     * 与 yml 片段来源校验一遍（{@link #validateRequirements}），保证「缺模板」在渲染任何
     * 文件之前就以明确明细失败，而不是产出一半后中途炸掉。
     */
    private Map<String, byte[]> renderTemplates(String bootVersionId, List<String> starterIds,
                                               Map<String, Object> model) {
        validateAliasConflicts(bootVersionId, starterIds);
        validateRequirements(bootVersionId, starterIds);
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, String> targetOwner = new LinkedHashMap<>();
        for (String starterId : starterIds) {
            // 未登记 starter 清单的（如仅登记在 metadata.yml 的官方 starter）无联动规则，跳过
            StarterCatalogEntry entry = entryFor(bootVersionId, starterId);
            if (entry == null) {
                continue;
            }
            for (TemplateLinkage template : entry.linkages().templates()) {
                String target = PlaceholderPolicy.replace(template.target(), model);
                String previous = targetOwner.putIfAbsent(target, starterId);
                if (previous != null) {
                    throw new BusinessException(ErrorCode.TEMPLATE_TARGET_CONFLICT,
                            "模板产物路径冲突: " + target + "（" + previous + " 与 " + starterId + "）");
                }
                Map<String, Object> templateModel = new LinkedHashMap<>(model);
                templateModel.putAll(abstractClassAliases(bootVersionId, starterId));
                String configPackage = resolveConfigPackage(bootVersionId, starterId, model);
                if (configPackage != null) {
                    templateModel.put("configPackage", configPackage);
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                renderer.render(template.source(), templateModel, buf);
                files.put(target, buf.toByteArray());
            }
        }
        return files;
    }

    /**
     * 收集全部 application-yml-fragments 并按请求顺序委托合并器合并。
     *
     * <p>读取优先级（M3.5）：快照模板覆盖映射 → classpath 资源。覆盖命中时对内容做
     * {@code \${} → ${}} 还原——发布期 FreeMarker 预校验要求 yml 中 Spring 占位符写作
     * {@code \${VAR:default}}（转义为字面），合并时还原为生成项目运行时所需的 {@code ${VAR:default}}。
     * classpath 内置 yml（含 {@code ${REDIS_HOST:localhost}} 风格）直读路径不变，行为兼容。
     *
     * @param starterIds 选中的 starter ID 清单
     * @return 合并后的 yml 树；选中的 starter 均无片段时为 null
     * @throws BusinessException 片段来源均不存在（3004）或读取失败（3004）
     */
    private Map<String, Object> mergeYmlFragments(String bootVersionId, List<String> starterIds) {
        List<YmlFragmentMerger.Fragment> fragments = new ArrayList<>();
        for (String starterId : starterIds) {
            StarterCatalogEntry entry = entryFor(bootVersionId, starterId);
            if (entry == null) {
                continue;
            }
            LinkageSpec linkages = entry.linkages();
            for (YmlFragmentLinkage fragmentLinkage : linkages.ymlFragments()) {
                String source = fragmentLinkage.source();
                try {
                    byte[] bytes = readYmlFragmentBytes(source);
                    try (InputStream in = new ByteArrayInputStream(bytes)) {
                        fragments.add(YmlFragmentMerger.Fragment.parse(in, source, fragmentLinkage.mergeStrategy()));
                    }
                } catch (BusinessException e) {
                    throw e;
                } catch (Exception e) {
                    throw new BusinessException(ErrorCode.YML_MERGE_CONFLICT,
                            "yml 片段读取失败: " + source + "（starter " + starterId + "）", e);
                }
            }
        }
        if (fragments.isEmpty()) {
            return null;
        }
        return YmlFragmentMerger.merge(null, fragments);
    }

    /**
     * 读取 yml 片段字节：**只**取自模板集映射（T5：DB 唯一数据源，无 classpath 兜底）。
     *
     * <p>合并前做两步归一化（兼容管理端维护现状）：
     * <ol>
     *   <li>URL 解码：管理端草稿中的 {@code %24}（{@code $}）等若来自 URL 编码片段，合并时还原为字面字符</li>
     *   <li>FreeMarker 转义还原：发布期预校验要求 yml 中的 Spring 占位符写作 {@code \${...}}（转义为字面），
     *       合并时还原为 {@code ${...}} 以让生成项目运行时由 Spring 解析</li>
     * </ol>
     *
     * @param source yml 片段相对 templates/ 的源名
     * @return yml 字节
     * @throws BusinessException 模板集中无该资源（3004）
     */
    private byte[] readYmlFragmentBytes(String source) {
        String template = overrides.get(source);
        if (template == null) {
            throw new BusinessException(ErrorCode.YML_MERGE_CONFLICT,
                    "yml 片段不存在: " + source + "（模板集无该条目）");
        }
        String restored = template;
        if (restored.indexOf('%') >= 0) {
            try {
                restored = URLDecoder.decode(restored, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // 非法 URL 编码片段（如不完整 %XY）：保留原文不阻断
            }
        }
        restored = restored.replace("\\${", "${");
        return restored.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 模板可达性前置校验（T5「缺模板即渲染失败」的明确化）：把本轮生成需要的全部
     * starter 模板（{@code linkages.templates[].source}）与 yml 片段来源
     * （{@code linkages.application-yml-fragments[].source}）在**模板集映射**里查一遍，
     * 缺失即汇总明细抛 3001，避免「渲染到一半才发现缺模板」。
     *
     * <p>管理端发布期「模板可达性」硬校验与实际生成共用本方法，保证不会出现
     * 「发布通过但生成失败」。
     *
     * @param bootVersionId 当前 Boot 版本 ID（null 表示忽略版本关联）
     * @param starterIds    选中的 starter ID 清单（可空）
     * @throws BusinessException 任一模板/片段来源在模板集中缺失（3001）
     */
    public void validateRequirements(String bootVersionId, List<String> starterIds) {
        if (starterIds == null || starterIds.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String starterId : starterIds) {
            StarterCatalogEntry entry = entryFor(bootVersionId, starterId);
            if (entry == null) {
                continue;
            }
            for (TemplateLinkage linkage : entry.linkages().templates()) {
                if (!overrides.containsKey(linkage.source())) {
                    missing.add("starter " + starterId + " 模板缺失: " + linkage.source());
                }
            }
            for (YmlFragmentLinkage linkage : entry.linkages().ymlFragments()) {
                if (!overrides.containsKey(linkage.source())) {
                    missing.add("starter " + starterId + " yml 片段缺失: " + linkage.source());
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.TEMPLATE_RENDER_FAILED,
                    "模板集缺失必需模板（DB 模板集为唯一数据源）: " + String.join("; ", missing));
        }
    }

    /** 抽象类联动：alias → fqcn 映射，注入模板 context（跨 starter 冲突已由 {@link #validateAliasConflicts} 前置拦截）。 */
    private Map<String, Object> abstractClassAliases(String bootVersionId, String starterId) {
        StarterCatalogEntry entry = entryFor(bootVersionId, starterId);
        if (entry == null) {
            return Map.of();
        }
        Map<String, Object> aliases = new LinkedHashMap<>();
        for (AbstractClassLinkage linkage : entry.linkages().abstractClasses()) {
            aliases.put(linkage.alias(), linkage.fqcn());
        }
        return aliases;
    }

    /**
     * 配置包名解析：config-package 占位符替换。
     *
     * @param starterId starter ID
     * @param model     占位符取值
     * @return 解析后的包名；starter 未声明 config-package 时为 null
     */
    public String resolveConfigPackage(String starterId, Map<String, Object> model) {
        return resolveConfigPackage(null, starterId, model);
    }

    /**
     * 按 Boot 版本关联解析配置类包名。
     *
     * @param bootVersionId Boot 版本 ID（null 表示忽略版本关联）
     * @param starterId     starter ID
     * @param model         占位符取值
     * @return 解析后的包名；starter 未声明 config-package 或未关联该版本时为 null
     */
    public String resolveConfigPackage(String bootVersionId, String starterId, Map<String, Object> model) {
        StarterCatalogEntry entry = entryFor(bootVersionId, starterId);
        if (entry == null) {
            return null;
        }
        String configPackage = entry.linkages().configPackage();
        return configPackage == null ? null : PlaceholderPolicy.replace(configPackage, model);
    }

    /**
     * 依赖坐标汇总：按请求顺序取各 starter yml 声明的坐标（去重，保留首次出现）。
     *
     * @param starterIds starter ID 清单
     * @return 依赖坐标清单
     */
    public List<DependencyCoord> resolveDependencies(List<String> starterIds) {
        return resolveDependencies(null, starterIds);
    }

    /**
     * 依赖坐标汇总（按 Boot 版本关联解析清单条目）：按请求顺序取各 starter 声明的坐标（去重，保留首次出现）。
     *
     * @param bootVersionId Boot 版本 ID（null 表示忽略版本关联）
     * @param starterIds    starter ID 清单
     * @return 依赖坐标清单
     */
    public List<DependencyCoord> resolveDependencies(String bootVersionId, List<String> starterIds) {
        LinkedHashMap<String, DependencyCoord> deps = new LinkedHashMap<>();
        for (String starterId : starterIds) {
            StarterCatalogEntry entry = entryFor(bootVersionId, starterId);
            if (entry == null) {
                continue;
            }
            for (DependencyCoord dep : entry.dependencies()) {
                String key = dep.groupId() + ":" + dep.artifactId();
                deps.putIfAbsent(key, dep);
            }
        }
        return List.copyOf(deps.values());
    }

    /** @return starter 是否已在清单登记（忽略 Boot 版本关联） */
    public boolean registered(String starterId) {
        return starterCatalog.contains(starterId);
    }

    /** @return starter 是否已在清单登记且关联指定 Boot 版本 */
    public boolean registered(String bootVersionId, String starterId) {
        return bootVersionId == null
                ? starterCatalog.contains(starterId)
                : starterCatalog.contains(bootVersionId, starterId);
    }
}
