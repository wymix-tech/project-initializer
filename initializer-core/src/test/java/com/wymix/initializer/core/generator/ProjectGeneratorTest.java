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
    void unknownBootVersionShouldThrow2002() {
        GenerateRequest bad = new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "17", "9.9.9", List.of("web"));
        assertThatThrownBy(() -> generator.generate(bad))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
    }

    @Test
    void unknownStarterShouldThrow2002() {
        GenerateRequest bad = new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "17", "3.2.5", List.of("nosuch"));
        assertThatThrownBy(() -> generator.generate(bad))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
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
