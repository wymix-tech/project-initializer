package com.wymix.initializer.core.generator;

import com.wymix.initializer.core.metadata.MetadataCatalog;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1.6 模板快照测试：固定输入 → 产物文件树 + 文件内容与 fixture 全量比对，防模板回归。
 *
 * <p>fixture 位于 {@code src/test/resources/snapshot/demo-app/}，由真实引擎对固定输入
 * （group=com.example / artifact=demo-app / java=17 / boot=3.2.5 / starters=[web, data-redis]）
 * 渲染生成。模板或生成逻辑变更导致产物变化时，本测试失败并给出 diff 定位；
 * 变更有意时同步更新 fixture 即可（重新生成落盘）。
 */
class TemplateSnapshotTest {

    /** 快照 fixture classpath 根目录。 */
    private static final String SNAPSHOT_ROOT = "snapshot/demo-app";

    /** 产物 zip 内顶层目录，即 artifactId。 */
    private static final String TOP_DIR = "demo-app/";

    /** 快照固定输入：任何字段改动都会改变产物，必须同步更新 fixture。 */
    private static final GenerateRequest SNAPSHOT_REQUEST = new GenerateRequest(
            "com.example", "demo-app", "com.example.demo",
            "17", "3.2.5", List.of("web", "data-redis"));

    private static ProjectGenerator generator;

    @BeforeAll
    static void setUp() {
        try (InputStream in = TemplateSnapshotTest.class.getResourceAsStream("/catalog/metadata.yml")) {
            com.wymix.initializer.core.starter.StarterCatalog starterCatalog = snapshotStarterCatalog();
            // starter 元信息来自清单：装载 metadata 后联动合并
            MetadataCatalog catalog = MetadataCatalog.load(in).merge(starterCatalog);
            generator = new ProjectGenerator(catalog, new TemplateRenderer(), starterCatalog);
        } catch (Exception e) {
            throw new IllegalStateException("快照测试环境初始化失败", e);
        }
    }

    /** 快照固定输入用到的 starter（web / data-redis）清单。 */
    private static com.wymix.initializer.core.starter.StarterCatalog snapshotStarterCatalog() throws Exception {
        List<com.wymix.initializer.core.starter.StarterCatalogEntry> entries = new java.util.ArrayList<>();
        for (String name : List.of("web-starter.yml", "data-redis-starter.yml")) {
            try (InputStream in = TemplateSnapshotTest.class.getResourceAsStream("/catalog/starters/" + name)) {
                entries.add(com.wymix.initializer.core.starter.YamlStarterParser.parse(in));
            }
        }
        return com.wymix.initializer.core.starter.StarterCatalog.of(entries);
    }

    @Test
    void generatedFileTreeShouldMatchSnapshotFixture() throws Exception {
        byte[] zip = generator.generate(SNAPSHOT_REQUEST);
        List<String> actualPaths = readZipEntryPaths(zip);
        List<String> expectedPaths = listFixturePaths();

        assertThat(actualPaths)
                .as("产物文件树应与 fixture 完全一致（顺序无关）")
                .containsExactlyInAnyOrderElementsOf(expectedPaths);
    }

    @Test
    void generatedFileContentsShouldMatchSnapshotFixture() throws Exception {
        byte[] zip = generator.generate(SNAPSHOT_REQUEST);
        Map<String, String> actual = readZipEntries(zip);

        assertThat(actual.keySet())
                .as("产物文件清单应与 fixture 完全一致")
                .containsExactlyInAnyOrderElementsOf(listFixturePaths());

        for (Map.Entry<String, String> entry : actual.entrySet()) {
            String expected = readFixtureFile(entry.getKey());
            assertThat(entry.getValue())
                    .as("文件 %s 内容应与 fixture 逐字节一致", entry.getKey())
                    .isEqualTo(expected);
        }
    }

    private static List<String> listFixturePaths() throws Exception {
        List<String> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(fixtureRoot())) {
            walk.filter(Files::isRegularFile)
                    .map(p -> fixtureRoot().relativize(p).toString().replace('\\', '/'))
                    .forEach(paths::add);
        }
        assertThat(paths).as("fixture 目录不应为空").isNotEmpty();
        return paths;
    }

    private static String readFixtureFile(String relativePath) throws Exception {
        byte[] bytes = Files.readAllBytes(fixtureRoot().resolve(relativePath));
        return normalize(new String(bytes, StandardCharsets.UTF_8));
    }

    private static Path fixtureRoot() {
        // 测试工作目录 = initializer-core/，fixture 在 src/test/resources 下
        return Paths.get("src", "test", "resources").resolve(SNAPSHOT_ROOT);
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

    private static List<String> readZipEntryPaths(byte[] zip) throws Exception {
        return new ArrayList<>(readZipEntries(zip).keySet());
    }

    /** 行尾统一 LF，避免 checkout 配置差异（core.autocrlf）导致快照误报。 */
    private static String normalize(String content) {
        return content.replace("\r\n", "\n");
    }
}
