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
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(InitializerApplication.class, args);
    }
}
