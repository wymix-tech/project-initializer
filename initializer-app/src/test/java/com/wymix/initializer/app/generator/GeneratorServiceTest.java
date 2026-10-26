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
            var starterCatalog = webStarterCatalog();
            // starter 元信息来自清单：装载 metadata 后联动合并
            var catalog = com.wymix.initializer.core.metadata.MetadataCatalog.load(in).merge(starterCatalog);
            ProjectGenerator generator = new ProjectGenerator(catalog,
                    TestTemplates.renderer(), starterCatalog);
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

    /** 测试用 starter 清单（starter 元信息唯一数据源）：官方 web。 */
    static StarterCatalog webStarterCatalog() {
        return StarterCatalog.of(List.of(webEntry()));
    }

    static StarterCatalogEntry webEntry() {
        return new StarterCatalogEntry("web", "web", Maintainer.OFFICIAL, "Spring Web",
                List.of(new DependencyCoord("org.springframework.boot", "spring-boot-starter-web", null)),
                new LinkageSpec(null, List.of(), List.of(), List.of()));
    }

    @Test
    void validRequestShouldStreamZip() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.generate(request(), out);
        assertThat(out.toByteArray()).isNotEmpty();
        assertThat(service.zipFileName(request())).isEqualTo("demo-app.zip");
    }

    @Test
    void snapshotBackedGeneration_shouldServeGeneration() {
        // 验收修复（P1-2）回归：热发布仅新增/调整清单时，生成侧同样走快照引擎，
        // 新 starter 对生成接口即时生效——原实现回退 classpath 兜底清单导致新 starter 选中即 2003
        CatalogSnapshotHolder holder = new CatalogSnapshotHolder();
        StarterCatalog snapshotStarters = StarterCatalog.of(List.of(
                webEntry(),
                new StarterCatalogEntry("hot-new-internal-starter", "middleware", Maintainer.INTERNAL,
                        "热发布新增内部 starter",
                        List.of(new DependencyCoord("com.company", "hot-new-internal-starter", "1.2.0")),
                        new LinkageSpec(null, List.of(), List.of(), List.of()))));
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
                """.getBytes(StandardCharsets.UTF_8))).merge(snapshotStarters);
        // T5：模板集只来自快照（DB 全量模板集），测试显式注入 classpath 种子模板集
        holder.seed(new CatalogSnapshot(snapshotMetadata, snapshotStarters, TestTemplates.load()));

        ProjectGenerator classpathFallback = new ProjectGenerator(
                MetadataCatalog.load(getClass().getResourceAsStream("/catalog/metadata.yml"))
                        .merge(webStarterCatalog()),
                TestTemplates.renderer(), webStarterCatalog());
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
    void snapshotWithEmptyTemplateSet_shouldThrow3001InsteadOfEmptySkeleton() {
        // T5：模板集为 DB 唯一数据源、渲染器无 classpath 兜底 —— 快照模板集为空时
        // 生成必须以前置校验失败（3001 明确明细），而不是产出空骨架或悄悄回退 classpath
        CatalogSnapshotHolder holder = new CatalogSnapshotHolder();
        holder.seed(new CatalogSnapshot(snapshotMetadata(), webStarterCatalog()));

        GeneratorService emptyTemplates = new GeneratorService(holder,
                new ProjectGenerator(
                        MetadataCatalog.load(getClass().getResourceAsStream("/catalog/metadata.yml"))
                                .merge(webStarterCatalog()),
                        new com.wymix.initializer.core.generator.TemplateRenderer(), webStarterCatalog()),
                validationService());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertThatThrownBy(() -> emptyTemplates.generate(request(), out))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_RENDER_FAILED);
                    assertThat(e.getMessage()).contains("pom.xml.ftl");
                });
        assertThat(out.size()).isZero();
    }

    private static MetadataCatalog snapshotMetadata() {
        return MetadataCatalog.load(new ByteArrayInputStream("""
                javaVersions:
                  - id: "17"
                    label: "Java 17"
                bootVersions:
                  - id: "3.2.5"
                    label: "Spring Boot 3.2.5"
                    official: true
                    supportedJavaVersions: ["17"]
                    starters: ["web"]
                """.getBytes(StandardCharsets.UTF_8))).merge(webStarterCatalog());
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
    @Test
    void omittedRequiredStarterStillParticipatesInConflictValidation() {
        StarterCatalog starters = StarterCatalog.of(List.of(
                new StarterCatalogEntry("required-a", "tool", Maintainer.INTERNAL, "A",
                        List.of(new DependencyCoord("com.example", "a", "1")),
                        new LinkageSpec(null, List.of(), List.of(), List.of()), true, List.of()), webEntry()));
        MetadataCatalog metadata = MetadataCatalog.of(
                List.of(new com.wymix.initializer.core.metadata.JavaVersion("17", "Java 17", false, null)),
                List.of(new com.wymix.initializer.core.metadata.BootVersion("3.2.5", "Boot", true,
                        List.of("17"), List.of()))).merge(starters);
        var conflicts = new com.wymix.initializer.core.conflict.ConflictCatalog(List.of(
                new com.wymix.initializer.core.conflict.ConflictRule("required-vs-web",
                        com.wymix.initializer.core.conflict.ConflictRule.Type.PAIR,
                        List.of("required-a", "web"), "不能同时选择")));
        CatalogSnapshotHolder holder = new CatalogSnapshotHolder();
        holder.seed(new CatalogSnapshot(metadata, starters, TestTemplates.load(), conflicts));
        GeneratorService target = new GeneratorService(holder, null, new ValidationService(
                com.wymix.initializer.core.validation.ValidationRules.load(
                        getClass().getResourceAsStream("/catalog/validation.yml"))));
        for (List<String> selection : List.of(List.of("web"), List.of("required-a", "web"))) {
            var out = new java.io.ByteArrayOutputStream();
            var req = new GenerateRequest("com.example", "demo", "com.example.demo", "17", "3.2.5", selection);
            assertThatThrownBy(() -> target.generate(req, out))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DEPENDENCY_CONFLICT));
            assertThat(out.size()).isZero();
        }
    }

    @Test
    void generationCapturesOnlyOneSnapshotEvenWhenReleaseChanges() {
        StarterCatalog starters = webStarterCatalog();
        MetadataCatalog metadata = MetadataCatalog.of(
                List.of(new com.wymix.initializer.core.metadata.JavaVersion("17", "Java 17", false, null)),
                List.of(new com.wymix.initializer.core.metadata.BootVersion("3.2.5", "Boot", true,
                        List.of("17"), List.of()))).merge(starters);
        CatalogSnapshot old = new CatalogSnapshot(metadata, starters, TestTemplates.load());
        CatalogSnapshot newer = new CatalogSnapshot(metadata, starters, java.util.Map.of());
        CatalogSnapshotHolder holder = org.mockito.Mockito.mock(CatalogSnapshotHolder.class);
        org.mockito.Mockito.when(holder.current()).thenReturn(old, newer);
        GeneratorService target = new GeneratorService(holder, null, new ValidationService(
                com.wymix.initializer.core.validation.ValidationRules.load(
                        getClass().getResourceAsStream("/catalog/validation.yml"))));
        var out = new java.io.ByteArrayOutputStream();
        target.generate(request(), out);
        assertThat(out.size()).isPositive();
        org.mockito.Mockito.verify(holder, org.mockito.Mockito.times(1)).current();
    }

}
