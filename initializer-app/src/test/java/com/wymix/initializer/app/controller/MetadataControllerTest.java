package com.wymix.initializer.app.controller;

import com.wymix.initializer.app.metadata.MetadataService;
import com.wymix.initializer.app.exception.GlobalExceptionHandler;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MetadataController} MockMvc 切片测试：真实 service + 真实 metadata.yml，验证 HTTP 契约与错误映射。
 */
class MetadataControllerTest {

    private MockMvc mockMvc;

    /** 测试专用元数据目录：在测试 metadata.yml 基础上为 3.2.5 追加内部 redis starter。 */
    private static final String METADATA_YAML = """
            javaVersions:
              - id: "8"
                label: "Java 8"
                deprecated: false
                recommendedBootVersion: "2.7.18"
              - id: "11"
                label: "Java 11"
                deprecated: false
                recommendedBootVersion: "2.7.18"
              - id: "17"
                label: "Java 17"
                deprecated: false
                recommendedBootVersion: "3.2.5"
              - id: "21"
                label: "Java 21"
                deprecated: false
                recommendedBootVersion: "3.2.5"
            bootVersions:
              - id: "2.7.18"
                label: "Spring Boot 2.7.18"
                official: true
                supportedJavaVersions: ["8", "11"]
                starters: ["web", "data-jpa", "data-redis", "actuator"]
              - id: "3.2.5"
                label: "Spring Boot 3.2.5"
                official: true
                supportedJavaVersions: ["17", "21"]
                starters: ["web", "data-jpa", "data-redis", "actuator", "xxx-redis-spring-boot-starter"]
            starters:
              web:
                label: "Spring Web"
                dependency:
                  groupId: "org.springframework.boot"
                  artifactId: "spring-boot-starter-web"
              data-jpa:
                label: "Spring Data JPA"
                dependency:
                  groupId: "org.springframework.boot"
                  artifactId: "spring-boot-starter-data-jpa"
              data-redis:
                label: "Spring Data Redis"
                dependency:
                  groupId: "org.springframework.boot"
                  artifactId: "spring-boot-starter-data-redis"
              actuator:
                label: "Spring Boot Actuator"
                dependency:
                  groupId: "org.springframework.boot"
                  artifactId: "spring-boot-starter-actuator"
              xxx-redis-spring-boot-starter:
                label: "内部 Redis 封装 starter"
                dependency:
                  groupId: "com.company"
                  artifactId: "xxx-redis-spring-boot-starter"
            """;

    @BeforeEach
    void setUp() {
        // 测试专用清单：3.2.5 下含内部 redis starter，供基线版本透出断言使用
        MetadataCatalog metadataCatalog = MetadataCatalog.load(
                new ByteArrayInputStream(METADATA_YAML.getBytes(StandardCharsets.UTF_8)));
        // 测试专用 starter 清单：登记内部 redis（声明 version=1.2.0）
        var starterCatalog = com.wymix.initializer.core.starter.StarterCatalog.of(java.util.List.of(
                new com.wymix.initializer.core.starter.StarterCatalogEntry(
                        "xxx-redis-spring-boot-starter", "middleware",
                        com.wymix.initializer.core.starter.Maintainer.INTERNAL, "内部 Redis 封装 starter",
                        java.util.List.of(new com.wymix.initializer.core.starter.DependencyCoord(
                                "com.company", "xxx-redis-spring-boot-starter", "1.2.0")),
                        new com.wymix.initializer.core.starter.LinkageSpec(null, java.util.List.of(),
                                java.util.List.of(), java.util.List.of()))));
        // 与生产装配对齐：classpath 内置清单 → 初始快照播种（CatalogSnapshotBootstrap 语义），
        // 快照携带 starter 清单后检索基于快照生效（version 透出依赖清单 dependencies 声明）
        var holder = new com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder();
        holder.seed(new com.wymix.initializer.app.admin.refresh.CatalogSnapshot(
                metadataCatalog, starterCatalog, java.util.Map.of()));
        MetadataService service = new MetadataService(holder, metadataCatalog, starterCatalog);
        mockMvc = MockMvcBuilders.standaloneSetup(new MetadataController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
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
                .andExpect(jsonPath("$[?(@.id == 'web')].version", org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void starters_unknownBoot_returns422WithCode2002() throws Exception {
        mockMvc.perform(get("/api/metadata/starters").param("boot", "9.9.9"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(2002));
    }
}
