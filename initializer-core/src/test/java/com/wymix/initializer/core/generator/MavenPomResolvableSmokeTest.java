package com.wymix.initializer.core.generator;

import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.LinkageSpec;
import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成物构建冒烟门禁（验收 P1-1 修复回归）：Maven 产物 pom 必须可解析，
 * 且「内部 starter 等声明了 version 的依赖」必须显式带 &lt;version&gt;——
 * 内部 starter 不在 Spring Boot BOM 管理范围，缺 version 会导致生成项目
 * {@code mvn compile} 直接失败（'dependencies.dependency.version' is missing）。
 *
 * <p>门禁语义：真实 {@code mvn dependency:resolve} 依赖外部仓库且内部 starter 坐标
 * 不存在（com.company 为占位），故以「pom 模型合法性 + 依赖版本完备性」静态校验代替：
 * 声明 version 的坐标在 pom 中必须带 &lt;version&gt;；官方 starter（走 Boot BOM）
 * 不得携带 &lt;version&gt;（版本收敛由 parent BOM 负责）。快照测试只比对文本，
 * 掩盖不了本门禁的结构断言。
 */
class MavenPomResolvableSmokeTest {

    /** 测试用 starter 清单：官方 web（无 version）+ 内部 redis（version 1.2.0），复用主资源。 */
    private static ProjectGenerator generator;

    @BeforeAll
    static void setUp() {
        try (InputStream in = MavenPomResolvableSmokeTest.class.getResourceAsStream("/catalog/metadata.yml")) {
            MetadataCatalog catalog = MetadataCatalog.load(in);
            List<StarterCatalogEntry> entries = new ArrayList<>();
            for (String name : List.of("web-starter.yml", "xxx-redis-spring-boot-starter.yml")) {
                try (InputStream starterIn = MavenPomResolvableSmokeTest.class
                        .getResourceAsStream("/catalog/starters/" + name)) {
                    entries.add(com.wymix.initializer.core.starter.YamlStarterParser.parse(starterIn));
                }
            }
            generator = new ProjectGenerator(catalog, new TemplateRenderer(), StarterCatalog.of(entries));
        } catch (Exception e) {
            throw new IllegalStateException("冒烟门禁测试环境初始化失败", e);
        }
    }

    @Test
    void internalStarterDependencyInPomMustCarryExplicitVersion() throws Exception {
        // P1-1 回归：内部 starter 不在 Boot BOM 中，pom 必须显式输出 <version>
        byte[] zip = generator.generate(mavenRequest(List.of("web", "xxx-redis-spring-boot-starter")));
        String pom = readEntry(zip, "demo-app/pom.xml");

        Map<String, String> deps = parseDependencies(pom);
        assertThat(deps).containsKey("com.company:xxx-redis-spring-boot-starter");
        assertThat(deps.get("com.company:xxx-redis-spring-boot-starter"))
                .as("内部 starter 依赖必须显式带 <version>（不在 Boot BOM 管理范围）")
                .isEqualTo("1.2.0");
    }

    @Test
    void officialStarterDependencyShouldStayBomManagedWithoutVersion() throws Exception {
        // 官方 starter 走 Boot parent BOM，pom 不应硬编码版本（升级 Boot 版本即可联动）
        byte[] zip = generator.generate(mavenRequest(List.of("web", "xxx-redis-spring-boot-starter")));
        Map<String, String> deps = parseDependencies(readEntry(zip, "demo-app/pom.xml"));

        assertThat(deps).containsKey("org.springframework.boot:spring-boot-starter-web");
        assertThat(deps.get("org.springframework.boot:spring-boot-starter-web"))
                .as("官方 starter 不应携带 <version>（由 Boot parent BOM 管理）")
                .isNull();
    }

    @Test
    void generatedPomMustBeWellFormedXml() throws Exception {
        // 产物 pom 必须是合法 XML（模板改动的基本结构门禁）
        byte[] zip = generator.generate(mavenRequest(List.of("web", "xxx-redis-spring-boot-starter")));
        String pom = readEntry(zip, "demo-app/pom.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8)));
        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("project");
    }

    /** 解析 pom 中全部 dependencies 项：键为 G:A，值为 version（未声明为 null）。 */
    private static Map<String, String> parseDependencies(String pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8)));
        NodeList list = doc.getElementsByTagName("dependency");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < list.getLength(); i++) {
            Element dep = (Element) list.item(i);
            String key = textOf(dep, "groupId") + ":" + textOf(dep, "artifactId");
            String version = textOf(dep, "version");
            result.put(key, version.isEmpty() ? null : version);
        }
        return result;
    }

    private static String textOf(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static GenerateRequest mavenRequest(List<String> starters) {
        return new GenerateRequest("com.example", "demo-app", "com.example.demo",
                "17", "3.2.5", starters, BuildTool.MAVEN);
    }

    private static String readEntry(byte[] zipBytes, String entryName) throws Exception {
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("entry not found: " + entryName);
    }
}
