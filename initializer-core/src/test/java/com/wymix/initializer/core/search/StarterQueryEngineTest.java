package com.wymix.initializer.core.search;

import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.DependencyCoord;
import com.wymix.initializer.core.starter.LinkageSpec;
import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StarterQueryEngine} 单元测试：内联 metadata 配置 + 合成 starter 清单。
 */
class StarterQueryEngineTest {

    private static final String METADATA_YAML = """
            javaVersions:
              - id: "17"
                label: "Java 17"
                deprecated: false
                recommendedBootVersion: "3.2.5"
            bootVersions:
              - id: "3.2.5"
                label: "Spring Boot 3.2.5"
                official: true
                supportedJavaVersions: ["17"]
            # starters 节已移除：starter 元信息全部由 starter 清单提供，经 merge 联动
            """;

    private MetadataCatalog catalog;
    private StarterCatalog starterCatalog;
    private StarterQueryEngine engine;

    @BeforeEach
    void setUp() {
        // 合成 starter 清单：覆盖 web/data/middleware/tool 四个分类（starter 元信息唯一数据源）
        starterCatalog = StarterCatalog.of(List.of(
                entry("web", "web", Maintainer.OFFICIAL, "Spring MVC + 内嵌 Tomcat"),
                entry("data-jpa", "data", Maintainer.OFFICIAL, "Spring Data JPA 持久层"),
                entry("data-redis", "data", Maintainer.OFFICIAL, "Spring Data Redis 缓存"),
                entry("xxx-redis-spring-boot-starter", "middleware", Maintainer.INTERNAL,
                        "内部 Redis 封装 starter"),
                entry("actuator", "tool", Maintainer.OFFICIAL, "Spring Boot Actuator")));
        // bootVersions[].starters 省略 → merge 后对全部清单条目可选
        catalog = MetadataCatalog.load(
                new java.io.ByteArrayInputStream(METADATA_YAML.getBytes(StandardCharsets.UTF_8)))
                .merge(starterCatalog);
        engine = new StarterQueryEngine();
    }

    private static StarterCatalogEntry entry(String id, String category, Maintainer maintainer,
                                             String description) {
        return new StarterCatalogEntry(id, category, maintainer, description,
                List.of(new DependencyCoord("com.example", id, null)),
                new LinkageSpec(null, List.of(), List.of(), List.of()));
    }

    @Test
    void noFilters_returnsAllBoot325Starters() {
        List<StarterQueryEngine.StarterSearchItem> items =
                engine.search(catalog, starterCatalog, "3.2.5", null, null);
        assertThat(items).extracting(StarterQueryEngine.StarterSearchItem::id)
                .containsExactlyInAnyOrder("web", "data-jpa", "data-redis", "actuator",
                        "xxx-redis-spring-boot-starter");
    }

    @Test
    void keywordMatchesIdCaseInsensitive() {
        List<StarterQueryEngine.StarterSearchItem> items =
                engine.search(catalog, starterCatalog, "3.2.5", "REDIS", null);
        assertThat(items).extracting(StarterQueryEngine.StarterSearchItem::id)
                .containsExactly("data-redis", "xxx-redis-spring-boot-starter");
    }

    @Test
    void keywordMatchesLabel() {
        List<StarterQueryEngine.StarterSearchItem> items =
                engine.search(catalog, starterCatalog, "3.2.5", "Web", null);
        assertThat(items).extracting(StarterQueryEngine.StarterSearchItem::id)
                .containsExactly("web");
    }

    @Test
    void keywordNoMatch_returnsEmpty() {
        assertThat(engine.search(catalog, starterCatalog, "3.2.5", "不存在的关键字", null)).isEmpty();
    }

    @Test
    void blankKeyword_treatedAsNoFilter() {
        assertThat(engine.search(catalog, starterCatalog, "3.2.5", "   ", null))
                .hasSize(catalog.starters("3.2.5").size());
    }

    @Test
    void categoryFilter_web() {
        List<StarterQueryEngine.StarterSearchItem> items =
                engine.search(catalog, starterCatalog, "3.2.5", null, "web");
        assertThat(items).extracting(StarterQueryEngine.StarterSearchItem::id)
                .containsExactly("web");
        assertThat(items.get(0).category()).isEqualTo("web");
    }

    @Test
    void categoryFilter_caseInsensitive() {
        assertThat(engine.search(catalog, starterCatalog, "3.2.5", null, "DATA"))
                .extracting(StarterQueryEngine.StarterSearchItem::id)
                .containsExactly("data-jpa", "data-redis");
    }

    @Test
    void unknownCategory_returnsEmptyNotError() {
        assertThat(engine.search(catalog, starterCatalog, "3.2.5", null, "no-such-category")).isEmpty();
    }

    @Test
    void combinedKeywordAndCategory() {
        List<StarterQueryEngine.StarterSearchItem> items =
                engine.search(catalog, starterCatalog, "3.2.5", "redis", "middleware");
        assertThat(items).extracting(StarterQueryEngine.StarterSearchItem::id)
                .containsExactly("xxx-redis-spring-boot-starter");
        assertThat(items.get(0).maintainer()).isEqualTo(Maintainer.INTERNAL);
    }

    @Test
    void itemCarriesCategoryAndDescription() {
        StarterQueryEngine.StarterSearchItem item =
                engine.search(catalog, starterCatalog, "3.2.5", "actuator", null).get(0);
        assertThat(item.category()).isEqualTo("tool");
        assertThat(item.description()).isEqualTo("Spring Boot Actuator");
        // 展示名取自清单 description（metadata 不再维护 starters 节）
        assertThat(item.label()).isEqualTo("Spring Boot Actuator");
    }

    @Test
    void itemCarriesHiddenFlagFromCatalog() {
        StarterCatalog withHidden = StarterCatalog.of(List.of(
                new StarterCatalogEntry("ghost", "tool", Maintainer.INTERNAL, "隐藏条目",
                        List.of(new DependencyCoord("com.example", "ghost", null)),
                        new LinkageSpec(null, List.of(), List.of(), List.of()),
                        false, List.of(), List.of(), true),
                entry("web", "web", Maintainer.OFFICIAL, "Spring MVC + 内嵌 Tomcat")));
        MetadataCatalog cat = MetadataCatalog.load(
                new java.io.ByteArrayInputStream(METADATA_YAML.getBytes(StandardCharsets.UTF_8)))
                .merge(withHidden);
        assertThat(engine.search(cat, withHidden, "3.2.5", "ghost", null).get(0).hidden()).isTrue();
        // 未声明 hidden 的条目默认 false
        assertThat(engine.search(cat, withHidden, "3.2.5", "web", null).get(0).hidden()).isFalse();
    }

    @Test
    void starterWithoutCatalogDefinition_notVisible() {
        // starter 元信息以清单为唯一来源：bootVersions.starters 列出但清单未登记的 id 查询时不可见
        MetadataCatalog minimal = MetadataCatalog.load(new java.io.ByteArrayInputStream(
                """
                javaVersions:
                  - id: "17"
                bootVersions:
                  - id: "3.2.5"
                    supportedJavaVersions: ["17"]
                    starters: ["bare-starter"]
                """.getBytes(StandardCharsets.UTF_8)));
        assertThat(engine.search(minimal, StarterCatalog.of(List.of()), "3.2.5", null, null)).isEmpty();
        assertThat(engine.search(minimal, StarterCatalog.of(List.of()), "3.2.5", "bare", null)).isEmpty();
    }

    @Test
    void keywordMatchesDescription() {
        // 描述含关键字且 ID/展示名不含 → 命中（设计：名称+描述 contains）
        assertThat(engine.search(catalog, starterCatalog, "3.2.5", "持久层", null))
                .extracting(StarterQueryEngine.StarterSearchItem::id)
                .containsExactly("data-jpa");
    }
}
