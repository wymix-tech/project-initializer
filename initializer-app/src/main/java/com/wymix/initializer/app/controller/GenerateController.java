package com.wymix.initializer.app.controller;

import com.wymix.initializer.app.generator.GeneratorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * 项目生成接口：{@code POST /api/generate} blob 下载。
 *
 * <p>错误语义：元信息/版本校验失败 422 + ErrorResponse 明细（全局异常处理器统一映射），
 * 成功返回 {@code application/zip} 附件，文件名 {@code {artifact}.zip}。
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
     * 生成项目骨架并返回 zip 附件下载流。
     *
     * @param request 生成请求体
     * @return zip 字节流附件
     */
    @PostMapping("/generate")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> generate(
            @Valid @RequestBody GenerateRequestBody request) {
        byte[] zipBytes = generatorService.generate(request.toDomain());
        String fileName = generatorService.zipFileName(request.toDomain());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDispositionFormData("attachment", fileName);
        headers.setContentLength(zipBytes.length);
        return ResponseEntity.ok()
                .headers(headers)
                .body(new org.springframework.core.io.InputStreamResource(
                        new ByteArrayInputStream(zipBytes)));
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
