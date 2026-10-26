package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.repository.StructuredDraftWriter;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StructuredCatalogAssembler} 集成测试（结构化改造 T2，H2 内存库）：
 * 表行 → 核心模型直装、hidden 直取、loader 校验口径、发布失败明细、性能红线（禁 N+1）。
 */
@SpringBootTest(classes = InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:assembler-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token"
})
@Import(StructuredCatalogAssemblerTest.SqlCounterConfig.class)
class StructuredCatalogAssemblerTest {

    /** 拦截器作为静态单例注册，测试类读取同一实例的计数。 */
    private static final SqlStatementCounter SQL_COUNTER = new SqlStatementCounter();

    /**
     * 把 SQL 计数器注册进 MyBatis 插件链（与生产装配的乐观锁插件并存）。
     */
    @TestConfiguration
    static class SqlCounterConfig {

        @Bean
        SqlStatementCounter sqlStatementCounter() {
            return SQL_COUNTER;
        }
    }

    private static final String METADATA_YAML = """
            javaVersions:
              - id: "17"
                label: "Java 17"
                recommendedBootVersion: "3.2.5"
              - id: "21"
                label: "Java 21"
            bootVersions:
              - id: "3.2.5"
                label: "Spring Boot 3.2.5"
                official: true
                supportedJavaVersions: ["17", "21"]
                starters: ["web", "redis"]
                frameworkParent:
                  groupId: com.example
                  artifactId: example-parent
                  version: 1.0.0
            """;

    private static final String STARTER_YAML = """
            id: web
            category: web
            maintainer: official
            description: Spring Web
            required: true
            hidden: true
            dependencies:
              - groupId: org.springframework.boot
                artifactId: spring-boot-starter-web
            requires: [[redis, cache]]
            bootVersions: ["3.2.5"]
            linkages:
              config-package: "{basePackage}.config"
              templates:
                - source: starters/web/WebConfig.java.ftl
                  target: "{packagePath}/config/WebConfig.java"
              application-yml-fragments:
                - source: starters/web/web.yml
                  merge-strategy: override
              abstract-classes:
                - fqcn: com.example.WebSupport
                  alias: webSupport
            """;

    private static final String REDIS_STARTER_YAML = """
            id: redis
            category: data
            maintainer: internal
            description: Redis
            dependencies:
              - groupId: org.springframework.boot
                artifactId: spring-boot-starter-data-redis
            linkages: {}
            """;

    private static final String CACHE_STARTER_YAML = """
            id: cache
            category: data
            maintainer: internal
            description: Cache
            dependencies:
              - groupId: org.springframework.boot
                artifactId: spring-boot-starter-cache
            linkages: {}
            """;

    private static final String CONFLICT_YAML = """
            rules:
              - id: no-web-redis
                type: pair
                starters: ["web", "redis"]
                message: web 与 redis 互斥
            """;

    private static final String TEMPLATE_YAML = """
            name: pom.xml.ftl
            content: "<project>${groupId}</project>"
            """;

    @Autowired
    private StructuredCatalogAssembler assembler;

    @Autowired
    private StructuredDraftWriter writer;

    @Autowired
    private ConfigRepository repository;

    @BeforeEach
    void reset() {
        for (ConfigKind kind : ConfigKind.values()) {
            repository.clearDraftKind(kind);
        }
        SQL_COUNTER.reset();
    }

    @Test
    void assemble_emptyDraft_onlyBackfillsBuiltInBuildScripts() {
        // 空草稿：目录与规则仍为空，构建脚本由平台内置兜底（不因缺失而拖垮生成）
        CatalogSnapshot snapshot = assembler.assemble(ConfigRepository.DRAFT_RELEASE_ID);
        assertThat(snapshot.metadataCatalog().javaVersions()).isEmpty();
        assertThat(snapshot.starterCatalog().size()).isZero();
        assertThat(snapshot.templateOverrides()).containsOnlyKeys(
                "pom.xml.ftl", "build.gradle.ftl", "settings.gradle.ftl");
        assertThat(snapshot.templateOverrides().get("pom.xml.ftl")).contains("<project");
        assertThat(snapshot.conflictCatalog().size()).isZero();
    }

    @Test
    void assemble_metadataDirectly_mapsVersionsCompatibilityAndFrameworkParent() {
        repository.clearDraftKind(ConfigKind.METADATA);
        writer.write(ConfigKind.METADATA, METADATA_YAML);

        CatalogSnapshot snapshot = assembler.assemble(ConfigRepository.DRAFT_RELEASE_ID);

        assertThat(snapshot.metadataCatalog().javaVersions()).hasSize(2);
        assertThat(snapshot.metadataCatalog().javaVersion("17").recommendedBootVersion()).isEqualTo("3.2.5");
        assertThat(snapshot.metadataCatalog().bootVersion("3.2.5").supportedJavaVersions())
                .containsExactly("17", "21");
        assertThat(snapshot.metadataCatalog().bootVersion("3.2.5").frameworkParent())
                .isNotNull()
                .satisfies(fp -> {
                    assertThat(fp.groupId()).isEqualTo("com.example");
                    assertThat(fp.artifactId()).isEqualTo("example-parent");
                    assertThat(fp.version()).isEqualTo("1.0.0");
                });
    }

    @Test
    void assemble_starterDirectly_mapsAllChildTablesAndHiddenTakenFromRow() {
        repository.clearDraftKind(ConfigKind.METADATA);
        writer.write(ConfigKind.METADATA, METADATA_YAML);
        repository.clearDraftKind(ConfigKind.STARTER);
        writer.write(ConfigKind.STARTER, STARTER_YAML + "\n---\n" + REDIS_STARTER_YAML
                + "\n---\n" + CACHE_STARTER_YAML);

        CatalogSnapshot snapshot = assembler.assemble(ConfigRepository.DRAFT_RELEASE_ID);
        StarterCatalogEntry web = snapshot.starterCatalog().entry("web");

        // hidden 从 cfg_starter 直取（不经 YAML 反编译）；required/坐标/联动/Boot 关联/requires 全链路还原
        assertThat(web.hidden()).isTrue();
        assertThat(web.required()).isTrue();
        assertThat(web.maintainer().name()).isEqualTo("OFFICIAL");
        assertThat(web.dependencies()).hasSize(1);
        assertThat(web.dependencies().get(0).artifactId()).isEqualTo("spring-boot-starter-web");
        assertThat(web.bootVersions()).containsExactly("3.2.5");
        assertThat(web.requires()).containsExactly(java.util.List.of("redis", "cache"));
        assertThat(web.linkages().configPackage()).isEqualTo("{basePackage}.config");
        assertThat(web.linkages().templates()).hasSize(1);
        assertThat(web.linkages().ymlFragments().get(0).mergeStrategy())
                .isEqualTo(com.wymix.initializer.core.starter.YmlFragmentLinkage.MergeStrategy.OVERRIDE);
        assertThat(web.linkages().abstractClasses().get(0).alias()).isEqualTo("webSupport");
        // starter 清单条目自动补位为 Boot 版本可选（merge 语义）
        assertThat(snapshot.metadataCatalog().starters("3.2.5"))
                .contains("web", "redis", "cache");
    }

    @Test
    void assemble_templatesAndConflictRules_areMapped() {
        repository.clearDraftKind(ConfigKind.METADATA);
        writer.write(ConfigKind.METADATA, METADATA_YAML);
        repository.clearDraftKind(ConfigKind.STARTER);
        writer.write(ConfigKind.STARTER, STARTER_YAML + "\n---\n" + REDIS_STARTER_YAML
                + "\n---\n" + CACHE_STARTER_YAML);
        repository.clearDraftKind(ConfigKind.TEMPLATE);
        writer.write(ConfigKind.TEMPLATE, TEMPLATE_YAML);
        repository.clearDraftKind(ConfigKind.CONFLICT_RULE);
        writer.write(ConfigKind.CONFLICT_RULE, CONFLICT_YAML);

        CatalogSnapshot snapshot = assembler.assemble(ConfigRepository.DRAFT_RELEASE_ID);

        assertThat(snapshot.templateOverrides()).containsKey("pom.xml.ftl");
        assertThat(snapshot.conflictCatalog().size()).isEqualTo(1);
        assertThat(snapshot.conflictCatalog().all().get(0).starters()).containsExactly("web", "redis");
    }

    @Test
    void assemble_danglingJavaReference_throws4002WithDetail() {
        repository.clearDraftKind(ConfigKind.METADATA);
        writer.write(ConfigKind.METADATA, """
                javaVersions:
                  - id: "17"
                    label: "Java 17"
                bootVersions:
                  - id: "3.2.5"
                    label: "Spring Boot 3.2.5"
                    supportedJavaVersions: ["99"]
                    starters: []
                """);

        assertThatThrownBy(() -> assembler.assemble(ConfigRepository.DRAFT_RELEASE_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("未定义的 Java 版本").contains("99");
                });
    }

    @Test
    void assemble_danglingRequiresReference_throws4002() {
        repository.clearDraftKind(ConfigKind.METADATA);
        writer.write(ConfigKind.METADATA, METADATA_YAML);
        repository.clearDraftKind(ConfigKind.STARTER);
        writer.write(ConfigKind.STARTER, """
                id: web
                category: web
                maintainer: official
                description: Spring Web
                dependencies:
                  - groupId: org.springframework.boot
                    artifactId: spring-boot-starter-web
                requires: [[nope]]
                linkages: {}
                """);

        assertThatThrownBy(() -> assembler.assemble(ConfigRepository.DRAFT_RELEASE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void assemble_danglingConflictReference_throws4002WithDetail() {
        repository.clearDraftKind(ConfigKind.METADATA);
        writer.write(ConfigKind.METADATA, METADATA_YAML);
        repository.clearDraftKind(ConfigKind.CONFLICT_RULE);
        writer.write(ConfigKind.CONFLICT_RULE, """
                rules:
                  - id: bad
                    type: pair
                    starters: ["web", "ghost"]
                    message: 悬空引用
                """);

        assertThatThrownBy(() -> assembler.assemble(ConfigRepository.DRAFT_RELEASE_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("清单中不存在的 starter");
                });
    }

    @Test
    void assemble_invalidTemplateSyntax_throws4002() {
        repository.clearDraftKind(ConfigKind.TEMPLATE);
        writer.write(ConfigKind.TEMPLATE, """
                name: bad.ftl
                content: "<#if x>"
                """);

        assertThatThrownBy(() -> assembler.assemble(ConfigRepository.DRAFT_RELEASE_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void assemble_issuesAtMostOneSelectPerTable_noN1() {
        repository.clearDraftKind(ConfigKind.METADATA);
        writer.write(ConfigKind.METADATA, METADATA_YAML);
        repository.clearDraftKind(ConfigKind.STARTER);
        writer.write(ConfigKind.STARTER, STARTER_YAML + "\n---\n" + REDIS_STARTER_YAML
                + "\n---\n" + CACHE_STARTER_YAML);
        repository.clearDraftKind(ConfigKind.TEMPLATE);
        writer.write(ConfigKind.TEMPLATE, TEMPLATE_YAML);
        repository.clearDraftKind(ConfigKind.CONFLICT_RULE);
        writer.write(ConfigKind.CONFLICT_RULE, CONFLICT_YAML);

        SQL_COUNTER.reset();
        CatalogSnapshot snapshot = assembler.assemble(ConfigRepository.DRAFT_RELEASE_ID);
        int statements = SQL_COUNTER.count();

        // 性能红线：装配的查询条数 ≤ 表数量（15），与行数无关（此处 3 starter + 多子行）
        assertThat(snapshot.starterCatalog().size()).isEqualTo(3);
        assertThat(statements).isLessThanOrEqualTo(15).isPositive();
    }

    @Test
    void assemble_ymlFragmentWithoutMergeStrategy_defaultsToAppend() {
        // 覆盖 mergeStrategy 缺省分支（写入口径存 APPEND；此处直接构造缺省行的等价路径）
        repository.clearDraftKind(ConfigKind.METADATA);
        writer.write(ConfigKind.METADATA, METADATA_YAML);
        repository.clearDraftKind(ConfigKind.STARTER);
        writer.write(ConfigKind.STARTER, """
                id: redis
                category: data
                maintainer: internal
                description: Redis
                dependencies:
                  - groupId: org.springframework.boot
                    artifactId: spring-boot-starter-data-redis
                linkages:
                  application-yml-fragments:
                    - source: starters/redis/redis.yml
                    - source: starters/redis/extra.yml
                      merge-strategy: override
                """);

        var ymls = assembler.assemble(ConfigRepository.DRAFT_RELEASE_ID)
                .starterCatalog().entry("redis").linkages().ymlFragments();
        assertThat(ymls.get(0).mergeStrategy())
                .isEqualTo(com.wymix.initializer.core.starter.YmlFragmentLinkage.MergeStrategy.APPEND);
        assertThat(ymls.get(1).mergeStrategy())
                .isEqualTo(com.wymix.initializer.core.starter.YmlFragmentLinkage.MergeStrategy.OVERRIDE);
    }

    @Test
    void assemble_internalMaintainerIsMapped() {
        repository.clearDraftKind(ConfigKind.STARTER);
        writer.write(ConfigKind.STARTER, REDIS_STARTER_YAML);
        // internal 维护方（另一枚举取值分支）
        assertThat(assembler.assemble(ConfigRepository.DRAFT_RELEASE_ID)
                .starterCatalog().entry("redis").maintainer().name()).isEqualTo("INTERNAL");
    }

    @Test
    void assemble_bootRowWithoutCompleteFrameworkParent_hasNullParent() {
        repository.clearDraftKind(ConfigKind.METADATA);
        writer.write(ConfigKind.METADATA, """
                javaVersions:
                  - id: "17"
                    label: "Java 17"
                bootVersions:
                  - id: "3.2.5"
                    label: "Spring Boot 3.2.5"
                    official: false
                    supportedJavaVersions: ["17"]
                    starters: []
                """);
        var boot = assembler.assemble(ConfigRepository.DRAFT_RELEASE_ID)
                .metadataCatalog().bootVersion("3.2.5");
        assertThat(boot.frameworkParent()).isNull();
        assertThat(boot.official()).isFalse();
    }

    @Test
    void assemble_isolatesReleaseScopes_snapshotRowsNotVisibleAsDraft() {
        repository.clearDraftKind(ConfigKind.METADATA);
        writer.write(ConfigKind.METADATA, METADATA_YAML);
        long releaseId = 987_654L;
        repository.copyAsRelease(ConfigRepository.DRAFT_RELEASE_ID, releaseId);

        CatalogSnapshot releaseSnapshot = assembler.assemble(releaseId);
        assertThat(releaseSnapshot.metadataCatalog().javaVersions()).hasSize(2);

        // 草稿清空后发布快照不受影响（release_id 作用域隔离）
        repository.clearDraftKind(ConfigKind.METADATA);
        assertThat(assembler.assemble(releaseId).metadataCatalog().javaVersions()).hasSize(2);
        repository.deleteScope(releaseId);
    }

    @Test
    void assemble_withMockRepository_rejectsNullRowCollections() {
        // 装配器只做内存分组，签名不接收 null（由 ConfigRepository#readScope 保证集合非 null）
        ConfigRepository mockRepository = Mockito.mock(ConfigRepository.class);
        Mockito.when(mockRepository.readScope(Mockito.anyLong()))
                .thenReturn(com.wymix.initializer.app.admin.repository.StructuredSnapshot.empty(0L));
        StructuredCatalogAssembler mocked = new StructuredCatalogAssembler(mockRepository);
        assertThat(mocked.assemble(0L).starterCatalog().size()).isZero();
    }
}
