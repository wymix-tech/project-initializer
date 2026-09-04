package com.wymix.initializer.core.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
            MetadataCatalog catalog = MetadataCatalog.load(in);
            generator = new ProjectGenerator(catalog, new TemplateRenderer(), testStarterCatalog());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 测试用 starter 清单：官方 web + 内部 xxx-redis（复用主资源，联动渲染断言用）。 */
    private static com.wymix.initializer.core.starter.StarterCatalog testStarterCatalog() throws Exception {
        List<com.wymix.initializer.core.starter.StarterCatalogEntry> entries = new java.util.ArrayList<>();
        for (String name : List.of("web-starter.yml", "xxx-redis-spring-boot-starter.yml")) {
            try (InputStream in = ProjectGeneratorTest.class.getResourceAsStream("/catalog/starters/" + name)) {
                entries.add(com.wymix.initializer.core.starter.YamlStarterParser.parse(in));
            }
        }
        return com.wymix.initializer.core.starter.StarterCatalog.of(entries);
    }

    private static GenerateRequest request(List<String> starters) {
        return new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "17", "3.2.5", starters);
    }

    private static byte[] generate(List<String> starters) {
        return generator.generate(request(starters));
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
                .contains("testImplementation 'org.springframework.boot:spring-boot-starter-test'")
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
