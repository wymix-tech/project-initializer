package com.wymix.initializer.app.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshot;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder;
import com.wymix.initializer.app.validation.ValidationService;
import com.wymix.initializer.core.conflict.ConflictCatalog;
import com.wymix.initializer.core.generator.BuildScriptRenderer;
import com.wymix.initializer.core.generator.BuildTool;
import com.wymix.initializer.core.generator.GradleRenderer;
import com.wymix.initializer.core.generator.MavenRenderer;
import com.wymix.initializer.core.generator.GenerateRequest;
import com.wymix.initializer.core.generator.ProjectGenerator;
import com.wymix.initializer.core.generator.TemplateRenderer;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * 生成应用服务：生成前做元信息最终校验，失败抛业务异常（422 + 明细），不输出半成品。
 *
 * <p>M3.2 清单热刷新：生成不再固定绑定 classpath 单例 Catalog/Renderer——每次生成
 * 从 {@link CatalogSnapshotHolder} 取当前快照即时构建引擎。请求持有的快照不可变，
 * 发布切换不影响进行中的生成请求；快照未播种（null）时才回退启动期单例引擎
 * （{@code --demo} 下即「内置清单入库后自动发布」的那一份快照，见 {@link GeneratorConfig}）。
 *
 * <p><b>T5 模板改为 DB 唯一数据源</b>：引擎构建不再使用 classpath 模板兜底，
 * 模板集一律取自快照（{@link CatalogSnapshot#templateOverrides()}，语义为 DB 全量模板集）。
 * 快照模板集为空时 → 生成前置校验阶段即 3001 失败（明确明细），<b>不会产出空骨架</b>。
 *
 * <p>验收修复（P1-2）：兜底条件由「模板覆盖为空」收紧为「快照不存在」——
 * 热发布仅新增/调整清单时，生成侧同样走快照引擎，
 * 保证零代码新增 starter 对生成接口即时生效（否则新 starter 选中即 2003）。
 *
 * <p>M3.5 依赖冲突兜底校验（硬校验）：生成前用当前快照的冲突规则复算已选 starter
 * 组合，命中规则抛 422 + 4007（防绕过前端直调）；前端提示为软提示，此处为最后防线。
 *
 * <p>线程安全性：无状态单例（快照、渲染器、生成引擎均不可变/无状态），线程安全。
 */
@Service
public class GeneratorService {

    private final CatalogSnapshotHolder snapshotHolder;
    /**
     * 启动期兜底生成引擎（T5：快照未播种时使用）。
     *
     * <p>由 {@link GeneratorConfig} 装配：{@code --demo} 下即「内置清单 + 内置模板入库后自动
     * 发布」的那份快照对应引擎；非 demo 下为空清单 + 空模板集（任何生成都在前置校验失败）。
     * <b>不再使用 classpath 模板兜底</b>。
     */
    private final ProjectGenerator fallbackGenerator;
    private final ValidationService validationService;
    /** 双轨构建脚本渲染器（无状态单例，快照模式与兜底模式共用） */
    private final BuildScriptRenderer mavenRenderer = new MavenRenderer();
    private final BuildScriptRenderer gradleRenderer = new GradleRenderer();

    /**
     * @param snapshotHolder    当前快照持有器（M3.2）
     * @param fallbackGenerator 启动期兜底生成引擎（由 {@link GeneratorConfig} 装配）
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
        CatalogSnapshot snapshot = snapshotHolder.current();
        ProjectGenerator generator = generatorFor(request, snapshot);
        request = generator.withRequiredStarters(request);
        validateConflicts(request, snapshot);
        // T5：模板集为 DB 唯一数据源，「缺模板」必须在渲染任何字节之前以明确明细失败
        generator.validate(request);
        generator.generate(request, out);
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
     * 冲突规则兜底校验（M3.5）：当前快照规则集复算已选 starter 组合，
     * 命中即抛 422 + 4007，明细含规则 ID 与提示文案。
     *
     * <p>快照未发布或规则为空时直接放行（兜底语义：无规则即无冲突）。
     *
     * @param request 生成请求
     * @throws BusinessException 命中冲突规则（错误码 {@link ErrorCode#DEPENDENCY_CONFLICT}）
     */
    private void validateConflicts(GenerateRequest request, CatalogSnapshot snapshot) {
        if (snapshot == null || snapshot.conflictCatalog().size() == 0) {
            return;
        }
        List<ConflictCatalog.TriggeredConflict> conflicts =
                snapshot.conflictCatalog().evaluate(java.util.Set.copyOf(request.starters()));
        if (conflicts.isEmpty()) {
            return;
        }
        String detail = conflicts.stream()
                .map(c -> "[" + c.ruleId() + "] " + c.message())
                .collect(java.util.stream.Collectors.joining("; "));
        throw new BusinessException(ErrorCode.DEPENDENCY_CONFLICT,
                "依赖组合冲突: " + detail);
    }

    /**
     * 按当前快照解析生成引擎：快照已播种即以快照构建请求级引擎
     * （模板集、清单、冲突规则全部取自快照；快照不可变，构建引擎线程安全）；
     * 仅快照未播种（null）时回退启动期单例引擎。
     *
     * <p><b>T5</b>：模板集只来自快照（DB 全量模板集），无 classpath 兜底。
     * 快照模板集为空 → 前置校验 3001 失败，不产出空骨架。
     *
     * <p>验收修复（P1-2）：回退语义为「快照不存在才兜底」，
     * 保证热发布新增 starter 对生成侧即时生效。
     *
     * @param request 生成请求（构建工具决定双轨渲染实现）
     * @return 本次请求使用的生成引擎
     */
    private ProjectGenerator generatorFor(GenerateRequest request, CatalogSnapshot snapshot) {
        // 兜底条件：仅快照未播种才回退启动期单例；快照存在即以其清单/模板集为准
        if (snapshot == null) {
            return fallbackGenerator;
        }
        Map<String, String> templates = snapshot.templateOverrides();
        TemplateRenderer renderer = new TemplateRenderer(templates);
        BuildScriptRenderer scriptRenderer =
                request.buildTool() == BuildTool.GRADLE ? gradleRenderer : mavenRenderer;
        return new ProjectGenerator(snapshot.metadataCatalog(), renderer, snapshot.starterCatalog(),
                scriptRenderer, scriptRenderer, templates);
    }
}
