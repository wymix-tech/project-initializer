package com.wymix.initializer.core.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.metadata.BootVersion;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * <p>产物清单：pom.xml、启动类、application.yml、README.md，包结构与元信息一致。
 *
 * <p>线程安全性：无状态单例（Renderer 与 Catalog 均不可变），线程安全。
 */
public class ProjectGenerator {

    /** 生成上下文：产物路径 → 渲染后的文件内容。 */
    private final MetadataCatalog catalog;
    private final TemplateRenderer renderer;

    /**
     * @param catalog  元数据目录（版本合法性校验）
     * @param renderer 模板渲染器
     */
    public ProjectGenerator(MetadataCatalog catalog, TemplateRenderer renderer) {
        this.catalog = catalog;
        this.renderer = renderer;
    }

    /**
     * 生成项目骨架 zip。
     *
     * @param request 生成请求
     * @return zip 字节流（完整可用，失败时抛异常而非返回半成品）
     * @throws BusinessException 版本不兼容（2001）、版本未知（2002）、starter 非法（2002）、渲染失败（3001）
     */
    public byte[] generate(GenerateRequest request) {
        validateRequest(request);
        Map<String, byte[]> files = renderAll(request);
        return packZip(files);
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
                throw new BusinessException(ErrorCode.METADATA_NOT_FOUND,
                        "Boot 版本 " + request.bootVersion() + " 不支持 starter: " + starter);
            }
        }
    }

    private Map<String, byte[]> renderAll(GenerateRequest request) {
        Map<String, Object> model = buildModel(request);
        String packagePath = (String) model.get(TemplateRenderer.K_PACKAGE_PATH);
        String basePackage = (String) model.get(TemplateRenderer.K_BASE_PACKAGE);

        Map<String, RenderTask> tasks = new LinkedHashMap<>();
        tasks.put("pom.xml", new RenderTask("pom.xml.ftl", request.artifact() + "/pom.xml"));
        tasks.put("application.yml", new RenderTask("application.yml.ftl",
                request.artifact() + "/src/main/resources/application.yml"));
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
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(zipBuf)) {
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
        return zipBuf.toByteArray();
    }

    /** 内部渲染任务：模板名 + 目标产物路径。 */
    private record RenderTask(String template, String target) {
    }
}
