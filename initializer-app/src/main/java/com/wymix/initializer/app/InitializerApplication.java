package com.wymix.initializer.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 平台服务启动入口。
 *
 * <p>仅做装配与 HTTP 层，核心逻辑位于 initializer-core。
 * M3.1 起接入持久化（MyBatis-Plus，管理端配置存储），移除 M1 的
 * {@code DataSourceAutoConfiguration} 排除项并扫描 Mapper。
 *
 * <p>线程安全性：Spring 环境管理，无需关注。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.wymix.initializer.app.admin.mapper")
public class InitializerApplication {

    /**
     * 应用入口。
     *
     * <p>内置清单开关：仅当命令行携带 {@code --demo} 时启用 classpath 内置清单
     * （Java 版本 / Boot 版本 / starter 清单）并启动播种；未携带则以空清单启动，
     * 全部清单数据等待管理端发布配置后生效。
     *
     * <p>开关通过 system property {@code initializer.demo.enabled} 下发（优先级高于
     * application.yml），故 jar 启动行为完全由 {@code --demo} 决定；单元测试与
     * {@code @SpringBootTest} 不经过本入口，沿用 application.yml 的默认值。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        System.setProperty(InitializationProperties.DEMO_ENABLED_PROPERTY,
                String.valueOf(isDemoEnabled(args)));
        SpringApplication.run(InitializerApplication.class, args);
    }

    /**
     * 解析命令行是否启用内置清单。
     *
     * <p>支持 {@code --demo}（开启）、{@code --demo=true} / {@code --demo=false}。
     *
     * @param args 命令行参数
     * @return 是否启用内置清单
     */
    private static boolean isDemoEnabled(String[] args) {
        boolean enabled = false;
        for (String arg : args) {
            if ("--demo".equals(arg)) {
                enabled = true;
            } else if (arg.startsWith("--demo=")) {
                enabled = !"false".equalsIgnoreCase(arg.substring("--demo=".length()));
            }
        }
        return enabled;
    }
}
