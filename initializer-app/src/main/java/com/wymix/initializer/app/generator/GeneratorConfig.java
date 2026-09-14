package com.wymix.initializer.app.generator;

import com.wymix.initializer.app.admin.refresh.CatalogSnapshot;
import com.wymix.initializer.app.admin.refresh.StructuredCatalogAssembler;
import com.wymix.initializer.core.generator.ProjectGenerator;
import com.wymix.initializer.core.generator.TemplateRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 生成引擎装配（T5：模板改为 DB 唯一数据源）。
 *
 * <p>启动期兜底引擎**全部取自当前快照**（{@code CatalogSnapshotHolder} 已由
 * {@code CatalogSnapshotBootstrap} 播种）：
 * <ul>
 *   <li>{@code --demo}：播种链为「classpath 内置清单 + 内置模板入库 → 自动发布一版 →
 *       从 DB 装表」，故此处拿到的即 DB 发布快照</li>
 *   <li>非 {@code --demo}：DB 有发布 → 该发布快照；无发布 → 空快照（生成在前置校验失败）</li>
 * </ul>
 * 模板集一律来自快照，<b>不再有 classpath 模板兜底</b>。
 *
 * <p>该 Bean 只服务「快照持有器尚未播种（null）」的极端启动竞态；正常路径由
 * {@code GeneratorService} 按当前快照逐请求构建引擎。
 *
 * <p>线程安全性：无状态配置类，线程安全。
 */
@Configuration
public class GeneratorConfig {

    /**
     * @param assembler 结构化目录装配器（从 DB 草稿作用域直装快照）
     * @return 启动期兜底生成引擎（清单/模板集均取自 DB 草稿作用域）
     */
    @Bean
    public ProjectGenerator projectGenerator(StructuredCatalogAssembler assembler) {
        CatalogSnapshot snapshot;
        try {
            snapshot = assembler.assemble(com.wymix.initializer.app.admin.repository.ConfigRepository.DRAFT_RELEASE_ID);
        } catch (RuntimeException e) {
            // 草稿作用域不可装配（半成品草稿）时退化为空快照：生成在前置校验阶段给出明确 3001/2003
            snapshot = new CatalogSnapshot(com.wymix.initializer.core.metadata.MetadataCatalog.empty(),
                    com.wymix.initializer.core.starter.StarterCatalog.of(java.util.List.of()));
        }
        TemplateRenderer renderer = new TemplateRenderer(snapshot.templateOverrides());
        return new ProjectGenerator(snapshot.metadataCatalog(), renderer, snapshot.starterCatalog());
    }
}
