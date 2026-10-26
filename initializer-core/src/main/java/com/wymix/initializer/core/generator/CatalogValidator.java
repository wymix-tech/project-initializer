package com.wymix.initializer.core.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.conflict.ConflictCatalog;
import com.wymix.initializer.core.metadata.BootVersion;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.LinkageRenderer;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Publication preflight: references and actual rendering, without claiming external builds passed. */
public final class CatalogValidator {
    private static final int MAX_SEARCH = 2048;

    /**
     * 校验各 Boot 版本的最小组合及每个 Starter 的可满足组合，实际渲染受支持构建工具的产物。
     * @param metadata 已合并 Starter 的元数据
     * @param starters Starter 清单
     * @param templates 唯一模板集
     * @param conflicts 互斥规则
     * @throws BusinessException 引用、组合或渲染校验失败（4002，含对象与路径）
     */
    public void validate(MetadataCatalog metadata, StarterCatalog starters, Map<String, String> templates,
                         ConflictCatalog conflicts) {
        for (StarterCatalogEntry entry : starters.all()) {
            for (String bootId : entry.bootVersions()) {
                try {
                    metadata.bootVersion(bootId);
                } catch (BusinessException e) {
                    throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                            "发布预检失败: starter " + entry.id() + " 引用未知 Boot 版本 " + bootId, e);
                }
            }
        }
        TemplateRenderer renderer = new TemplateRenderer(templates);
        LinkageRenderer linkage = new LinkageRenderer(starters, renderer, templates);
        ProjectGenerator generator = new ProjectGenerator(metadata, renderer, starters);
        for (BootVersion boot : metadata.bootVersions()) {
            String context = "Boot " + boot.id();
            try {
                List<String> available = metadata.starters(boot.id());
                linkage.validateRequirements(boot.id(), available);
                if (boot.supportedJavaVersions().isEmpty()) {
                    throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED, "没有兼容 Java 版本");
                }
                List<List<String>> seeds = new ArrayList<>();
                seeds.add(List.of());
                for (String id : available) {
                    seeds.add(List.of(id));
                }
                for (List<String> seed : seeds) {
                    context = "Boot " + boot.id() + " / starter " + seed;
                    Set<String> selection = new LinkedHashSet<>(metadata.requiredStarters(boot.id()));
                    selection.addAll(seed);
                    List<String> resolved = satisfy(boot.id(), selection, available, starters, conflicts, new int[]{0});
                    if (resolved == null) {
                        throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                                "依赖与互斥约束无法满足，或超过预检组合搜索上限 " + MAX_SEARCH);
                    }
                    for (BuildTool tool : BuildTool.values()) {
                        // 跳过该 Boot 版本未声明支持的构建工具（当前 maven / gradle 均受支持，保留兜底）
                        if (!boot.supportedBuildTools().contains(tool.name().toLowerCase(java.util.Locale.ROOT))) {
                            continue;
                        }
                        context = "Boot " + boot.id() + " / starter " + seed + " / " + tool;
                        generator.generate(new GenerateRequest("com.example", "preflight", "com.example.preflight",
                                boot.supportedJavaVersions().get(0), boot.id(), resolved, tool));
                    }
                }
            } catch (BusinessException e) {
                throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                        "发布预检失败 [" + context + "]: " + e.getMessage(), e);
            }
        }
    }

    /** Bounded backtracking handles OR dependencies without blindly choosing a conflicting candidate. */
    private List<String> satisfy(String boot, Set<String> selection, List<String> available,
                                 StarterCatalog starters, ConflictCatalog conflicts, int[] attempts) {
        if (++attempts[0] > MAX_SEARCH || !conflicts.evaluate(selection).isEmpty()) {
            return null;
        }
        for (String id : selection) {
            StarterCatalogEntry entry = starters.contains(boot, id) ? starters.entry(boot, id) : null;
            if (entry == null) {
                continue;
            }
            for (List<String> group : entry.requires()) {
                if (group.stream().anyMatch(selection::contains)) {
                    continue;
                }
                for (String candidate : group) {
                    if (available.contains(candidate)) {
                        Set<String> expanded = new LinkedHashSet<>(selection);
                        expanded.add(candidate);
                        List<String> resolved = satisfy(boot, expanded, available, starters, conflicts, attempts);
                        if (resolved != null) {
                            return resolved;
                        }
                    }
                }
                return null;
            }
        }
        return List.copyOf(selection);
    }
}
