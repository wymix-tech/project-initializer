package com.wymix.initializer.app.admin.dto;

import java.util.List;

/**
 * 管理端结构化配置 DTO（结构化改造 T3）：元数据 / starter / 冲突规则 / 模板四类模块的
 * 表单化读写契约，直接对应 {@code cfg_*} 关系表的列语义。
 *
 * <p><b>与 YAML 契约的关系</b>：T3 之前管理端以「YAML 全文」为读写契约（T1 过渡桥）；
 * 本 DTO 集合起，结构化端点以「完整模块对象」整体提交——表单化维护的语义就是
 * 「一次保存提交该模块的完整结构（而非增量 diff）」，故全部子集合均为全量集合，
 * 写入期以「整体替换该模块草稿行」实现，天然避免孤儿行与增删改差异计算。
 *
 * <p><b>草稿宽松 / 发布严格</b>：本 DTO 只做字段级与结构级校验（必填、取值域、数量上限）；
 * 跨模块引用（Boot ↔ Java、冲突规则 ↔ starter、requires 悬空）与图合法性
 * （重复 ID、成环）**统一留到发布期**由 {@code StructuredCatalogAssembler} 收敛为 4002 明细，
 * 保证草稿态「边补边存」不被拦截。
 *
 * <p><b>CLOB</b>：模板全文只在 {@link TemplateDetail} / {@link TemplateUpsertRequest} 出现，
 * 列表走 {@link TemplateSummary}（不含 content），避免列表接口搬运大文本。
 *
 * <p>线程安全性：全部为不可变 record（集合字段构造期做防御性拷贝），线程安全。
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    // ==================== 元数据（metadata） ====================

    /**
     * 元数据模块完整视图（Java 版本 + Boot 版本 + 兼容矩阵 + starter 白名单 + 框架父 POM）。
     *
     * @param javaVersions Java 版本清单（按 sort 升序）
     * @param bootVersions Boot 版本清单（按 sort 升序）
     */
    public record MetadataView(List<JavaVersionDto> javaVersions, List<BootVersionDto> bootVersions) {

        public MetadataView {
            javaVersions = List.copyOf(javaVersions);
            bootVersions = List.copyOf(bootVersions);
        }
    }

    /**
     * 元数据整体提交请求。
     *
     * @param javaVersions Java 版本清单（全量）
     * @param bootVersions Boot 版本清单（全量）
     * @param operator     操作人
     */
    public record MetadataUpsertRequest(List<JavaVersionDto> javaVersions,
                                        List<BootVersionDto> bootVersions,
                                        String operator) {
    }

    /**
     * Java 版本项。
     *
     * @param id                      Java 版本 ID（如 {@code "17"}，同 release 内唯一）
     * @param label                   展示名（空时按 {@code Java <id>} 兜底）
     * @param deprecated              是否已废弃
     * @param recommendedBootVersion  推荐 Boot 版本 ID（可空）
     * @param sort                    展示顺序（可空，缺省按提交顺序）
     */
    public record JavaVersionDto(String id, String label, Boolean deprecated,
                                 String recommendedBootVersion, Integer sort) {
    }

    /**
     * Boot 版本项。
     *
     * @param id                      Boot 版本 ID（如 {@code "3.2.5"}，同 release 内唯一）
     * @param label                   展示名（空时按 {@code Spring Boot <id>} 兜底）
     * @param official                是否官方发布版本
     * @param supportedJavaVersions   兼容的 Java 版本 ID 清单（可空 = 无兼容声明）
     * @param starters                可选 starter 白名单（可空 = 不设白名单）
     * @param frameworkParent         框架父 POM（可空 = 回退官方父 POM）
     * @param sort                    展示顺序（可空，缺省按提交顺序）
     */
    public record BootVersionDto(String id, String label, Boolean official,
                                 List<String> supportedJavaVersions, List<String> starters,
                                 FrameworkParentDto frameworkParent, Integer sort,
                                 String springBootVersion) {

        public BootVersionDto {
            supportedJavaVersions = supportedJavaVersions == null ? List.of() : List.copyOf(supportedJavaVersions);
            starters = starters == null ? List.of() : List.copyOf(starters);
        }

        /** 兼容构造：未声明 Spring Boot 版本（等价 {@code springBootVersion = null}，回退版本 ID）。 */
        public BootVersionDto(String id, String label, Boolean official,
                              List<String> supportedJavaVersions, List<String> starters,
                              FrameworkParentDto frameworkParent, Integer sort) {
            this(id, label, official, supportedJavaVersions, starters, frameworkParent, sort, null);
        }
    }

    /**
     * 框架父 POM 三字段（缺一不可，整体可空）。
     *
     * @param groupId    Maven groupId
     * @param artifactId Maven artifactId
     * @param version    Maven version（支持 {@code ${revision}} 等占位符）
     */
    public record FrameworkParentDto(String groupId, String artifactId, String version) {
    }

    // ==================== starter 清单 ====================

    /**
     * starter 清单完整视图。
     *
     * @param starters starter 项清单（按 sort 升序）
     */
    public record StarterCatalogView(List<StarterDto> starters) {

        public StarterCatalogView {
            starters = List.copyOf(starters);
        }
    }

    /**
     * starter 清单整体提交请求。
     *
     * @param starters starter 项清单（全量）
     * @param operator 操作人
     */
    public record StarterCatalogUpsertRequest(List<StarterUpsertDto> starters, String operator) {
    }

    /**
     * starter 项（读写同构）。
     *
     * <p>{@code source} 为只读通道标记：手工维护端点恒写 {@code MANUAL}，
     * 制品库同步层的 {@code SYNC} 行不通过本 DTO 写入（由 {@code CatalogSyncService} 管理）。
     *
     * @param id           starter ID（同 release 内唯一）
     * @param category     分类（web / data / middleware / tool）
     * @param maintainer   维护方（OFFICIAL / INTERNAL，大小写不敏感）
     * @param description  展示描述
     * @param required     是否必选
     * @param hidden       是否在前端依赖选择列表中默认隐藏（PR #40 语义，直落 {@code cfg_starter.hidden}）
     * @param configPackage 配置类目标包名（可空）
     * @param dependencies 依赖坐标清单（可空）
     * @param requires     依赖联动分组（AND-of-OR，可空；单元素组 = 强制依赖）
     * @param bootVersions 关联 Boot 版本 ID 清单（可空 = 全部版本可用）
     * @param linkages     联动规则（可空 = 无联动）
     * @param sort         展示顺序（可空，缺省按提交顺序）
     */
    public record StarterDto(String id, String category, String maintainer, String description,
                             Boolean required, Boolean hidden, String configPackage,
                             List<DependencyDto> dependencies, List<List<String>> requires,
                             List<String> bootVersions, LinkageDto linkages, Integer sort) {

        public StarterDto {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            requires = requires == null ? List.of()
                    : requires.stream().map(g -> g == null ? List.<String>of() : List.copyOf(g)).toList();
            bootVersions = bootVersions == null ? List.of() : List.copyOf(bootVersions);
        }
    }

    /**
     * starter 整体提交项：读写同构，仅用于声明语义（保留类型位以便后续分化）。
     *
     * @param id           starter ID
     * @param category     分类
     * @param maintainer   维护方
     * @param description  展示描述
     * @param required     是否必选
     * @param hidden       是否默认隐藏
     * @param configPackage 配置类目标包名
     * @param dependencies 依赖坐标清单
     * @param requires     依赖联动分组
     * @param bootVersions 关联 Boot 版本清单
     * @param linkages     联动规则
     * @param sort         展示顺序
     */
    public record StarterUpsertDto(String id, String category, String maintainer, String description,
                                   Boolean required, Boolean hidden, String configPackage,
                                   List<DependencyDto> dependencies, List<List<String>> requires,
                                   List<String> bootVersions, LinkageDto linkages, Integer sort) {

        public StarterUpsertDto {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            requires = requires == null ? List.of()
                    : requires.stream().map(g -> g == null ? List.<String>of() : List.copyOf(g)).toList();
            bootVersions = bootVersions == null ? List.of() : List.copyOf(bootVersions);
        }

        /**
         * 投影为统一读视图（写入链路复用同一结构映射）。
         *
         * @return starter 项视图
         */
        public StarterDto toDto() {
            return new StarterDto(id, category, maintainer, description, required, hidden, configPackage,
                    dependencies, requires, bootVersions, linkages, sort);
        }
    }

    /**
     * 依赖坐标（GAV；{@code version} 可空 = 走 Boot BOM）。
     *
     * @param groupId    Maven groupId
     * @param artifactId Maven artifactId
     * @param version    版本（可空）
     */
    public record DependencyDto(String groupId, String artifactId, String version) {
    }

    /**
     * starter 联动规则（三类联动 + 配置包名）。
     *
     * @param templates       模板联动项（可空）
     * @param ymlFragments    application.yml 片段联动项（可空）
     * @param abstractClasses 抽象类联动项（可空）
     */
    public record LinkageDto(List<TemplateLinkageDto> templates, List<YmlFragmentDto> ymlFragments,
                             List<AbstractClassDto> abstractClasses) {

        public LinkageDto {
            templates = templates == null ? List.of() : List.copyOf(templates);
            ymlFragments = ymlFragments == null ? List.of() : List.copyOf(ymlFragments);
            abstractClasses = abstractClasses == null ? List.of() : List.copyOf(abstractClasses);
        }

        /** @return 是否三类联动全空 */
        public boolean isEmpty() {
            return templates.isEmpty() && ymlFragments.isEmpty() && abstractClasses.isEmpty();
        }
    }

    /**
     * 模板联动项。
     *
     * @param source 源模板名
     * @param target 目标模板名
     */
    public record TemplateLinkageDto(String source, String target) {
    }

    /**
     * yml 片段联动项。
     *
     * @param source        片段名
     * @param mergeStrategy 合并策略（APPEND / OVERRIDE，可空 = APPEND）
     */
    public record YmlFragmentDto(String source, String mergeStrategy) {
    }

    /**
     * 抽象类联动项。
     *
     * @param fqcn  抽象类全限定名
     * @param alias 生成类别名
     */
    public record AbstractClassDto(String fqcn, String alias) {
    }

    // ==================== 冲突规则 ====================

    /**
     * 冲突规则完整视图。
     *
     * @param rules 规则清单（按 sort 升序）
     */
    public record ConflictRuleView(List<ConflictRuleDto> rules) {

        public ConflictRuleView {
            rules = List.copyOf(rules);
        }
    }

    /**
     * 冲突规则整体提交请求。
     *
     * @param rules    规则清单（全量）
     * @param operator 操作人
     */
    public record ConflictRuleUpsertRequest(List<ConflictRuleDto> rules, String operator) {
    }

    /**
     * 冲突规则项。
     *
     * @param id       规则 ID（同 release 内唯一）
     * @param type     规则类型（pair / group，大小写不敏感）
     * @param starters 涉及 starter ID 清单
     * @param message  冲突提示文案
     * @param sort     展示顺序（可空，缺省按提交顺序）
     */
    public record ConflictRuleDto(String id, String type, List<String> starters, String message,
                                  Integer sort) {

        public ConflictRuleDto {
            starters = starters == null ? List.of() : List.copyOf(starters);
        }
    }

    // ==================== 模板 ====================

    /**
     * 模板列表项（**不含 content CLOB**，列表接口专用）。
     *
     * @param name      模板名（相对 templates/ 路径）
     * @param updatedAt 最后更新时间（可空）
     * @param sort      展示顺序
     */
    public record TemplateSummary(String name, java.time.Instant updatedAt, Integer sort) {
    }

    /**
     * 模板列表视图。
     *
     * @param templates 模板摘要清单（按 sort 升序，不含 content）
     */
    public record TemplateListView(List<TemplateSummary> templates) {

        public TemplateListView {
            templates = List.copyOf(templates);
        }
    }

    /**
     * 模板详情（含 content 全文，按需单独取）。
     *
     * @param name      模板名
     * @param content   模板全文（FreeMarker）
     * @param updatedAt 最后更新时间（可空）
     * @param sort      展示顺序
     */
    public record TemplateDetail(String name, String content, java.time.Instant updatedAt, Integer sort) {
    }

    /**
     * 单模板新增/更新请求（按 name 幂等 upsert；整体提交语义请用
     * {@link TemplateCatalogUpsertRequest}）。
     *
     * @param name     模板名（相对 templates/ 路径）
     * @param content  模板全文
     * @param operator 操作人
     * @param sort     展示顺序（可空，缺省追加至末尾）
     */
    public record TemplateUpsertRequest(String name, String content, String operator, Integer sort) {
    }

    /**
     * 模板集合整体提交请求（全量替换草稿模板集）。
     *
     * @param templates 模板项清单（含 content，全量）
     * @param operator  操作人
     */
    public record TemplateCatalogUpsertRequest(List<TemplateDetail> templates, String operator) {
    }

    /**
     * 结构化草稿保存结果。
     *
     * @param kind     配置种类（小写中划线形式）
     * @param rows     实际写入的结构行数（审计与前端提示用）
     * @param version  保存后的草稿版本（kind 级乐观锁）
     * @param summary  规模摘要（行数/模板数/规则数，不含模板全文）
     */
    public record DraftSaveResult(String kind, int rows, Integer version, String summary) {
    }
}
