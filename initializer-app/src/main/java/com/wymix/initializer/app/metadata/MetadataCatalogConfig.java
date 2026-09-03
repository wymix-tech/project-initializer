package com.wymix.initializer.app.metadata;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
     * 装载元数据目录 Bean。
     *
     * @return 元数据目录（不可变，可安全共享）
     * @throws BusinessException 配置文件缺失或解析失败（错误码 {@link ErrorCode#INTERNAL_ERROR}，启动即失败）
     */
    @Bean
    public MetadataCatalog metadataCatalog() {
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
