package com.wymix.initializer.app.admin.sync;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.entity.ConfigDraftEntity;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.refresh.CatalogRefresher;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.core.sync.CatalogMerger;
import com.wymix.initializer.core.sync.CatalogSyncProvider;
import com.wymix.initializer.core.sync.SyncedStarter;
import com.wymix.initializer.core.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 制品库同步应用服务（M3.3）：拉取 → 归一化 → 合并 → 同步层草稿重建。
 *
 * <p>同步 = 只读贡献者（概要设计决策 3）：同步结果写入 STARTER_SYNC 草稿（多文档 yml），
 * 不触碰手工 STARTER/METADATA 草稿；合并语义为同名条目手工配置优先（合并发生在
 * {@link #syncChannel}，同名条目不写同步层草稿）。
 *
 * <p>生效通道与手工配置一致：同步层草稿写入后，管理端发布时
 * {@code CatalogRefresher.validateAndBuild} 三通道归并（classpath 内置 → 同步层 → 手工，
 * 同名以手工为准），同步条目随快照原子切换热生效——全部清单变更共用同一条
 * 校验/快照通道，无旁路；starter 清单中未登记 metadata 的条目由发布链路自动补位
 * 为全部 Boot 版本可选（验收标准 3）。
 *
 * <p>失败语义：拉取失败（4005）不触碰快照与草稿，审计留痕（SYNC_FAILED），
 * 生成服务无感。
 *
 * <p>线程安全性：无状态单例（并发同步由草稿行级覆盖收敛，后写者胜），线程安全。
 */
@Service
public class CatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncService.class);

    /** 审计动作：同步成功。 */
    public static final String ACTION_SYNC = "SYNC";
    /** 审计动作：同步失败。 */
    public static final String ACTION_SYNC_FAILED = "SYNC_FAILED";

    /** 系统操作人（定时同步无人工参与）。 */
    public static final String SYSTEM_OPERATOR = "system";

    private final List<CatalogSyncProvider> providers;
    private final ConfigRepository repository;
    private final CatalogRefresher refresher;

    /**
     * @param providers  同步通道清单（Bean 注入，按装配顺序；空清单时同步禁用）
     * @param repository 配置存储仓库
     * @param refresher  清单校验器（宽松 ID 解析与发布链路同源，保证保留集口径一致）
     */
    public CatalogSyncService(List<CatalogSyncProvider> providers, ConfigRepository repository,
                              CatalogRefresher refresher) {
        this.providers = providers.stream().filter(CatalogSyncProvider::enabled).toList();
        this.repository = repository;
        this.refresher = refresher;
    }

    /**
     * 执行全通道同步：逐 provider 拉取 → 合并 → 同步层草稿重建。
     *
     * <p>草稿重建语义：同步层草稿 = 本轮全部通道采纳条目的全量重写（制品库为权威源），
     * 手工 STARTER/METADATA 草稿零改动；生效走发布链路（发布后快照原子切换热生效）。
     *
     * @param operator 触发人（定时任务传 system，手动触发传管理员）
     * @return 同步结果概况（各通道采纳/覆盖计数）
     * @throws BusinessException 全部通道拉取失败时抛 4005（保留上次同步草稿）；单通道失败审计后跳过
     */
    public SyncOutcome syncAll(String operator) {
        if (providers.isEmpty()) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                    "制品库同步失败: 未装配任何同步通道（CatalogSyncProvider）");
        }
        List<ChannelOutcome> channels = new ArrayList<>();
        boolean anySuccess = false;
        BusinessException lastFailure = null;
        for (CatalogSyncProvider provider : providers) {
            try {
                channels.add(syncChannel(provider));
                anySuccess = true;
            } catch (BusinessException e) {
                // 单通道失败不阻断其他通道；全部失败才向上抛 4005
                lastFailure = e;
                channels.add(new ChannelOutcome(provider.providerId(), 0, 0, e.getMessage()));
                repository.insertAudit(ACTION_SYNC_FAILED, ConfigKind.STARTER_SYNC,
                        operator, provider.providerId() + " 拉取失败: " + e.getMessage());
                log.warn("制品库同步通道失败: provider={}, error={}", provider.providerId(), e.getMessage());
            }
        }
        if (!anySuccess) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                    "制品库同步失败: 全部通道拉取失败（保留上次同步结果）", lastFailure);
        }
        SyncOutcome outcome = new SyncOutcome(channels);
        repository.insertAudit(ACTION_SYNC, ConfigKind.STARTER_SYNC, operator, outcome.summary());
        log.info("制品库同步完成: {}", outcome.summary());
        return outcome;
    }

    /**
     * 同步单个通道：拉取 → 合并（同名手工优先）→ 同步层草稿重建。
     *
     * <p>保留集 = 手工 STARTER 草稿条目 ID + 当前同步层草稿条目 ID（本轮重建的全量输入，
     * 不含同步层会导致草稿残留旧条目）；同名条目进覆盖清单仅审计留痕。
     *
     * @param provider 同步通道
     * @return 通道结果
     * @throws BusinessException 拉取失败（4005）
     */
    private ChannelOutcome syncChannel(CatalogSyncProvider provider) {
        SyncResult fetched = provider.fetch();

        Set<String> reservedIds = new LinkedHashSet<>();
        ConfigDraftEntity starterDraft = repository.findDraft(ConfigKind.STARTER);
        if (starterDraft != null && starterDraft.getContentYaml() != null) {
            reservedIds.addAll(refresher.parseStarterIds(starterDraft.getContentYaml()));
        }

        CatalogMerger.MergeOutcome merged = CatalogMerger.merge(reservedIds, fetched.starters());

        // 同步层草稿全量重建：本轮采纳条目多文档 yml（制品库为权威源，下架条目自然移除）
        String syncYaml = merged.adopted().isEmpty()
                ? "" : merged.adopted().stream().map(SyncedStarter::toYaml).collect(Collectors.joining("\n"));
        upsertSyncDraft(syncYaml, provider.providerId());
        return new ChannelOutcome(provider.providerId(), merged.adopted().size(),
                merged.overridden().size(), null);
    }

    /**
     * 同步层草稿写入：存在则覆盖，不存在则插入。
     *
     * @param yaml     同步层草稿全文（多文档，空串表示清空）
     * @param operator 操作人（provider 标识）
     */
    private void upsertSyncDraft(String yaml, String operator) {
        ConfigDraftEntity entity = repository.findDraft(ConfigKind.STARTER_SYNC);
        if (entity == null) {
            repository.insertDraft(ConfigKind.STARTER_SYNC, yaml, operator);
        } else {
            repository.updateDraft(entity, yaml);
        }
    }

    /**
     * 单通道同步结果。
     *
     * @param providerId 来源标识
     * @param adopted    采纳条目数（进入同步层草稿，发布后可见）
     * @param overridden 被手工配置覆盖的条目数（同名手工优先，仅审计留痕）
     * @param error      失败原因（成功为 null）
     */
    public record ChannelOutcome(String providerId, int adopted, int overridden, String error) {
    }

    /**
     * 全量同步结果。
     *
     * @param channels 各通道结果
     */
    public record SyncOutcome(List<ChannelOutcome> channels) {

        public SyncOutcome {
            channels = channels == null ? List.of() : List.copyOf(channels);
        }

        /** @return 审计明细一句话摘要 */
        public String summary() {
            return channels.stream()
                    .map(c -> c.error() != null
                            ? c.providerId() + " 失败(" + c.error() + ")"
                            : c.providerId() + " 采纳 " + c.adopted() + " / 覆盖 " + c.overridden())
                    .collect(Collectors.joining("; "));
        }
    }
}
