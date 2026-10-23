package com.wymix.initializer.app.admin.service;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.dto.CatalogDtos.DependencyDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.FrameworkParentDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.LinkageDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.MetadataUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.BootVersionDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.JavaVersionDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterCatalogUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterUpsertDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateCatalogUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateDetail;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateLinkageDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.ConflictRuleDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.ConflictRuleUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.YmlFragmentDto;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ConfigImportExportService} 集成测试（结构化改造 T4，H2 内存库）：
 * 导出归档布局、导出→导入往返幂等（含 {@code hidden}）、导入整体回滚不污染草稿、
 * 归档安全加固（路径穿越 / 条目数 / 单文件 / 严格 UTF-8 / 缺 metadata.yml）。
 */
@SpringBootTest(classes = InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:config-import-export-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token"
})
class ConfigImportExportServiceTest {

    @Autowired
    private ConfigImportExportService service;

    @Autowired
    private StructuredDraftService draftService;

    @Autowired
    private ConfigRepository repository;

    @Autowired
    private ConfigService legacyConfig;

    /** 清空全部 kind 草稿行，保证用例互不干扰（共享 H2 库）。 */
    @BeforeEach
    void resetDrafts() {
        for (ConfigKind kind : ConfigKind.values()) {
            repository.clearDraftKind(kind);
        }
    }

    // ==================== 导出 ====================

    @Test
    void export_producesCatalogLayoutArchive_withHiddenColumn() {
        seedFullDraft();

        ConfigImportExportService.ExportResult result = service.export("tester");

        assertThat(result.fileName()).isEqualTo("initializer-config.zip");
        var entries = unzip(result.content());
        assertThat(entries).containsKeys("metadata.yml", "starters/web",
                "starters/xxx-redis-spring-boot-starter", "conflict-rules.yml", "templates/pom.xml.ftl");

        assertThat(entries.get("metadata.yml")).contains("javaVersions").contains("17").contains("3.2.5");
        // hidden 必须出现在导出文本里（PR #40 语义端到端）
        assertThat(entries.get("starters/xxx-redis-spring-boot-starter")).contains("hidden: true");
        assertThat(entries.get("starters/web")).contains("id: web");
        assertThat(entries.get("conflict-rules.yml")).contains("rules").contains("redis-vs-web");
        // 模板条目是「纯模板全文」（路径即模板名），不再包一层 {name, content} 文档
        assertThat(entries.get("templates/pom.xml.ftl")).isEqualTo("<project/>");
        assertThat(result.summary()).contains("starter 2").contains("模板 1");
    }

    @Test
    void export_emptyDraft_stillReturnsValidArchive() {
        ConfigImportExportService.ExportResult result = service.export("tester");
        assertThat(result.content()).isNotEmpty();
    }

    // ==================== 导出 → 导入 往返 ====================

    @Test
    void roundTrip_exportThenImport_isIdempotent_andKeepsHidden() {
        seedFullDraft();
        ConfigImportExportService.ExportResult exported = service.export("tester");

        // 清空草稿（同时清空模板内容），再导入归档
        for (ConfigKind kind : ConfigKind.values()) {
            repository.clearDraftKind(kind);
        }
        ConfigImportExportService.ImportResult imported = service.importArchive(
                new ByteArrayInputStream(exported.content()), "tester", null);

        assertThat(imported.rows()).isPositive();
        // 摘要含各模块规模（ASCII 计数，便于断言）；模板全文与 token 不在其中
        assertThat(imported.summary()).contains("java=2").contains("boot=1")
                .contains("starters[2,").contains("conflictRules[1]").contains("templates[1]");
        assertThat(imported.versions().keySet()).extracting(ConfigKind::lowerName)
                .containsExactlyInAnyOrder("metadata", "starter", "conflict-rule", "template");

        // hidden 往返不丢（直接断言草稿作用域的 cfg_starter 列）
        var starterRows = repository.readDraftScope(ConfigKind.STARTER).starters();
        assertThat(starterRows).extracting("starterId").containsExactlyInAnyOrder(
                "web", "xxx-redis-spring-boot-starter");
        var hiddenStarter = starterRows.stream()
                .filter(row -> "xxx-redis-spring-boot-starter".equals(row.getStarterId()))
                .findFirst().orElseThrow();
        assertThat(hiddenStarter.getHidden()).isTrue();
        assertThat(hiddenStarter.getConfigPackage()).isEqualTo("{basePackage}.config");
        assertThat(starterRows.stream().filter(row -> "web".equals(row.getStarterId())).findFirst()
                .orElseThrow().getHidden()).isFalse();
        assertThat(repository.readDraftScope(ConfigKind.STARTER).starterLinkageTemplates())
                .extracting("source").containsExactly("starters/xxx-redis/RedisConfig.java.ftl");

        // 元数据 / 规则 / 模板
        assertThat(draftService.getMetadata().javaVersions()).extracting("id").containsExactly("17", "21");
        assertThat(draftService.getMetadata().bootVersions().get(0).frameworkParent().artifactId())
                .isEqualTo("framework-parent");
        assertThat(draftService.getConflictRules().rules()).extracting("id").containsExactly("redis-vs-web");
        assertThat(draftService.getTemplate("pom.xml.ftl").content()).isEqualTo("<project/>");

        // 再次导出与首次导出等价（幂等的可观测断言：往返后不产生新的归档内容差异）
        ConfigImportExportService.ExportResult again = service.export("tester");
        assertThat(unzip(again.content())).isEqualTo(unzip(exported.content()));
    }

    @Test
    void import_replacesWholeDraft_andPreservesSyncChannel() {
        // 先写入同步层（source = SYNC）条目，导入必须不触碰它
        draftService.saveStarters(new StarterCatalogUpsertRequest(List.of(
                new StarterUpsertDto("synced-a", "web", "internal", "同步条目", false, false, null,
                        List.of(new DependencyDto("g", "synced-a", null)), List.of(), List.of("3.2.5"),
                        null, 0)), "sync-bot"), null,
                ConfigKind.STARTER_SYNC);
        seedFullDraft();
        ConfigImportExportService.ExportResult exported = service.export("tester");

        draftService.saveStarters(new StarterCatalogUpsertRequest(List.of(
                new StarterUpsertDto("manual-x", "web", "official", "手工条目", false, false, null,
                        List.of(), List.of(), List.of(), null, 0)), "tester"), null);

        service.importArchive(new ByteArrayInputStream(exported.content()), "tester", null);

        // 手工通道被归档整体替换（临时引入的 manual-x 已不在）
        var starterRows = repository.readDraftScope(ConfigKind.STARTER).starters();
        var srcs = starterRows.stream().map(r -> r.getStarterId() + "|" + r.getSource()).toList();
        assertThat(srcs).isNotNull(); // DIAG
        assertThat(draftService.getStarters().starters()).extracting("id")
                .containsExactly("web", "xxx-redis-spring-boot-starter");
        // 同步通道零改动（导入只写 MANUAL 行）
        assertThat(repository.readDraftScope(ConfigKind.STARTER_SYNC).starters())
                .extracting("starterId").containsExactly("synced-a");
    }

    // ==================== 导入失败：整体回滚 ====================

    @Test
    void import_danglingRequires_isAccepted_draftModeSkipsDependencyValidation() {
        seedFullDraft();
        ConfigImportExportService.ExportResult exported = service.export("tester");
        var originalEntries = unzip(exported.content());

        // 构造归档：metadata.yml 合法，starters/bad 的 require 引用了不存在条目
        // 导入期不再校验 requires 依赖关系，应成功写入草稿
        byte[] archive = zip(java.util.Map.of(
                "metadata.yml", originalEntries.get("metadata.yml"),
                "starters/bad", "id: bad\ncategory: web\nmaintainer: official\ndescription: d\n"
                        + "dependencies:\n  - groupId: g\n    artifactId: a\n"
                        + "requires: [ghost-starter]\n"));

        ConfigImportExportService.ImportResult result = service.importArchive(
                new ByteArrayInputStream(archive), "tester", null);

        assertThat(result.rows()).isPositive();
        // 草稿被整体替换为归档内容（含 dangling requires 的 bad starter 已写入）
        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters()).extracting("starterId")
                .containsExactlyInAnyOrder("bad");
    }

    @Test
    void import_selfReferencingRequires_isAccepted_draftModeSkipsDependencyValidation() {
        // 对应截图场景：starter requires 引用了自身
        byte[] archive = zip(java.util.Map.of(
                "metadata.yml", "javaVersions:\n  - id: \"17\"\n    label: Java 17\n"
                        + "bootVersions:\n  - id: \"3.2.5\"\n    label: Boot\n    official: true\n"
                        + "    supportedJavaVersions: [\"17\"]\n    starters: [travelsky_dev_jpa_repository]\n",
                "starters/travelsky_dev_jpa_repository",
                "id: travelsky_dev_jpa_repository\ncategory: data\nmaintainer: official\ndescription: d\n"
                        + "dependencies:\n  - groupId: g\n    artifactId: a\n"
                        + "requires: [travelsky_dev_jpa_repository]\n"));

        ConfigImportExportService.ImportResult result = service.importArchive(
                new ByteArrayInputStream(archive), "tester", null);

        assertThat(result.rows()).isPositive();
        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters()).extracting("starterId")
                .containsExactly("travelsky_dev_jpa_repository");
    }

    @Test
    void import_missingMetadataFile_rejected() {
        byte[] archive = zip(java.util.Map.of(
                "starters/web", "id: web\ncategory: web\nmaintainer: official\ndescription: d\n"));
        assertThatThrownBy(() -> service.importArchive(new ByteArrayInputStream(archive), "tester", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_VALIDATION_FAILED))
                .hasMessageContaining("metadata.yml");
    }

    @Test
    void import_danglingMetadataReference_isAccepted_draftModeSkipsReferenceValidation() {
        byte[] archive = zip(java.util.Map.of(
                "metadata.yml", "javaVersions:\n  - id: \"17\"\n    label: Java 17\n"
                        + "bootVersions:\n  - id: \"3.2.5\"\n    label: Boot\n    official: true\n"
                        + "    supportedJavaVersions: [\"99\"]\n    starters: [web]\n",
                "starters/web", "id: web\ncategory: web\nmaintainer: official\ndescription: d\n"
                        + "dependencies:\n  - groupId: g\n    artifactId: a\n"));
        ConfigImportExportService.ImportResult result = service.importArchive(
                new ByteArrayInputStream(archive), "tester", null);

        assertThat(result.rows()).isPositive();
        // metadata 中引用了未定义的 Java 版本或 starter，导入期不再校验，直接写入草稿
        assertThat(draftService.getMetadata().bootVersions().get(0).supportedJavaVersions()).containsExactly("99");
        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters()).extracting("starterId")
                .containsExactly("web");
    }

    @Test
    void import_metadataWhitelistReferencesUnknownStarter_isAccepted_draftModeSkipsWhitelistValidation() {
        // metadata 白名单写了 starters: [ghost]，归档内并没有 ghost 条目
        // 导入期不再校验白名单引用，应成功写入草稿
        byte[] archive = zip(java.util.Map.of(
                "metadata.yml", "javaVersions:\n  - id: \"17\"\n    label: Java 17\n"
                        + "bootVersions:\n  - id: \"3.2.5\"\n    label: Boot\n    official: true\n"
                        + "    supportedJavaVersions: [\"17\"]\n    starters: [ghost]\n",
                "starters/web", "id: web\ncategory: web\nmaintainer: official\ndescription: d\n"
                        + "dependencies:\n  - groupId: g\n    artifactId: a\n"));
        ConfigImportExportService.ImportResult result = service.importArchive(
                new ByteArrayInputStream(archive), "tester", null);

        assertThat(result.rows()).isPositive();
        assertThat(draftService.getMetadata().bootVersions().get(0).starters()).containsExactly("ghost");
    }

    @Test
    void import_duplicateWhitelistEntry_rejected_andLegacyV0601() {
        // 结构级：同一 Boot 的白名单条目重复
        assertThatThrownBy(() -> service.importArchive(new ByteArrayInputStream(zip(java.util.Map.of(
                "metadata.yml", "javaVersions:\n  - id: \"17\"\n    label: Java 17\n"
                        + "bootVersions:\n  - id: \"3.2.5\"\n    label: B\n"
                        + "    supportedJavaVersions: [\"17\"]\n    starters: [web, web]\n",
                "starters/web", "id: web\ncategory: web\nmaintainer: official\ndescription: d\n"
                        + "dependencies:\n  - groupId: g\n    artifactId: a\n"))), "tester", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("白名单存在重复项"));
    }

    @Test
    void import_blankOperator_rejected() {
        byte[] archive = zip(java.util.Map.of("metadata.yml",
                "javaVersions:\n  - id: \"17\"\n    label: Java 17\nbootVersions:\n  - id: \"3.2.5\"\n"
                        + "    label: Boot\n    supportedJavaVersions: [\"17\"]\n"));
        assertThatThrownBy(() -> service.importArchive(new ByteArrayInputStream(archive), "  ", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("operator"));
    }

    @Test
    void import_staleIfMatch_rejected() {
        seedFullDraft();
        ConfigImportExportService.ExportResult exported = service.export("tester");
        int stale = repository.findDraftMeta(ConfigKind.METADATA).getVersion() + 100;

        assertThatThrownBy(() -> service.importArchive(
                new ByteArrayInputStream(exported.content()), "tester", stale))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_CONCURRENT_MODIFY));
    }

    // ==================== 归档安全加固 ====================

    @Test
    void readArchive_rejectsPathTraversalEntries() {
        byte[] archive = zip(java.util.Map.of(
                "metadata.yml", "javaVersions:\n  - id: \"17\"\n    label: Java 17\n",
                "templates/../../etc/passwd", "boom"));
        assertThatThrownBy(() -> service.importArchive(new ByteArrayInputStream(archive), "tester", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_ARCHIVE_INVALID))
                .hasMessageContaining("路径穿越");
    }

    @Test
    void readArchive_rejectsAbsoluteAndBackslashTraversalEntries() {
        assertThatThrownBy(() -> service.importArchive(new ByteArrayInputStream(
                zip(java.util.Map.of("metadata.yml", "a: 1\n", "/abs/path.yml", "x"))), "tester", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_ARCHIVE_INVALID))
                .hasMessageContaining("绝对路径");

        assertThatThrownBy(() -> service.importArchive(new ByteArrayInputStream(
                zip(java.util.Map.of("metadata.yml", "a: 1\n", "templates\\..\\x.ftl", "x"))), "tester", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_ARCHIVE_INVALID));
    }

    @Test
    void readArchive_rejectsIllegalUtf8AndDuplicateEntries() {
        java.util.Map<String, byte[]> rawEntries = new java.util.LinkedHashMap<>();
        rawEntries.put("metadata.yml",
                "javaVersions:\n  - id: \"17\"\n    label: Java 17\n".getBytes(StandardCharsets.UTF_8));
        rawEntries.put("templates/bad.ftl", new byte[] {(byte) 0xC3, (byte) 0x28});
        byte[] badUtf8 = zipRaw(rawEntries);
        assertThatThrownBy(() -> service.importArchive(new ByteArrayInputStream(badUtf8), "tester", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_ARCHIVE_INVALID))
                .hasMessageContaining("UTF-8");

        assertThatThrownBy(() -> service.importArchive(new ByteArrayInputStream(
                zipDuplicate("metadata.yml", "a: 1\n")), "tester", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_ARCHIVE_INVALID))
                .hasMessageContaining("重复条目");
    }

    @Test
    void readArchive_rejectsEmptyArchiveAndTrailingDotDotSegment() {
        assertThatThrownBy(() -> service.importArchive(
                new ByteArrayInputStream(new byte[] {}), "tester", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_ARCHIVE_INVALID));

        assertThatThrownBy(() -> service.importArchive(new ByteArrayInputStream(
                zip(java.util.Map.of("metadata.yml", "a: 1\n", "starters/..", "x"))), "tester", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_ARCHIVE_INVALID));
    }

    @Test
    void readArchive_rejectsTooManyEntries() {
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("metadata.yml", "javaVersions:\n  - id: \"17\"\n    label: Java 17\n");
        for (int i = 0; i <= 2048; i++) {
            entries.put("templates/t" + i + ".ftl", "x");
        }
        assertThatThrownBy(() -> service.importArchive(
                new ByteArrayInputStream(zip(entries)), "tester", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_ARCHIVE_INVALID))
                .hasMessageContaining("条目数超限");
    }

    @Test
    void import_templateSyntaxError_rejected() {
        byte[] archive = zip(java.util.Map.of(
                "metadata.yml", "javaVersions:\n  - id: \"17\"\n    label: Java 17\n"
                        + "bootVersions:\n  - id: \"3.2.5\"\n    label: Boot\n"
                        + "    supportedJavaVersions: [\"17\"]\n    starters: []\n",
                "templates/broken.ftl", "<#if x>"));  // 缺少 </#if>：FreeMarker 语法非法
        assertThatThrownBy(() -> service.importArchive(new ByteArrayInputStream(archive), "tester", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_VALIDATION_FAILED));
    }

    @Test
    void import_templateTooLong_rejected() {
        String huge = "x".repeat(512 * 1024 + 1);
        byte[] archive = zip(java.util.Map.of(
                "metadata.yml", "javaVersions:\n  - id: \"17\"\n    label: Java 17\n"
                        + "bootVersions:\n  - id: \"3.2.5\"\n    label: Boot\n"
                        + "    supportedJavaVersions: [\"17\"]\n    starters: []\n",
                "templates/huge.ftl", huge));
        assertThatThrownBy(() -> service.importArchive(new ByteArrayInputStream(archive), "tester", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("超长"));
    }

    // ==================== 夹具 ====================

    /** 播种一份覆盖四模块（含 hidden、联动、矩阵、规则、模板）的完整草稿。 */
    private void seedFullDraft() {
        draftService.saveMetadata(new MetadataUpsertRequest(
                List.of(new JavaVersionDto("17", "Java 17", false, "3.2.5", 0),
                        new JavaVersionDto("21", "Java 21", true, null, 1)),
                List.of(new BootVersionDto("3.2.5", "Spring Boot 3.2.5", true, List.of("17", "21"),
                        List.of("web", "xxx-redis-spring-boot-starter"),
                        new FrameworkParentDto("com.example", "framework-parent", "1.0.0"), 0)),
                "tester"), null);

        draftService.saveStarters(new StarterCatalogUpsertRequest(List.of(
                new StarterUpsertDto("web", "web", "official", "Spring MVC + 内嵌 Tomcat",
                        true, false, null,
                        List.of(new DependencyDto("org.springframework.boot", "spring-boot-starter-web", null)),
                        List.of(), List.of("3.2.5"), null, 0),
                new StarterUpsertDto("xxx-redis-spring-boot-starter", "middleware", "internal",
                        "内部 Redis 封装 starter", false, true, "{basePackage}.config",
                        List.of(new DependencyDto("com.company", "xxx-redis-spring-boot-starter", "1.2.0")),
                        List.of(List.of("web")), List.of("3.2.5"),
                        new LinkageDto(
                                List.of(new TemplateLinkageDto("starters/xxx-redis/RedisConfig.java.ftl",
                                        "src/main/java/{packagePath}/config/RedisConfig.java")),
                                List.of(new YmlFragmentDto("starters/xxx-redis/application-redis.yml", "APPEND")),
                                List.of()),
                        1)), "tester"), null);

        draftService.saveConflictRules(new ConflictRuleUpsertRequest(List.of(
                new ConflictRuleDto("redis-vs-web", "pair",
                        List.of("web", "xxx-redis-spring-boot-starter"), "两者不可同时选中", 0)),
                "tester"), null);

        draftService.saveTemplates(new TemplateCatalogUpsertRequest(List.of(
                new TemplateDetail("pom.xml.ftl", "<project/>", null, 0)), "tester"), null);
    }

    /** 打包为 ZIP（UTF-8 文本条目）。 */
    private static byte[] zip(java.util.Map<String, String> entries) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                for (var entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 打包为 ZIP（可含原始字节条目）。 */
    private static byte[] zipRaw(java.util.Map<String, byte[]> entries) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                for (var entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 同一路径写入两次（重复条目）。
     *
     * <p>{@link ZipOutputStream} 自带重名检查，故此处手工拼装两个同名 local file header +
     * central directory，模拟真实世界「打包工具允许重名」的归档，验证读取侧的重名拦截。
     */
    private static byte[] zipDuplicate(String name, String content) {
        try {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            int crc = (int) crc32(data);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            for (int i = 0; i < 2; i++) {
                buffer.write(localHeader(nameBytes, data.length, crc));
                buffer.write(data);
            }
            int centralOffset = buffer.size();
            for (int i = 0; i < 2; i++) {
                buffer.write(centralHeader(nameBytes, data.length, crc, 0));
            }
            buffer.write(centralDirectoryEnd(2, buffer.size() - centralOffset, centralOffset, nameBytes.length));
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** ZIP local file header（STORED，无扩展字段）。 */
    private static byte[] localHeader(byte[] name, int size, int crc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 0x04034b50L);
        writeShort(out, 20);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeInt(out, crc & 0xFFFFFFFFL);
        writeInt(out, size);
        writeInt(out, size);
        writeShort(out, name.length);
        writeShort(out, 0);
        out.write(name, 0, name.length);
        return out.toByteArray();
    }

    /** ZIP central directory header。 */
    private static byte[] centralHeader(byte[] name, int size, int crc, int localOffset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 0x02014b50L);
        writeShort(out, 20);
        writeShort(out, 20);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeInt(out, crc & 0xFFFFFFFFL);
        writeInt(out, size);
        writeInt(out, size);
        writeShort(out, name.length);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeInt(out, 0);
        writeInt(out, localOffset);
        out.write(name, 0, name.length);
        return out.toByteArray();
    }

    /** ZIP 中央目录结束记录（EOCD）。 */
    private static byte[] centralDirectoryEnd(int entries, int centralSize, int centralOffset, int nameLength) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 0x06054b50L);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, entries);
        writeShort(out, entries);
        writeInt(out, centralSize);
        writeInt(out, centralOffset);
        writeShort(out, 0);
        return out.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
    }

    private static long crc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /** 解包为「条目名 → UTF-8 文本」。 */
    private static java.util.Map<String, String> unzip(byte[] archive) {
        try {
            java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            return entries;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 边界与分支补充 ====================

    @Test
    void import_withIfMatch_onExistingDrafts_advancesVersions() {
        seedFullDraft();
        ConfigImportExportService.ExportResult exported = service.export("tester");
        int current = repository.findDraftMeta(ConfigKind.METADATA).getVersion();

        ConfigImportExportService.ImportResult imported = service.importArchive(
                new ByteArrayInputStream(exported.content()), "tester", current);
        assertThat(imported.versions()).hasSize(4);
        assertThat(imported.rows()).isPositive();
    }

    @Test
    void import_withStaleIfMatch_perKindMismatchRejected() {
        seedFullDraft();
        ConfigImportExportService.ExportResult exported = service.export("tester");
        int stale = repository.findDraftMeta(ConfigKind.METADATA).getVersion() + 1000;

        assertThatThrownBy(() -> service.importArchive(
                new ByteArrayInputStream(exported.content()), "tester", stale))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_CONCURRENT_MODIFY);
                    assertThat(e.getMessage()).contains("kind=metadata");
                });
        // 失败留痕：审计写 IMPORT_FAILED（noRollbackFor 保证失败明细落库，便于管理端排查）
        assertThat(repository.listAudits()).extracting("action").contains("IMPORT_FAILED");
        // 且草稿未被改动
        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters())
                .extracting("starterId").containsExactlyInAnyOrder(
                        "web", "xxx-redis-spring-boot-starter");
        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters())
                .extracting("starterId").containsExactlyInAnyOrder(
                        "web", "xxx-redis-spring-boot-starter");
    }

    @Test
    void export_ignoresSyncChannelAndHandlesUnusualStarterIds() {
        // 非法文件名字符的 starter ID → 导出文件名归一（/ 与空格替换为 -）
        draftService.saveMetadata(new MetadataUpsertRequest(
                List.of(new JavaVersionDto("17", "Java 17", false, null, 0)),
                List.of(new BootVersionDto("3.2.5", "Boot", true, List.of("17"),
                        List.of("weird/id"), null, 0)), "tester"), null);
        draftService.saveStarters(new StarterCatalogUpsertRequest(List.of(
                new StarterUpsertDto("weird/id", "web", "official", "d", false, false, null,
                        List.of(new DependencyDto("g", "a", null)), List.of(), List.of(), null, 0),
                new StarterUpsertDto("weird id", "web", "official", "d", false, false, null,
                        List.of(new DependencyDto("g", "b", null)), List.of(), List.of(), null, 1)),
                "tester"), null);
        // 同步通道条目不应进入归档
        draftService.saveStarters(new StarterCatalogUpsertRequest(List.of(
                new StarterUpsertDto("synced", "web", "internal", "d", false, false, null,
                        List.of(new DependencyDto("g", "c", null)), List.of(), List.of(), null, 0)),
                "bot"), null, ConfigKind.STARTER_SYNC);

        var entries = unzip(service.export("tester").content());
        assertThat(entries.keySet()).filteredOn(name -> name.startsWith("starters/"))
                .noneMatch(name -> name.contains("synced"));
    }

    @Test
    void export_draftWithoutMetadata_stillProducesArchive() {
        // 只写模板，metadata 为空 → 归档不含 metadata.yml，但仍可导出
        draftService.saveTemplates(new TemplateCatalogUpsertRequest(List.of(
                new TemplateDetail("t.ftl", "content", null, 0)), "tester"), null);
        var entries = unzip(service.export("tester").content());
        assertThat(entries).doesNotContainKey("metadata.yml");
        assertThat(entries).containsKey("templates/t.ftl");
    }

    @Test
    void import_archiveWithOnlyMetadata_isAccepted_andClearsOtherDraftParts() {
        seedFullDraft();
        byte[] archive = zip(java.util.Map.of("metadata.yml",
                "javaVersions:\n  - id: \"17\"\n    label: Java 17\n"
                        + "bootVersions:\n  - id: \"3.2.5\"\n    label: Boot\n"
                        + "    supportedJavaVersions: [\"17\"]\n"));
        ConfigImportExportService.ImportResult result = service.importArchive(
                new ByteArrayInputStream(archive), "tester", null);

        assertThat(result.summary()).contains("java=1");
        // starter / 规则 / 模板被整体清空（归档未包含这些节 = 空集语义）
        assertThat(repository.readDraftScope(ConfigKind.STARTER).starters()).isEmpty();
        assertThat(repository.readDraftScope(ConfigKind.CONFLICT_RULE).conflictRules()).isEmpty();
        assertThat(repository.readDraftScope(ConfigKind.TEMPLATE).templates()).isEmpty();
    }
    @Test
    void exportAndReimportRetainsYamlRuntimePlaceholders() {
        seedFullDraft();
        draftService.saveTemplate(new com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateUpsertRequest(
                "fragments/redis.yml", "redis:\n  host: ${REDIS_HOST:localhost}\n  name: ${artifact}\n", "tester", null), null);
        byte[] archive = service.export("tester").content();
        service.importArchive(new ByteArrayInputStream(archive), "tester", null);
        assertThat(draftService.getTemplate("fragments/redis.yml").content())
                .contains("${REDIS_HOST:localhost}").contains("${artifact}");
    }

    @Test
    void sameIdBootVariantsSurviveYamlWriteAndArchiveRoundTripWithoutCrossingDependencies() {
        seedFullDraft();
        legacyConfig.saveDraft(ConfigKind.STARTER, """
                id: shared
                category: tool
                maintainer: internal
                bootVersions: ["2.7.18"]
                dependencies:
                  - groupId: com.example
                    artifactId: shared-two
                    version: "2"
                ---
                id: shared
                category: tool
                maintainer: internal
                bootVersions: ["3.2.5"]
                dependencies:
                  - groupId: com.example
                    artifactId: shared-three
                    version: "3"
                """, null, "tester");
        byte[] archive = service.export("tester").content();
        service.importArchive(new ByteArrayInputStream(archive), "tester", null);
        var entries = draftService.getStarters().starters();
        assertThat(entries).hasSize(2);
        for (var entry : entries) {
            assertThat(entry.dependencies()).hasSize(1);
            assertThat(entry.dependencies().get(0).version())
                    .isEqualTo(entry.bootVersions().contains("2.7.18") ? "2" : "3");
        }
    }

}
