package com.wymix.initializer.app.admin.sync;

import com.wymix.initializer.core.sync.CatalogMerger;
import com.wymix.initializer.core.sync.SyncedStarter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CatalogMerger} 单元测试（M3.3）：同名条目以手工配置为准（验收标准 3），
 * 仅同步层存在的条目自动采纳。
 */
class CatalogMergerTest {

    private SyncedStarter starter(String id) {
        return new SyncedStarter(id, "middleware", "desc-" + id,
                "com.company", id, "1.0.0");
    }

    @Test
    void merge_adoptsOnlyNewEntries() {
        CatalogMerger.MergeOutcome outcome = CatalogMerger.merge(
                Set.of("web", "xxx-redis-spring-boot-starter"),
                List.of(starter("xxx-oss-spring-boot-starter"), starter("xxx-sms-spring-boot-starter")));
        assertThat(outcome.adopted()).extracting(SyncedStarter::id)
                .containsExactly("xxx-oss-spring-boot-starter", "xxx-sms-spring-boot-starter");
        assertThat(outcome.overridden()).isEmpty();
    }

    @Test
    void merge_reservedIdGoesToOverridden_manualWins() {
        CatalogMerger.MergeOutcome outcome = CatalogMerger.merge(
                Set.of("xxx-redis-spring-boot-starter"),
                List.of(starter("xxx-redis-spring-boot-starter"), starter("xxx-oss-spring-boot-starter")));
        // 同名条目手工优先：进覆盖清单留痕，不进采纳清单
        assertThat(outcome.adopted()).extracting(SyncedStarter::id).containsExactly("xxx-oss-spring-boot-starter");
        assertThat(outcome.overridden()).extracting(SyncedStarter::id)
                .containsExactly("xxx-redis-spring-boot-starter");
    }

    @Test
    void merge_emptyFetched_meansEmptyOutcome() {
        CatalogMerger.MergeOutcome outcome = CatalogMerger.merge(Set.of(), List.of());
        assertThat(outcome.adopted()).isEmpty();
        assertThat(outcome.overridden()).isEmpty();
    }
}
