package com.wymix.initializer.app.controller;

import com.wymix.initializer.app.generator.GeneratorService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 项目生成接口：{@code POST /api/generate} zip 附件流式下载。
 *
 * <p>错误语义：元信息/版本校验失败 422 + ErrorResponse 明细（全局异常处理器统一映射，
 * 此时响应尚未提交，前端仍可解析错误体）；成功返回 {@code application/zip} 附件，
 * 文件名 {@code {artifact}.zip}，产物流式写出不经内存全量缓冲。
 *
 * <p>线程安全性：无状态单例，线程安全。
 */
@RestController
@RequestMapping("/api")
@Validated
public class GenerateController {

    private final GeneratorService generatorService;

    /**
     * @param generatorService 生成应用服务
     */
    public GenerateController(GeneratorService generatorService) {
        this.generatorService = generatorService;
    }

    /**
     * 生成项目骨架并流式写出 zip 附件。
     *
     * @param request  生成请求体
     * @param response Servlet 响应（用于设置附件头与写产物流）
     * @throws IOException 响应流写入失败（由容器处理）
     */
    @PostMapping("/generate")
    public void generate(@Valid @RequestBody GenerateRequestBody request,
                         HttpServletResponse response) throws IOException {
        com.wymix.initializer.core.generator.GenerateRequest domain = request.toDomain();
        response.setContentType(MediaType.parseMediaType("application/zip").toString());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + domain.artifact() + ".zip\"");
        generatorService.generate(domain, response.getOutputStream());
        response.flushBuffer();
    }

    /**
     * 生成请求体。
     *
     * @param group       groupId
     * @param artifact    artifactId
     * @param packageName 基础包名（允许与推导值不同）
     * @param javaVersion Java 版本 ID
     * @param bootVersion Boot 版本 ID
     * @param starters    starter 清单（可选）
     */
    public record GenerateRequestBody(
            @NotBlank(message = "group 不能为空") String group,
            @NotBlank(message = "artifact 不能为空") String artifact,
            @NotBlank(message = "packageName 不能为空") String packageName,
            @NotBlank(message = "javaVersion 不能为空") String javaVersion,
            @NotBlank(message = "bootVersion 不能为空") String bootVersion,
            List<String> starters) {

        /**
         * 转核心域生成请求。
         *
         * @return 核心域 GenerateRequest
         */
        public com.wymix.initializer.core.generator.GenerateRequest toDomain() {
            return new com.wymix.initializer.core.generator.GenerateRequest(
                    group, artifact, packageName, javaVersion, bootVersion, starters);
        }
    }
}
