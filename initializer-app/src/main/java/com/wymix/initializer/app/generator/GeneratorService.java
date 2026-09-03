package com.wymix.initializer.app.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.validation.ValidationService;
import com.wymix.initializer.core.generator.GenerateRequest;
import com.wymix.initializer.core.generator.ProjectGenerator;
import org.springframework.stereotype.Service;

/**
 * 生成应用服务：生成前做元信息最终校验，失败抛业务异常（422 + 明细），不输出半成品。
 *
 * <p>线程安全性：无状态单例，线程安全。
 */
@Service
public class GeneratorService {

    private final ProjectGenerator projectGenerator;
    private final ValidationService validationService;

    /**
     * @param projectGenerator  生成引擎（由 {@link GeneratorConfig} 装配）
     * @param validationService 元信息校验服务
     */
    public GeneratorService(ProjectGenerator projectGenerator, ValidationService validationService) {
        this.projectGenerator = projectGenerator;
        this.validationService = validationService;
    }

    /**
     * 生成项目骨架 zip 字节流。
     *
     * @param request 生成请求
     * @return 完整可用的 zip 字节流
     * @throws BusinessException 元信息非法（1001）、版本组合问题（2001/2002）、生成失败（3001/3002/3003）
     */
    public byte[] generate(GenerateRequest request) {
        preValidate(request);
        return projectGenerator.generate(request);
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
}
