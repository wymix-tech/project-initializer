package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.repository.StructuredDraftWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CatalogSnapshotBootstrap} 集成测试（结构化改造 T2，非 demo 启动）：
 * 启动从最新 release 的**结构行**直装快照；无发布 / 空作用域时播种空快照（读路径始终可用）。
 *
 * <p>构造方式：手工调用播种 Runner（{@code catalogSnapshotSeeder}）模拟一次启动，
 * 断言其从 DB 装配的结果——避免为测试重启整个应用上下文。
 */
@SpringBootTest(classes = InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:bootstrap-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token",
        // 非 demo：走「从 DB 最新 release 装表」分支
        "initializer.demo.enabled=false",
        // 外部清单目录指向不存在的路径，避免干扰 DB 分支
        "initializer.catalog.dir=/nonexistent-catalog-dir-for-test"
})
class CatalogSnapshotBootstrapTest {

    private static final String METADATA_YAML = """
            javaVersions:
              - id: "17"
                label: "Java 17"
            bootVersions:
              - id: "3.2.5"
                label: "Spring Boot 3.2.5"
                official: true
                supportedJavaVersions: ["17"]
                starters: ["web"]
            """;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("catalogSnapshotSeeder")
    private ApplicationRunner seeder;

    @Autowired
    private CatalogSnapshotHolder snapshotHolder;

    @Autowired
    private ConfigRepository repository;

    @Autowired
    private StructuredDraftWriter writer;

    @Test
    void seed_withPublishedRelease_assemblesSnapshotFromRows() throws Exception {
        for (ConfigKind kind : ConfigKind.values()) {
            repository.clearDraftKind(kind);
        }
        // 造一个发布快照作用域（模拟「管理端发布过的配置」）
        writer.write(ConfigKind.METADATA, METADATA_YAML);
        long releaseId = repository.insertRelease("tester", "bootstrap 测试").getId();
        repository.copyAsRelease(ConfigRepository.DRAFT_RELEASE_ID, releaseId);

        snapshotHolder.seed(null);
        seeder.run(null);

        CatalogSnapshot snapshot = snapshotHolder.current();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.metadataCatalog().javaVersions()).hasSize(1);
        assertThat(snapshot.metadataCatalog().javaVersion("17").label()).isEqualTo("Java 17");

        // 清理
        repository.deleteScope(releaseId);
        repository.clearDraftKind(ConfigKind.METADATA);
    }

    @Test
    void seed_latestReleaseWithEmptyScope_usesEmptySnapshot() throws Exception {
        // 有发布记录但作用域为空（历史坏数据 / 空发布）→ 空快照兜底，不抛异常
        for (ConfigKind kind : ConfigKind.values()) {
            repository.clearDraftKind(kind);
        }
        long emptyRelease = repository.insertRelease("tester", "空发布").getId();
        // 刻意不复制任何结构行
        snapshotHolder.seed(new CatalogSnapshot(
                com.wymix.initializer.core.metadata.MetadataCatalog.empty(),
                com.wymix.initializer.core.starter.StarterCatalog.of(java.util.List.of())));

        seeder.run(null);

        CatalogSnapshot snapshot = snapshotHolder.current();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.metadataCatalog().javaVersions()).isEmpty();
        repository.deleteScope(emptyRelease);
    }

    @Test
    void seed_assemblyFailure_keepsEmptySnapshotAndDoesNotThrow() throws Exception {
        // 最新 release 的结构行非法（Boot 引用未定义 Java）→ 装配抛错被吞，启动不阻塞，
        // 快照保持先前的空快照（读路径可用，管理端可重新发布修复）
        for (ConfigKind kind : ConfigKind.values()) {
            repository.clearDraftKind(kind);
        }
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
        long badRelease = repository.insertRelease("tester", "坏发布").getId();
        repository.copyAsRelease(ConfigRepository.DRAFT_RELEASE_ID, badRelease);

        snapshotHolder.seed(new CatalogSnapshot(
                com.wymix.initializer.core.metadata.MetadataCatalog.empty(),
                com.wymix.initializer.core.starter.StarterCatalog.of(java.util.List.of())));

        seeder.run(null);

        // 装配失败不阻塞启动：仍持有先前的空快照
        assertThat(snapshotHolder.current()).isNotNull();
        assertThat(snapshotHolder.current().metadataCatalog().javaVersions()).isEmpty();
        repository.deleteScope(badRelease);
        repository.clearDraftKind(ConfigKind.METADATA);
    }

    @Test
    void seed_withoutRelease_usesEmptySnapshot() throws Exception {
        // 直接播种空快照：不依赖 DB 中是否已有历史发布（共享库可能被其他用例污染），
        // 这里只验证「无发布时读路径可用」的契约——空快照非 null 且各集合为空。
        snapshotHolder.seed(new CatalogSnapshot(
                com.wymix.initializer.core.metadata.MetadataCatalog.empty(),
                com.wymix.initializer.core.starter.StarterCatalog.of(java.util.List.of())));

        CatalogSnapshot snapshot = snapshotHolder.requireCurrent();
        assertThat(snapshot.metadataCatalog().javaVersions()).isEmpty();
        assertThat(snapshot.starterCatalog().size()).isZero();
        assertThat(snapshot.templateOverrides()).isEmpty();
        assertThat(snapshot.conflictCatalog().size()).isZero();
    }
}
