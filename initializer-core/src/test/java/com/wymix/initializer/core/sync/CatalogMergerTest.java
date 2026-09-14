package com.wymix.initializer.core.sync;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CatalogMerger} 单元测试（M3.3 三通道合并）：
 * 保留集命中即覆盖留痕，未命中即采纳；空输入与空保留集两个边界。
 */
class CatalogMergerTest {

    private static SyncedStarter starter(String id) {
        return new SyncedStarter(id, "middleware", null, "com.company", id, "1.0.0");
    }

    @Test
    void reservedId_shouldBeOverriddenNotAdopted() {
        CatalogMerger.MergeOutcome outcome = CatalogMerger.merge(
                Set.of("manual-starter"), List.of(starter("manual-starter"), starter("synced-starter")));
        assertThat(outcome.adopted()).extracting(SyncedStarter::id).containsExactly("synced-starter");
        assertThat(outcome.overridden()).extracting(SyncedStarter::id).containsExactly("manual-starter");
    }

    @Test
    void emptyReservedSet_shouldAdoptAll() {
        CatalogMerger.MergeOutcome outcome = CatalogMerger.merge(
                Set.of(), List.of(starter("a"), starter("b")));
        assertThat(outcome.adopted()).hasSize(2);
        assertThat(outcome.overridden()).isEmpty();
    }

    @Test
    void emptyFetched_shouldAdoptNothing() {
        CatalogMerger.MergeOutcome outcome = CatalogMerger.merge(Set.of("a"), List.of());
        assertThat(outcome.adopted()).isEmpty();
        assertThat(outcome.overridden()).isEmpty();
    }

    @Test
    void mergeOutcome_nullCollections_shouldNormalizeToEmpty() {
        // 记录构造的 null 归一化（同步通道空结果场景）
        CatalogMerger.MergeOutcome outcome = new CatalogMerger.MergeOutcome(null, null);
        assertThat(outcome.adopted()).isEmpty();
        assertThat(outcome.overridden()).isEmpty();
    }

    @Test
    void mergeOutcome_shouldBeImmutableCopy() {
        // 构造期拷贝：外部列表后续变更不得影响结果（快照不变性要求）
        java.util.List<SyncedStarter> mutable = new java.util.ArrayList<>(List.of(starter("a")));
        CatalogMerger.MergeOutcome outcome = CatalogMerger.merge(Set.of(), mutable);
        mutable.clear();
        assertThat(outcome.adopted()).hasSize(1);
    }
}
