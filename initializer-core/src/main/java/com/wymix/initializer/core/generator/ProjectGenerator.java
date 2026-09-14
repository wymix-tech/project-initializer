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
 * <p><b>T5 模板改为 DB 唯一数据源</b>：渲染器持有的模板集即数据库全量模板集，
 * 无 classpath 兜底——任一必需模板缺失即 3001 失败（{@link #validateRequirements} 前置汇总明细）。
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
     * 默认构造：Maven/Gradle 渲染器 + 覆盖为空。
     *
     * @param catalog        元数据目录（版本合法性校验）
     * @param renderer       模板渲染器
     * @param starterCatalog starter 清单（联动规则来源；M2.3 新增，禁 null）
     */
    public ProjectGenerator(MetadataCatalog catalog, TemplateRenderer renderer, StarterCatalog starterCatalog) {
        this(catalog, renderer, starterCatalog, new MavenRenderer(), new GradleRenderer(),
                renderer.templates());
    }

    /**
     * M2.4 全参构造：允许注入自定义构建脚本渲染实现（SPI 扩展点）。覆盖映射为空。
     *
     * @param catalog        元数据目录
     * @param renderer       模板渲染器
     * @param starterCatalog starter 清单（联动规则来源）
     * @param mavenRenderer  Maven 渲染实现
     * @param gradleRenderer Gradle 渲染实现
     */
    public ProjectGenerator(MetadataCatalog catalog, TemplateRenderer renderer, StarterCatalog starterCatalog,
                            BuildScriptRenderer mavenRenderer, BuildScriptRenderer gradleRenderer) {
        this(catalog, renderer, starterCatalog, mavenRenderer, gradleRenderer, renderer.templates());
    }

    /**
     * T5 全参构造：传入**唯一模板集**（DB 全量模板集），同时驱动 .ftl 渲染、yml 片段合并与
     * 「模板可达性」校验。
     *
     * @param catalog            元数据目录
     * @param renderer           模板渲染器
     * @param starterCatalog     starter 清单（联动规则来源）
     * @param mavenRenderer      Maven 渲染实现
     * @param gradleRenderer     Gradle 渲染实现
     * @param templateSet  模板集（T5：DB 全量模板集，唯一数据源；null 时取渲染器自带模板集）
     */
    public ProjectGenerator(MetadataCatalog catalog, TemplateRenderer renderer, StarterCatalog starterCatalog,
                            BuildScriptRenderer mavenRenderer, BuildScriptRenderer gradleRenderer,
                            Map<String, String> templateSet) {
        this.catalog = catalog;
        this.renderer = renderer;
        Map<String, String> templates = templateSet == null ? renderer.templates() : templateSet;
        this.linkageRenderer = new LinkageRenderer(starterCatalog, renderer, templates);
        this.mavenRenderer = mavenRenderer;
        this.gradleRenderer = gradleRenderer;
    }

    /** @deprecated M2.3 前旧签名，仅保留给无联动场景的旧调用方过渡；新代码应传 starterCatalog。 */
    @Deprecated(since = "0.1.0", forRemoval = true)
    public ProjectGenerator(MetadataCatalog catalog, TemplateRenderer renderer) {
        this(catalog, renderer, StarterCatalog.of(List.of()), new MavenRenderer(), new GradleRenderer(), Map.of());
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
        GenerateRequest effective = withRequiredStarters(request);
        validateRequest(effective);
        linkageRenderer.validateRequirements(effective.bootVersion(), effective.starters());
        Map<String, byte[]> files = renderAll(effective);
        return packZip(files);
    }

    /**
     * 补齐必选 starter（M3.6）：把当前 Boot 版本的必选项合并进请求（必选项在前，去重保序）。
     *
     * <p>兜底语义：请求未携带必选项时自动加入，不报错——保证生成产物一定包含必选能力
     * （前端同策略默认勾选并禁用取消，此处是最后防线，防止绕过前端直调 API）。
     *
     * @param request 原始生成请求
     * @return 补齐必选项后的请求（无必选项或已全部携带时返回原对象）
     */
    private GenerateRequest withRequiredStarters(GenerateRequest request) {
        List<String> required = catalog.requiredStarters(request.bootVersion());
        List<String> explicit = request.starters();
        // 种子 = 必选项 + 用户选择；resolveRequires 不再自动带上 requires 依赖，
        // 只校验依赖分组是否被满足（未满足即抛 2004），故此处无需再求传递闭包
        List<String> seed = new ArrayList<>(required);
        seed.addAll(explicit);
        List<String> resolved = catalog.resolveRequires(request.bootVersion(), seed);
        // 闭包未引入新项（用户选择已覆盖全部隐式依赖）时保持用户原本顺序
        if (explicit.containsAll(resolved)) {
            return request;
        }
        return new GenerateRequest(request.group(), request.artifact(), request.basePackage(),
                request.javaVersion(), request.bootVersion(), resolved, request.buildTool());
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
        GenerateRequest effective = withRequiredStarters(request);
        validateRequest(effective);
        linkageRenderer.validateRequirements(effective.bootVersion(), effective.starters());
        Map<String, byte[]> files = renderAll(effective);
        writeZip(files, out);
    }

    /**
     * 生成前置校验（T5）：解析生效请求（补齐必选 starter）后校验
     * ①元信息/版本组合 ②starter 可达性 ③模板集可达性 ④冲突规则，
     * 全部通过才允许进入渲染。
     *
     * <p>对外暴露给 {@code GeneratorService}，让「模板缺失」在写任何字节之前就以
     * 明确明细失败（不输出半成品）。
     *
     * @param request 生成请求
     * @throws BusinessException 版本组合非法（2001/2002）、starter 不支持（2003）、
     *         模板缺失（3001）
     */
    public void validate(GenerateRequest request) {
        GenerateRequest effective = withRequiredStarters(request);
        validateRequest(effective);
        linkageRenderer.validateRequirements(effective.bootVersion(), effective.starters());
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
    private List<Map<String, String>> resolveStarterDependencies(String bootVersionId, List<String> starterIds) {
        List<Map<String, String>> deps = new ArrayList<>();
        // 跨来源统一去重：同一 G:A 坐标可能在 starter 清单与 metadata.yml 两边声明。
        // 约定：先遍历 starter 清单来源（含 version，Gradle GAV 透传），再遍历 metadata.yml 来源，
        // 同坐标冲突时清单声明优先——去重键统一为 groupId:artifactId。
        Set<String> seen = new LinkedHashSet<>();
        // 按 Boot 版本关联解析清单条目（同 ID 在不同 Boot 版本可有不同坐标/版本）
        for (DependencyCoord dep : linkageRenderer.resolveDependencies(bootVersionId, starterIds)) {
            if (seen.add(dep.groupId() + ":" + dep.artifactId())) {
                // version 双轨透传（Gradle GAV / Maven <version>）：仅声明了 version 的依赖输出，
                // 官方 starter 等无 version 坐标继续走 Boot BOM（M2.4 + 验收 P1-1 修复：
                // 内部 starter 不在 Boot BOM 管理范围，Maven pom 必须显式输出版本）
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
            if (!starterRegistered(bootVersionId, id)) {
                MavenDependency dep = catalog.starterDefinition(id).dependency();
                if (dep == null) {
                    // 既无 starter 清单文件、metadata starters 节也未登记 dependency：无法生成依赖
                    throw new BusinessException(ErrorCode.UNKNOWN_STARTER,
                            "starter " + id + " 缺少依赖坐标：需在 starter 清单（catalog/starters/*.yml）"
                                    + "声明 dependencies，或在 metadata.yml 的 starters." + id + ".dependency 登记");
                }
                if (seen.add(dep.groupId() + ":" + dep.artifactId())) {
                    deps.add(Map.of("groupId", dep.groupId(), "artifactId", dep.artifactId()));
                }
            }
        }
        return deps;
    }

    private boolean starterRegistered(String bootVersionId, String starterId) {
        return linkageRenderer.registered(bootVersionId, starterId);
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
        LinkageRenderer.LinkageResult linkage =
                linkageRenderer.render(request.bootVersion(), request.starters(), model);
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
        model.put(TemplateRenderer.K_STARTER_DEPENDENCIES,
                resolveStarterDependencies(request.bootVersion(), request.starters()));
        // 框架父 POM：按所选 Boot 版本取 bootVersions[].frameworkParent（不同版本可挂不同父 POM），
        // 未配置时回退 spring-boot-starter-parent:${bootVersion}
        com.wymix.initializer.core.metadata.FrameworkParent fp =
                catalog.bootVersion(request.bootVersion()).frameworkParent();
        if (fp == null) {
            fp = com.wymix.initializer.core.metadata.FrameworkParent.defaultSpringBootParent(request.bootVersion());
        }
        model.put("parentGroupId", fp.groupId());
        model.put("parentArtifactId", fp.artifactId());
        model.put("parentVersion", fp.version());
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
