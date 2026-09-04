package com.wymix.initializer.app.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshot;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder;
import com.wymix.initializer.app.validation.ValidationService;
import com.wymix.initializer.core.generator.BuildScriptRenderer;
import com.wymix.initializer.core.generator.BuildTool;
import com.wymix.initializer.core.generator.GradleRenderer;
import com.wymix.initializer.core.generator.MavenRenderer;
import com.wymix.initializer.core.generator.GenerateRequest;
import com.wymix.initializer.core.generator.ProjectGenerator;
import com.wymix.initializer.core.generator.TemplateRenderer;
import org.springframework.stereotype.Service;

import java.io.OutputStream;

/**
 * 生成应用服务：生成前做元信息最终校验，失败抛业务异常（422 + 明细），不输出半成品。
 *
 * <p>M3.2 清单热刷新：生成不再固定绑定 classpath 单例 Catalog/Renderer——每次生成
 * 从 {@link CatalogSnapshotHolder} 取当前快照即时构建引擎（模板覆盖优先、classpath 兜底）。
 * 请求持有的快照不可变，发布切换不影响进行中的生成请求；快照未发布时回退
 * classpath 单例 Bean（兜底语义，行为与 M3.1 前一致）。
 *
 * <p>线程安全性：无状态单例（快照、渲染器、生成引擎均不可变/无状态），线程安全。
 */
@Service
public class GeneratorService {

    private final CatalogSnapshotHolder snapshotHolder;
    /** 兜底生成引擎（classpath 内置清单 + 内置模板） */
    private final ProjectGenerator fallbackGenerator;
    private final ValidationService validationService;
    /** 双轨构建脚本渲染器（无状态单例，快照模式与兜底模式共用） */
    private final BuildScriptRenderer mavenRenderer = new MavenRenderer();
    private final BuildScriptRenderer gradleRenderer = new GradleRenderer();

    /**
     * @param snapshotHolder    当前快照持有器（M3.2）
     * @param fallbackGenerator classpath 生成引擎（兜底，由 {@link GeneratorConfig} 装配）
     * @param validationService 元信息校验服务
     */
    public GeneratorService(CatalogSnapshotHolder snapshotHolder, ProjectGenerator fallbackGenerator,
                            ValidationService validationService) {
        this.snapshotHolder = snapshotHolder;
        this.fallbackGenerator = fallbackGenerator;
        this.validationService = validationService;
    }

    /**
     * 生成项目骨架 zip，流式写入目标输出流。
     *
     * <p>生成前完成元信息与版本组合的最终校验；校验失败或模板渲染失败时目标流不会被写入，
     * 保证不输出半成品。
     *
     * @param request 生成请求
     * @param out     zip 输出流（不关闭，由调用方管理）
     * @throws BusinessException 元信息非法（1001）、版本组合问题（2001/2002/2003）、生成失败（3001/3002/3003）
     */
    public void generate(GenerateRequest request, OutputStream out) {
        preValidate(request);
        generatorFor(request).generate(request, out);
    }

    /**
     * 产物 zip 文件名。
     *
     * @param request 生成请求
     * @return 如 {@code demo-app.zip}
     */
    public String zipFileName(GenerateRequest request) {
        return ProjectGenerator.zipFileName(request);
    }

    private void preValidate(GenerateRequest request) {
        // 元信息三项校验：任一失败即抛 1001，明细随异常文案输出
        var result = validationService.validate(request.group(), request.artifact(), request.basePackage());
        if (!result.valid()) {
            throw new BusinessException(ErrorCode.INVALID_METADATA, String.join("; ", result.errors()));
        }
    }

    /**
     * 按当前快照解析生成引擎：快照携带模板覆盖时以覆盖渲染器构建请求级引擎
     * （快照不可变，构建引擎线程安全），否则回退兜底单例。
     *
     * @param request 生成请求（构建工具决定双轨渲染实现）
     * @return 本次请求使用的生成引擎
     */
    private ProjectGenerator generatorFor(GenerateRequest request) {
        CatalogSnapshot snapshot = snapshotHolder.current();
        if (snapshot == null || snapshot.templateOverrides().isEmpty()) {
            return fallbackGenerator;
        }
        TemplateRenderer renderer = new TemplateRenderer(snapshot.templateOverrides());
        BuildScriptRenderer scriptRenderer =
                request.buildTool() == BuildTool.GRADLE ? gradleRenderer : mavenRenderer;
        return new ProjectGenerator(snapshot.metadataCatalog(), renderer, snapshot.starterCatalog(),
                scriptRenderer, scriptRenderer);
    }
}
