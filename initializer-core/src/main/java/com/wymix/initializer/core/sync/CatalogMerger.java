package com.wymix.initializer.core.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 三通道合并策略（M3.3，概要设计 §6.3）：classpath 内置 → 手工配置 → 制品库同步，
 * 同名条目以手工配置为准（PRD 已确认双通道并存语义）。
 *
 * <p>合并发生在快照构建期：同步条目不直接改手工配置，仅当 ID 未被
 * 「保留集」（手工草稿 + classpath 内置清单）占用时才被采纳进入同步层；
 * 被保留集占用的条目记入 {@link MergeOutcome#overridden()} 供审计展示。
 *
 * <p>线程安全性：无状态纯函数，线程安全。
 */
public final class CatalogMerger {

    private CatalogMerger() {
    }

    /**
     * 按合并策略过滤同步条目。
     *
     * @param reservedIds 保留集 ID（手工草稿条目 + 内置清单条目），同步条目与之同名即被覆盖跳过
     * @param fetched     本次拉取的制品库条目（当前全量）
     * @return 合并结果：采纳清单（进入同步层）+ 被覆盖清单（手工优先语义留痕）
     */
    public static MergeOutcome merge(Collection<String> reservedIds, List<SyncedStarter> fetched) {
        List<SyncedStarter> adopted = new ArrayList<>();
        List<SyncedStarter> overridden = new ArrayList<>();
        for (SyncedStarter starter : fetched) {
            (reservedIds.contains(starter.id()) ? overridden : adopted).add(starter);
        }
        return new MergeOutcome(List.copyOf(adopted), List.copyOf(overridden));
    }

    /**
     * 合并结果载体。
     *
     * @param adopted    采纳的同步条目（写入同步层草稿并进入快照）
     * @param overridden 被保留集覆盖的同步条目（同名以手工配置为准，仅审计留痕）
     */
    public record MergeOutcome(List<SyncedStarter> adopted, List<SyncedStarter> overridden) {

        public MergeOutcome {
            adopted = adopted == null ? List.of() : List.copyOf(adopted);
            overridden = overridden == null ? List.of() : List.copyOf(overridden);
        }
    }
}
