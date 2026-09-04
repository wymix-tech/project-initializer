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
// M1 无持久化场景，MyBatis-Plus 在 classpath 但无 DataSource，排除 JDBC 自动配置避免启动失败；M2 接入持久化时移除该排除
@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
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
