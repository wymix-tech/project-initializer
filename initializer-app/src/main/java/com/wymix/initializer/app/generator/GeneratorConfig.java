package com.wymix.initializer.app.generator;

import com.wymix.initializer.core.generator.ProjectGenerator;
import com.wymix.initializer.core.generator.TemplateRenderer;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.StarterCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 生成引擎装配：复用元数据目录 Bean，装配渲染器与生成器单例。
 *
 * <p>M3.2 语义变更：该 Bean 降级为「classpath 兜底引擎」——快照未发布时生成
 * 走本 Bean，快照发布后由 {@code GeneratorService} 按快照即时构建引擎。
 *
 * <p>线程安全性：无状态配置类，线程安全。
 */
@Configuration
public class GeneratorConfig {

    /**
     * @return 模板渲染器单例（classpath templates/ 目录，兜底）
     */
    @Bean
    public TemplateRenderer templateRenderer() {
        return new TemplateRenderer();
    }

    /**
     * @param catalog        元数据目录（复用 {@code MetadataCatalogConfig} 装配的 Bean，兜底）
     * @param renderer       模板渲染器
     * @param starterCatalog starter 清单（复用 {@code StarterCatalogConfig} 装配的 Bean，兜底）
     * @return classpath 兜底生成引擎单例
     */
    @Bean
    public ProjectGenerator projectGenerator(MetadataCatalog catalog, TemplateRenderer renderer,
                                             StarterCatalog starterCatalog) {
        return new ProjectGenerator(catalog, renderer, starterCatalog);
    }
}
