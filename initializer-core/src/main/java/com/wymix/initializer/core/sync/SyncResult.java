package com.wymix.initializer.core.sync;

import java.util.List;

/**
 * 制品库同步结果（M3.3）：{@link CatalogSyncProvider#fetch()} 的归一化返回。
 *
 * <p>starters 语义为制品库「当前全量」清单：同步层草稿按本结果全量重建，
 * 制品库下架的条目随下一次同步发布后从可选清单消失。
 *
 * <p>线程安全性：不可变对象，线程安全。
 *
 * @param providerId 来源标识（与 {@link CatalogSyncProvider#providerId()} 一致）
 * @param starters   同步条目清单（可为空 = 制品库当前无 starter）
 */
public record SyncResult(String providerId, List<SyncedStarter> starters) {

    public SyncResult {
        starters = starters == null ? List.of() : List.copyOf(starters);
    }
}
