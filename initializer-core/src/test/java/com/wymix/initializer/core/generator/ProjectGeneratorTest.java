package com.wymix.initializer.core.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProjectGenerator 快照测试：固定输入 → 产物文件树断言，防模板回归。
 */
class ProjectGeneratorTest {

    private static ProjectGenerator generator;

    @BeforeAll
    static void setUp() {
        try (InputStream in = ProjectGeneratorTest.class.getResourceAsStream("/catalog/metadata.yml")) {
            // starter 元信息来自清单：装载 metadata 后联动合并
            MetadataCatalog catalog = MetadataCatalog.load(in).merge(testStarterCatalog());
            generator = new ProjectGenerator(catalog, ClasspathTemplates.renderer(), testStarterCatalog());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 测试用 starter 清单：复用主资源全部清单（starter 元信息唯一数据源，联动渲染断言用）。 */
    private static com.wymix.initializer.core.starter.StarterCatalog testStarterCatalog() throws Exception {
        List<com.wymix.initializer.core.starter.StarterCatalogEntry> entries = new java.util.ArrayList<>();
        for (String name : List.of("web-starter.yml", "data-jpa-starter.yml", "data-redis-starter.yml",
                "actuator-starter.yml", "xxx-redis-spring-boot-starter.yml")) {
            try (InputStream in = ProjectGeneratorTest.class.getResourceAsStream("/catalog/starters/" + name)) {
                entries.add(com.wymix.initializer.core.starter.YamlStarterParser.parse(in));
            }
        }
        return com.wymix.initializer.core.starter.StarterCatalog.of(entries);
    }

    /**
     * 合成 starter 清单（starter 元信息唯一数据源）：web 官方、framework-core 必选、
     * framework-dal 依赖 framework-core。
     */
    private static com.wymix.initializer.core.starter.StarterCatalog syntheticCatalog() {
        return com.wymix.initializer.core.starter.StarterCatalog.of(List.of(
                new com.wymix.initializer.core.starter.StarterCatalogEntry("web", "web",
                        com.wymix.initializer.core.starter.Maintainer.OFFICIAL, "Spring Web",
                        List.of(new com.wymix.initializer.core.starter.DependencyCoord(
                                "org.springframework.boot", "spring-boot-starter-web", null)),
                        new com.wymix.initializer.core.starter.LinkageSpec(null, List.of(), List.of(), List.of()),
                        false, List.of()),
                new com.wymix.initializer.core.starter.StarterCatalogEntry("framework-core", "tool",
                        com.wymix.initializer.core.starter.Maintainer.INTERNAL, "框架核心",
                        List.of(new com.wymix.initializer.core.starter.DependencyCoord(
                                "com.company", "framework-core", null)),
                        new com.wymix.initializer.core.starter.LinkageSpec(null, List.of(), List.of(), List.of()),
                        true, List.of()),
                new com.wymix.initializer.core.starter.StarterCatalogEntry("framework-dal", "data",
                        com.wymix.initializer.core.starter.Maintainer.INTERNAL, "数据访问",
                        List.of(new com.wymix.initializer.core.starter.DependencyCoord(
                                "com.company", "framework-dal", null)),
                        new com.wymix.initializer.core.starter.LinkageSpec(null, List.of(), List.of(), List.of()),
                        false, List.of("framework-core"))));
    }

    private static GenerateRequest request(List<String> starters) {
        return new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "17", "3.2.5", starters);
    }

    private static byte[] generate(List<String> starters) {
        return generator.generate(request(starters));
    }

    @Test
    void generatedZipShouldContainResolvedYamlFragmentParameters() throws Exception {
        Map<String, String> templates = new java.util.LinkedHashMap<>(ClasspathTemplates.load());
        templates.put("starters/xxx-redis/application-redis.yml", """
                framework:
                  name: ${artifact}
                  package: ${basePackage}
                  host: ${REDIS_HOST:localhost}
                """);
        MetadataCatalog catalog;
        try (InputStream in = getClass().getResourceAsStream("/catalog/metadata.yml")) {
            catalog = MetadataCatalog.load(in).merge(testStarterCatalog());
        }
        ProjectGenerator custom = new ProjectGenerator(catalog, new TemplateRenderer(templates), testStarterCatalog());
        byte[] bytes = custom.generate(request(List.of("xxx-redis-spring-boot-starter")));
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith("/application.yml")) {
                    Map<?, ?> framework = (Map<?, ?>) YamlFacade.parse(
                            new String(zip.readAllBytes(), StandardCharsets.UTF_8)).get("framework");
                    assertThat(framework.get("name")).isEqualTo("demo-app");
                    assertThat(framework.get("package")).isEqualTo("com.example.demo");
                    assertThat(framework.get("host")).isEqualTo("${REDIS_HOST:localhost}");
                    return;
                }
            }
        }
        throw new AssertionError("ZIP 中缺少 application.yml");
    }

    @Test
    void shouldGenerateZipWithExpectedFileTree() throws Exception {
        byte[] zipBytes = generate(List.of("web"));
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.List<String> names = new java.util.ArrayList<>();
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                names.add(e.getName());
            }
            assertThat(names).containsExactlyInAnyOrder(
                    "demo-app/pom.xml",
                    "demo-app/README.md",
                    "demo-app/src/main/resources/application.yml",
                    "demo-app/src/main/java/com/example/demo/DemoAppApplication.java");
        }
    }

    @Test
    void pomShouldContainBootVersionAndWebStarter() throws Exception {
        String pom = readEntry(generate(List.of("web")), "demo-app/pom.xml");
        assertThat(pom).contains("<version>3.2.5</version>")
                .contains("<java.version>17</java.version>")
                .contains("<groupId>com.example</groupId>")
                .contains("<artifactId>demo-app</artifactId>")
                .contains("spring-boot-starter-web")
                .doesNotContain("spring-boot-starter-data-jpa");
    }

    @Test
    void mainClassShouldFollowPackageAndNameConvention() throws Exception {
        String src = readEntry(generate(List.of("web")),
                "demo-app/src/main/java/com/example/demo/DemoAppApplication.java");
        assertThat(src).contains("package com.example.demo;")
                .contains("public class DemoAppApplication")
                .contains("@SpringBootApplication");
    }

    @Test
    void applicationYmlShouldReferenceArtifact() throws Exception {
        String yml = readEntry(generate(List.of("web")), "demo-app/src/main/resources/application.yml");
        assertThat(yml).contains("name: demo-app").contains("port: 8080");
    }

    @Test
    void readmeShouldContainMetadataAndStarterList() throws Exception {
        String readme = readEntry(generate(List.of("web", "data-redis")), "demo-app/README.md");
        assertThat(readme).contains("com.example", "demo-app", "com.example.demo", "17", "3.2.5")
                .contains("`web`", "`data-redis`");
    }

    @Test
    void startersConditionalsShouldToggleDependencies() throws Exception {
        String pom = readEntry(generate(List.of("actuator", "data-jpa")), "demo-app/pom.xml");
        assertThat(pom).contains("spring-boot-starter-actuator")
                .contains("spring-boot-starter-data-jpa")
                .doesNotContain("spring-boot-starter-web");
    }

    @Test
    void incompatibleJavaBootCombinationShouldThrow2001() {
        GenerateRequest bad = new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "8", "3.2.5", List.of("web"));
        assertThatThrownBy(() -> generator.generate(bad))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INCOMPATIBLE_VERSION));
    }

    @Test
    void sameCoordAcrossSourcesShouldDeduplicate() throws Exception {
        // 评审修复：web 同时登记在 starter 清单（catalog/starters/web-starter.yml）
        // 与 metadata.yml starters 节，同 G:A 坐标跨来源必须显式去重且清单声明优先（保留 version）
        String pom = readEntry(generate(List.of("web")), "demo-app/pom.xml");
        assertThat(countOccurrences(pom, "spring-boot-starter-web</artifactId>"))
                .as("同坐标跨来源只输出一次").isEqualTo(1);
        // 清单声明优先：依赖坐标保留 version 透传（Gradle GAV），Maven pom 模板忽略 version 走 Boot BOM
        String gradle = readEntry(generator.generate(gradleRequest(List.of("web"))), "demo-app/build.gradle");
        assertThat(gradle).contains("org.springframework.boot:spring-boot-starter-web");
        long gavLines = gradle.lines().filter(l -> l.contains("spring-boot-starter-web")).count();
        assertThat(gavLines).as("gradle 中 spring-boot-starter-web 仅一条 implementation").isEqualTo(1);
    }

    /** 统计子串出现次数。 */
    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void unknownBootVersionShouldThrow2002() {
        GenerateRequest bad = new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "17", "9.9.9", List.of("web"));
        assertThatThrownBy(() -> generator.generate(bad))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
    }

    @Test
    void unknownStarterShouldThrow2003() {
        // 2003 UNKNOWN_STARTER：starter 不在所选 Boot 版本清单（与 2002 元数据缺失区分）
        GenerateRequest bad = new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "17", "3.2.5", List.of("nosuch"));
        assertThatThrownBy(() -> generator.generate(bad))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNKNOWN_STARTER));
    }

    @Test
    void streamingGenerateShouldWriteSameZipBytes() throws Exception {
        // 流式输出与内存输出产物一致，且先校验后写流：失败时不写任何字节
        byte[] buffered = generate(List.of("web"));
        java.io.ByteArrayOutputStream streamed = new java.io.ByteArrayOutputStream();
        generator.generate(request(List.of("web")), streamed);
        assertThat(streamed.toByteArray()).isEqualTo(buffered);
    }

    @Test
    void streamingGenerateFailureShouldWriteNothing() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        GenerateRequest bad = new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "8", "3.2.5", List.of("web"));
        assertThatThrownBy(() -> generator.generate(bad, out))
                .isInstanceOf(BusinessException.class);
        assertThat(out.size()).as("校验失败时输出流不应被写入").isZero();
    }

    @Test
    void zipFileNameShouldFollowConvention() {
        assertThat(ProjectGenerator.zipFileName(request(List.of()))).isEqualTo("demo-app.zip");
    }

    @Test
    void mainClassNameShouldConvertKebabCase() {
        assertThat(ProjectGenerator.mainClassName("demo-app")).isEqualTo("DemoAppApplication");
        assertThat(ProjectGenerator.mainClassName("app")).isEqualTo("AppApplication");
        assertThat(ProjectGenerator.mainClassName("a-b-c")).isEqualTo("ABCApplication");
    }

    @Test
    void generateRequestShouldNormalizeNullStarters() {
        GenerateRequest req = new GenerateRequest("com.example", "demo", "com.example.demo",
                "17", "3.2.5", null);
        assertThat(req.starters()).isEmpty();
    }

    @Test
    void linkageStarterShouldRenderConfigClassAndMergeYml() throws Exception {
        // M2.3 联动管线：勾选内部 redis starter → 配置类 + application.yml 片段合并 + 依赖坐标
        byte[] zip = generate(List.of("web", "xxx-redis-spring-boot-starter"));
        String config = readEntry(zip,
                "demo-app/src/main/java/com/example/demo/config/RedisConfig.java");
        assertThat(config).contains("package com.example.demo.config;")
                .contains("RedisSupport").contains("@Configuration");
        String yml = readEntry(zip, "demo-app/src/main/resources/application.yml");
        assertThat(yml).contains("redis").contains("${REDIS_HOST:localhost}")
                .contains("name: demo-app");
        String pom = readEntry(zip, "demo-app/pom.xml");
        assertThat(pom).contains("xxx-redis-spring-boot-starter").contains("com.company")
                .contains("spring-boot-starter-web");
    }

    private static GenerateRequest gradleRequest(List<String> starters) {
        return GenerateRequest.of("com.example", "demo-app", "com.example.demo",
                "17", "3.2.5", starters, "gradle");
    }

    @Test
    void gradleRequestShouldRenderBuildAndSettingsGradle() throws Exception {
        // M2.4：gradle 请求产出 build.gradle + settings.gradle，且不含 pom.xml
        byte[] zip = generator.generate(gradleRequest(List.of("web")));
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            java.util.List<String> names = new java.util.ArrayList<>();
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                names.add(e.getName());
            }
            assertThat(names).contains("demo-app/build.gradle", "demo-app/settings.gradle")
                    .doesNotContain("demo-app/pom.xml");
        }
    }

    @Test
    void gradleBuildScriptShouldContainPluginsAndDependencies() throws Exception {
        byte[] zip = generator.generate(gradleRequest(List.of("web", "xxx-redis-spring-boot-starter")));
        String build = readEntry(zip, "demo-app/build.gradle");
        assertThat(build).contains("id 'org.springframework.boot' version '3.2.5'")
                .contains("group = 'com.example'")
                .contains("sourceCompatibility = JavaVersion.toVersion('17')")
                .contains("implementation 'org.springframework.boot:spring-boot-starter-web'")
                .contains("implementation 'com.company:xxx-redis-spring-boot-starter:1.2.0'")
                .doesNotContain("testImplementation")
                .doesNotContain("<dependency>");
    }

    @Test
    void gradleSettingsShouldContainRootProjectName() throws Exception {
        byte[] zip = generator.generate(gradleRequest(List.of("web")));
        String settings = readEntry(zip, "demo-app/settings.gradle");
        assertThat(settings).contains("rootProject.name = 'demo-app'");
    }

    @Test
    void gradleRequestShouldKeepLinkagePipelineOutputs() throws Exception {
        // M2.4：构建脚本双轨不影响联动管线——配置类 + yml 合并产物一致
        byte[] zip = generator.generate(gradleRequest(List.of("web", "xxx-redis-spring-boot-starter")));
        String config = readEntry(zip, "demo-app/src/main/java/com/example/demo/config/RedisConfig.java");
        assertThat(config).contains("package com.example.demo.config;").contains("RedisSupport");
        String yml = readEntry(zip, "demo-app/src/main/resources/application.yml");
        assertThat(yml).contains("redis").contains("name: demo-app");
    }

    @Test
    void invalidBuildToolShouldThrow1004() {
        // M2.4：buildTool 非法取值 → 1004
        assertThatThrownBy(() -> GenerateRequest.of("com.example", "demo-app", "com.example.demo",
                "17", "3.2.5", List.of("web"), "buck"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_BUILD_TOOL));
    }

    @Test
    void legacyRequestShouldDefaultToMaven() throws Exception {
        // M2.4：6 参兼容构造器默认 maven，产物保持 pom.xml
        byte[] zip = generate(List.of("web"));
        assertThat(readEntry(zip, "demo-app/pom.xml")).contains("<project");
    }

    @Test
    void requiredStartersShouldBeAutoIncluded() throws Exception {
        // M3.6：starter 清单中 required: true 的 starter，请求未携带时自动补齐（不报错）
        String yaml = """
                javaVersions:
                  - id: "17"
                    label: "Java 17"
                    recommendedBootVersion: "3.2.5"
                bootVersions:
                  - id: "3.2.5"
                    label: "Spring Boot 3.2.5"
                    official: true
                    supportedJavaVersions: ["17"]
                    starters: ["web", "framework-core"]
                """;
        MetadataCatalog catalog = MetadataCatalog.load(
                new java.io.ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                .merge(syntheticCatalog());
        ProjectGenerator gen = new ProjectGenerator(catalog, ClasspathTemplates.renderer(), syntheticCatalog());
        assertThat(catalog.requiredStarters("3.2.5")).containsExactly("framework-core");

        byte[] zip = gen.generate(new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "17", "3.2.5", List.of("web")));
        String pom = readEntry(zip, "demo-app/pom.xml");
        assertThat(pom).contains("spring-boot-starter-web").contains("framework-core");
    }

    @Test
    void requiresShouldBeAutoIncludedAndNonSelectedKeptOut() throws Exception {
        // M3.6：core 必选 + dal requires core；选 dal 自动带 core，不选 dal 则不引入 dal
        String yaml = """
                javaVersions:
                  - id: "17"
                    label: "Java 17"
                    recommendedBootVersion: "3.2.5"
                bootVersions:
                  - id: "3.2.5"
                    label: "Spring Boot 3.2.5"
                    official: true
                    supportedJavaVersions: ["17"]
                    starters: ["web", "framework-core", "framework-dal"]
                """;
        MetadataCatalog catalog = MetadataCatalog.load(
                new java.io.ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                .merge(syntheticCatalog());
        ProjectGenerator gen = new ProjectGenerator(catalog, ClasspathTemplates.renderer(), syntheticCatalog());

        // 只选 dal：依赖联动自动带出 core
        String pomWithDal = readEntry(gen.generate(new GenerateRequest("com.example", "demo-app",
                "com.example.demo", "17", "3.2.5", List.of("framework-dal"))), "demo-app/pom.xml");
        assertThat(pomWithDal).contains("framework-dal").contains("framework-core");

        // 只选 web：必选 core 在，未被选中的 dal 不在
        String pomWebOnly = readEntry(gen.generate(new GenerateRequest("com.example", "demo-app",
                "com.example.demo", "17", "3.2.5", List.of("web"))), "demo-app/pom.xml");
        assertThat(pomWebOnly).contains("spring-boot-starter-web").contains("framework-core")
                .doesNotContain("framework-dal");
    }

    // ==================== T5：模板 DB 唯一数据源 ====================

    @Test
    void validate_withMissingLinkageTemplate_throws3001BeforeWritingAnyByte() {
        // T5：starter 声明了联动模板而模板集缺失时，validate 必须在渲染前失败（不产出半成品）
        ProjectGenerator incomplete = new ProjectGenerator(
                catalogForValidation(), new TemplateRenderer(Map.of("application.yml.ftl", "x")),
                validationStarters());
        assertThatThrownBy(() -> incomplete.validate(new GenerateRequest("com.example", "demo-app",
                "com.example.demo", "17", "3.2.5", List.of("xxx-redis-spring-boot-starter"))))
                .isInstanceOfSatisfying(com.wymix.initializer.api.error.BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(com.wymix.initializer.api.error.ErrorCode.TEMPLATE_RENDER_FAILED);
                    assertThat(e.getMessage()).contains("starters/xxx-redis/RedisConfig.java.ftl");
                });
    }

    @Test
    void validate_withCompleteTemplateSet_passes() {
        // 完整模板集 → 校验通过（与生成共用同一口径）
        ProjectGenerator complete = new ProjectGenerator(catalogForValidation(),
                ClasspathTemplates.renderer(), validationStarters());
        complete.validate(new GenerateRequest("com.example", "demo-app",
                "com.example.demo", "17", "3.2.5", List.of("web")));
    }

    @Test
    void validate_unknownStarter_throws2002BeforeTemplateCheck() {
        // 版本/清单校验先于模板可达性：未知 starter 报 2002 而非 3001
        ProjectGenerator generator = new ProjectGenerator(catalogForValidation(),
                ClasspathTemplates.renderer(), validationStarters());
        assertThatThrownBy(() -> generator.validate(new GenerateRequest("com.example", "demo-app",
                "com.example.demo", "17", "3.2.5", List.of("ghost-starter"))))
                .isInstanceOfSatisfying(com.wymix.initializer.api.error.BusinessException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(com.wymix.initializer.api.error.ErrorCode.UNKNOWN_STARTER));
    }

    @Test
    void validate_incompatibleJavaVersion_throws2001BeforeTemplateCheck() {
        ProjectGenerator generator = new ProjectGenerator(catalogForValidation(),
                ClasspathTemplates.renderer(), validationStarters());
        assertThatThrownBy(() -> generator.validate(new GenerateRequest("com.example", "demo-app",
                "com.example.demo", "17", "2.7.18", List.of("web"))))
                .isInstanceOfSatisfying(com.wymix.initializer.api.error.BusinessException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(com.wymix.initializer.api.error.ErrorCode.INCOMPATIBLE_VERSION));
    }

    @Test
    void rendererTemplateSetAndLinkageTemplateSet_shouldStaySameInstance() {
        // T5：ProjectGenerator 未显式传模板集时，必须与渲染器共用同一份（否则联动渲染会漏模板）
        TemplateRenderer renderer = ClasspathTemplates.renderer();
        ProjectGenerator generator = new ProjectGenerator(catalogForValidation(), renderer,
                validationStarters());
        // 通过「缺模板的渲染器」反向验证共用关系：删掉 starter 联动模板后生成必失败
        assertThat(renderer.templates()).containsKey("starters/xxx-redis/RedisConfig.java.ftl");
        generator.validate(new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "17", "3.2.5", List.of("web")));
    }

    private static MetadataCatalog catalogForValidation() {
        try (InputStream in = ProjectGeneratorTest.class.getResourceAsStream("/catalog/metadata.yml")) {
            return MetadataCatalog.load(in).merge(validationStarters());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static com.wymix.initializer.core.starter.StarterCatalog validationStarters() {
        try (InputStream in = ProjectGeneratorTest.class.getResourceAsStream(
                "/catalog/starters/xxx-redis-spring-boot-starter.yml")) {
            return com.wymix.initializer.core.starter.StarterCatalog.of(List.of(
                    new com.wymix.initializer.core.starter.StarterCatalogEntry("web", "web",
                            com.wymix.initializer.core.starter.Maintainer.OFFICIAL, "Spring Web",
                            List.of(new com.wymix.initializer.core.starter.DependencyCoord(
                                    "org.springframework.boot", "spring-boot-starter-web", null)),
                            new com.wymix.initializer.core.starter.LinkageSpec(null, List.of(), List.of(), List.of()),
                            false, List.of()),
                    com.wymix.initializer.core.starter.YamlStarterParser.parse(in)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readEntry(byte[] zipBytes, String entryName) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("entry not found: " + entryName);
    }
}
