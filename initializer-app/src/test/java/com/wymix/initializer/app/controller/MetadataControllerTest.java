package com.wymix.initializer.app.controller;

import com.wymix.initializer.app.metadata.MetadataService;
import com.wymix.initializer.app.exception.GlobalExceptionHandler;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MetadataController} MockMvc 切片测试：真实 service + 真实 metadata.yml，验证 HTTP 契约与错误映射。
 */
class MetadataControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("catalog/metadata.yml")) {
            MetadataService service = new MetadataService(
                    new com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder(),
                    MetadataCatalog.load(in),
                    com.wymix.initializer.core.starter.StarterCatalog.of(List.of()));
            mockMvc = MockMvcBuilders.standaloneSetup(new MetadataController(service))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();
        }
    }

    @Test
    void javaVersions_returns200WithList() throws Exception {
        mockMvc.perform(get("/api/metadata/java-versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("8"))
                .andExpect(jsonPath("$[3].id").value("21"))
                .andExpect(jsonPath("$[2].recommendedBootVersion").value("3.2.5"));
    }

    @Test
    void bootVersions_java17_returnsOnly3x() throws Exception {
        mockMvc.perform(get("/api/metadata/boot-versions").param("java", "17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("3.2.5"))
                .andExpect(jsonPath("$[0].official").value(true));
    }

    @Test
    void bootVersions_java8_returnsOnly2x() throws Exception {
        mockMvc.perform(get("/api/metadata/boot-versions").param("java", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("2.7.18"));
    }

    @Test
    void bootVersions_unknownJava_returns422WithCode2002() throws Exception {
        mockMvc.perform(get("/api/metadata/boot-versions").param("java", "99"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(2002))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void bootVersions_missingParam_returns400WithCode1001() throws Exception {
        mockMvc.perform(get("/api/metadata/boot-versions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void starters_boot325_returnsList() throws Exception {
        mockMvc.perform(get("/api/metadata/starters").param("boot", "3.2.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'web')].label").value("Spring Web"))
                .andExpect(jsonPath("$[?(@.id == 'web')].maintainer").value("official"));
    }

    @Test
    void starters_boot325_exposesBaselineVersion() throws Exception {
        // 基线版本透出（验收一般项①）：内部 starter 透出清单声明版本，官方 starter 为 null
        mockMvc.perform(get("/api/metadata/starters").param("boot", "3.2.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'xxx-redis-spring-boot-starter')].version").value("1.2.0"))
                .andExpect(jsonPath("$[?(@.id == 'web')].version").value(null));
    }

    @Test
    void starters_unknownBoot_returns422WithCode2002() throws Exception {
        mockMvc.perform(get("/api/metadata/starters").param("boot", "9.9.9"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(2002));
    }
}
