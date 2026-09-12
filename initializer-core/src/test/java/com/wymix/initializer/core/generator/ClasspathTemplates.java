package com.wymix.initializer.core.generator;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试支持：装载 classpath {@code templates/**} 为「模板名 → 模板全文」映射。
 *
 * <p>T5 起模板集是 DB 唯一数据源、渲染器无 classpath 兜底，故测试需显式构造模板集。
 * 生产侧同口径实现是 {@code --demo} 的 {@code DemoCatalogSeeder.loadClasspathTemplates()}；
 * 本类是同口径的测试替身（扫描模式同为 {@code classpath*:templates/**}）。
 *
 * <p>线程安全性：仅静态纯函数，线程安全。
 */
public final class ClasspathTemplates {

    /** classpath 模板集扫描模式（与生产 {@code DemoCatalogSeeder} 同口径）。 */
    public static final String PATTERN = "classpath*:" + TemplateRenderer.TEMPLATE_BASE + "**";

    private ClasspathTemplates() {
        throw new AssertionError("测试支持类不可实例化");
    }

    /**
     * 装载 classpath 全部模板。
     *
     * @return 模板名（相对 {@code templates/}，{@code /} 分隔）→ 模板全文
     */
    public static Map<String, String> load() {
        return load(null);
    }

    /**
     * 装载 classpath 全部模板，并在指定目录下额外覆盖（如 {@code starters/e2e-redis}）。
     *
     * @param extraDir 额外模板目录（相对 templates/），可空
     * @return 模板集映射
     */
    public static Map<String, String> load(String extraDir) {
        String pattern = extraDir == null || extraDir.isBlank() ? PATTERN : PATTERN + extraDir + "/**";
        Map<String, String> templates = new LinkedHashMap<>();
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(pattern);
        } catch (IOException e) {
            throw new IllegalStateException("测试模板集扫描失败: " + pattern, e);
        }
        List<Resource> sorted = new ArrayList<>(List.of(resources));
        sorted.sort((a, b) -> String.valueOf(a).compareTo(String.valueOf(b)));
        for (Resource resource : sorted) {
            String name = name(resource);
            if (name == null) {
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                templates.putIfAbsent(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("测试模板读取失败: " + resource, e);
            }
        }
        return templates;
    }

    /**
     * 以 classpath 模板集构造渲染器（T5 口径：模板集即全部可用模板，无兜底）。
     *
     * @return 装载 classpath 全部模板的渲染器
     */
    public static TemplateRenderer renderer() {
        return new TemplateRenderer(load());
    }

    /** 由资源定位反推模板名（相对 templates/ 的路径）；无法定位时返回 null。 */
    private static String name(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null || !filename.contains(".")) {
            // 目录型资源：jar 与目录两种协议下目录 URL 都不带尾斜杠，只能靠扩展名判定
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
}
