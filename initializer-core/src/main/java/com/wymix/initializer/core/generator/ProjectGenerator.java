package com.wymix.initializer.core.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.metadata.BootVersion;
import com.wymix.initializer.core.metadata.MavenDependency;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.DependencyCoord;
import com.wymix.initializer.core.starter.LinkageRenderer;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.YmlFragmentMerger;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 项目骨架生成引擎主流程：
 * <ol>
 *   <li>校验版本组合合法性（复用 {@link MetadataCatalog} 兼容矩阵）</li>
 *   <li>逐模板渲染（失败抛业务异常，不输出半成品）</li>
 *   <li>commons-compress zip 流式输出，文件名 {@code {artifact}.zip}</li>
 * </ol>
 *
 * <p>产物清单：构建脚本（M2.4 起 Maven pom.xml / Gradle build.gradle + settings.gradle 双轨，
 * 由请求 buildTool 决定）、启动类、application.yml、README.md，包结构与元信息一致；
 * M2.3 起接入联动渲染管线（{@link LinkageRenderer}）：starter yml 声明的模板联动、
 * application.yml 片段合并、抽象类引用随选中 starter 自动生成。
 *
 * <p>线程安全性：无状态单例（Renderer 与 Catalog 均不可变），线程安全。
 */
public class ProjectGenerator {

    private final MetadataCatalog catalog;
    private final TemplateRenderer renderer;
    private final LinkageRenderer linkageRenderer;
    /** Maven 构建脚本渲染实现（M2.4，双轨之一）。 */
    private final BuildScriptRenderer mavenRenderer;
    /** Gradle 构建脚本渲染实现（M2.4，双轨之一）。 */
    private final BuildScriptRenderer gradleRenderer;

    /**
     * @param catalog  元数据目录（版本合法性校验）
     * @param renderer 模板渲染器
     * @param starterCatalog starter 清单（联动规则来源；M2.3 新增，禁 null）
     */
    public ProjectGenerator(MetadataCatalog catalog, TemplateRenderer renderer, StarterCatalog starterCatalog) {
        this(catalog, renderer, new LinkageRenderer(starterCatalog, renderer));
    }

    /**
     * M2.4 全参构造：允许注入自定义构建脚本渲染实现（SPI 扩展点）。
     *
     * @param catalog       元数据目录
     * @param renderer      模板渲染器
     * @param starterCatalog starter 清单（联动规则来源）
     * @param mavenRenderer Maven 渲染实现
     * @param gradleRenderer Gradle 渲染实现
     */
    public ProjectGenerator(MetadataCatalog catalog, TemplateRenderer renderer, StarterCatalog starterCatalog,
                            BuildScriptRenderer mavenRenderer, BuildScriptRenderer gradleRenderer) {
        this(catalog, renderer, new LinkageRenderer(starterCatalog, renderer), mavenRenderer, gradleRenderer);
    }

    /** @deprecated M2.3 前旧签名，仅保留给无联动场景的旧调用方过渡；新代码应传 starterCatalog。 */
    @Deprecated(since = "0.1.0", forRemoval = true)
    public ProjectGenerator(MetadataCatalog catalog, TemplateRenderer renderer) {
        this(catalog, renderer, new LinkageRenderer(StarterCatalog.of(List.of()), renderer));
    }

    private ProjectGenerator(MetadataCatalog catalog, TemplateRenderer renderer, LinkageRenderer linkageRenderer) {
        this(catalog, renderer, linkageRenderer, new MavenRenderer(), new GradleRenderer());
    }

    private ProjectGenerator(MetadataCatalog catalog, TemplateRenderer renderer, LinkageRenderer linkageRenderer,
                             BuildScriptRenderer mavenRenderer, BuildScriptRenderer gradleRenderer) {
        this.catalog = catalog;
        this.renderer = renderer;
        this.linkageRenderer = linkageRenderer;
        this.mavenRenderer = mavenRenderer;
        this.gradleRenderer = gradleRenderer;
    }

    /**
     * 生成项目骨架 zip。
     *
     * @param request 生成请求
     * @return zip 字节流（完整可用，失败时抛异常而非返回半成品）
     * @throws BusinessException 版本不兼容（2001）、版本未知（2002）、starter 非法（2002）、
     *         构建工具非法（1004）、渲染失败（3001）
     */
    public byte[] generate(GenerateRequest request) {
        validateRequest(request);
        Map<String, byte[]> files = renderAll(request);
        return packZip(files);
    }

    /**
     * 生成项目骨架 zip，流式写入目标输出流（不落内存全量缓冲）。
     *
     * <p>先完成全部校验与模板渲染再开始写流，任一环节失败不会写入任何字节，
     * 调用方可保证不输出半成品。写流过程中的失败（3003）可能导致目标流内容不完整，
     * 调用方应放弃该输出（HTTP 层以异常中断响应实现）。
     *
     * @param request 生成请求
     * @param out     zip 输出流（本方法写入后不关闭，由调用方管理）
     * @throws BusinessException 版本不兼容（2001）、版本未知（2002）、starter 非法（2002）、
     *         构建工具非法（1004）、渲染失败（3001）、打包失败（3003）
     */
    public void generate(GenerateRequest request, OutputStream out) {
        validateRequest(request);
        Map<String, byte[]> files = renderAll(request);
        writeZip(files, out);
    }

    /**
     * 产物 zip 文件名：{@code {artifact}.zip}。
     *
     * @param request 生成请求
     * @return zip 文件名
     */
    public static String zipFileName(GenerateRequest request) {
        return request.artifact() + ".zip";
    }

    private void validateRequest(GenerateRequest request) {
        BootVersion bv = catalog.bootVersion(request.bootVersion());
        if (!bv.supportsJava(request.javaVersion())) {
            throw new BusinessException(ErrorCode.INCOMPATIBLE_VERSION,
                    "Boot 版本 " + request.bootVersion() + " 不兼容 Java 版本 " + request.javaVersion());
        }
        Set<String> available = new LinkedHashSet<>(catalog.starters(request.bootVersion()));
        for (String starter : request.starters()) {
            if (!available.contains(starter)) {
                throw new BusinessException(ErrorCode.UNKNOWN_STARTER,
                        "Boot 版本 " + request.bootVersion() + " 不支持 starter: " + starter);
            }
        }
    }

    /**
     * starter → Maven 依赖坐标解析（M2.3 起配置驱动：优先 starter 清单 yml 的 dependencies 节，
     * 未登记的回退 metadata.yml starters 节坐标映射）。
     *
     * @param starterIds starter ID 清单
     * @return 依赖坐标模型清单（保持请求顺序，去重）
     */
    private List<Map<String, String>> resolveStarterDependencies(List<String> starterIds) {
        List<Map<String, String>> deps = new ArrayList<>();
        // 跨来源统一去重：同一 G:A 坐标可能在 starter 清单与 metadata.yml 两边声明。
        // 约定：先遍历 starter 清单来源（含 version，Gradle GAV 透传），再遍历 metadata.yml 来源，
        // 同坐标冲突时清单声明优先——去重键统一为 groupId:artifactId。
        Set<String> seen = new LinkedHashSet<>();
        for (DependencyCoord dep : linkageRenderer.resolveDependencies(starterIds)) {
            if (seen.add(dep.groupId() + ":" + dep.artifactId())) {
                // M2.4：version 透传给 Gradle 模板（GAV 形式）；Maven 模板忽略 version 走 Boot BOM
                Map<String, String> coord = new LinkedHashMap<>();
                coord.put("groupId", dep.groupId());
                coord.put("artifactId", dep.artifactId());
                if (dep.version() != null && !dep.version().isBlank()) {
                    coord.put("version", dep.version());
                }
                deps.add(coord);
            }
        }
        for (String id : starterIds) {
            if (!starterRegistered(id)) {
                MavenDependency dep = catalog.starterDefinition(id).dependency();
                if (seen.add(dep.groupId() + ":" + dep.artifactId())) {
                    deps.add(Map.of("groupId", dep.groupId(), "artifactId", dep.artifactId()));
                }
            }
        }
        return deps;
    }

    private boolean starterRegistered(String starterId) {
        return linkageRenderer.registered(starterId);
    }

    private Map<String, byte[]> renderAll(GenerateRequest request) {
        Map<String, Object> model = buildModel(request);
        String packagePath = (String) model.get(TemplateRenderer.K_PACKAGE_PATH);

        // M2.4：构建脚本按 buildTool 双轨分派，写法差异隔离在 BuildScriptRenderer 之下
        BuildScriptRenderer buildScriptRenderer = request.buildTool() == BuildTool.GRADLE
                ? gradleRenderer : mavenRenderer;

        Map<String, RenderTask> tasks = new LinkedHashMap<>();
        tasks.put(buildScriptRenderer.buildScriptPath(),
                new RenderTask(buildScriptRenderer.buildScriptTemplate(), request.artifact() + "/" + buildScriptRenderer.buildScriptPath()));
        if (buildScriptRenderer.settingsTemplate() != null) {
            tasks.put(buildScriptRenderer.settingsPath(),
                    new RenderTask(buildScriptRenderer.settingsTemplate(),
                            request.artifact() + "/" + buildScriptRenderer.settingsPath()));
        }
        tasks.put("README.md", new RenderTask("README.md.ftl", request.artifact() + "/README.md"));
        tasks.put("Application.java", new RenderTask("Application.java.ftl",
                request.artifact() + "/src/main/java/" + packagePath + "/"
                        + mainClassName(request.artifact()) + ".java"));

        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Map.Entry<String, RenderTask> entry : tasks.entrySet()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            renderer.render(entry.getValue().template(), model, buf);
            byte[] prev = files.put(entry.getValue().target(), buf.toByteArray());
            if (prev != null) {
                throw new BusinessException(ErrorCode.TEMPLATE_TARGET_CONFLICT,
                        "模板产物路径冲突: " + entry.getValue().target());
            }
        }

        // 联动渲染管线（M2.3）：starter 模板联动 + application.yml 片段合并 + 抽象类引用
        LinkageRenderer.LinkageResult linkage = linkageRenderer.render(request.starters(), model);
        for (Map.Entry<String, byte[]> entry : linkage.templateFiles().entrySet()) {
            byte[] prev = files.put(request.artifact() + "/" + entry.getKey(), entry.getValue());
            if (prev != null) {
                throw new BusinessException(ErrorCode.TEMPLATE_TARGET_CONFLICT,
                        "模板产物路径冲突: " + entry.getKey());
            }
        }
        // application.yml：基础模板渲染后叠加合并 yml 片段；无片段时保持基础模板产物
        ByteArrayOutputStream baseYml = new ByteArrayOutputStream();
        renderer.render("application.yml.ftl", model, baseYml);
        String applicationYml = new String(baseYml.toByteArray(), StandardCharsets.UTF_8);
        Map<String, Object> mergedTree = linkage.mergedYml();
        if (mergedTree != null) {
            Map<String, Object> baseTree = YamlFacade.parse(applicationYml);
            applicationYml = YmlFragmentMerger.toYaml(YmlFragmentMerger.merge(baseTree,
                    List.of(new YmlFragmentMerger.Fragment(mergedTree,
                            com.wymix.initializer.core.starter.YmlFragmentLinkage.MergeStrategy.APPEND,
                            "linkage-merged"))));
        }
        files.put(request.artifact() + "/src/main/resources/application.yml",
                applicationYml.getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private Map<String, Object> buildModel(GenerateRequest request) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put(TemplateRenderer.K_BASE_PACKAGE, request.basePackage());
        model.put(TemplateRenderer.K_PACKAGE_PATH, request.basePackage().replace('.', '/'));
        model.put(TemplateRenderer.K_ARTIFACT, request.artifact());
        model.put(TemplateRenderer.K_GROUP, request.group());
        model.put(TemplateRenderer.K_BOOT_VERSION, request.bootVersion());
        model.put(TemplateRenderer.K_JAVA_VERSION, request.javaVersion());
        model.put(TemplateRenderer.K_STARTERS, new ArrayList<>(request.starters()));
        model.put(TemplateRenderer.K_STARTER_DEPENDENCIES, resolveStarterDependencies(request.starters()));
        return model;
    }

    /**
     * 启动类名：artifact 中划线转驼峰 + Application 后缀，如 demo-app → DemoAppApplication。
     *
     * @param artifact artifactId
     * @return 启动类名
     */
    static String mainClassName(String artifact) {
        StringBuilder sb = new StringBuilder();
        for (String part : artifact.split("-")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        sb.append("Application");
        return sb.toString();
    }

    private byte[] packZip(Map<String, byte[]> files) {
        ByteArrayOutputStream zipBuf = new ByteArrayOutputStream();
        writeZip(files, zipBuf);
        return zipBuf.toByteArray();
    }

    /** 将产物逐条写入 zip 输出流；IOException 统一收敛为 3003 业务异常。 */
    private void writeZip(Map<String, byte[]> files, OutputStream out) {
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            zip.setEncoding(StandardCharsets.UTF_8.name());
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                ZipArchiveEntry zipEntry = new ZipArchiveEntry(entry.getKey());
                zipEntry.setSize(entry.getValue().length);
                zip.putArchiveEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeArchiveEntry();
            }
            zip.finish();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.ZIP_PACK_FAILED, "zip 打包失败: " + e.getMessage(), e);
        }
    }

    /** 内部渲染任务：模板名 + 目标产物路径。 */
    private record RenderTask(String template, String target) {
    }
}
