package com.wymix.initializer.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 平台服务启动入口。
 *
 * <p>仅做装配与 HTTP 层，核心逻辑位于 initializer-core。
 *
 * <p>线程安全性：Spring 环境管理，无需关注。
 */
@SpringBootApplication
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
