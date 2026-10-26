package com.wymix.initializer.app.admin.sync;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.dto.CatalogDtos.DependencyDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterCatalogUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterUpsertDto;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.service.StructuredDraftService;
import com.wymix.initializer.core.sync.CatalogMerger;
import com.wymix.initializer.core.sync.CatalogSyncProvider;
import com.wymix.initializer.core.sync.SyncedStarter;
import com.wymix.initializer.core.sync.SyncResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 制品库同步应用服务（M3.3）：拉取 → 归一化 → 合并 → 同步层草稿重建。
 *
 * <p>同步 = 只读贡献者（概要设计决策 3）：同步结果**直写结构化 starter 草稿行**
 * （{@code cfg_starter} 等表、{@code release_id = 0}、{@code source = 'SYNC'}，
 * T3 起不再拼 YAML），不触碰手工 STARTER/METADATA 草稿；合并语义为同名条目手工配置优先
 * （合并发生在 {@link #syncAll}，同名条目不写同步层草稿）。
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
 * <p>线程安全性：无状态单例（同实例同步串行执行，草稿提交由数据库事务与工作区锁保护），线程安全。
 */
@Service
public class CatalogSyncService {

    /** 审计动作：同步成功。 */
    public static final String ACTION_SYNC = "SYNC";
    /** 审计动作：同步失败。 */
    public static final String ACTION_SYNC_FAILED = "SYNC_FAILED";

    /** 系统操作人（定时同步无人工参与）。 */
    public static final String SYSTEM_OPERATOR = "system";

    private final List<CatalogSyncProvider> providers;
    private final ConfigRepository repository;
    /**
     * 结构化草稿服务（T3）：同步层 starter 行以结构化 DTO 直写
     * （{@code source = 'SYNC'}，不经 YAML 文本往返）。
     */
    private final StructuredDraftService structuredDraftService;

    /**
     * @param providers              同步通道清单（Bean 注入，按装配顺序；空清单时同步禁用）
     * @param repository             配置存储仓库（结构化 cfg_* 表读写）
     * @param structuredDraftService 结构化草稿服务（同步层 starter 结构化读写）
     */
    public CatalogSyncService(List<CatalogSyncProvider> providers, ConfigRepository repository,
                              StructuredDraftService structuredDraftService) {
        this.providers = providers.stream().filter(CatalogSyncProvider::enabled).toList();
        this.repository = repository;
        this.structuredDraftService = structuredDraftService;
    }

    /**
     * 执行全通道同步：逐 provider 拉取 → 合并 → 同步层草稿重建。
     *
     * <p>草稿重建语义：同步层草稿 = 本轮全部通道采纳条目的全量重写（制品库为权威源），
     * 手工 STARTER/METADATA 草稿零改动；生效走发布链路（发布后快照原子切换热生效）。
     *
     * @param operator 触发人（定时任务传 system，手动触发传管理员）
     * @return 同步结果概况（各通道采纳/覆盖计数）
     * @throws BusinessException 任一通道失败时抛 4005，整轮保留上次同步草稿
     */
    public synchronized SyncOutcome syncAll(String operator) {
        if (providers.isEmpty()) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                    "制品库同步失败: 未装配任何同步通道（CatalogSyncProvider）");
        }
        List<ChannelOutcome> channels = new ArrayList<>();
        List<SyncedStarter> combined = new ArrayList<>();
        Set<String> reserved = new LinkedHashSet<>(structuredDraftService.getStarters().starters().stream()
                .map(StarterDto::id).toList());
        Set<String> providerIds = new LinkedHashSet<>();
        Set<String> adoptedIds = new LinkedHashSet<>();
        for (CatalogSyncProvider provider : providers) {
            try {
                if (!providerIds.add(provider.providerId())) {
                    throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                            "重复同步来源: " + provider.providerId());
                }
                SyncResult fetched = provider.fetch();
                CatalogMerger.MergeOutcome merged = CatalogMerger.merge(reserved, fetched.starters());
                for (SyncedStarter starter : merged.adopted()) {
                    if (!adoptedIds.add(starter.id())) {
                        throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                                "同步来源存在重复 starter ID: " + starter.id());
                    }
                    combined.add(starter);
                }
                channels.add(new ChannelOutcome(provider.providerId(), merged.adopted().size(),
                        merged.overridden().size(), null));
            } catch (RuntimeException e) {
                repository.insertAudit(ACTION_SYNC_FAILED, ConfigKind.STARTER_SYNC, operator,
                        provider.providerId() + " 同步失败，本轮草稿未写入: " + e.getMessage());
                throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                        "制品库同步失败: " + provider.providerId() + "（整轮未写入，保留上次同步结果）", e);
            }
        }
        // All enabled channels must succeed. Merge once, then transactionally replace the SYNC layer once.
        upsertSyncDraft(combined, operator);
        SyncOutcome outcome = new SyncOutcome(channels);
        repository.insertAudit(ACTION_SYNC, ConfigKind.STARTER_SYNC, operator,
                "同步草稿已更新，需发布后生效: " + outcome.summary());
        return outcome;
    }

    /**
     * 同步层草稿写入（结构化，T3）：清空旧 {@code source = SYNC} 结构行后整体重写为
     * 本轮采纳条目，并推进 {@code config_draft_meta} 的 kind 级乐观锁与修改人。
     *
     * <p>草稿载体已是 {@code cfg_starter} 等表的结构行（T3 起不再拼 YAML），
     * {@link ConfigKind#STARTER_SYNC} 取值与审计语义不变；
     * 同名手工优先由上游 {@code CatalogMerger} 的保留集过滤保证，本方法不做二次去重。
     *
     * @param adopted  本轮采纳的同步条目（制品库权威源；空列表表示清空同步层草稿）
     * @param operator 操作人（provider 标识）
     */
    private void upsertSyncDraft(List<SyncedStarter> adopted, String operator) {
        List<StarterUpsertDto> dtos = IntStream.range(0, adopted.size())
                .mapToObj(i -> toStarterDto(adopted.get(i), i))
                .toList();
        structuredDraftService.saveSyncedStarters(new StarterCatalogUpsertRequest(dtos, operator));
    }

    /**
     * 同步条目 → 结构化 starter DTO（{@code source} 由写入通道固定为 SYNC）。
     *
     * <p>制品库只提供「ID + 分类 + 描述 + 单个 GAV 坐标」，因此坐标按单依赖项落库，
     * 联动与 requires 为空；{@code maintainer} 归一为制品库默认（{@code INTERNAL}）。
     *
     * @param synced 同步条目
     * @param sort   展示顺序
     * @return starter 结构 DTO
     */
    private static StarterUpsertDto toStarterDto(SyncedStarter synced, int sort) {
        return new StarterUpsertDto(
                synced.id(),
                synced.category() == null || synced.category().isBlank() ? "middleware" : synced.category(),
                "INTERNAL",
                synced.description(),
                false,
                false,
                null,
                List.of(new DependencyDto(synced.groupId(), synced.artifactId(), synced.version())),
                List.of(),
                List.of(),
                null,
                sort);
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
