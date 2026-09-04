package com.wymix.initializer.app.admin.controller;

import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import com.wymix.initializer.app.admin.security.AdminAuthFilter;
import com.wymix.initializer.app.admin.service.ConfigService;
import com.wymix.initializer.app.admin.sync.CatalogSyncService;
import com.wymix.initializer.app.admin.service.ConfigService.DraftView;
import com.wymix.initializer.app.admin.service.ConfigService.PublishResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 管理端配置接口（M3.1/M3.2）：{@code /api/admin/**}，均需 {@code X-Admin-Token} 鉴权
 * （{@link AdminAuthFilter} 前置拦截）。
 *
 * <p>草稿-生效分离：GET/PUT 只操作草稿，POST /publish 才触发校验 + 快照切换；
 * M3.2 支持 POST /releases/{id}/rollback 回滚到历史发布（重新走完整校验通道）。
 * 错误语义：草稿缺失 4001、发布/回滚校验失败 4002、乐观锁冲突 4003（全局异常处理器统一映射）。
 *
 * <p>线程安全性：无状态单例，线程安全。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminApiController {

    /** 乐观锁请求头（HTTP If-Match 语义，值为 GET 草稿返回的 version）。 */
    public static final String IF_MATCH_HEADER = "If-Match";

    private final ConfigService configService;
    private final CatalogSyncService syncService;

    /**
     * @param configService 配置应用服务
     * @param syncService   制品库同步应用服务（M3.3）
     */
    public AdminApiController(ConfigService configService, CatalogSyncService syncService) {
        this.configService = configService;
        this.syncService = syncService;
    }

    /**
     * 读取当前草稿。
     *
     * @param kind 配置种类（metadata / starter / conflict-rule / template）
     * @return 草稿视图（未保存过时 contentYaml 为 null、version 为 null）
     */
    @GetMapping("/config/{kind}")
    public DraftView getDraft(@PathVariable("kind") String kind) {
        return configService.getDraft(ConfigKind.parse(kind));
    }

    /**
     * 更新草稿（乐观锁）。
     *
     * @param kind    配置种类
     * @param ifMatch 期望版本（首次保存可省略）
     * @param request 草稿保存请求体
     * @return 204
     */
    @PutMapping("/config/{kind}")
    public ResponseEntity<Void> putDraft(@PathVariable("kind") String kind,
                                         @RequestHeader(value = IF_MATCH_HEADER, required = false) Integer ifMatch,
                                         @Valid @RequestBody SaveDraftRequest request) {
        configService.saveDraft(ConfigKind.parse(kind), request.contentYaml(), ifMatch, request.operator());
        return ResponseEntity.noContent().build();
    }

    /**
     * 发布：全量校验 → 快照构建 → 原子切换 → 发布历史落库。
     *
     * @param request 发布请求（操作人必填；备注可空）
     * @return 发布结果
     */
    @PostMapping("/config/publish")
    public PublishResult publish(@Valid @RequestBody PublishRequest request) {
        return configService.publish(request.operator(), request.remark());
    }

    /**
     * 回滚到指定历史发布（M3.2）：目标版本全文重新走完整发布校验，通过后原子切换。
     *
     * @param releaseId 发布记录 ID
     * @param request   回滚请求（操作人必填）
     * @return 回滚结果（新发布记录 ID + 快照概况）
     */
    @PostMapping("/releases/{releaseId}/rollback")
    public PublishResult rollback(@PathVariable("releaseId") long releaseId,
                                  @Valid @RequestBody PublishRequest request) {
        return configService.rollback(releaseId, request.operator());
    }

    /**
     * 手动触发制品库同步（M3.3）：拉取 → 合并（同名手工优先）→ 同步层草稿重建 →
     * 快照增量重建热生效。
     *
     * @param request 同步请求（操作人必填）
     * @return 同步结果概况（各通道采纳/覆盖计数）
     */
    @PostMapping("/sync")
    public CatalogSyncService.SyncOutcome sync(@Valid @RequestBody SyncRequest request) {
        return syncService.syncAll(request.operator());
    }

    /** @return 发布历史（最新在前） */
    @GetMapping("/releases")
    public List<ReleaseView> releases() {
        return configService.listReleases().stream().map(ReleaseView::from).toList();
    }

    /** @return 审计日志（最新在前） */
    @GetMapping("/audits")
    public List<AuditView> audits() {
        return configService.listAudits().stream().map(AuditView::from).toList();
    }

    /** 草稿保存请求体。 */
    public record SaveDraftRequest(
            @NotNull(message = "contentYaml 不能为空") String contentYaml,
            @NotNull(message = "operator 不能为空") String operator) {
    }

    /** 手动同步请求体（M3.3）。 */
    public record SyncRequest(
            @NotNull(message = "operator 不能为空") String operator) {
    }

    /** 发布请求体。 */
    public record PublishRequest(
            @NotNull(message = "operator 不能为空") String operator,
            String remark) {
    }

    /** 发布历史视图 DTO。 */
    public record ReleaseView(long id, String releasedBy, Instant releasedAt, String remark) {

        static ReleaseView from(ConfigReleaseEntity entity) {
            return new ReleaseView(entity.getId(), entity.getReleasedBy(),
                    entity.getReleasedAt(), entity.getRemark());
        }
    }

    /** 审计日志视图 DTO。 */
    public record AuditView(long id, String action, String targetKind, String operator,
                            String detail, Instant createdAt) {

        static AuditView from(com.wymix.initializer.app.admin.entity.ConfigAuditEntity entity) {
            return new AuditView(entity.getId(), entity.getAction(), entity.getTargetKind(),
                    entity.getOperator(), entity.getDetail(), entity.getCreatedAt());
        }
    }
}
