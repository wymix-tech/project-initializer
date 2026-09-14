package com.wymix.initializer.app.admin.service;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.dto.CatalogDtos.AbstractClassDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.BootVersionDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.ConflictRuleDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.ConflictRuleUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.DependencyDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.DraftSaveResult;
import com.wymix.initializer.app.admin.dto.CatalogDtos.FrameworkParentDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.JavaVersionDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.LinkageDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.MetadataUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.MetadataView;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterCatalogUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterUpsertDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateCatalogUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateDetail;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateLinkageDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.YmlFragmentDto;
import com.wymix.initializer.app.admin.entity.CfgTemplateEntity;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StructuredDraftService} 集成测试（结构化改造 T3，H2 内存库）：
 * 四类模块的结构化整体读写、kind 级乐观锁、草稿宽松边界、模板 CLOB 列表隔离、
 * 审计规模留痕，以及「结构化写入 ↔ YAML 读取同源」的交叉验证。
 */
@SpringBootTest(classes = InitializerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:structured-draft-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token"
})
class StructuredDraftServiceTest {

    @Autowired
    private StructuredDraftService service;

    @Autowired
    private ConfigService configService;

    @Autowired
    private ConfigRepository repository;

    /** 清空全部 kind 草稿行，保证用例互不干扰（共享 H2 库）。 */
    @BeforeEach
    void resetDrafts() {
        for (ConfigKind kind : ConfigKind.values()) {
            repository.clearDraftKind(kind);
        }
    }

    // ==================== 元数据 ====================

    @Test
    void metadata_roundTrip_includingMatrixAndFrameworkParent() {
        DraftSaveResult result = service.saveMetadata(new MetadataUpsertRequest(
                List.of(new JavaVersionDto("17", "Java 17", false, "3.2.5", 0),
                        new JavaVersionDto("21", null, true, null, 1)),
                List.of(new BootVersionDto("3.2.5", "Spring Boot 3.2.5", true,
                        List.of("17", "21"), List.of("web"),
                        new FrameworkParentDto("com.example", "framework-parent", "1.0.0"), 0)),
                "tester"), null);

        assertThat(result.kind()).isEqualTo("metadata");
        assertThat(result.rows()).isEqualTo(6);
        // 版本号在同类用例间单调推进（共享 H2 库），此处只断言非空
        assertThat(result.version()).isNotNull();
        assertThat(result.summary()).contains("Java 版本 2 个", "Boot 版本 1 个");

        MetadataView view = service.getMetadata();
        assertThat(view.javaVersions()).hasSize(2);
        assertThat(view.javaVersions().get(1).label()).isEqualTo("Java 21");
        assertThat(view.javaVersions().get(1).deprecated()).isTrue();
        assertThat(view.bootVersions()).hasSize(1);
        BootVersionDto boot = view.bootVersions().get(0);
        assertThat(boot.supportedJavaVersions()).containsExactly("17", "21");
        assertThat(boot.starters()).containsExactly("web");
        assertThat(boot.frameworkParent().artifactId()).isEqualTo("framework-parent");
        assertThat(boot.official()).isTrue();
    }

    @Test
    void metadata_draftsTolerateDanglingReferences_butPublishRejects() {
        // 草稿宽松：Boot 引用未定义 Java 版本也允许落库（边补边存）
        service.saveMetadata(new MetadataUpsertRequest(
                List.of(new JavaVersionDto("17", "Java 17", false, null, 0)),
                List.of(new BootVersionDto("3.2.5", null, true, List.of("ghost"), List.of(), null, 0)),
                "tester"), null);

        assertThat(service.getMetadata().bootVersions().get(0).supportedJavaVersions())
                .containsExactly("ghost");

        // 发布严格：装配期 4002 + 精确明细
        assertThatThrownBy(() -> configService.publish("tester", "dangling"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("ghost");
                });
    }

    @Test
    void metadata_duplicateIdAndIncompleteFrameworkParent_rejected() {
        assertThatThrownBy(() -> service.saveMetadata(new MetadataUpsertRequest(
                List.of(new JavaVersionDto("17", null, false, null, 0),
                        new JavaVersionDto("17", null, false, null, 1)),
                List.of(), "tester"), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("Java 版本 ID 重复"));

        assertThatThrownBy(() -> service.saveMetadata(new MetadataUpsertRequest(
                List.of(new JavaVersionDto("17", null, false, null, 0)),
                List.of(new BootVersionDto("3.2.5", null, true, List.of("17"), List.of(),
                        new FrameworkParentDto("g", null, "1"), 0)),
                "tester"), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("frameworkParent"));
    }

    @Test
    void metadata_replacesWholeModule_atomically() {
        service.saveMetadata(new MetadataUpsertRequest(
                List.of(new JavaVersionDto("17", null, false, null, 0)),
                List.of(new BootVersionDto("3.2.5", null, true, List.of("17"), List.of(), null, 0)),
                "tester"), null);
        // 二次保存整体替换：旧的 Java 21 与关联的兼容矩阵行一并消失（无孤儿行）
        service.saveMetadata(new MetadataUpsertRequest(
                List.of(new JavaVersionDto("21", null, false, null, 0)),
                List.of(new BootVersionDto("3.3.0", null, true, List.of("21"), List.of(), null, 0)),
                "tester"), null);

        MetadataView view = service.getMetadata();
        assertThat(view.javaVersions()).extracting(JavaVersionDto::id).containsExactly("21");
        assertThat(view.bootVersions()).extracting(BootVersionDto::id).containsExactly("3.3.0");
        assertThat(view.bootVersions().get(0).supportedJavaVersions()).containsExactly("21");
    }

    // ==================== starter 清单 ====================

    @Test
    void starters_roundTrip_allAttributes() {
        service.saveStarters(new StarterCatalogUpsertRequest(List.of(new StarterUpsertDto(
                "web", "web", "official", "Spring Web", true, true, "${basePackage}.config",
                List.of(new DependencyDto("org.springframework.boot", "spring-boot-starter-web", null)),
                List.of(List.of("validation"), List.of("a", "b")),
                List.of("3.2.5"),
                new LinkageDto(
                        List.of(new TemplateLinkageDto("a.ftl", "b.ftl")),
                        List.of(new YmlFragmentDto("app.yml", "append")),
                        List.of(new AbstractClassDto("com.example.Base", "Base"))),
                0)), "tester"), null);

        StarterDto dto = service.getStarters().starters().get(0);
        assertThat(dto.id()).isEqualTo("web");
        assertThat(dto.required()).isTrue();
        // PR #40 的 hidden 必须端到端保留（T3 结构化端点是权威写入点）
        assertThat(dto.hidden()).isTrue();
        assertThat(dto.configPackage()).isEqualTo("${basePackage}.config");
        assertThat(dto.dependencies().get(0).artifactId()).isEqualTo("spring-boot-starter-web");
        assertThat(dto.dependencies().get(0).version()).isNull();
        assertThat(dto.requires()).containsExactly(List.of("validation"), List.of("a", "b"));
        assertThat(dto.bootVersions()).containsExactly("3.2.5");
        assertThat(dto.linkages().templates()).hasSize(1);
        assertThat(dto.linkages().ymlFragments().get(0).mergeStrategy()).isEqualTo("APPEND");
        assertThat(dto.linkages().abstractClasses().get(0).alias()).isEqualTo("Base");
    }

    @Test
    void starters_manualWriteDoesNotTouchSyncChannel() {
        service.saveStarters(new StarterCatalogUpsertRequest(List.of(starter("sync-only")),
                "repo"), null, ConfigKind.STARTER_SYNC);
        service.saveStarters(new StarterCatalogUpsertRequest(List.of(starter("manual-only")),
                "tester"), null);

        assertThat(service.getStarters().starters()).extracting(StarterDto::id)
                .containsExactly("manual-only");
        // 手工通道写入后同步层行仍在（两通道隔离）
        assertThat(repository.readDraftScope(ConfigKind.STARTER_SYNC).starters()).hasSize(1);

        // 手工清单再次整体替换，同步层零改动
        service.saveStarters(new StarterCatalogUpsertRequest(List.of(starter("manual-2")),
                "tester"), null);
        assertThat(repository.readDraftScope(ConfigKind.STARTER_SYNC).starters())
                .extracting("starterId").containsExactly("sync-only");
    }

    @Test
    void starters_duplicateIdAndMissingFields_rejected() {
        assertThatThrownBy(() -> service.saveStarters(new StarterCatalogUpsertRequest(
                List.of(starter("dup"), starter("dup")), "tester"), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("starter ID 重复"));

        assertThatThrownBy(() -> service.saveStarters(new StarterCatalogUpsertRequest(
                List.of(new StarterUpsertDto("x", null, "official", null, false, false, null,
                        List.of(), List.of(), List.of(), null, 0)), "tester"), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("category"));

        assertThatThrownBy(() -> service.saveStarters(new StarterCatalogUpsertRequest(
                List.of(starter("x")), null), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("operator"));

        assertThatThrownBy(() -> service.saveTemplates(
                new TemplateCatalogUpsertRequest(List.of(), " "), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("operator"));

        assertThatThrownBy(() -> service.deleteTemplate("a.ftl", null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("operator"));

        assertThatThrownBy(() -> service.saveTemplate(
                new TemplateUpsertRequest("a.ftl", "x", null, null), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("operator"));

        assertThatThrownBy(() -> service.saveConflictRules(null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("缺少请求体"));

        assertThatThrownBy(() -> service.saveConflictRules(
                new ConflictRuleUpsertRequest(List.of(), null), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("operator"));

        assertThatThrownBy(() -> service.saveMetadata(new MetadataUpsertRequest(
                List.of(), List.of(), null), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("operator"));
    }

    // ==================== 冲突规则 ====================

    @Test
    void conflictRules_roundTrip_andTypeValidation() {
        service.saveConflictRules(new ConflictRuleUpsertRequest(List.of(
                new ConflictRuleDto("r1", "PAIR", List.of("a", "b"), "二者互斥", 0),
                new ConflictRuleDto("r2", "group", List.of("c", "d", "e"), "组内互斥", 1)),
                "tester"), null);

        var view = service.getConflictRules();
        assertThat(view.rules()).hasSize(2);
        assertThat(view.rules().get(0).type()).isEqualTo("pair");
        assertThat(view.rules().get(0).starters()).containsExactly("a", "b");
        assertThat(view.rules().get(1).type()).isEqualTo("group");
        assertThat(view.rules().get(1).starters()).containsExactly("c", "d", "e");

        assertThatThrownBy(() -> service.saveConflictRules(new ConflictRuleUpsertRequest(
                List.of(new ConflictRuleDto("r", "unknown", List.of("a"), "m", 0)), "tester"), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("类型非法"));
    }

    // ==================== 模板（CLOB 隔离） ====================

    @Test
    void templates_listDoesNotLoadContent_andDetailLoadsOnDemand() {
        service.saveTemplates(new TemplateCatalogUpsertRequest(List.of(
                new TemplateDetail("pom.ftl", "<project/>", null, 0),
                new TemplateDetail("App.java.ftl", "class App {}", null, 1)), "tester"), null);

        var list = service.listTemplates();
        assertThat(list.templates()).extracting("name").containsExactly("pom.ftl", "App.java.ftl");

        // 列表查询不搬 CLOB：仓库层摘要查询返回的 content 恒为 null
        List<CfgTemplateEntity> summaries = repository.listDraftTemplateSummaries();
        assertThat(summaries).isNotEmpty();
        assertThat(summaries).allSatisfy(row -> assertThat(row.getContent()).isNull());

        var detail = service.getTemplate("App.java.ftl");
        assertThat(detail.content()).isEqualTo("class App {}");

        assertThatThrownBy(() -> service.getTemplate("missing.ftl"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_DRAFT_NOT_FOUND));
    }

    @Test
    void templates_upsertAndDeleteByName_preserveOthers() {
        service.saveTemplates(new TemplateCatalogUpsertRequest(List.of(
                new TemplateDetail("a.ftl", "A", null, 0),
                new TemplateDetail("b.ftl", "B", null, 1)), "tester"), null);

        DraftSaveResult updated = service.saveTemplate(
                new TemplateUpsertRequest("a.ftl", "A2", "tester", null), null);
        assertThat(updated.kind()).isEqualTo("template");
        assertThat(updated.rows()).isEqualTo(2);
        assertThat(updated.summary()).isEqualTo("模板 2 个");
        var list = service.listTemplates().templates();
        assertThat(list).hasSize(2);
        assertThat(service.getTemplate("a.ftl").content()).isEqualTo("A2");
        assertThat(service.getTemplate("b.ftl").content()).isEqualTo("B");

        // 新增第三个模板：追加至末尾
        service.saveTemplate(new TemplateUpsertRequest("c.ftl", "C", "tester", null), null);
        assertThat(service.listTemplates().templates()).extracting("name")
                .containsExactly("a.ftl", "b.ftl", "c.ftl");

        DraftSaveResult deleted = service.deleteTemplate("b.ftl", "tester", null);
        assertThat(deleted.rows()).isEqualTo(2);
        assertThat(service.listTemplates().templates()).extracting("name")
                .containsExactly("a.ftl", "c.ftl");

        assertThatThrownBy(() -> service.deleteTemplate("nope.ftl", "tester", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_DRAFT_NOT_FOUND));
    }

    @Test
    void templates_oversizedContentRejected() {
        String huge = "x".repeat(StructuredDraftService.MAX_TEMPLATE_LENGTH + 1);
        assertThatThrownBy(() -> service.saveTemplate(
                new TemplateUpsertRequest("big.ftl", huge, "tester", null), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("内容超长"));
    }

    // ==================== 乐观锁与审计 ====================

    @Test
    void optimisticLock_staleIfMatchRejectedAndVersionAdvances() {
        int base = repository.findDraftMeta(ConfigKind.METADATA).getVersion();
        DraftSaveResult first = service.saveMetadata(new MetadataUpsertRequest(
                List.of(new JavaVersionDto("17", null, false, null, 0)),
                List.of(new BootVersionDto("3.2.5", null, true, List.of("17"), List.of(), null, 0)),
                "tester"), base);
        int version = first.version();
        assertThat(version).isEqualTo(base + 1);

        DraftSaveResult second = service.saveMetadata(new MetadataUpsertRequest(
                List.of(new JavaVersionDto("17", null, false, null, 0)),
                List.of(new BootVersionDto("3.2.5", null, true, List.of("17"), List.of(), null, 0)),
                "tester"), version);
        assertThat(second.version()).isEqualTo(version + 1);

        assertThatThrownBy(() -> service.saveMetadata(new MetadataUpsertRequest(
                List.of(new JavaVersionDto("17", null, false, null, 0)),
                List.of(new BootVersionDto("3.2.5", null, true, List.of("17"), List.of(), null, 0)),
                "tester"), version))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_CONCURRENT_MODIFY));

        // 携带与当前 version 不符的 If-Match → 4003（kind 级乐观锁拦截交叉修改）
        assertThatThrownBy(() -> service.saveTemplates(new TemplateCatalogUpsertRequest(
                List.of(new TemplateDetail("a.ftl", "A", null, 0)), "tester"), 9999))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_CONCURRENT_MODIFY));
    }

    @Test
    void audit_recordsScaleWithoutTemplateContent() {
        service.saveTemplates(new TemplateCatalogUpsertRequest(
                List.of(new TemplateDetail("secret.ftl", "SECRET-TOKEN-VALUE", null, 0)), "tester"), null);
        service.saveStarters(new StarterCatalogUpsertRequest(List.of(starter("web")), "tester"), null);

        var audits = repository.listAudits().stream()
                .filter(a -> StructuredDraftService.ACTION_UPDATE_STRUCTURED.equals(a.getAction()))
                .toList();
        assertThat(audits).isNotEmpty();
        assertThat(audits).allSatisfy(a -> assertThat(a.getDetail()).doesNotContain("SECRET-TOKEN-VALUE"));
        assertThat(audits.stream().map(a -> a.getDetail()).toList())
                .anyMatch(d -> d.contains("模板 1 个"))
                .anyMatch(d -> d.contains("starter 1 项"));
    }

    // ==================== 与 YAML 契约同源 ====================

    @Test
    void structuredWrite_isVisibleToYamlDraftContract_andViceVersa() {
        // 结构化写入 → YAML 读同源
        service.saveStarters(new StarterCatalogUpsertRequest(List.of(starter("web")), "tester"), null);
        assertThat(configService.getDraft(ConfigKind.STARTER).contentYaml()).contains("id: web");

        // YAML 写入 → 结构化读同源
        configService.saveDraft(ConfigKind.STARTER, """
                id: data-jpa
                category: data
                maintainer: official
                description: JPA
                dependencies:
                  - groupId: org.springframework.boot
                    artifactId: spring-boot-starter-data-jpa
                linkages: {}
                """, null, "tester");
        assertThat(service.getStarters().starters()).extracting(StarterDto::id)
                .containsExactly("data-jpa");

        // 版本（乐观锁）在两条契约间共享同一行
        assertThat(service.saveStarters(new StarterCatalogUpsertRequest(
                List.of(starter("x")), "tester"),
                configService.getDraft(ConfigKind.STARTER).version()).version())
                .isEqualTo(configService.getDraft(ConfigKind.STARTER).version());
    }

    // ==================== 边界与规模保护 ====================

    @Test
    void emptyLists_clearModuleDraft() {
        service.saveConflictRules(new ConflictRuleUpsertRequest(List.of(
                new ConflictRuleDto("r", "pair", List.of("a", "b"), "m", 0)), "tester"), null);
        assertThat(service.getConflictRules().rules()).hasSize(1);

        // 空清单 = 清空该模块草稿（整体替换语义）
        DraftSaveResult cleared = service.saveConflictRules(
                new ConflictRuleUpsertRequest(List.of(), "tester"), null);
        assertThat(cleared.rows()).isZero();
        assertThat(service.getConflictRules().rules()).isEmpty();

        // metadata / starter / template 同理
        assertThat(service.saveMetadata(new MetadataUpsertRequest(List.of(), List.of(), "tester"), null)
                .rows()).isZero();
        assertThat(service.getMetadata().javaVersions()).isEmpty();
        assertThat(service.saveStarters(new StarterCatalogUpsertRequest(List.of(), "tester"), null)
                .rows()).isZero();
        assertThat(service.getStarters().starters()).isEmpty();
        assertThat(service.saveTemplates(new TemplateCatalogUpsertRequest(List.of(), "tester"), null)
                .rows()).isZero();
    }

    @Test
    void itemCountLimit_rejected() {
        List<StarterUpsertDto> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i <= StructuredDraftService.MAX_ITEMS; i++) {
            tooMany.add(starter("s" + i));
        }
        assertThatThrownBy(() -> service.saveStarters(
                new StarterCatalogUpsertRequest(tooMany, "tester"), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("条目数超限"));
    }

    @Test
    void rowCountLimit_rejected() {
        // 单 starter 挂超过 MAX_ROWS 的子行（依赖）→ 结构行数超限
        List<DependencyDto> deps = new java.util.ArrayList<>();
        for (int i = 0; i <= StructuredDraftService.MAX_ROWS; i++) {
            deps.add(new DependencyDto("g", "a" + i, null));
        }
        StarterUpsertDto big = new StarterUpsertDto("web", "web", "official", "d", false, false, null,
                deps, List.of(), List.of(), null, 0);
        assertThatThrownBy(() -> service.saveStarters(
                new StarterCatalogUpsertRequest(List.of(big), "tester"), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("结构行数超限"));
    }

    @Test
    void templateMissingNameOrContent_rejected() {
        assertThatThrownBy(() -> service.saveTemplates(new TemplateCatalogUpsertRequest(
                List.of(new TemplateDetail(null, "x", null, 0)), "tester"), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("name"));
        assertThatThrownBy(() -> service.saveTemplates(new TemplateCatalogUpsertRequest(
                List.of(new TemplateDetail("a.ftl", "  ", null, 0)), "tester"), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("content"));
        assertThatThrownBy(() -> service.saveTemplates(new TemplateCatalogUpsertRequest(
                List.of(new TemplateDetail("a.ftl", "x", null, 0),
                        new TemplateDetail("a.ftl", "y", null, 1)), "tester"), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("模板名称重复"));
    }

    @Test
    void starterRequireGroups_skipEmptyGroupsAndBlankFieldsRejected() {
        service.saveStarters(new StarterCatalogUpsertRequest(List.of(new StarterUpsertDto(
                "web", "web", "official", "d", false, false, null,
                List.of(), List.of(List.of(), List.of("a")), List.of(), null, 0)), "tester"), null);
        // 空组被跳过，仅保留非空组
        assertThat(service.getStarters().starters().get(0).requires())
                .containsExactly(List.of("a"));

        assertThatThrownBy(() -> service.saveStarters(new StarterCatalogUpsertRequest(List.of(
                new StarterUpsertDto("x", " ", "official", "d", false, false, null,
                        List.of(), List.of(), List.of(), null, 0)), "tester"), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("category"));
    }

    @Test
    void metadataBootWithoutStarterWhitelist_roundTrips() {
        service.saveMetadata(new MetadataUpsertRequest(
                List.of(new JavaVersionDto("17", null, false, null, null)),
                List.of(new BootVersionDto("3.2.5", null, false, null, null, null, null),
                        new BootVersionDto("3.3.0", "自定义", true, List.of("17"), null, null, 5)),
                "tester"), null);
        MetadataView view = service.getMetadata();
        assertThat(view.bootVersions()).extracting(BootVersionDto::id)
                .containsExactlyInAnyOrder("3.2.5", "3.3.0");
        assertThat(view.bootVersions()).filteredOn(b -> "3.2.5".equals(b.id()))
                .allSatisfy(b -> {
                    assertThat(b.official()).isFalse();
                    assertThat(b.starters()).isEmpty();
                });
        assertThat(view.javaVersions().get(0).deprecated()).isFalse();
    }

    /**
     * 构造最小合法 starter 项。
     *
     * @param id starter ID
     * @return starter 提交项
     */
    private static StarterUpsertDto starter(String id) {
        return new StarterUpsertDto(id, "web", "official", "desc", false, false, null,
                List.of(), List.of(), List.of(), null, 0);
    }
}
