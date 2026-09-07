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
     * 默认构造：覆盖为空，yml 片段仅从 classpath 读取。
     *
     * @param starterCatalog starter 清单（联动规则来源）
     * @param renderer       模板渲染器（复用基础模板渲染器，模板目录一致）
     */
    public LinkageRenderer(StarterCatalog starterCatalog, TemplateRenderer renderer) {
        this(starterCatalog, renderer, Map.of());
    }

    /**
     * M3.5 扩展：传入模板覆盖映射。
     * <p>作用：
     * <ul>
     *   <li>.ftl 渲染仍由 {@link TemplateRenderer} 的 MultiTemplateLoader 覆盖（覆盖优先、classpath 兜底）</li>
     *   <li>application.yml 片段合并：键命中时直接用覆盖文本（处理 {@code \${} → ${} 还原），未命中时 classpath 兜底</li>
     * </ul>
     * 这样管理端 TEMPLATE 草稿可对 starter 的 application-*.yml 片段即时维护，发布即生效。
     *
     * @param starterCatalog starter 清单
     * @param renderer       模板渲染器
     * @param overrides      模板覆盖映射（null 视为空映射）
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
    public LinkageResult render(List<String> starterIds, Map<String, Object> model) {
        Map<String, byte[]> templateFiles = renderTemplates(starterIds, model);
        Map<String, Object> mergedYml = mergeYmlFragments(starterIds);
        return new LinkageResult(templateFiles, mergedYml);
    }

    /**
     * 抽象类别名跨 starter 冲突校验（评审修复）：
     * 多个 starter 声明同 alias 不同 fqcn 时显式报错（不静默覆盖），
     * 与「冲突显式失败」的整体原则保持一致。同 alias 同 fqcn 视为合法重复。
     */
    private void validateAliasConflicts(List<String> starterIds) {
        Map<String, String> owner = new LinkedHashMap<>();
        for (String starterId : starterIds) {
            if (!starterCatalog.contains(starterId)) {
                continue;
            }
            for (AbstractClassLinkage linkage : starterCatalog.entry(starterId).linkages().abstractClasses()) {
                String previous = owner.putIfAbsent(linkage.alias(), linkage.fqcn());
                if (previous != null && !previous.equals(linkage.fqcn())) {
                    throw new BusinessException(ErrorCode.TEMPLATE_TARGET_CONFLICT,
                            "抽象类别名冲突: " + linkage.alias() + "（" + previous
                                    + " 与 " + linkage.fqcn() + "，starter " + starterId + "）");
                }
            }
        }
    }

    /** 逐 starter 渲染模板联动，并做 target 路径冲突检测。 */
    private Map<String, byte[]> renderTemplates(List<String> starterIds, Map<String, Object> model) {
        validateAliasConflicts(starterIds);
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, String> targetOwner = new LinkedHashMap<>();
        for (String starterId : starterIds) {
            // 未登记 starter 清单的（如仅登记在 metadata.yml 的官方 starter）无联动规则，跳过
            if (!starterCatalog.contains(starterId)) {
                continue;
            }
            StarterCatalogEntry entry = starterCatalog.entry(starterId);
            for (TemplateLinkage template : entry.linkages().templates()) {
                String target = PlaceholderPolicy.replace(template.target(), model);
                String previous = targetOwner.putIfAbsent(target, starterId);
                if (previous != null) {
                    throw new BusinessException(ErrorCode.TEMPLATE_TARGET_CONFLICT,
                            "模板产物路径冲突: " + target + "（" + previous + " 与 " + starterId + "）");
                }
                Map<String, Object> templateModel = new LinkedHashMap<>(model);
                templateModel.putAll(abstractClassAliases(starterId));
                String configPackage = resolveConfigPackage(starterId, model);
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
    private Map<String, Object> mergeYmlFragments(List<String> starterIds) {
        List<YmlFragmentMerger.Fragment> fragments = new ArrayList<>();
        for (String starterId : starterIds) {
            if (!starterCatalog.contains(starterId)) {
                continue;
            }
            LinkageSpec linkages = starterCatalog.entry(starterId).linkages();
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
     * 读取 yml 片段字节：覆盖映射优先（含 URL 解码与 {@code \${} → ${}} 还原），classpath 兜底。
     *
     * <p>合并前做两步归一化（兼容管理端维护现状）：
     * <ol>
     *   <li>URL 解码：管理端草稿中的 {@code %24}（{@code $}）等若来自 URL 编码片段，合并时还原为字面字符</li>
     *   <li>FreeMarker 转义还原：发布期预校验要求 yml 中的 Spring 占位符写作 {@code \${...}}（转义为字面），
     *       合并时还原为 {@code ${...}} 以让生成项目运行时由 Spring 解析</li>
     * </ol>
     * classpath 内置 yml 直读路径不变，行为兼容。
     *
     * @param source yml 片段相对 templates/ 的源名
     * @return yml 字节
     * @throws BusinessException 覆盖与 classpath 均无该资源（3004）
     */
    private byte[] readYmlFragmentBytes(String source) {
        String override = overrides.get(source);
        if (override != null) {
            String restored = override;
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
        try (InputStream in = getClass().getResourceAsStream(TemplateRenderer.TEMPLATE_BASE + source)) {
            if (in == null) {
                throw new BusinessException(ErrorCode.YML_MERGE_CONFLICT,
                        "yml 片段不存在: " + source);
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.YML_MERGE_CONFLICT,
                    "yml 片段读取失败: " + source, e);
        }
    }

    /** 抽象类联动：alias → fqcn 映射，注入模板 context（跨 starter 冲突已由 {@link #validateAliasConflicts} 前置拦截）。 */
    private Map<String, Object> abstractClassAliases(String starterId) {
        if (!starterCatalog.contains(starterId)) {
            return Map.of();
        }
        Map<String, Object> aliases = new LinkedHashMap<>();
        for (AbstractClassLinkage linkage : starterCatalog.entry(starterId).linkages().abstractClasses()) {
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
        if (!starterCatalog.contains(starterId)) {
            return null;
        }
        String configPackage = starterCatalog.entry(starterId).linkages().configPackage();
        return configPackage == null ? null : PlaceholderPolicy.replace(configPackage, model);
    }

    /**
     * 依赖坐标汇总：按请求顺序取各 starter yml 声明的坐标（去重，保留首次出现）。
     *
     * @param starterIds starter ID 清单
     * @return 依赖坐标清单
     */
    public List<DependencyCoord> resolveDependencies(List<String> starterIds) {
        LinkedHashMap<String, DependencyCoord> deps = new LinkedHashMap<>();
        for (String starterId : starterIds) {
            if (!starterCatalog.contains(starterId)) {
                continue;
            }
            for (DependencyCoord dep : starterCatalog.entry(starterId).dependencies()) {
                String key = dep.groupId() + ":" + dep.artifactId();
                deps.putIfAbsent(key, dep);
            }
        }
        return List.copyOf(deps.values());
    }

    /** @return starter 是否已在清单登记 */
    public boolean registered(String starterId) {
        return starterCatalog.contains(starterId);
    }
}
