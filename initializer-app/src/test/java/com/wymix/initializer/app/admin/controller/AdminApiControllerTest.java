package com.wymix.initializer.app.admin.controller;

import com.wymix.initializer.app.admin.security.AdminAuthFilter;
import com.wymix.initializer.app.admin.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
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
     * 构造被测 ConfigService（全部协作者 mock）。
     *
     * <p>T1 结构化改造后 ConfigService 新增「结构行码编器 / 结构行写入器」两个协作者，
     * 本方法集中构造，避免各用例重复列表导致新增依赖时多处遗漏。
     *
     * @return 全部协作者均为 mock 的 ConfigService
     */
    private static ConfigService newConfigService() {
        return new ConfigService(
                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.repository.ConfigRepository.class),
                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.refresh.CatalogRefresher.class),
                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder.class),
                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.repository.StructuredYamlCodec.class),
                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.repository.StructuredDraftWriter.class));
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AdminApiController(newConfigService(),
                                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.sync.CatalogSyncService.class)))
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
    void getDraft_invalidKind_returns404MappedFrom4001() throws Exception {
        // kind 非法 → 4001（CONFIG_DRAFT_NOT_FOUND）→ GlobalExceptionHandler 422 语义测试在集成层覆盖
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AdminApiController(newConfigService(),
                                org.mockito.Mockito.mock(com.wymix.initializer.app.admin.sync.CatalogSyncService.class)))
                .setControllerAdvice(new com.wymix.initializer.app.exception.GlobalExceptionHandler())
                .build();
        mockMvc.perform(get("/api/admin/config/unknown-kind"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(4001));
    }
}
