package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.generator.TemplateRenderer;

import java.io.ByteArrayOutputStream;
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
     * @param starterCatalog starter 清单（联动规则来源）
     * @param renderer       模板渲染器（复用基础模板渲染器，模板目录一致）
     */
    public LinkageRenderer(StarterCatalog starterCatalog, TemplateRenderer renderer) {
        this.starterCatalog = starterCatalog;
        this.renderer = renderer;
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

    /** 收集全部 application-yml-fragments 并按请求顺序委托合并器合并。 */
    private Map<String, Object> mergeYmlFragments(List<String> starterIds) {
        List<YmlFragmentMerger.Fragment> fragments = new ArrayList<>();
        for (String starterId : starterIds) {
            if (!starterCatalog.contains(starterId)) {
                continue;
            }
            LinkageSpec linkages = starterCatalog.entry(starterId).linkages();
            for (YmlFragmentLinkage fragmentLinkage : linkages.ymlFragments()) {
                String source = fragmentLinkage.source();
                try (var in = getClass().getResourceAsStream(TemplateRenderer.TEMPLATE_BASE + source)) {
                    if (in == null) {
                        throw new BusinessException(ErrorCode.YML_MERGE_CONFLICT,
                                "yml 片段不存在: " + source + "（starter " + starterId + "）");
                    }
                    fragments.add(YmlFragmentMerger.Fragment.parse(in, source, fragmentLinkage.mergeStrategy()));
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
