package com.wymix.initializer.app.admin.sync;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshot;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.service.ConfigService;
import com.wymix.initializer.core.sync.CatalogSyncProvider;
import com.wymix.initializer.core.sync.SyncResult;
import com.wymix.initializer.core.sync.SyncedStarter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CatalogSyncService} 集成测试（M3.3，H2 内存库）：
 * 拉取 → 合并（同名手工优先）→ 同步层草稿重建 → 发布链路三通道归并热生效（验收标准 3）。
 */
@SpringBootTest(classes = InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:sync-svc-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token",
        // 排除自动装配的 Mock/Remote 通道：本测试通过 TestConfiguration 注入受控 provider
        "initializer.sync.schedule.enabled=false",
        "initializer.sync.mock.enabled=false"
})
@Import(CatalogSyncServiceTest.SharedProviderConfig.class)
class CatalogSyncServiceTest {

    private static final String METADATA_YAML = """
            javaVersions:
              - id: "17"
                label: "Java 17"
                recommendedBootVersion: "3.2.5"
            bootVersions:
              - id: "3.2.5"
                label: "Spring Boot 3.2.5"
                official: true
                supportedJavaVersions: ["17"]
                starters: ["web"]
            starters:
              web:
                label: "Spring Web"
                dependency:
                  groupId: "org.springframework.boot"
                  artifactId: "spring-boot-starter-web"
            """;

    private static final String STARTER_YAML = """
            id: web
            category: web
            maintainer: official
            description: Spring MVC
            dependencies:
              - groupId: org.springframework.boot
                artifactId: spring-boot-starter-web
            linkages: {}
            """;

    /** 手工配置与制品库同名的条目（验证同名以手工为准）。 */
    private static final String MANUAL_CONFLICT_YAML = """
            id: xxx-oss-spring-boot-starter
            category: middleware
            maintainer: internal
            description: 手工配置的 OSS starter（应优先于制品库同步）
            dependencies:
              - groupId: com.company.manual
                artifactId: manual-xxx-oss
                version: "9.9.9"
            linkages: {}
            """;

    @Autowired
    private CatalogSyncService syncService;

    @Autowired
    private CatalogSnapshotHolder snapshotHolder;

    @Autowired
    private ConfigRepository repository;

    @Autowired
    private ConfigService configService;

    /** 受控同步 provider：测试内可编程制品库返回内容（与测试类共享实例）。 */
    static class ProgrammableProvider implements CatalogSyncProvider {
        private List<SyncedStarter> starters = List.of();
        private boolean failing;

        void setStarters(List<SyncedStarter> starters) {
            this.starters = starters;
        }

        void setFailing(boolean failing) {
            this.failing = failing;
        }

        @Override
        public String providerId() {
            return "test-repo";
        }

        @Override
        public SyncResult fetch() {
            if (failing) {
                throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED, "模拟制品库不可达");
            }
            return new SyncResult(providerId(), starters);
        }
    }

    /** 共享 provider 实例。 */
    static final ProgrammableProvider SHARED_PROVIDER = new ProgrammableProvider();

    @TestConfiguration
    static class SharedProviderConfig {
        @Bean
        public CatalogSyncProvider sharedTestProvider() {
            return SHARED_PROVIDER;
        }
    }

    private void publishBaseline() {
        configService.saveDraft(ConfigKind.METADATA, METADATA_YAML, null, "tester");
        configService.saveDraft(ConfigKind.STARTER, STARTER_YAML, null, "tester");
        configService.publish("tester", "baseline");
    }

    private SyncedStarter synced(String id, String groupId, String artifactId) {
        return new SyncedStarter(id, "middleware", "制品库同步的 " + id,
                groupId, artifactId, "1.0.0");
    }

    @Test
    void syncAll_writesSyncDraftAndAudit() {
        publishBaseline();
        setProgrammable(synced("xxx-oss-spring-boot-starter", "com.company", "xxx-oss"),
                synced("xxx-sms-spring-boot-starter", "com.company", "xxx-sms"));

        var outcome = syncService.syncAll("tester");

        assertThat(outcome.channels()).hasSize(1);
        assertThat(outcome.channels().get(0).providerId()).isEqualTo("test-repo");
        assertThat(outcome.channels().get(0).adopted()).isEqualTo(2);
        assertThat(outcome.channels().get(0).overridden()).isZero();
        assertThat(outcome.summary()).contains("test-repo 采纳 2 / 覆盖 0");

        // 同步层草稿已重建（结构化行，制品库为权威源），手工草稿零改动
        var syncRows = repository.readDraftScope(ConfigKind.STARTER_SYNC);
        assertThat(syncRows.starters()).extracting("starterId")
                .containsExactlyInAnyOrder("xxx-oss-spring-boot-starter", "xxx-sms-spring-boot-starter");
        assertThat(syncRows.starters()).allSatisfy(row ->
                assertThat(row.getSource()).isEqualTo(com.wymix.initializer.app.admin.repository
                        .ConfigRepository.SOURCE_SYNC));
        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters()).hasSize(1);
        assertThat(repository.readDraftScope(ConfigKind.METADATA).javaVersions()).hasSize(1);

        // 审计留痕
        assertThat(repository.listAudits().stream()
                .anyMatch(a -> "SYNC".equals(a.getAction()))).isTrue();
    }

    @Test
    void syncAll_thenPublish_syncedStarterAppearsInSnapshot() {
        publishBaseline();
        setProgrammable(synced("xxx-oss-spring-boot-starter", "com.company", "xxx-oss"));
        syncService.syncAll("tester");

        // 发布链路三通道归并：同步条目随快照热生效（验收标准 3）
        configService.publish("tester", "after-sync");

        CatalogSnapshot snapshot = snapshotHolder.requireCurrent();
        assertThat(snapshot.starterCatalog().contains("xxx-oss-spring-boot-starter")).isTrue();
        var entry = snapshot.starterCatalog().entry("xxx-oss-spring-boot-starter");
        assertThat(entry.maintainer().name()).isEqualTo("INTERNAL");
        assertThat(entry.dependencies().get(0).artifactId()).isEqualTo("xxx-oss");
        assertThat(entry.dependencies().get(0).version()).isEqualTo("1.0.0");

        // 发布链路自动补位：同步条目对全部 Boot 版本可选（/api/metadata/starters 可见）
        assertThat(snapshot.metadataCatalog().starters("3.2.5"))
                .contains("xxx-oss-spring-boot-starter");
        var def = snapshot.metadataCatalog().starterDefinition("xxx-oss-spring-boot-starter");
        assertThat(def.dependency().groupId()).isEqualTo("com.company");
    }

    @Test
    void syncAll_manualConfigWinsOnSameId() {
        publishBaseline();
        // 手工配置先声明同名条目
        configService.saveDraft(ConfigKind.STARTER,
                STARTER_YAML + "\n---\n" + MANUAL_CONFLICT_YAML, null, "tester");
        configService.publish("tester", "with-manual-conflict");
        setProgrammable(synced("xxx-oss-spring-boot-starter", "com.company.repo", "repo-xxx-oss"));

        var outcome = syncService.syncAll("tester");

        // 同名以手工配置为准：采纳 0、覆盖 1（PRD 双通道并存语义）
        assertThat(outcome.channels().get(0).adopted()).isZero();
        assertThat(outcome.channels().get(0).overridden()).isEqualTo(1);

        // 同步层草稿不含被覆盖条目（结构化行按 starterId 断言）
        assertThat(repository.readDraftScope(ConfigKind.STARTER_SYNC).starters())
                .extracting("starterId").doesNotContain("xxx-oss-spring-boot-starter");

        // 发布后快照保留手工配置
        configService.publish("tester", "after-conflict-sync");
        var entry = snapshotHolder.requireCurrent()
                .starterCatalog().entry("xxx-oss-spring-boot-starter");
        assertThat(entry.dependencies().get(0).groupId()).isEqualTo("com.company.manual");
        assertThat(entry.dependencies().get(0).artifactId()).isEqualTo("manual-xxx-oss");
        assertThat(entry.description()).contains("手工配置");
    }

    @Test
    void syncAll_repoRemovesStarter_disappearsAfterNextPublish() {
        publishBaseline();
        setProgrammable(synced("xxx-oss-spring-boot-starter", "com.company", "xxx-oss"));
        syncService.syncAll("tester");
        configService.publish("tester", "first-sync");
        assertThat(snapshotHolder.requireCurrent().starterCatalog()
                .contains("xxx-oss-spring-boot-starter")).isTrue();

        // 制品库下架：下一次全量同步重建草稿（权威源收缩），发布后从可选清单消失
        setProgrammable();
        syncService.syncAll("tester");
        configService.publish("tester", "second-sync");

        CatalogSnapshot snapshot = snapshotHolder.requireCurrent();
        assertThat(snapshot.starterCatalog().contains("xxx-oss-spring-boot-starter")).isFalse();
        assertThat(snapshot.metadataCatalog().starters("3.2.5"))
                .doesNotContain("xxx-oss-spring-boot-starter");
        // 手工条目不受影响
        assertThat(snapshot.starterCatalog().contains("web")).isTrue();
    }

    @Test
    void syncAll_allChannelsFail_throws4005AndKeepsDrafts() {
        publishBaseline();
        SHARED_PROVIDER.setFailing(true);
        try {
            assertThatThrownBy(() -> syncService.syncAll("tester"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED))
                    .hasMessageContaining("全部通道拉取失败");
            // 审计留痕
            assertThat(repository.listAudits().stream()
                    .anyMatch(a -> "SYNC_FAILED".equals(a.getAction()))).isTrue();
            // 草稿保持不变（上次同步结果保留）
            assertThat(configService.getDraft(ConfigKind.STARTER).contentYaml())
                    .contains("id: web");
        } finally {
            SHARED_PROVIDER.setFailing(false);
        }
    }

    @Test
    void syncOutcome_summaryDescribesChannels() {
        var outcome = new CatalogSyncService.SyncOutcome(List.of(
                new CatalogSyncService.ChannelOutcome("mock-repo", 2, 1, null),
                new CatalogSyncService.ChannelOutcome("internal-repo", 0, 0, "HTTP 500")));
        assertThat(outcome.summary()).contains("mock-repo 采纳 2 / 覆盖 1");
        assertThat(outcome.summary()).contains("internal-repo 失败");
    }

    @Test
    void mergedStarterYaml_nullChannels_meansNull() {
        assertThat(ConfigService.mergedStarterYaml(null, null)).isNull();
    }

    @Test
    void mergedStarterYaml_syncOnlyChannel_passesThrough() {
        assertThat(ConfigService.mergedStarterYaml(null, "id: x\n")).isEqualTo("id: x\n");
    }

    @Test
    void mergedStarterYaml_blankManual_fallsBackToSync() {
        assertThat(ConfigService.mergedStarterYaml("  ", "id: x\n")).isEqualTo("id: x\n");
    }

    @Test
    void mergedStarterYaml_bothChannels_joinedWithDocumentSeparator() {
        assertThat(ConfigService.mergedStarterYaml("id: a\n", "id: b\n"))
                .isEqualTo("id: a\n\n---\nid: b\n");
    }

    private void setProgrammable(SyncedStarter... starters) {
        SHARED_PROVIDER.setStarters(List.of(starters));
    }
}
