package com.wymix.initializer.core.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import com.wymix.initializer.core.starter.YamlStarterParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2.5 联动端到端快照测试：以内部 {@code xxx-redis-e2e-spring-boot-starter} 为例，
 * 固定输入（group/artifact/包名/Java 17/Boot 3.2.5/starters=[web, 内部 redis]）生成完整项目，
 * 产物文件树与逐文件内容与 fixture 全量比对，防联动管线与模板回归。
 *
 * <p>双构建工具双快照：Maven 与 Gradle 各锁定一份 fixture（{@code snapshot-linkage/} 下），
 * 同一固定输入仅切换 buildTool——构建脚本分轨（pom.xml / build.gradle+settings.gradle），
 * 联动产物（RedisConfig 配置类 + application.yml 合并结果）保持一致。
 *
 * <p>快照 fixture 由真实引擎对固定输入渲染生成落盘；模板或生成逻辑变更导致快照失败时，
 * 若为有意变更则同步重新生成 fixture。
 *
 * <p>夹具说明：端到端专用 starter 与 metadata 夹具位于 {@code catalog/e2e/}（主资源不动，
 * 避免污染生产清单）；冲突负向用例共用同批夹具（同 target → 3002；yml 同 key 不同值 → 3004）。
 */
class LinkageEndToEndSnapshotTest {

    /** 快照 fixture 根目录（相对 initializer-core 测试工作目录）。 */
    private static final String SNAPSHOT_ROOT = "src/test/resources/snapshot-linkage";

    /** Maven 快照 fixture 目录名。 */
    private static final String MAVEN_SNAPSHOT = "maven";
    /** Gradle 快照 fixture 目录名。 */
    private static final String GRADLE_SNAPSHOT = "gradle";

    /** 快照固定输入：任何字段改动都会改变产物，必须同步更新 fixture。 */
    private static final String GROUP = "com.example";
    private static final String ARTIFACT = "redis-demo";
    private static final String BASE_PACKAGE = "com.example.redisdemo";
    private static final String JAVA_VERSION = "17";
    private static final String BOOT_VERSION = "3.2.5";
    private static final List<String> STARTERS =
            List.of("web", "xxx-redis-e2e-spring-boot-starter");

    /** 产物 zip 内顶层目录，即 artifactId。 */
    private static final String TOP_DIR = ARTIFACT + "/";

    private static ProjectGenerator generator;

    @BeforeAll
    static void setUp() {
        try (InputStream metadataIn = LinkageEndToEndSnapshotTest.class
                .getResourceAsStream("/catalog/e2e/metadata-e2e.yml");
             InputStream starterIn = LinkageEndToEndSnapshotTest.class
                .getResourceAsStream("/catalog/e2e/xxx-redis-e2e-spring-boot-starter.yml");
             InputStream anotherIn = LinkageEndToEndSnapshotTest.class
                .getResourceAsStream("/catalog/e2e/another-redis-e2e-spring-boot-starter.yml");
             InputStream conflictIn = LinkageEndToEndSnapshotTest.class
                .getResourceAsStream("/catalog/e2e/yml-conflict-e2e-starter.yml")) {
            MetadataCatalog catalog = MetadataCatalog.load(metadataIn);
            List<StarterCatalogEntry> entries = new ArrayList<>();
            entries.add(YamlStarterParser.parse(starterIn));
            entries.add(YamlStarterParser.parse(anotherIn));
            entries.add(YamlStarterParser.parse(conflictIn));
            generator = new ProjectGenerator(catalog, new TemplateRenderer(),
                    StarterCatalog.of(entries));
        } catch (Exception e) {
            throw new IllegalStateException("联动端到端快照测试环境初始化失败", e);
        }
    }

    private static GenerateRequest request(BuildTool buildTool) {
        return new GenerateRequest(GROUP, ARTIFACT, BASE_PACKAGE,
                JAVA_VERSION, BOOT_VERSION, STARTERS, buildTool);
    }

    @Test
    void mavenSnapshotShouldMatchFixtureFileTreeAndContents() throws Exception {
        byte[] zip = generator.generate(request(BuildTool.MAVEN));
        assertMatchesFixture(readZipEntries(zip), MAVEN_SNAPSHOT);
    }

    @Test
    void gradleSnapshotShouldMatchFixtureFileTreeAndContents() throws Exception {
        byte[] zip = generator.generate(request(BuildTool.GRADLE));
        assertMatchesFixture(readZipEntries(zip), GRADLE_SNAPSHOT);
    }

    @Test
    void buildToolSwitchShouldKeepLinkageOutputsIdentical() throws Exception {
        // M2.4 双轨不改变联动产物：除构建脚本（及 gradle 多出的 settings.gradle）外，文件树与内容一致
        Map<String, String> maven = readZipEntries(generator.generate(request(BuildTool.MAVEN)));
        Map<String, String> gradle = readZipEntries(generator.generate(request(BuildTool.GRADLE)));

        // 1. 构建脚本分轨：maven 产 pom.xml；gradle 产 build.gradle + settings.gradle
        assertThat(maven).containsKey("pom.xml").doesNotContainKeys("build.gradle", "settings.gradle");
        assertThat(gradle).containsKeys("build.gradle", "settings.gradle").doesNotContainKey("pom.xml");

        // 2. 构建脚本之外逐文件一致（联动渲染与构建工具无关）
        Map<String, String> mavenCommon = withoutBuildScripts(maven);
        Map<String, String> gradleCommon = withoutBuildScripts(gradle);
        assertThat(mavenCommon).as("除构建脚本外双轨产物文件树应一致")
                .containsExactlyInAnyOrderEntriesOf(gradleCommon);
    }

    @Test
    void generatedProjectShouldContainFullLinkageOutputs() throws Exception {
        // 端到端验收语义：starter 依赖坐标 + RedisConfig 配置类（引用集成抽象类）+ yml redis 连接占位
        Map<String, String> files = readZipEntries(generator.generate(request(BuildTool.MAVEN)));

        String config = files.get("src/main/java/com/example/redisdemo/config/RedisConfig.java");
        assertThat(config)
                .as("联动应生成 RedisConfig 配置类并引用 starter 集成抽象类")
                .contains("package com.example.redisdemo.config;")
                .contains("import com.company.redis.RedisSupport;")
                .contains("public class RedisConfig")
                .contains("@Configuration")
                .contains("public RedisSupport redisSupport()");

        String yml = files.get("src/main/resources/application.yml");
        assertThat(yml)
                .as("application.yml 应合并 redis 连接配置占位且保留基础配置")
                .contains("name: " + ARTIFACT)
                .contains("port: 8080")
                .contains("redis:")
                .contains("host: ${REDIS_HOST:localhost}")
                .contains("port: ${REDIS_PORT:6379}")
                .contains("password: ${REDIS_PASSWORD:}");

        assertThat(files.get("pom.xml"))
                .as("Maven 构建脚本应包含 starter 声明的依赖坐标与官方 web starter 依赖")
                .contains("<groupId>com.company</groupId>")
                .contains("<artifactId>xxx-redis-e2e-spring-boot-starter</artifactId>")
                .contains("<artifactId>spring-boot-starter-web</artifactId>");
        // Gradle 双轨校验单独断言（maven 产物不含 build.gradle）
        Map<String, String> gradleFiles = readZipEntries(
                generator.generate(request(BuildTool.GRADLE)));
        assertThat(gradleFiles.get("build.gradle"))
                .as("Gradle 构建脚本应包含 starter 依赖坐标（GAV 透传版本）")
                .contains("implementation 'com.company:xxx-redis-e2e-spring-boot-starter:1.2.0'");

    }
    @Test
    void duplicateTemplateTargetShouldThrow3002() {
        // 冲突负向用例：两个 starter 模板写同一路径 → 3002，显式失败不产出半成品
        GenerateRequest conflict = new GenerateRequest(GROUP, ARTIFACT, BASE_PACKAGE,
                JAVA_VERSION, BOOT_VERSION,
                List.of("xxx-redis-e2e-spring-boot-starter",
                        "another-redis-e2e-spring-boot-starter"),
                BuildTool.MAVEN);
        assertThatThrownBy(() -> generator.generate(conflict))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_TARGET_CONFLICT));
    }

    @Test
    void conflictingYmlScalarValuesShouldThrow3004() {
        // 冲突负向用例：多 starter yml 片段同 key 不同值 → 3004
        GenerateRequest conflict = new GenerateRequest(GROUP, ARTIFACT, BASE_PACKAGE,
                JAVA_VERSION, BOOT_VERSION,
                List.of("yml-conflict-e2e-starter", "xxx-redis-e2e-spring-boot-starter"),
                BuildTool.MAVEN);
        assertThatThrownBy(() -> generator.generate(conflict))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.YML_MERGE_CONFLICT));
    }

    /** 去除构建脚本条目（pom.xml / build.gradle / settings.gradle），仅保留联动与基础产物。 */
    private static Map<String, String> withoutBuildScripts(Map<String, String> files) {
        Map<String, String> result = new TreeMap<>(files);
        result.remove("pom.xml");
        result.remove("build.gradle");
        result.remove("settings.gradle");
        return result;
    }

    private static void assertMatchesFixture(Map<String, String> actual, String snapshotDir)
            throws Exception {
        List<String> expectedPaths = listFixturePaths(snapshotDir);
        assertThat(actual.keySet())
                .as("快照 %s：产物文件清单应与 fixture 完全一致", snapshotDir)
                .containsExactlyInAnyOrderElementsOf(expectedPaths);
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            String expected = readFixtureFile(snapshotDir, entry.getKey());
            assertThat(entry.getValue())
                    .as("快照 %s：文件 %s 内容应与 fixture 逐字节一致", snapshotDir, entry.getKey())
                    .isEqualTo(expected);
        }
    }

    private static List<String> listFixturePaths(String snapshotDir) throws Exception {
        List<String> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(fixtureRoot(snapshotDir))) {
            walk.filter(Files::isRegularFile)
                    .map(p -> fixtureRoot(snapshotDir).relativize(p).toString().replace('\\', '/'))
                    .forEach(paths::add);
        }
        assertThat(paths).as("fixture 目录不应为空: %s", snapshotDir).isNotEmpty();
        return paths;
    }

    private static String readFixtureFile(String snapshotDir, String relativePath) throws Exception {
        byte[] bytes = Files.readAllBytes(fixtureRoot(snapshotDir).resolve(relativePath));
        return normalize(new String(bytes, StandardCharsets.UTF_8));
    }

    private static Path fixtureRoot(String snapshotDir) {
        // 测试工作目录 = initializer-core/，fixture 在 src/test/resources 下
        return Paths.get(SNAPSHOT_ROOT).resolve(snapshotDir);
    }

    private static Map<String, String> readZipEntries(byte[] zip) throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                assertThat(name)
                        .as("产物 zip 内所有条目应位于顶层目录 %s 下", TOP_DIR)
                        .startsWith(TOP_DIR);
                files.put(name.substring(TOP_DIR.length()),
                        normalize(new String(zis.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        return files;
    }

    /** 行尾统一 LF，避免 checkout 配置差异（core.autocrlf）导致快照误报。 */
    private static String normalize(String content) {
        return content.replace("\r\n", "\n");
    }
}
