package com.wymix.initializer.app.admin.repository;

import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.entity.ConfigAuditEntity;
import com.wymix.initializer.app.admin.entity.ConfigDraftEntity;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ConfigRepository} 集成测试（M3.1，H2 内存库）：Mapper 层 CRUD + 乐观锁语义。
 */
@SpringBootTest(classes = InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-repo-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token"
})
class ConfigRepositoryTest {

    @Autowired
    private ConfigRepository repository;

    @Test
    void insertAndFindDraft_roundTrip() {
        ConfigKind kind = ConfigKind.CONFLICT_RULE; // data.sql 预置了 METADATA/STARTER 占位行，测试用未预置 kind
        repository.insertDraft(kind, "a: 1", "tester");

        ConfigDraftEntity loaded = repository.findDraft(kind);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getContentYaml()).isEqualTo("a: 1");
        assertThat(loaded.getUpdatedBy()).isEqualTo("tester");
    }

    @Test
    void updateDraft_withStaleVersion_throws4003() {
        ConfigKind kind = ConfigKind.TEMPLATE;
        repository.insertDraft(kind, "v: 1", "tester");
        ConfigDraftEntity first = repository.findDraft(kind);
        int firstVersion = first.getVersion();

        // 第一次更新成功（携带当前版本）
        repository.updateDraft(first, "v: 2");
        assertThat(repository.findDraft(kind).getContentYaml()).isEqualTo("v: 2");
        assertThat(repository.findDraft(kind).getVersion()).isGreaterThan(firstVersion);

        // 模拟另一会话持有陈旧实体（旧 version）交叉修改 → 乐观锁拦截 → 0 行 → 4003
        ConfigDraftEntity stale = repository.findDraft(kind);
        repository.updateDraft(repository.findDraft(kind), "v: 3"); // 当前最新版本先推进
        stale.setVersion(firstVersion); // 回退为陈旧版本
        assertThatThrownBy(() -> repository.updateDraft(stale, "v: 4"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_CONCURRENT_MODIFY));
    }

    @Test
    void insertRelease_andListReleases() {
        repository.insertRelease("m: 1", "s: 1", null, "tester", "首次发布");
        List<ConfigReleaseEntity> releases = repository.listReleases();
        assertThat(releases).isNotEmpty();
        assertThat(releases.get(0).getMetadataYaml()).isEqualTo("m: 1");
        assertThat(releases.get(0).getReleasedBy()).isEqualTo("tester");
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
}
