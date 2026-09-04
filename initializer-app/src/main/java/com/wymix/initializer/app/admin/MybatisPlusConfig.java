package com.wymix.initializer.app.admin;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件装配（M3.1）：注册乐观锁拦截器。
 *
 * <p>草稿并发修改依赖 {@link OptimisticLockerInnerInterceptor}：携带加载时 version
 * 的 update 影响行数为 0 时由 {@code ConfigRepository} 收敛为 4003 业务异常。
 *
 * <p>线程安全性：Spring 配置类，无状态，线程安全。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * @return MyBatis-Plus 拦截器链（乐观锁）
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
