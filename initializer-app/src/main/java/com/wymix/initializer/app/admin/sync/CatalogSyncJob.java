package com.wymix.initializer.app.admin.sync;

import com.wymix.initializer.api.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 制品库定时同步任务（M3.3）：按配置化间隔触发全通道同步。
 *
 * <p>开关与间隔配置化（{@code initializer.sync.schedule.enabled} /
 * {@code initializer.sync.schedule.fixed-delay-ms}，默认关闭——避免测试与
 * 未接入制品库环境的无谓拉取）；手动触发走管理端
 * {@code POST /api/admin/sync}，两者共用 {@link CatalogSyncService#syncAll}。
 *
 * <p>失败语义：同步失败仅告警 + 审计留痕，不中断调度、不影响生成服务。
 *
 * <p>线程安全性：无状态单例（调度器串行触发默认同一线程池），线程安全。
 */
@Component
@ConditionalOnProperty(prefix = "initializer.sync.schedule", name = "enabled", havingValue = "true")
public class CatalogSyncJob {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncJob.class);

    /** 定时同步默认间隔（毫秒，可由 initializer.sync.schedule.fixed-delay-ms 覆盖）。 */
    static final long DEFAULT_FIXED_DELAY_MS = 300_000L;

    private final CatalogSyncService syncService;
    private final long fixedDelayMs;

    /**
     * @param syncService   制品库同步应用服务
     * @param fixedDelayMs  同步间隔（毫秒）
     */
    public CatalogSyncJob(CatalogSyncService syncService,
                          @Value("${initializer.sync.schedule.fixed-delay-ms:" + DEFAULT_FIXED_DELAY_MS + "}") long fixedDelayMs) {
        this.syncService = syncService;
        this.fixedDelayMs = fixedDelayMs;
    }

    /** @return 同步间隔（毫秒） */
    public long fixedDelayMs() {
        return fixedDelayMs;
    }

    /**
     * 定时同步入口：失败告警不抛出（调度器要求任务方法不中断后续调度）。
     */
    @Scheduled(fixedDelayString = "${initializer.sync.schedule.fixed-delay-ms:" + DEFAULT_FIXED_DELAY_MS + "}")
    public void syncScheduled() {
        try {
            CatalogSyncService.SyncOutcome outcome = syncService.syncAll(CatalogSyncService.SYSTEM_OPERATOR);
            log.info("定时制品库同步完成: {}", outcome.summary());
        } catch (BusinessException e) {
            log.warn("定时制品库同步失败（保留上次同步结果）: {}", e.getMessage());
        }
    }
}
