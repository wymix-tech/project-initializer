package com.wymix.initializer.app.generator;

import com.wymix.initializer.core.generator.TemplateRenderer;
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
 * 测试支持：装载 classpath {@code templates/**} 为模板集映射（T5）。
 *
 * <p>T5 起模板集是 DB 唯一数据源、渲染器无 classpath 兜底，测试需显式构造模板集；
 * 生产侧同口径实现是 {@code DemoCatalogSeeder.loadClasspathTemplates()}。
 *
 * <p>线程安全性：仅静态纯函数，线程安全。
 */
public final class TestTemplates {

    /** classpath 模板集扫描模式（与生产 {@code DemoCatalogSeeder} 同口径）。 */
    public static final String PATTERN = "classpath*:" + TemplateRenderer.TEMPLATE_BASE + "**";

    private TestTemplates() {
        throw new AssertionError("测试支持类不可实例化");
    }

    /**
     * 装载 classpath 全部模板（测试类路径已包含 initializer-core 主资源）。
     *
     * @return 模板名（相对 {@code templates/}）→ 模板全文
     */
    public static Map<String, String> load() {
        Map<String, String> templates = new LinkedHashMap<>();
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
        } catch (IOException e) {
            throw new IllegalStateException("测试模板集扫描失败: " + PATTERN, e);
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

    /** @return 装载 classpath 全部模板的渲染器（唯一模板来源，无兜底） */
    public static TemplateRenderer renderer() {
        return new TemplateRenderer(load());
    }

    /**
     * 渲染「TEMPLATE 草稿」YAML（多文档 {@code {name, content}}），只含基础模板。
     *
     * <p>生成链路（pom/README/启动类/application.yml）只需 6 个基础模板；
     * starter 联动模板按需另加（如 {@code starters/xxx-redis/**}）。
     *
     * @return 与 {@code StructuredDraftWriter.writeTemplates} 同构的多文档 YAML
     */
    public static String templateYaml() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : load().entrySet()) {
            if (entry.getKey().contains("starters/")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("---\n");
            }
            sb.append("name: \"").append(entry.getKey()).append("\"\n");
            sb.append("content: |\n");
            for (String line : entry.getValue().split("\n", -1)) {
                sb.append("  ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 装载 classpath 全部模板（T5：模板集即全部可用模板，无兜底）。
     *
     * @return 模板名（相对 {@code templates/}）→ 模板全文
     */
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

    /**
     * 构造结构化模板集**保存请求** JSON（{@code /api/admin/templates} 的
     * {@code TemplateCatalogUpsertRequest} 契约），只含基础模板 + 调用方追加条目。
     *
     * <p>用于集成测试：T5 起模板集是生成期唯一模板来源，发布前必须把生成链路需要的
     * 基础模板整体提交。
     *
     * @param extraJson 追加的模板 JSON 对象（{@code {"name":..,"content":..}}），可空
     * @return 可直接 PUT 的请求体 JSON
     */
    public static String structuredTemplatesJson(String extraJson) {
        StringBuilder sb = new StringBuilder("{\"templates\":[");
        boolean first = true;
        for (Map.Entry<String, String> entry : load().entrySet()) {
            if (entry.getKey().contains("starters/")) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":").append(json(entry.getKey()))
                    .append(",\"content\":").append(json(entry.getValue())).append('}');
        }
        if (extraJson != null && !extraJson.isBlank()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(extraJson);
        }
        return sb.append("],\"operator\":\"tester\"}").toString();
    }

    /** 最小 JSON 字符串转义（模板全文含引号/换行，不能直接拼接）。 */
    private static String json(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
