package com.wymix.initializer.app.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.InitializationProperties;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import com.wymix.initializer.core.starter.YamlStarterParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * starter 清单装配：扫描 {@code classpath*:catalog/starters/*.yml} 全量装载单例 {@link StarterCatalog}。
 *
 * <p>新增 starter 仅需在 {@code catalog/starters/} 下新增一个 yml（+ 模板文件），
 * 生成器代码零改动。任一 yml 装载失败抛 9002，启动即失败。
 *
 * <p>线程安全性：Spring 配置类，无状态，线程安全。
 */
@Configuration
public class StarterCatalogConfig {

    private static final Logger log = LoggerFactory.getLogger(StarterCatalogConfig.class);

    /** starter 清单 classpath 模式（classpath*: 覆盖所有 jar 包内的清单文件）。 */
    static final String STARTER_YML_PATTERN = "classpath*:catalog/starters/*.yml";

    /**
     * 装载 starter 清单（启用内置清单的便捷重载），等价 {@code starterCatalog(true)}。
     *
     * @return starter 清单
     */
    public StarterCatalog starterCatalog() {
        return starterCatalog(true);
    }

    /**
     * 装载 starter 清单 Bean。
     *
     * <p>内置清单开关：未启用（启动未携带 {@code --demo}）时返回空清单，
     * 不扫描 {@code catalog/starters/*.yml}（空目录下的「清单为空」校验随之豁免），
     * starter 全量由管理端发布提供。
     *
     * @param demoEnabled 是否启用内置清单（由 {@code --demo} 决定）
     * @return starter 清单（不可变，可安全共享）
     * @throws BusinessException 清单缺失/解析/校验失败（错误码 {@link ErrorCode#STARTER_CATALOG_INVALID}，启动即失败）
     */
    @Bean
    public StarterCatalog starterCatalog(
            @Value("${" + InitializationProperties.DEMO_ENABLED_PROPERTY + ":"
                    + InitializationProperties.DEMO_ENABLED_DEFAULT + "}") boolean demoEnabled) {
        if (!demoEnabled) {
            log.info("内置清单未启用（启动未携带 --demo）：starter 清单为空，等待管理端发布配置");
            return StarterCatalog.of(List.of());
        }
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(STARTER_YML_PATTERN);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    "starter 清单扫描失败: " + e.getMessage(), e);
        }
        if (resources.length == 0) {
            throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                    "starter 清单为空: " + STARTER_YML_PATTERN);
        }
        List<StarterCatalogEntry> entries = new ArrayList<>();
        for (Resource resource : sortByName(resources)) {
            String fileName = resource.getFilename() == null ? resource.toString() : resource.getFilename();
            try (InputStream in = resource.getInputStream()) {
                entries.add(YamlStarterParser.parse(in));
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.STARTER_CATALOG_INVALID,
                        "starter 清单文件读取失败: " + fileName, e);
            }
        }
        StarterCatalog catalog = StarterCatalog.of(entries);
        log.info("starter 清单装载完成: {} 个 starter", catalog.size());
        return catalog;
    }

    /** 按文件名排序，保证装载顺序确定性（跨 jar 场景下 Resource 顺序不保证）。 */
    private static List<Resource> sortByName(Resource[] resources) {
        return Arrays.stream(resources)
                .sorted((a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())))
                .toList();
    }
}
