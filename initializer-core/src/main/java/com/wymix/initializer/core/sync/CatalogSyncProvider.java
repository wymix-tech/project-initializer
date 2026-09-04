package com.wymix.initializer.core.sync;

/**
 * 制品库同步适配器 SPI（M3.3）：从外部制品库拉取 starter 元数据的统一抽象。
 *
 * <p>设计决策（概要设计 §6.3）：制品库接口契约未知，以本 SPI 隔离差异——首期提供
 * Mock 实现（本地 fixture）与 Remote 实现（HTTP 拉取），真实制品库对接时仅新增实现类。
 *
 * <p>实现约定：
 * <ul>
 *   <li>{@link #fetch()} 每次调用返回制品库「当前全量」starter 清单（非增量），
 *       下架条目随下一次同步从清单消失</li>
 *   <li>拉取失败抛业务异常（错误码 4005 SYNC_PROVIDER_FAILED），由调用方决定
 *       审计留痕与是否阻断（同步失败不触碰快照，生成服务不受影响）</li>
 * </ul>
 *
 * <p>线程安全性：实现类须为线程安全（单例调用）。
 */
public interface CatalogSyncProvider {

    /**
     * @return 来源标识，写入同步条目 maintainer 元信息与审计明细（如 {@code internal-repo}）
     */
    String providerId();

    /**
     * 通道是否启用（M3.3）：默认启用；依赖外部配置的实现（如 remote endpoint 未配置）
     * 覆盖本方法返回 false，由 {@code CatalogSyncService} 过滤出同步通道清单。
     *
     * @return 未配置就绪条件时返回 false（本通道不参与同步）
     */
    default boolean enabled() {
        return true;
    }

    /**
     * 拉取制品库 starter 元数据（当前全量）。
     *
     * @return 归一化同步结果
     * @throws com.wymix.initializer.api.error.BusinessException 拉取/解析失败（4005）
     */
    SyncResult fetch();
}
