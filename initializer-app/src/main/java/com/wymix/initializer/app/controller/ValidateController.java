package com.wymix.initializer.app.controller;

import com.wymix.initializer.app.validation.ValidationService;
import com.wymix.initializer.app.validation.ValidationService.ValidationResult;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 元信息校验接口：即时校验与包名推导。
 *
 * <p>错误语义：未知字段/内部错误由 {@link com.wymix.initializer.app.exception.GlobalExceptionHandler} 处理。
 * 校验失败不抛异常，而是 200 + {@code valid=false} + 错误明细（前端即时反馈场景）。
 *
 * <p>线程安全性：无状态单例，线程安全。
 */
@RestController
@RequestMapping("/api")
@Validated
public class ValidateController {

    private final ValidationService validationService;

    /**
     * @param validationService 校验应用服务
     */
    public ValidateController(ValidationService validationService) {
        this.validationService = validationService;
    }

    /**
     * 即时校验 Group / Artifact / Package。
     *
     * @param request 校验请求体
     * @return 校验结果：{@code valid=false} 时 errors 含各字段错误明细
     */
    @PostMapping("/validate")
    public ValidationResult validate(@jakarta.validation.Valid @RequestBody ValidateRequest request) {
        return validationService.validate(request.group(), request.artifact(), request.packageName());
    }

    /**
     * 由 Group + Artifact 推导默认包名。
     *
     * @param group    groupId
     * @param artifact artifactId
     * @return 推导出的包名，如 {@code com.example.demo-app -> com.example.demoapp}
     */
    @GetMapping("/derive-package")
    public DerivePackageResponse derivePackage(@NotBlank @RequestParam("group") String group,
                                               @NotBlank @RequestParam("artifact") String artifact) {
        return new DerivePackageResponse(validationService.derivePackage(group, artifact));
    }

    /** 校验请求体：group / artifact / packageName。 */
    public record ValidateRequest(String group, String artifact, String packageName) {
    }

    /** 包名推导响应：{@code packageName}。 */
    public record DerivePackageResponse(String packageName) {
    }
}
