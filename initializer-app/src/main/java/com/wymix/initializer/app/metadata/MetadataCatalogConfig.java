package com.wymix.initializer.app.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.InitializationProperties;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;

/**
 * 元数据目录装配：从 {@code classpath:catalog/metadata.yml} 装载单例 {@link MetadataCatalog}。
 *
 * <p>线程安全性：Spring 配置类，无状态，线程安全。
 */
@Configuration
public class MetadataCatalogConfig {

    private static final Logger log = LoggerFactory.getLogger(MetadataCatalogConfig.class);

    /**
     * 装载元数据目录（启用内置清单的便捷重载）。
     *
     * <p>供直接实例化本配置类的场景（如单元测试）调用，等价 {@code metadataCatalog(true)}。
     *
     * @return 元数据目录
     */
    public MetadataCatalog metadataCatalog() {
        return metadataCatalog(true);
    }

    /**
     * 装载元数据目录 Bean。
     *
     * <p>内置清单开关：未启用（启动未携带 {@code --demo}）时返回 {@link MetadataCatalog#empty()}，
     * 不读取 {@code catalog/metadata.yml}，元数据全量由管理端发布提供。
     *
     * @param demoEnabled 是否启用内置清单（由 {@code --demo} 决定）
     * @return 元数据目录（不可变，可安全共享）
     * @throws BusinessException 配置文件缺失或解析失败（错误码 {@link ErrorCode#INTERNAL_ERROR}，启动即失败）
     */
    @Bean
    public MetadataCatalog metadataCatalog(
            @Value("${" + InitializationProperties.DEMO_ENABLED_PROPERTY + ":"
                    + InitializationProperties.DEMO_ENABLED_DEFAULT + "}") boolean demoEnabled) {
        if (!demoEnabled) {
            log.info("内置清单未启用（启动未携带 --demo）：元数据目录为空，等待管理端发布配置");
            return MetadataCatalog.empty();
        }
        try (InputStream in = new ClassPathResource("catalog/metadata.yml").getInputStream()) {
            MetadataCatalog catalog = MetadataCatalog.load(in);
            log.info("元数据目录装载完成: {} 个 Java 版本, {} 个 Boot 版本",
                    catalog.javaVersions().size(), catalog.bootVersions().size());
            return catalog;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "catalog/metadata.yml 读取失败", e);
        }
    }
}
