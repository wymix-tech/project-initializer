package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.app.InitializationProperties;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.repository.StructuredSnapshot;
import com.wymix.initializer.app.catalog.ExternalCatalogLoader;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.StarterCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 快照启动播种（M3.2/M3.5）：保证读路径自启动起始终可用。
 *
 * <p>三种初始化路径（按优先级）：
 * <ul>
 *   <li>外部清单目录（{@code initializer.catalog.dir}，默认 jar 同级 {@code catalog/}）：
 *       目录下有 {@code metadata.yml} / {@code starters/*.yml} 即以其播种，格式正确即可用</li>
 *   <li>{@code --demo} 启动：以 classpath 内置清单构建初始快照</li>
 *   <li>非 {@code --demo} 启动：从 DB 最新已发布 release 的**结构行**由
 *       {@link StructuredCatalogAssembler} 直装快照，保证管理后台发布过的配置重启后依然生效
 *       （避免快照在内存中丢失）；无发布记录或作用域为空时播种**空快照**</li>
 * </ul>
 * 外部目录与 {@code --demo} 都未命中、且非 demo 时，仍保证快照非 null（空快照兜底），
 * 元数据/生成读路径始终可用。
 *
 * <p>线程安全性：Spring 配置类，无状态，线程安全。
 */
@Configuration
public class CatalogSnapshotBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CatalogSnapshotBootstrap.class);

    /**
     * 启动播种任务：外部清单目录（{@code initializer.catalog.dir}）→
     * classpath 内置清单（--demo）或 DB 最新已发布快照（默认）。
     *
     * <p>外部清单目录优先级最高：目录下存在 {@code metadata.yml} / {@code starters/*.yml}
     * 即直接以其构建初始快照，不与管理端已发布配置做一致性校验，格式正确即可用。
     *
     * @param snapshotHolder  快照持有器
     * @param externalCatalogLoader 外部清单目录装载器（jar 外配置，未提供时为空结果）
     * @param metadataCatalog classpath 内置元数据目录
     * @param starterCatalog  classpath 内置 starter 清单
     * @param assembler        结构化目录装配器（T2：从最新 release 的结构行直装快照）
     * @param repository       配置存储仓库（从最新 release 的 cfg_* 行装表）
     * @param demoEnabled      是否启用内置清单（由 {@code --demo} 决定）
     * @return 播种 Runner
     */
    @Bean
    public ApplicationRunner catalogSnapshotSeeder(CatalogSnapshotHolder snapshotHolder,
                                                   ExternalCatalogLoader externalCatalogLoader,
                                                   MetadataCatalog metadataCatalog,
                                                   StarterCatalog starterCatalog,
                                                   StructuredCatalogAssembler assembler,
                                                   ConfigRepository repository,
                                                   @Value("${" + InitializationProperties.DEMO_ENABLED_PROPERTY + ":"
                                                           + InitializationProperties.DEMO_ENABLED_DEFAULT + "}")
                                                   boolean demoEnabled) {
        return args -> {
            ExternalCatalogLoader.ExternalCatalog external = externalCatalogLoader.catalog();
            if (external.hasAnyCatalog()) {
                MetadataCatalog metadata = external.hasMetadata()
                        ? external.metadataCatalog() : MetadataCatalog.empty();
                StarterCatalog starters = external.hasStarters()
                        ? external.starterCatalog() : StarterCatalog.of(List.of());
                // starter 清单联动：展示名/坐标以清单为准，未登记条目自动可选
                if (external.hasMetadata()) {
                    metadata = metadata.merge(starters);
                }
                snapshotHolder.seed(new CatalogSnapshot(metadata, starters,
                        external.templateOverrides(), external.conflictCatalog()));
                log.info("配置快照已播种: 外部清单目录 {}（Java {} 版 / starter {} 项 / 模板覆盖 {} 个 / 冲突规则 {} 条）",
                        externalCatalogLoader.directory(), metadata.javaVersions().size(), starters.size(),
                        external.templateOverrides().size(), external.conflictCatalog().size());
                return;
            }
            if (demoEnabled) {
                // 内置清单同样联动：starter 展示名与坐标取自 starter 清单，无需在 metadata.yml 重复登记
                snapshotHolder.seed(new CatalogSnapshot(metadataCatalog.merge(starterCatalog), starterCatalog, Map.of()));
                log.info("配置快照已播种: classpath 内置清单（Java {} 版 / starter {} 项）",
                        metadataCatalog.javaVersions().size(), starterCatalog.size());
                return;
            }
            // 非 --demo：从最新已发布 release 的「结构化行」直装快照（T2 装配器）。
            // 表空（无发布记录或空作用域）→ 空快照占位：保证读路径自启动起可用，
            // 清单数据等待管理端首次发布后填充。
            ConfigReleaseEntity latest = repository.findLatestRelease();
            if (latest == null) {
                log.info("无 --demo 且 DB 无已发布快照：播种空快照，等待管理端发布配置");
                snapshotHolder.seed(new CatalogSnapshot(MetadataCatalog.empty(), StarterCatalog.of(List.of())));
                return;
            }
            try {
                StructuredSnapshot rows = repository.readScope(latest.getId());
                if (rows.isEmpty()) {
                    log.info("最新发布 #{} 结构行为空：播种空快照，等待管理端发布配置", latest.getId());
                    snapshotHolder.seed(new CatalogSnapshot(MetadataCatalog.empty(), StarterCatalog.of(List.of())));
                    return;
                }
                CatalogSnapshot snapshot = assembler.assemble(rows);
                snapshotHolder.seed(snapshot);
                log.info("已从 DB 结构化行装配最新发布快照: releaseId={}, rows={}, releasedAt={}",
                        latest.getId(), rows.rowCount(), latest.getReleasedAt());
            } catch (RuntimeException e) {
                // 启动期还原失败：不要阻塞启动（生成/读路径保持空清单兜底）
                // 管理后台可重新发布修复，刷新会走正常的发布校验流程
                log.warn("从 DB 还原最新发布快照失败（保持空清单兜底，可由管理端重新发布修复）: {}",
                        e.getMessage());
            }
        };
    }
}
