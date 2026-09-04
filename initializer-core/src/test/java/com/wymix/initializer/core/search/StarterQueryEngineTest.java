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
                starters: ["web", "data-jpa", "data-redis", "actuator", "xxx-redis-spring-boot-starter"]
            starters:
              web:
                label: "Spring Web"
                dependency:
                  groupId: org.springframework.boot
                  artifactId: spring-boot-starter-web
              data-jpa:
                label: "Spring Data JPA"
                dependency:
                  groupId: org.springframework.boot
                  artifactId: spring-boot-starter-data-jpa
              data-redis:
                label: "Spring Data Redis"
                dependency:
                  groupId: org.springframework.boot
                  artifactId: spring-boot-starter-data-redis
              actuator:
                label: "Spring Boot Actuator"
                dependency:
                  groupId: org.springframework.boot
                  artifactId: spring-boot-starter-actuator
              xxx-redis-spring-boot-starter:
                label: "内部 Redis 封装 starter"
                dependency:
                  groupId: com.company
                  artifactId: xxx-redis-spring-boot-starter
            """;

    private MetadataCatalog catalog;
    private StarterCatalog starterCatalog;
    private StarterQueryEngine engine;

    @BeforeEach
    void setUp() {
        catalog = MetadataCatalog.load(
                new java.io.ByteArrayInputStream(METADATA_YAML.getBytes(StandardCharsets.UTF_8)));
        // 合成 starter 清单：覆盖 web/data/middleware/tool 四个分类
        starterCatalog = StarterCatalog.of(List.of(
                entry("web", "web", Maintainer.OFFICIAL, "Spring MVC + 内嵌 Tomcat"),
                entry("data-jpa", "data", Maintainer.OFFICIAL, "Spring Data JPA 持久层"),
                entry("data-redis", "data", Maintainer.OFFICIAL, "Spring Data Redis 缓存"),
                entry("xxx-redis-spring-boot-starter", "middleware", Maintainer.INTERNAL,
                        "内部 Redis 封装 starter"),
                entry("actuator", "tool", Maintainer.OFFICIAL, "生产可观测端点")));
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
                .containsExactly("web", "data-jpa", "data-redis", "actuator",
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
        assertThat(item.description()).isEqualTo("生产可观测端点");
        assertThat(item.label()).isEqualTo("Spring Boot Actuator");
    }

    @Test
    void unregisteredEntry_defaultsOfficialWithNullCategory() {
        // starter 仅在 metadata 登记而清单未登记：官方兜底 + 分类为 null；
        // 分类过滤时被排除（无分类信息），关键字过滤仍可用
        MetadataCatalog minimal = MetadataCatalog.load(new java.io.ByteArrayInputStream(
                """
                javaVersions:
                  - id: "17"
                bootVersions:
                  - id: "3.2.5"
                    supportedJavaVersions: ["17"]
                    starters: ["bare-starter"]
                starters:
                  bare-starter:
                    label: "Bare Starter"
                    dependency:
                      groupId: org.springframework.boot
                      artifactId: bare-starter
                """.getBytes(StandardCharsets.UTF_8)));
        List<StarterQueryEngine.StarterSearchItem> items =
                engine.search(minimal, StarterCatalog.of(List.of()), "3.2.5", "bare", null);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).maintainer()).isEqualTo(Maintainer.OFFICIAL);
        assertThat(items.get(0).category()).isNull();
        // 分类过滤：未登记条目无分类信息，被排除
        assertThat(engine.search(minimal, StarterCatalog.of(List.of()), "3.2.5", null, "web"))
                .isEmpty();
    }

    @Test
    void keywordMatchesDescription() {
        // 描述含关键字且 ID/展示名不含 → 命中（设计：名称+描述 contains）
        assertThat(engine.search(catalog, starterCatalog, "3.2.5", "持久层", null))
                .extracting(StarterQueryEngine.StarterSearchItem::id)
                .containsExactly("data-jpa");
    }
}
