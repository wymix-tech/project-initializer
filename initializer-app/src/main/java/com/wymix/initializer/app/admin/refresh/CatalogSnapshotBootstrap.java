package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.app.InitializationProperties;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.repository.StructuredSnapshot;
import com.wymix.initializer.app.admin.repository.StructuredYamlCodec;
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
 *   <li>非 {@code --demo} 启动：从 DB 最新已发布 release 的**结构行**还原快照，
 *       保证管理后台发布过的配置重启后依然生效（避免快照在内存中丢失）</li>
 * </ul>
 * 两者都未命中时快照保持未初始化，元数据/生成读路径回退到空清单。
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
     * @param catalogRefresher 快照构建器（结构化改造后仍负责装载期校验，输入改由结构行渲染）
     * @param repository       配置存储仓库（结构化改造 T1：从最新 release 的 cfg_* 行装表）
     * @param codec            结构行 ↔ 语义视图码编器（结构行渲染为校验通道可消费的语义文本）
     * @param demoEnabled      是否启用内置清单（由 {@code --demo} 决定）
     * @return 播种 Runner
     */
    @Bean
    public ApplicationRunner catalogSnapshotSeeder(CatalogSnapshotHolder snapshotHolder,
                                                   ExternalCatalogLoader externalCatalogLoader,
                                                   MetadataCatalog metadataCatalog,
                                                   StarterCatalog starterCatalog,
                                                   CatalogRefresher catalogRefresher,
                                                   ConfigRepository repository,
                                                   StructuredYamlCodec codec,
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
            // 非 --demo：从最新已发布 release 的「结构化行」还原快照。
            // T1 阶段仍经「结构行 → 语义文本 → 装载期校验」复用既有校验通道；
            // T2 的 StructuredCatalogAssembler 落地后这一步改为「结构行 → 核心模型 → 快照」直装。
            ConfigReleaseEntity latest = repository.findLatestRelease();
            if (latest == null) {
                log.info("无 --demo 且 DB 无已发布快照：等待管理端发布配置");
                return;
            }
            try {
                StructuredSnapshot rows = repository.readScope(latest.getId());
                if (rows.isEmpty()) {
                    log.info("最新发布 #{} 结构行为空：等待管理端发布配置", latest.getId());
                    return;
                }
                CatalogSnapshot snapshot = catalogRefresher.validateAndBuild(
                        codec.renderMetadata(rows),
                        codec.renderStarters(rows),
                        codec.renderTemplates(rows),
                        codec.renderConflictRules(rows));
                snapshotHolder.seed(snapshot);
                log.info("已从 DB 结构化行还原最新发布快照: releaseId={}, rows={}, releasedAt={}",
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
