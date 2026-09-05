package com.wymix.initializer.app.admin.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 管理端 token 安全启动检查（M3 评审遗留项 3）：默认开发 token
 * {@value #DEFAULT_DEV_TOKEN} 仅供本地开发，生产环境必须通过
 * {@code INITIALIZER_ADMIN_TOKEN} 环境变量覆盖。
 *
 * <p>启动时检测到默认 token 且未显式配置安全来源时输出 WARNING 告警；
 * 不阻断启动（本地开发场景仍可直接运行），告警内容含覆盖方式指引。
 *
 * <p>线程安全性：Spring 配置类，无状态，线程安全。
 */
@Configuration
public class AdminTokenWarningRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenWarningRunner.class);

    /** 与 application.yml 默认值一致的本地开发 token。 */
    public static final String DEFAULT_DEV_TOKEN = "admin-dev-token";

    /**
     * 启动检查任务：默认 token 时告警。
     *
     * @param adminToken 配置的管理端 token
     * @return 检查 Runner
     */
    @Bean
    public ApplicationRunner adminTokenSecurityChecker(
            @Value("${initializer.admin.token:" + DEFAULT_DEV_TOKEN + "}") String adminToken) {
        return args -> {
            if (DEFAULT_DEV_TOKEN.equals(adminToken)) {
                log.warn("管理端正在使用默认开发 token（admin-dev-token），存在安全风险；"
                        + "生产环境必须通过环境变量 INITIALIZER_ADMIN_TOKEN 覆盖后再部署");
            }
        };
    }
}
