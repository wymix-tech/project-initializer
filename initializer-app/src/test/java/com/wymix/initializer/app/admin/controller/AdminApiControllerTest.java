package com.wymix.initializer.app.admin.controller;

import com.wymix.initializer.app.admin.security.AdminAuthFilter;
import com.wymix.initializer.app.admin.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdminApiController} MockMvc 切片测试：HTTP 契约、错误码映射、If-Match 乐观锁头。
 */
class AdminApiControllerTest {

    private static final String VALID_METADATA = """
            javaVersions:
              - id: "17"
                label: "Java 17"
                recommendedBootVersion: "3.2.5"
            bootVersions:
              - id: "3.2.5"
                label: "Spring Boot 3.2.5"
                official: true
                supportedJavaVersions: ["17"]
                starters: ["web"]
            starters:
              web:
                label: "Spring Web"
                dependency:
                  groupId: "org.springframework.boot"
                  artifactId: "spring-boot-starter-web"
            """;

    private MockMvc mockMvc;

    /**
     * 构造被测结构化草稿服务（协作者 mock）。
     *
     * @return 仅依赖 mock 仓库的结构化草稿服务
     */
    private static com.wymix.initializer.app.admin.service.StructuredDraftService newStructuredService() {
        com.wymix.initializer.app.admin.repository.ConfigRepository repository =
                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.repository.ConfigRepository.class);
        // 草稿读取默认返回空快照（0 行），列表返回空清单
        org.mockito.Mockito.when(repository.readDraftScope(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.wymix.initializer.app.admin.repository.StructuredSnapshot.empty(0L));
        org.mockito.Mockito.when(repository.listDraftTemplateSummaries())
                .thenReturn(java.util.List.of());
        com.wymix.initializer.app.admin.entity.ConfigDraftMetaEntity meta =
                new com.wymix.initializer.app.admin.entity.ConfigDraftMetaEntity();
        meta.setKind("CONFLICT_RULE");
        meta.setVersion(0);
        org.mockito.Mockito.when(repository.findDraftMeta(org.mockito.ArgumentMatchers.any()))
                .thenReturn(meta);
        return new com.wymix.initializer.app.admin.service.StructuredDraftService(repository);
    }

    /**
     * 构造被测 ConfigService（全部协作者 mock）。
     *
     * <p>T2 结构化改造后 ConfigService 的「清单校验器」协作者换成了
     * 「结构化目录装配器」（发布/回滚改为表行直装），本方法集中构造，
     * 避免各用例重复列表导致新增依赖时多处遗漏。
     *
     * @return 全部协作者均为 mock 的 ConfigService
     */
    private static ConfigService newConfigService() {
        return new ConfigService(
                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.repository.ConfigRepository.class),
                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.refresh.StructuredCatalogAssembler.class),
                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder.class),
                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.repository.StructuredYamlCodec.class),
                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.repository.StructuredDraftWriter.class));
    }


    /**
     * 构造被测控制器（全部协作者 mock，导入导出服务提供 T4 端点的桩）。
     *
     * @return 可直接挂到 MockMvc 的控制器实例
     */
    private static AdminApiController newController() {
        return new AdminApiController(newConfigService(),
                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.sync.CatalogSyncService.class),
                newStructuredService(),
                newImportExportService(),
                newLegacyMigration());
    }

    /**
     * 构造存量迁移服务（T8 端点切片用，协作者 mock）。
     *
     * @return 全协作者为 mock 的存量 YAML 迁移服务
     */
    private static com.wymix.initializer.app.admin.repository.LegacyYamlMigration newLegacyMigration() {
        return org.mockito.Mockito.mock(com.wymix.initializer.app.admin.repository.LegacyYamlMigration.class);
    }

    /**
     * 构造导入导出服务（mock 仓库 + 真实编解码器，T4 端点切片用）。
     *
     * @return 全协作者为 mock/无状态组件的导入导出服务
     */
    private static com.wymix.initializer.app.admin.service.ConfigImportExportService newImportExportService() {
        com.wymix.initializer.app.admin.repository.ConfigRepository repository =
                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.repository.ConfigRepository.class);
        // 草稿作用域读取默认返回空快照（切片测试只验证 HTTP 契约，不关心内容）
        org.mockito.Mockito.when(repository.readScope(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(com.wymix.initializer.app.admin.repository.StructuredSnapshot.empty(0L));
        return new com.wymix.initializer.app.admin.service.ConfigImportExportService(
                repository,
                new com.wymix.initializer.app.admin.repository.ConfigArchiveCodec(),
                new com.wymix.initializer.app.admin.repository.StructuredSnapshotCodec(),
                new com.wymix.initializer.app.admin.repository.StructuredYamlCodec());
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(newController())
                .build();
    }

    @Test
    void putDraft_missingBody_returns400() throws Exception {
        mockMvc.perform(put("/api/admin/config/metadata")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"tester\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putDraft_missingOperator_returns400() throws Exception {
        mockMvc.perform(put("/api/admin/config/metadata")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentYaml\":\"a: 1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publish_missingOperator_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/config/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void releases_returnsEmptyListForMockedRepo() throws Exception {
        mockMvc.perform(get("/api/admin/releases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void audits_returnsEmptyListForMockedRepo() throws Exception {
        mockMvc.perform(get("/api/admin/audits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void ifMatchHeader_constantMatchesHttpSemantics() {
        // 契约固定：乐观锁走 HTTP If-Match 头
        assertThat(AdminApiController.IF_MATCH_HEADER).isEqualTo("If-Match");
        assertThat(AdminAuthFilter.TOKEN_HEADER).isEqualTo("X-Admin-Token");
    }

    @Test
    void structuredEndpoints_requireOperatorAndValidateBody() throws Exception {
        // 结构化契约（全局异常处理器映射）：operator 缺省 → 422/4002
        mockMvc = MockMvcBuilders.standaloneSetup(
                        newController())
                .setControllerAdvice(new com.wymix.initializer.app.exception.GlobalExceptionHandler())
                .build();
        mockMvc.perform(put("/api/admin/metadata")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"javaVersions\":[],\"bootVersions\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(4002));

        mockMvc.perform(put("/api/admin/starters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"starters\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(4002));

        mockMvc.perform(put("/api/admin/conflict-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(4002));

        // 单模板 upsert：name 由路径给出、content 缺省 → 4002
        mockMvc.perform(put("/api/admin/templates/foo.ftl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"tester\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(4002));
    }

    @Test
    void structuredEndpoints_emptyListsAndListQuery() throws Exception {
        // 空清单合法（草稿宽松）：mock 仓库写入 0 行，乐观锁走 data.sql 预置版本
        mockMvc = MockMvcBuilders.standaloneSetup(
                        newController())
                .setControllerAdvice(new com.wymix.initializer.app.exception.GlobalExceptionHandler())
                .build();
        mockMvc.perform(put("/api/admin/conflict-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\":[],\"operator\":\"tester\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("conflict-rule"))
                .andExpect(jsonPath("$.rows").value(0));
        mockMvc.perform(get("/api/admin/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates.length()").value(0));
    }

    @Test
    void importExportEndpoints_requireFileAndOperator() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(newController())
                .setControllerAdvice(new com.wymix.initializer.app.exception.GlobalExceptionHandler())
                .build();

        // 缺 multipart 文件 → 400（MissingServletRequestPart 由 spring 映射为 400）
        mockMvc.perform(multipart("/api/admin/config/import?operator=tester"))
                .andExpect(status().isBadRequest());

        // 缺 operator 查询参数 → 400
        mockMvc.perform(multipart("/api/admin/config/import")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "config.zip", "application/zip", "x".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void probeLegacyMigration_returnsProbeView() throws Exception {
        com.wymix.initializer.app.admin.repository.LegacyYamlMigration migration = newLegacyMigration();
        org.mockito.Mockito.when(migration.probe()).thenReturn(java.util.Map.of(
                "legacyTable", "config_draft",
                "legacyTableExists", true,
                "kinds", java.util.Map.of("METADATA", 1),
                "rows", 1,
                "migrationRequired", true,
                "hint", "可执行直迁"));
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminApiController(newConfigService(),
                        org.mockito.Mockito.mock(com.wymix.initializer.app.admin.sync.CatalogSyncService.class),
                        newStructuredService(), newImportExportService(), migration))
                .build();

        mockMvc.perform(get("/api/admin/config/migrate-legacy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legacyTableExists").value(true))
                .andExpect(jsonPath("$.migrationRequired").value(true))
                .andExpect(jsonPath("$.rows").value(1));
    }

    @Test
    void runLegacyMigration_requiresOperatorAndReturnsSummary() throws Exception {
        com.wymix.initializer.app.admin.repository.LegacyYamlMigration migration = newLegacyMigration();
        org.mockito.Mockito.when(migration.migrateFromLegacyDraftTable("tester"))
                .thenReturn(java.util.Map.of("METADATA", 3, "STARTER", 2));
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminApiController(newConfigService(),
                        org.mockito.Mockito.mock(com.wymix.initializer.app.admin.sync.CatalogSyncService.class),
                        newStructuredService(), newImportExportService(), migration))
                .setControllerAdvice(new com.wymix.initializer.app.exception.GlobalExceptionHandler())
                .build();

        // 缺 operator → 400
        mockMvc.perform(post("/api/admin/config/migrate-legacy"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/admin/config/migrate-legacy?operator=tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").value(5))
                .andExpect(jsonPath("$.written.METADATA").value(3));
    }

    @Test
    void exportEndpoint_returnsZipAttachment() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(newController())
                .setControllerAdvice(new com.wymix.initializer.app.exception.GlobalExceptionHandler())
                .build();
        // mock 仓库返回空作用域 → 导出仍产出合法（空）归档
        mockMvc.perform(get("/api/admin/config/export"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition",
                                org.hamcrest.Matchers.containsString("initializer-config.zip")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentType("application/zip"));
    }

    @Test
    void getDraft_invalidKind_returns404MappedFrom4001() throws Exception {
        // kind 非法 → 4001（CONFIG_DRAFT_NOT_FOUND）→ GlobalExceptionHandler 422 语义测试在集成层覆盖
        mockMvc = MockMvcBuilders.standaloneSetup(
                        newController())
                .setControllerAdvice(new com.wymix.initializer.app.exception.GlobalExceptionHandler())
                .build();
        mockMvc.perform(get("/api/admin/config/unknown-kind"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(4001));
    }
}
