package com.wymix.initializer.app.admin.repository;

import java.util.List;

/**
 * metadata 草稿的「结构映射」文档（T1 过渡层）：只承载 YAML 的结构字段，
 * 不含兼容矩阵图校验结果，供 {@link StructuredDraftWriter} 把草稿写入关系表。
 *
 * <p><b>与 {@code MetadataCatalog} 的分工</b>：{@code MetadataCatalog} 是**已校验的运行时目录**
 * （装载即校验悬空引用 / ID 重复，失败即拒）；本类只做「YAML 结构 → 字段」的哑映射，
 * 允许悬空引用与重复 ID 暂时存在，让用户在草稿态逐步补全配置；
 * 语义校验在发布期由 {@code CatalogRefresher} 统一执行并给出 4002 明细。
 *
 * <p>线程安全性：不可变记录，线程安全。
 */
final class MetadataDoc {

    private MetadataDoc() {
    }

    /**
     * metadata 草稿结构：Java 版本行 + Boot 版本行。
     *
     * @param javaVersions Java 版本行
     * @param bootVersions Boot 版本行
     */
    record Parsed(List<JavaRow> javaVersions, List<BootRow> bootVersions) {

        Parsed {
            javaVersions = List.copyOf(javaVersions);
            bootVersions = List.copyOf(bootVersions);
        }
    }

    /**
     * Java 版本行（结构映射，未校验）。
     *
     * @param id                     Java 版本 ID
     * @param label                  展示名
     * @param deprecated             是否已废弃
     * @param recommendedBootVersion 推荐 Boot 版本 ID（可空）
     */
    record JavaRow(String id, String label, boolean deprecated, String recommendedBootVersion) {
    }

    /**
     * Boot 版本行（结构映射，未校验）。
     *
     * @param id                    Boot 版本 ID
     * @param label                 展示名
     * @param official              是否官方版本
     * @param supportedJavaVersions 兼容 Java 版本 ID 清单（可能含悬空引用）
     * @param starters              可选 starter 白名单（可能含未登记条目）
     * @param frameworkParent       框架父 POM（可空）
     */
    record BootRow(String id, String label, boolean official, List<String> supportedJavaVersions,
                   List<String> starters, Parent frameworkParent) {

        BootRow {
            supportedJavaVersions = List.copyOf(supportedJavaVersions);
            starters = List.copyOf(starters);
        }
    }

    /**
     * 框架父 POM 三字段（结构映射）。
     *
     * @param groupId    groupId
     * @param artifactId artifactId
     * @param version    version
     */
    record Parent(String groupId, String artifactId, String version) {
    }
}
