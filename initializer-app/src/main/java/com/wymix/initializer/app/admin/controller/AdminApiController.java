package com.wymix.initializer.app.admin.controller;

import com.wymix.initializer.app.admin.dto.CatalogDtos.ConflictRuleUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.ConflictRuleView;
import com.wymix.initializer.app.admin.dto.CatalogDtos.DraftSaveResult;
import com.wymix.initializer.app.admin.dto.CatalogDtos.MetadataUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.MetadataView;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterCatalogUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterCatalogView;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateCatalogUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateDetail;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateListView;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateUpsertRequest;
import com.wymix.initializer.app.admin.dto.ConfigArchiveDtos.ImportView;
import com.wymix.initializer.app.admin.dto.LegacyMigrationDtos.MigrationProbeView;
import com.wymix.initializer.app.admin.dto.LegacyMigrationDtos.MigrationRunView;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.entity.ConfigReleaseEntity;
import com.wymix.initializer.app.admin.repository.LegacyYamlMigration;
import com.wymix.initializer.app.admin.security.AdminAuthFilter;
import com.wymix.initializer.app.admin.service.ConfigImportExportService;
import com.wymix.initializer.app.admin.service.ConfigService;
import com.wymix.initializer.app.admin.service.StructuredDraftService;
import com.wymix.initializer.app.admin.sync.CatalogSyncService;
import com.wymix.initializer.app.admin.service.ConfigService.DraftView;
import com.wymix.initializer.app.admin.service.ConfigService.PublishResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * 管理端配置接口（M3.1/M3.2 → 结构化改造 T3）：{@code /api/admin/**}，
 * 均需 {@code X-Admin-Token} 鉴权（{@link AdminAuthFilter} 前置拦截）。
 *
 * <p><b>两套契约并存（T3）</b>：
 * <ul>
 *   <li><b>结构化契约（推荐）</b>：{@code /api/admin/{metadata,starters,conflict-rules,templates}}
 *       系列，按模块整体读写结构化 DTO，直接对应 {@code cfg_*} 关系表列语义；
 *       模板列表只回 name/更新时间，{@code content} 走 {@code /templates/{name}} 按需单取。</li>
 *   <li><b>YAML 契约（T1 过渡，保留）</b>：{@code /api/admin/config/{kind}} + {@code PUT}
 *       仍以 YAML 全文为契约，经 {@code StructuredYamlCodec}/{@code StructuredDraftWriter}
 *       读写同一批 {@code release_id = 0} 结构行；供导入通道（T4）与旧客户端使用。</li>
 * </ul>
 * 两套契约读写同一份草稿行，权威来源唯一。
 *
 * <p>草稿-生效分离：GET/PUT 只操作草稿，POST /publish 才触发校验 + 快照切换；
 * M3.2 支持 POST /releases/{id}/rollback 回滚到历史发布（重新走完整校验通道）。
 * 错误语义：草稿缺失 4001、发布/回滚/结构校验失败 4002、乐观锁冲突 4003
 * （全局异常处理器统一映射）。
 *
 * <p>线程安全性：无状态单例，线程安全。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminApiController {

    /** 乐观锁请求头（HTTP If-Match 语义，值为 GET 草稿返回的 version）。 */
    public static final String IF_MATCH_HEADER = "If-Match";

    /** 导入归档的表单字段名（与前端约定，multipart/file）。 */
    public static final String IMPORT_FILE_FIELD = "file";

    private final ConfigService configService;
    private final CatalogSyncService syncService;
    private final StructuredDraftService structuredDraftService;
    private final ConfigImportExportService importExportService;
    private final LegacyYamlMigration legacyYamlMigration;

    /**
     * @param configService          配置应用服务（YAML 契约 + 发布/回滚/历史/审计）
     * @param syncService            制品库同步应用服务（M3.3）
     * @param structuredDraftService 结构化草稿服务（T3 分模块表单化读写）
     * @param importExportService    配置导入导出服务（T4：ZIP 下载 / 上传解析入库）
     * @param legacyYamlMigration    存量 YAML 库内直迁服务（T8：旧 config_draft 表 → 结构行）
     */
    public AdminApiController(ConfigService configService, CatalogSyncService syncService,
                              StructuredDraftService structuredDraftService,
                              ConfigImportExportService importExportService,
                              LegacyYamlMigration legacyYamlMigration) {
        this.configService = configService;
        this.syncService = syncService;
        this.structuredDraftService = structuredDraftService;
        this.importExportService = importExportService;
        this.legacyYamlMigration = legacyYamlMigration;
    }

    // ==================== 结构化契约（T3，推荐） ====================

    /**
     * 读取元数据模块草稿（结构化）。
     *
     * @return 元数据视图（Java/Boot 版本、兼容矩阵、starter 白名单、框架父 POM）
     */
    @GetMapping("/metadata")
    public MetadataView getMetadata() {
        return structuredDraftService.getMetadata();
    }

    /**
     * 整体保存元数据模块草稿（结构化）。
     *
     * @param ifMatch 期望版本（首次保存可省略）
     * @param request 元数据整体提交请求
     * @return 保存结果（写入行数 + 新版本 + 规模摘要）
     */
    @PutMapping("/metadata")
    public DraftSaveResult putMetadata(
            @RequestHeader(value = IF_MATCH_HEADER, required = false) Integer ifMatch,
            @Valid @RequestBody MetadataUpsertRequest request) {
        return structuredDraftService.saveMetadata(request, ifMatch);
    }

    /**
     * 读取手工 starter 清单草稿（结构化）。
     *
     * @return starter 清单视图
     */
    @GetMapping("/starters")
    public StarterCatalogView getStarters() {
        return structuredDraftService.getStarters();
    }

    /**
     * 整体保存手工 starter 清单草稿（结构化）。
     *
     * @param ifMatch 期望版本
     * @param request starter 清单整体提交请求
     * @return 保存结果
     */
    @PutMapping("/starters")
    public DraftSaveResult putStarters(
            @RequestHeader(value = IF_MATCH_HEADER, required = false) Integer ifMatch,
            @Valid @RequestBody StarterCatalogUpsertRequest request) {
        return structuredDraftService.saveStarters(request, ifMatch);
    }

    /**
     * 读取冲突规则草稿（结构化）。
     *
     * @return 规则视图
     */
    @GetMapping("/conflict-rules")
    public ConflictRuleView getConflictRules() {
        return structuredDraftService.getConflictRules();
    }

    /**
     * 整体保存冲突规则草稿（结构化）。
     *
     * @param ifMatch 期望版本
     * @param request 冲突规则整体提交请求
     * @return 保存结果
     */
    @PutMapping("/conflict-rules")
    public DraftSaveResult putConflictRules(
            @RequestHeader(value = IF_MATCH_HEADER, required = false) Integer ifMatch,
            @Valid @RequestBody ConflictRuleUpsertRequest request) {
        return structuredDraftService.saveConflictRules(request, ifMatch);
    }

    /**
     * 读取模板列表（结构化，**不含 content CLOB**）。
     *
     * @return 模板摘要清单（name + 更新时间 + 顺序）
     */
    @GetMapping("/templates")
    public TemplateListView listTemplates() {
        return structuredDraftService.listTemplates();
    }

    /**
     * 读取单个模板详情（含 content 全文，按需单独取）。
     *
     * @param name 模板名（相对 templates/ 路径）
     * @return 模板详情
     */
    @GetMapping("/templates/{name}")
    public TemplateDetail getTemplate(@PathVariable("name") String name) {
        return structuredDraftService.getTemplate(name);
    }

    /**
     * 新增或更新单个模板（按 name 幂等 upsert，保留其余模板）。
     *
     * @param ifMatch 期望版本
     * @param request 单模板请求（name + content + operator）
     * @return 保存结果
     */
    @PutMapping("/templates/{name}")
    public DraftSaveResult putTemplate(
            @PathVariable("name") String name,
            @RequestHeader(value = IF_MATCH_HEADER, required = false) Integer ifMatch,
            @Valid @RequestBody TemplateUpsertRequest request) {
        TemplateUpsertRequest normalized = new TemplateUpsertRequest(
                name, request.content(), request.operator(), request.sort());
        return structuredDraftService.saveTemplate(normalized, ifMatch);
    }

    /**
     * 整体保存模板集草稿（结构化，全量替换）。
     *
     * @param ifMatch 期望版本
     * @param request 模板集合整体提交请求
     * @return 保存结果
     */
    @PutMapping("/templates")
    public DraftSaveResult putTemplates(
            @RequestHeader(value = IF_MATCH_HEADER, required = false) Integer ifMatch,
            @Valid @RequestBody TemplateCatalogUpsertRequest request) {
        return structuredDraftService.saveTemplates(request, ifMatch);
    }

    /**
     * 删除单个模板（按 name，保留其余模板）。
     *
     * @param name     模板名
     * @param ifMatch  期望版本
     * @param operator 操作人
     * @return 保存结果
     */
    @DeleteMapping("/templates/{name}")
    public DraftSaveResult deleteTemplate(
            @PathVariable("name") String name,
            @RequestHeader(value = IF_MATCH_HEADER, required = false) Integer ifMatch,
            @RequestParam("operator") String operator) {
        return structuredDraftService.deleteTemplate(name, operator, ifMatch);
    }

    // ==================== 导入导出（T4） ====================

    /**
     * 导出当前草稿为 ZIP 归档（数据库 → YAML 文件下载）。
     *
     * <p>归档目录与外部清单目录一致：{@code metadata.yml} + {@code starters/*.yml} +
     * {@code conflict-rules.yml} + {@code templates/**}，包含 {@code hidden} 列语义，
     * 「导出 → 再导入」幂等。
     *
     * @return ZIP 下载响应（附件文件名 {@code initializer-config.zip}）
     */
    @GetMapping("/config/export")
    public ResponseEntity<Resource> exportConfig() {
        ConfigImportExportService.ExportResult result = importExportService.export("admin");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.fileName() + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(new ByteArrayResource(result.content()));
    }

    /**
     * 从 ZIP 归档导入配置到草稿（YAML 文件 → 数据库，整体替换且失败整体回滚）。
     *
     * <p>导入只写草稿（{@code release_id = 0}），<b>不发布、不切换快照</b>；
     * 归档内的路径穿越、条目数、单文件与解压总量、UTF-8 合法性均在解析前拦截。
     *
     * @param file     上传的 ZIP 归档（multipart 字段名 {@code file}）
     * @param ifMatch  可选整体乐观锁期望版本（任一 kind 不匹配即拒）
     * @param operator 操作人（查询参数，必填）
     * @return 导入结果（各 kind 写入行数 + 新版本 + 规模摘要）
     * @throws IOException 读取上传流失败
     */
    @PostMapping(value = "/config/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportView importConfig(@RequestPart(IMPORT_FILE_FIELD) MultipartFile file,
                                   @RequestHeader(value = IF_MATCH_HEADER, required = false) Integer ifMatch,
                                   @RequestParam("operator") String operator) throws IOException {
        ConfigImportExportService.ImportResult result;
        try (var in = file.getInputStream()) {
            result = importExportService.importArchive(in, operator, ifMatch);
        }
        return ImportView.from(result);
    }

    // ==================== 存量迁移（T8） ====================

    /**
     * 存量 YAML 迁移预检：判断旧 {@code config_draft} 表是否存在、有多少有效行、是否需要迁移。
     *
     * <p>只读探测，不写库、不改草稿；供运维在升级窗口内「先看一眼」再决定是否执行直迁。
     *
     * @return 预检视图（旧表名 / 是否存在 / 各 kind 行数 / 是否需迁移 / 提示）
     */
    @GetMapping("/config/migrate-legacy")
    public MigrationProbeView probeLegacyMigration() {
        return MigrationProbeView.from(legacyYamlMigration.probe());
    }

    /**
     * 执行存量 YAML 库内直迁：旧 {@code config_draft} 四类 YAML → 结构化草稿行
     * （{@code release_id = 0}）。
     *
     * <p><b>只写草稿，不发布、不切换快照</b>；整体在同一事务内执行，
     * 任一 kind 失败即回滚，草稿保持迁移前状态。执行后仍需人工核对并
     * {@code POST /config/publish} 发布一版作为回滚基线
     * （升级前的历史发布仅有头信息，不可作为回滚点）。
     *
     * <p>与归档通道（{@code POST /config/import}）的区别：本端点直接读旧库表，
     * 无需先用旧版 jar 导出 YAML 文件，适合可停机维护的升级窗口。
     *
     * @param operator 操作人（查询参数，必填；用于审计留痕）
     * @return 迁移结果（各 kind 写入结构行数 + 规模摘要）
     */
    @PostMapping("/config/migrate-legacy")
    public MigrationRunView runLegacyMigration(@RequestParam("operator") String operator) {
        return MigrationRunView.from(legacyYamlMigration.migrateFromLegacyDraftTable(operator));
    }

    // ==================== YAML 契约（T1 过渡，保留） ====================

    /**
     * 读取当前草稿（YAML 契约，T1 过渡）。
     *
     * @param kind 配置种类（metadata / starter / conflict-rule / template）
     * @return 草稿视图（未保存过时 contentYaml 为 null、version 为 null）
     */
    @GetMapping("/config/{kind}")
    public DraftView getDraft(@PathVariable("kind") String kind) {
        return configService.getDraft(ConfigKind.parse(kind));
    }

    /**
     * 更新草稿（乐观锁；YAML 契约，T1 过渡；T4 导入通道复用）。
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

    /** 草稿保存请求体（YAML 契约，T1 过渡）。 */
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
