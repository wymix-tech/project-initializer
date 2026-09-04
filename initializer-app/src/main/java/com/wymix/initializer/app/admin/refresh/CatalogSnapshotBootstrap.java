package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.StarterCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 快照启动播种（M3.2）：以 classpath 内置清单构建初始快照写入持有器，
 * 保证读路径自启动起始终可用——即使管理员从未发布过配置，生成与元数据
 * 接口的行为也与 M3.1 前一致（兜底语义）；一旦管理端发布，快照原子切换热生效。
 *
 * <p>线程安全性：Spring 配置类，无状态，线程安全。
 */
@Configuration
public class CatalogSnapshotBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CatalogSnapshotBootstrap.class);

    /**
     * 启动播种任务：classpath 内置清单 → 初始快照。
     *
     * @param snapshotHolder 快照持有器
     * @param metadataCatalog classpath 内置元数据目录
     * @param starterCatalog  classpath 内置 starter 清单
     * @return 播种 Runner
     */
    @Bean
    public ApplicationRunner catalogSnapshotSeeder(CatalogSnapshotHolder snapshotHolder,
                                                   MetadataCatalog metadataCatalog,
                                                   StarterCatalog starterCatalog) {
        return args -> {
            snapshotHolder.seed(new CatalogSnapshot(metadataCatalog, starterCatalog, Map.of()));
            log.info("配置快照已播种: classpath 内置清单（Java {} 版 / starter {} 项）",
                    metadataCatalog.javaVersions().size(), starterCatalog.size());
        };
    }
}
