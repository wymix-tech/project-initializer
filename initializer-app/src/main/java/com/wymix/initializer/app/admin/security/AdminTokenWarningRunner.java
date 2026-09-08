package com.wymix.initializer.app.admin.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Locale;

/**
 * 管理端 token 安全启动检查（M3 评审遗留项 3）：默认开发 token
 * {@value #DEFAULT_DEV_TOKEN} 仅供本地开发，生产环境必须通过
 * {@code INITIALIZER_ADMIN_TOKEN} 环境变量覆盖。
 *
 * <p>分级处置：
 * <ul>
 *   <li>非生产 profile：检测到默认 token 仅输出 WARNING，不阻断启动（本地开发可直接运行）</li>
 *   <li>生产 profile（{@code prod} / {@code production} / {@code prd}）：仍在用默认 token
 *       视为致命配置错误，直接抛异常阻断启动，避免管理端被任何人以公开默认值登录</li>
 * </ul>
 *
 * <p>线程安全性：Spring 配置类，无状态，线程安全。
 */
@Configuration
public class AdminTokenWarningRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenWarningRunner.class);

    /** 与 application.yml 默认值一致的本地开发 token。 */
    public static final String DEFAULT_DEV_TOKEN = "admin-dev-token";

    /** 视为生产环境的 profile 名称（小写）。 */
    private static final String[] PRODUCTION_PROFILES = {"prod", "production", "prd"};

    /**
     * 启动检查任务：默认 token 时告警，生产 profile 下阻断启动。
     *
     * @param adminToken 配置的管理端 token
     * @param environment Spring 环境（读取激活 profile）
     * @return 检查 Runner
     */
    @Bean
    public ApplicationRunner adminTokenSecurityChecker(
            @Value("${initializer.admin.token:" + DEFAULT_DEV_TOKEN + "}") String adminToken,
            Environment environment) {
        return args -> {
            if (!DEFAULT_DEV_TOKEN.equals(adminToken)) {
                return;
            }
            if (isProduction(environment)) {
                throw new IllegalStateException("管理端仍在使用默认开发 token（" + DEFAULT_DEV_TOKEN
                        + "），生产环境禁止启动；请通过环境变量 INITIALIZER_ADMIN_TOKEN 设置强随机 token 后重启");
            }
            log.warn("管理端正在使用默认开发 token（admin-dev-token），存在安全风险；"
                    + "生产环境必须通过环境变量 INITIALIZER_ADMIN_TOKEN 覆盖后再部署");
        };
    }

    /** 当前激活 profile 是否属于生产环境。 */
    static boolean isProduction(Environment environment) {
        if (environment == null) {
            return false;
        }
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> Arrays.asList(PRODUCTION_PROFILES).contains(profile));
    }
}
