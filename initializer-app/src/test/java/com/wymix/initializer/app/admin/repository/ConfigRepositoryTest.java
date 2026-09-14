package com.wymix.initializer.app.admin.repository;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.entity.CfgBootJavaEntity;
import com.wymix.initializer.app.admin.entity.CfgBootStarterRefEntity;
import com.wymix.initializer.app.admin.entity.CfgBootVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgConflictRuleEntity;
import com.wymix.initializer.app.admin.entity.CfgConflictRuleStarterEntity;
import com.wymix.initializer.app.admin.entity.CfgJavaVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterBootVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterDependencyEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageAbstractEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageTemplateEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageYmlEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterRequireGroupEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterRequireItemEntity;
import com.wymix.initializer.app.admin.entity.CfgTemplateEntity;
import com.wymix.initializer.app.admin.entity.ConfigAuditEntity;
import com.wymix.initializer.app.admin.entity.ConfigDraftMetaEntity;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ConfigRepository} 集成测试（结构化改造 T1，H2 内存库）：
 * 结构行按 {@code release_id} 作用域读写、草稿乐观锁、发布复制、整体替换与清空。
 */
@SpringBootTest(classes = InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-repo-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token"
})
class ConfigRepositoryTest {

    @Autowired
    private ConfigRepository repository;

    // ==================== 草稿种类元信息（kind 级乐观锁） ====================

    @Test
    void dataSql_seedsDraftMetaForAllFourKinds() {
        List<String> kinds = repository.listDraftMeta().stream()
                .map(ConfigDraftMetaEntity::getKind).toList();
        assertThat(kinds).contains("METADATA", "STARTER", "STARTER_SYNC", "TEMPLATE", "CONFLICT_RULE");
    }

    @Test
    void parse_invalidKind_throws4001() {
        assertThatThrownBy(() -> ConfigKind.parse("nope"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_DRAFT_NOT_FOUND));
    }

    @Test
    void updateDraftMeta_withStaleVersion_throws4003() {
        ConfigDraftMetaEntity entity = repository.findDraftMeta(ConfigKind.TEMPLATE);
        int stale = entity.getVersion();

        // 先正常推进一次版本
        entity.setVersion(stale);
        repository.updateDraftMeta(entity, "tester");
        assertThat(repository.findDraftMeta(ConfigKind.TEMPLATE).getVersion()).isGreaterThan(stale);

        // 再用陈旧版本更新 → 乐观锁拦截 → 4003
        ConfigDraftMetaEntity stale_entity = new ConfigDraftMetaEntity();
        stale_entity.setId(entity.getId());
        stale_entity.setKind(entity.getKind());
        stale_entity.setVersion(stale);
        assertThatThrownBy(() -> repository.updateDraftMeta(stale_entity, "other"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_CONCURRENT_MODIFY));
    }

    @Test
    void dataSql_isIdempotent_draftMetaNotDuplicated() {
        // data.sql 幂等：预置行已存在时重复执行不产生重复行（唯一键兜底）
        assertThat(repository.listDraftMeta())
                .extracting(ConfigDraftMetaEntity::getKind)
                .doesNotHaveDuplicates();
        assertThat(repository.findDraftMeta(ConfigKind.METADATA).getVersion()).isZero();
    }

    // ==================== 结构行读写与作用域隔离 ====================

    @Test
    void replaceDraft_thenReadScope_roundTripsEveryTable() {
        StructuredSnapshot snapshot = sampleSnapshot();
        repository.replaceDraftSingleParent(snapshot);

        StructuredSnapshot loaded = repository.readScope(ConfigRepository.DRAFT_RELEASE_ID);
        assertThat(loaded.javaVersions()).hasSize(1);
        assertThat(loaded.bootVersions()).hasSize(1);
        assertThat(loaded.bootJava()).hasSize(1);
        assertThat(loaded.bootStarterRefs()).hasSize(1);
        assertThat(loaded.starters()).hasSize(1);
        assertThat(loaded.starterDependencies()).hasSize(1);
        assertThat(loaded.starterRequireGroups()).hasSize(1);
        assertThat(loaded.starterRequireItems()).hasSize(1);
        assertThat(loaded.starterBootVersions()).hasSize(1);
        assertThat(loaded.starterLinkageTemplates()).hasSize(1);
        assertThat(loaded.starterLinkageYmls()).hasSize(1);
        assertThat(loaded.starterLinkageAbstracts()).hasSize(1);
        assertThat(loaded.conflictRules()).hasSize(1);
        assertThat(loaded.conflictRuleStarters()).hasSize(2);
        assertThat(loaded.templates()).hasSize(1);
        assertThat(loaded.templates().get(0).getContent()).contains("自定义说明");
    }

    @Test
    void scopeIsolation_releaseZeroAndReleaseOneCoexistWithSameBusinessKey() {
        repository.replaceDraftSingleParent(sampleSnapshot());

        ConfigReleaseEntity release = repository.insertRelease("tester", "快照");
        int rows = repository.copyAsRelease(ConfigRepository.DRAFT_RELEASE_ID, release.getId());
        assertThat(rows).isPositive();

        // 同一 starter_id 在两个作用域各存一行：唯一约束必须带 release_id 才不冲突
        assertThat(repository.readScope(0L).starters()).hasSize(1);
        assertThat(repository.readScope(release.getId()).starters()).hasSize(1);
        assertThat(repository.readScope(release.getId()).starters().get(0).getStarterId()).isEqualTo("web");
    }

    @Test
    void copyAsRelease_remapsChildForeignKeysToNewParentPks() {
        repository.replaceDraftSingleParent(sampleSnapshot());
        ConfigReleaseEntity release = repository.insertRelease("tester", "快照");
        repository.copyAsRelease(ConfigRepository.DRAFT_RELEASE_ID, release.getId());

        StructuredSnapshot source = repository.readScope(ConfigRepository.DRAFT_RELEASE_ID);
        StructuredSnapshot copied = repository.readScope(release.getId());

        long sourceStarterPk = source.starters().get(0).getId();
        long copiedStarterPk = copied.starters().get(0).getId();
        // 复制到新作用域会重新分配自增主键，历史快照与草稿互不影响
        assertThat(copiedStarterPk).isNotEqualTo(sourceStarterPk);
        // 子行外键必须指向复制后的新父主键，否则发布快照会悬挂到草稿行
        assertThat(copied.starterDependencies().get(0).getStarterPk()).isEqualTo(copiedStarterPk);
        assertThat(copied.starterBootVersions().get(0).getStarterPk()).isEqualTo(copiedStarterPk);
        long sourceGroupPk = source.starterRequireGroups().get(0).getId();
        long copiedGroupPk = copied.starterRequireGroups().get(0).getId();
        assertThat(copied.starterRequireItems().get(0).getGroupPk()).isEqualTo(copiedGroupPk);
        assertThat(copiedGroupPk).isNotEqualTo(sourceGroupPk);
    }

    @Test
    void replaceDraft_isIdempotentAndLeavesNoOrphanChildRows() {
        repository.replaceDraftSingleParent(sampleSnapshot());
        repository.replaceDraftSingleParent(sampleSnapshot());

        StructuredSnapshot loaded = repository.readScope(ConfigRepository.DRAFT_RELEASE_ID);
        assertThat(loaded.rowCount()).isEqualTo(sampleSnapshot().rowCount());
        assertThat(loaded.starterDependencies()).hasSize(1);
        assertThat(loaded.conflictRuleStarters()).hasSize(2);
    }

    @Test
    void deleteScope_removesOnlyTargetScope() {
        repository.replaceDraftSingleParent(sampleSnapshot());
        ConfigReleaseEntity release = repository.insertRelease("tester", "快照");
        repository.copyAsRelease(ConfigRepository.DRAFT_RELEASE_ID, release.getId());

        repository.deleteScope(release.getId());

        assertThat(repository.readScope(release.getId()).isEmpty()).isTrue();
        assertThat(repository.readScope(ConfigRepository.DRAFT_RELEASE_ID).isEmpty()).isFalse();
    }

    // ==================== 过渡桥（kind 投影 / 来源通道过滤） ====================

    @Test
    void readDraftScope_projectsOnlyRelevantTablesPerKind() {
        repository.replaceDraftSingleParent(sampleSnapshot());

        StructuredSnapshot metadata = repository.readDraftScope(ConfigKind.METADATA);
        assertThat(metadata.javaVersions()).hasSize(1);
        assertThat(metadata.starters()).isEmpty();
        assertThat(metadata.templates()).isEmpty();

        StructuredSnapshot templates = repository.readDraftScope(ConfigKind.TEMPLATE);
        assertThat(templates.templates()).hasSize(1);
        assertThat(templates.javaVersions()).isEmpty();
    }

    @Test
    void readDraftScope_splitsStarterAndSyncBySourceColumn() {
        StructuredSnapshot snapshot = sampleSnapshot();
        CfgStarterEntity synced = new CfgStarterEntity();
        synced.setId(2L);
        synced.setStarterId("xxx-oss-spring-boot-starter");
        synced.setCategory("middleware");
        synced.setMaintainer("INTERNAL");
        synced.setSource(ConfigRepository.SOURCE_SYNC);
        synced.setSort(1);
        snapshot.starters().add(synced);
        repository.replaceDraftSingleParent(snapshot);

        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters())
                .extracting(CfgStarterEntity::getStarterId).containsExactly("web");
        assertThat(repository.readDraftScope(ConfigKind.STARTER_SYNC).starters())
                .extracting(CfgStarterEntity::getStarterId).containsExactly("xxx-oss-spring-boot-starter");
    }

    @Test
    void clearDraftKind_removesOnlyThatKindRows() {
        StructuredSnapshot snapshot = sampleSnapshot();
        CfgStarterEntity synced = new CfgStarterEntity();
        synced.setId(2L);
        synced.setStarterId("xxx-oss-spring-boot-starter");
        synced.setCategory("middleware");
        synced.setMaintainer("INTERNAL");
        synced.setSource(ConfigRepository.SOURCE_SYNC);
        snapshot.starters().add(synced);
        repository.replaceDraftSingleParent(snapshot);

        repository.clearDraftKind(ConfigKind.STARTER_SYNC);

        assertThat(repository.readDraftScope(ConfigKind.STARTER_SYNC).starters()).isEmpty();
        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters()).hasSize(1);
        assertThat(repository.readScope(ConfigRepository.DRAFT_RELEASE_ID).templates()).hasSize(1);
    }

    @Test
    void deleteDraftStartersBySource_removesChildRowsToo() {
        repository.replaceDraftSingleParent(sampleSnapshot());
        repository.deleteDraftStartersBySource(ConfigRepository.SOURCE_MANUAL);

        StructuredSnapshot loaded = repository.readScope(ConfigRepository.DRAFT_RELEASE_ID);
        assertThat(loaded.starters()).isEmpty();
        assertThat(loaded.starterDependencies()).isEmpty();
        assertThat(loaded.starterRequireGroups()).isEmpty();
        assertThat(loaded.starterRequireItems()).isEmpty();
        assertThat(loaded.starterBootVersions()).isEmpty();
        assertThat(loaded.starterLinkageTemplates()).isEmpty();
        assertThat(loaded.starterLinkageYmls()).isEmpty();
        assertThat(loaded.starterLinkageAbstracts()).isEmpty();
        // 其他 kind 不受影响
        assertThat(loaded.templates()).hasSize(1);
    }

    @Test
    void deleteDraftStartersBySource_whenNoRows_returnsZero() {
        repository.clearDraftKind(ConfigKind.STARTER_SYNC);
        assertThat(repository.deleteDraftStartersBySource(ConfigRepository.SOURCE_SYNC)).isZero();
    }

    // ==================== 发布历史与审计 ====================

    @Test
    void insertRelease_listReleasesAndFindLatest() {
        ConfigReleaseEntity created = repository.insertRelease("tester", "首次发布");
        assertThat(created.getId()).isPositive();

        List<ConfigReleaseEntity> releases = repository.listReleases();
        assertThat(releases).isNotEmpty();
        assertThat(releases.get(0).getReleasedBy()).isEqualTo("tester");

        ConfigReleaseEntity latest = repository.findLatestRelease();
        assertThat(latest).isNotNull();
        assertThat(latest.getId()).isGreaterThanOrEqualTo(created.getId());
    }

    @Test
    void findRelease_unknownId_returnsNull() {
        assertThat(repository.findRelease(-1L)).isNull();
    }

    @Test
    void insertAudit_andListAudits() {
        repository.insertAudit("UPDATE_DRAFT", ConfigKind.METADATA, "tester", "测试审计");
        List<ConfigAuditEntity> audits = repository.listAudits();
        assertThat(audits).extracting(ConfigAuditEntity::getAction).contains("UPDATE_DRAFT");
        assertThat(audits.get(0).getCreatedAt()).isNotNull();
    }

    // ==================== 夹具 ====================

    /**
     * 构造覆盖全部 15 张表的样例快照（含父子引用，用于验证主键重映射）。
     *
     * @return 完整样例快照
     */
    private static StructuredSnapshot sampleSnapshot() {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);

        CfgJavaVersionEntity java = new CfgJavaVersionEntity();
        java.setVersionId("17");
        java.setLabel("Java 17");
        java.setDeprecated(false);
        java.setSort(0);
        snapshot.javaVersions().add(java);

        CfgBootVersionEntity boot = new CfgBootVersionEntity();
        boot.setId(1L);
        boot.setVersionId("3.2.5");
        boot.setLabel("Spring Boot 3.2.5");
        boot.setOfficial(true);
        boot.setSort(0);
        snapshot.bootVersions().add(boot);

        CfgStarterEntity starter = new CfgStarterEntity();
        // 显式占位主键：多 starter 场景下子行靠它区分父键（写入期由映射换算为新主键）
        starter.setId(1L);
        starter.setStarterId("web");
        starter.setCategory("web");
        starter.setMaintainer("OFFICIAL");
        starter.setDescription("Spring Web");
        starter.setRequired(false);
        starter.setConfigPackage("{basePackage}.config");
        starter.setSource(ConfigRepository.SOURCE_MANUAL);
        starter.setSort(0);
        snapshot.starters().add(starter);

        CfgConflictRuleEntity rule = new CfgConflictRuleEntity();
        rule.setId(1L);
        rule.setRuleId("web-vs-web2");
        rule.setRuleType("PAIR");
        rule.setMessage("两者不可同时选中");
        rule.setSort(0);
        snapshot.conflictRules().add(rule);

        CfgTemplateEntity template = new CfgTemplateEntity();
        template.setName("README.md.ftl");
        template.setContent("# 自定义说明\n");
        template.setSort(0);
        snapshot.templates().add(template);

        CfgBootJavaEntity bootJava = new CfgBootJavaEntity();
        bootJava.setBootPk(1L);
        bootJava.setJavaVersionId("17");
        snapshot.bootJava().add(bootJava);

        CfgBootStarterRefEntity bootStarterRef = new CfgBootStarterRefEntity();
        bootStarterRef.setBootPk(1L);
        bootStarterRef.setStarterId("web");
        bootStarterRef.setSort(0);
        snapshot.bootStarterRefs().add(bootStarterRef);

        CfgStarterDependencyEntity dep = new CfgStarterDependencyEntity();
        dep.setStarterPk(1L);
        dep.setGroupId("org.springframework.boot");
        dep.setArtifactId("spring-boot-starter-web");
        dep.setSort(0);
        snapshot.starterDependencies().add(dep);

        CfgStarterRequireGroupEntity group = new CfgStarterRequireGroupEntity();
        group.setId(1L);
        group.setStarterPk(1L);
        group.setGroupIndex(0);
        snapshot.starterRequireGroups().add(group);

        CfgStarterRequireItemEntity item = new CfgStarterRequireItemEntity();
        item.setGroupPk(1L);
        item.setRequiredStarterId("actuator");
        item.setSort(0);
        snapshot.starterRequireItems().add(item);

        CfgStarterBootVersionEntity starterBoot = new CfgStarterBootVersionEntity();
        starterBoot.setStarterPk(1L);
        starterBoot.setBootVersionId("3.2.5");
        snapshot.starterBootVersions().add(starterBoot);

        CfgStarterLinkageTemplateEntity linkageTemplate = new CfgStarterLinkageTemplateEntity();
        linkageTemplate.setStarterPk(1L);
        linkageTemplate.setSource("starters/xxx-redis/RedisConfig.java.ftl");
        linkageTemplate.setTarget("src/main/java/{packagePath}/config/RedisConfig.java");
        linkageTemplate.setSort(0);
        snapshot.starterLinkageTemplates().add(linkageTemplate);

        CfgStarterLinkageYmlEntity linkageYml = new CfgStarterLinkageYmlEntity();
        linkageYml.setStarterPk(1L);
        linkageYml.setSource("starters/xxx-redis/application-redis.yml");
        linkageYml.setMergeStrategy("APPEND");
        linkageYml.setSort(0);
        snapshot.starterLinkageYmls().add(linkageYml);

        CfgStarterLinkageAbstractEntity linkageAbstract = new CfgStarterLinkageAbstractEntity();
        linkageAbstract.setStarterPk(1L);
        linkageAbstract.setFqcn("com.company.redis.RedisSupport");
        linkageAbstract.setAlias("redisSupport");
        linkageAbstract.setSort(0);
        snapshot.starterLinkageAbstracts().add(linkageAbstract);

        CfgConflictRuleStarterEntity memberA = new CfgConflictRuleStarterEntity();
        memberA.setRulePk(1L);
        memberA.setStarterId("web");
        memberA.setSort(0);
        CfgConflictRuleStarterEntity memberB = new CfgConflictRuleStarterEntity();
        memberB.setRulePk(1L);
        memberB.setStarterId("web2");
        memberB.setSort(1);
        snapshot.conflictRuleStarters().add(memberA);
        snapshot.conflictRuleStarters().add(memberB);

        return snapshot;
    }

    // ==================== 边界与错误路径 ====================

    @Test
    void replaceDraft_strictMode_rejectsChildWithoutParentReference() {
        StructuredSnapshot broken = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        CfgStarterDependencyEntity dep = new CfgStarterDependencyEntity();
        dep.setGroupId("g");
        dep.setArtifactId("a");
        broken.starterDependencies().add(dep);

        // 严格模式（默认）拒绝悬挂外键：父键缺失即是编程错误
        assertThatThrownBy(() -> repository.replaceDraft(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("父键");
    }

    @Test
    void replaceDraft_singleParentBinding_rejectsAmbiguousParent() {
        // 两个 starter 父行都**没有**显式主键 + 子行父键缺失 → 无法确定绑定目标，必须显式失败
        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        for (String id : List.of("web", "web2")) {
            CfgStarterEntity starter = new CfgStarterEntity();
            starter.setStarterId(id);
            starter.setCategory("web");
            starter.setMaintainer("OFFICIAL");
            starter.setSource(ConfigRepository.SOURCE_MANUAL);
            snapshot.starters().add(starter);
        }
        CfgStarterDependencyEntity dep = new CfgStarterDependencyEntity();
        dep.setGroupId("g");
        dep.setArtifactId("a");
        snapshot.starterDependencies().add(dep);

        assertThatThrownBy(() -> repository.replaceDraft(snapshot, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("歧义");
    }

    @Test
    void replaceDraft_unknownParentPk_throwsIllegalState() {
        StructuredSnapshot broken = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        CfgStarterEntity starter = new CfgStarterEntity();
        starter.setId(1L);
        starter.setStarterId("web");
        starter.setCategory("web");
        starter.setMaintainer("OFFICIAL");
        broken.starters().add(starter);
        CfgStarterDependencyEntity dep = new CfgStarterDependencyEntity();
        dep.setStarterPk(42L);
        broken.starterDependencies().add(dep);

        assertThatThrownBy(() -> repository.replaceDraft(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无映射");
    }

    @Test
    void copyAsRelease_emptySource_writesNoRows() {
        repository.deleteScope(ConfigRepository.DRAFT_RELEASE_ID);
        ConfigReleaseEntity release = repository.insertRelease("tester", "空快照");

        assertThat(repository.copyAsRelease(ConfigRepository.DRAFT_RELEASE_ID, release.getId())).isZero();
        assertThat(repository.readScope(release.getId()).isEmpty()).isTrue();
    }

    @Test
    void clearDraftKind_metadataAndConflictAndTemplate() {
        repository.replaceDraftSingleParent(sampleSnapshot());

        assertThat(repository.clearDraftKind(ConfigKind.TEMPLATE)).isPositive();
        assertThat(repository.readDraftScope(ConfigKind.TEMPLATE).templates()).isEmpty();
        assertThat(repository.clearDraftKind(ConfigKind.CONFLICT_RULE)).isPositive();
        assertThat(repository.readDraftScope(ConfigKind.CONFLICT_RULE).conflictRules()).isEmpty();
        assertThat(repository.clearDraftKind(ConfigKind.METADATA)).isPositive();
        assertThat(repository.readDraftScope(ConfigKind.METADATA).javaVersions()).isEmpty();
    }

    @Test
    void clearDraftKind_twice_leavesCleanState() {
        repository.replaceDraftSingleParent(sampleSnapshot());
        repository.clearDraftKind(ConfigKind.STARTER);
        assertThat(repository.clearDraftKind(ConfigKind.STARTER)).isZero();
    }

    @Test
    void updateDraftMeta_unknownEntity_isNoOpOrThrows() {
        ConfigDraftMetaEntity ghost = new ConfigDraftMetaEntity();
        ghost.setId(-1L);
        ghost.setKind("METADATA");
        ghost.setVersion(0);
        // MyBatis-Plus 乐观锁：不存在的行影响 0 行 → 收敛为 4003
        assertThatThrownBy(() -> repository.updateDraftMeta(ghost, "tester"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_CONCURRENT_MODIFY));
    }
}
