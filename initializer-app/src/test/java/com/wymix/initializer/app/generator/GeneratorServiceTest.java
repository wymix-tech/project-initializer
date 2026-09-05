package com.wymix.initializer.app.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshot;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder;
import com.wymix.initializer.app.validation.ValidationService;
import com.wymix.initializer.core.generator.GenerateRequest;
import com.wymix.initializer.core.generator.ProjectGenerator;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.DependencyCoord;
import com.wymix.initializer.core.starter.LinkageSpec;
import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GeneratorService 单元测试：前置校验失败 1001；成功透传生成引擎产物。
 */
class GeneratorServiceTest {

    private GeneratorService service;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/catalog/metadata.yml")) {
            var catalog = com.wymix.initializer.core.metadata.MetadataCatalog.load(in);
            var starterCatalog = com.wymix.initializer.core.starter.StarterCatalog.of(java.util.List.of());
            ProjectGenerator generator = new ProjectGenerator(catalog,
                    new com.wymix.initializer.core.generator.TemplateRenderer(), starterCatalog);
            service = new GeneratorService(new com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder(),
                    generator, new ValidationService(
                    com.wymix.initializer.core.validation.ValidationRules.load(
                            getClass().getResourceAsStream("/catalog/validation.yml"))));
        }
    }

    private static GenerateRequest request() {
        return new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "17", "3.2.5", List.of("web"));
    }

    @Test
    void validRequestShouldStreamZip() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.generate(request(), out);
        assertThat(out.toByteArray()).isNotEmpty();
        assertThat(service.zipFileName(request())).isEqualTo("demo-app.zip");
    }

    @Test
    void snapshotWithoutTemplateOverrides_shouldServeGeneration() {
        // 验收修复（P1-2）回归：热发布仅新增/调整清单（无模板覆盖）时，生成侧同样走快照引擎，
        // 新 starter 对生成接口即时生效——原实现回退 classpath 兜底清单导致新 starter 选中即 2003
        CatalogSnapshotHolder holder = new CatalogSnapshotHolder();
        MetadataCatalog snapshotMetadata = MetadataCatalog.load(new ByteArrayInputStream(
                """
                javaVersions:
                  - id: "17"
                    label: "Java 17"
                bootVersions:
                  - id: "3.2.5"
                    label: "Spring Boot 3.2.5"
                    official: true
                    supportedJavaVersions: ["17"]
                    starters: ["web", "hot-new-internal-starter"]
                starters:
                  web:
                    label: "Spring Web"
                    dependency:
                      groupId: org.springframework.boot
                      artifactId: spring-boot-starter-web
                  hot-new-internal-starter:
                    label: "热发布新增内部 starter"
                    dependency:
                      groupId: com.company
                      artifactId: hot-new-internal-starter
                """.getBytes(StandardCharsets.UTF_8)));
        StarterCatalog snapshotStarters = StarterCatalog.of(List.of(
                new StarterCatalogEntry("hot-new-internal-starter", "middleware", Maintainer.INTERNAL,
                        "热发布新增内部 starter",
                        List.of(new DependencyCoord("com.company", "hot-new-internal-starter", "1.2.0")),
                        new LinkageSpec(null, List.of(), List.of(), List.of()))));
        holder.seed(new CatalogSnapshot(snapshotMetadata, snapshotStarters));

        ProjectGenerator classpathFallback = new ProjectGenerator(
                MetadataCatalog.load(getClass().getResourceAsStream("/catalog/metadata.yml")),
                new com.wymix.initializer.core.generator.TemplateRenderer(),
                StarterCatalog.of(List.of()));
        GeneratorService snapService = new GeneratorService(holder, classpathFallback, validationService());
        GenerateRequest req = new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "17", "3.2.5", List.of("web", "hot-new-internal-starter"));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        snapService.generate(req, out);
        assertThat(out.toByteArray()).isNotEmpty();
    }

    private ValidationService validationService() {
        try (InputStream in = getClass().getResourceAsStream("/catalog/validation.yml")) {
            return new ValidationService(com.wymix.initializer.core.validation.ValidationRules.load(in));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void invalidGroupShouldThrow1001() {
        GenerateRequest bad = new GenerateRequest("Bad", "demo-app", "com.example.demo",
                "17", "3.2.5", List.of("web"));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertThatThrownBy(() -> service.generate(bad, out))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_METADATA));
        // 校验失败时输出流不应被写入（无半成品）
        assertThat(out.size()).isZero();
    }
}
