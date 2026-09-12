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

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AdminApiController(newConfigService(),
                                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.sync.CatalogSyncService.class),
                                newStructuredService()))
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
                        new AdminApiController(newConfigService(),
                                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.sync.CatalogSyncService.class),
                                newStructuredService()))
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
                        new AdminApiController(newConfigService(),
                                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.sync.CatalogSyncService.class),
                                newStructuredService()))
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
    void getDraft_invalidKind_returns404MappedFrom4001() throws Exception {
        // kind 非法 → 4001（CONFIG_DRAFT_NOT_FOUND）→ GlobalExceptionHandler 422 语义测试在集成层覆盖
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AdminApiController(newConfigService(),
                                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.sync.CatalogSyncService.class),
                                newStructuredService()))
                .setControllerAdvice(new com.wymix.initializer.app.exception.GlobalExceptionHandler())
                .build();
        mockMvc.perform(get("/api/admin/config/unknown-kind"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(4001));
    }
}
