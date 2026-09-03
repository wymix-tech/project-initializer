package com.wymix.initializer.app.generator;

import com.wymix.initializer.core.generator.ProjectGenerator;
import com.wymix.initializer.core.generator.TemplateRenderer;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 生成引擎装配：复用元数据目录 Bean，装配渲染器与生成器单例。
 *
 * <p>线程安全性：无状态配置类，线程安全。
 */
@Configuration
public class GeneratorConfig {

    /**
     * @return 模板渲染器单例（classpath templates/ 目录）
     */
    @Bean
    public TemplateRenderer templateRenderer() {
        return new TemplateRenderer();
    }

    /**
     * @param catalog  元数据目录（复用 {@code MetadataCatalogConfig} 装配的 Bean）
     * @param renderer 模板渲染器
     * @return 生成引擎单例
     */
    @Bean
    public ProjectGenerator projectGenerator(MetadataCatalog catalog, TemplateRenderer renderer) {
        return new ProjectGenerator(catalog, renderer);
    }
}
