package com.wymix.initializer.app.validation;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.validation.ValidationRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;

/**
 * 校验规则集装配：从 {@code classpath:catalog/validation.yml} 装载单例 {@link ValidationRules}。
 *
 * <p>线程安全性：Spring 配置类，无状态，线程安全。
 */
@Configuration
public class ValidationRulesConfig {

    private static final Logger log = LoggerFactory.getLogger(ValidationRulesConfig.class);

    /**
     * 装载校验规则集 Bean。
     *
     * @return 校验规则集（不可变，可安全共享）
     * @throws BusinessException 配置文件缺失或解析失败（错误码 {@link ErrorCode#INTERNAL_ERROR}，启动即失败）
     */
    @Bean
    public ValidationRules validationRules() {
        try (InputStream in = new ClassPathResource("catalog/validation.yml").getInputStream()) {
            ValidationRules rules = ValidationRules.load(in);
            log.info("校验规则集装载完成");
            return rules;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "catalog/validation.yml 读取失败", e);
        }
    }
}
