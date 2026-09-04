package com.wymix.initializer.core.starter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实清单资源装载测试：classpath {@code catalog/starters/*.yml} 全量可装载，
 * 且官方/内部样例齐备（M2.1 验收：统一入清单，标注维护方）。
 */
class RealStarterCatalogResourceTest {

    private static List<StarterCatalogEntry> loadAll() throws IOException {
        List<StarterCatalogEntry> entries = new ArrayList<>();
        // core 模块无 Spring 依赖，此处按文件名清单装载（app 层配置类负责 classpath*: 扫描）
        for (String name : List.of("web-starter.yml", "xxx-redis-spring-boot-starter.yml")) {
            try (InputStream in = RealStarterCatalogResourceTest.class.getClassLoader()
                    .getResourceAsStream("catalog/starters/" + name)) {
                assertThat(in).as("catalog/starters/%s 存在", name).isNotNull();
                entries.add(YamlStarterParser.parse(in));
            }
        }
        return entries;
    }

    @Test
    void realCatalog_officialAndInternalLoaded() throws IOException {
        StarterCatalog catalog = StarterCatalog.of(loadAll());
        assertThat(catalog.size()).isEqualTo(2);
        assertThat(catalog.contains("web")).isTrue();
        assertThat(catalog.contains("xxx-redis-spring-boot-starter")).isTrue();
        assertThat(catalog.entry("web").maintainer()).isEqualTo(Maintainer.OFFICIAL);
        assertThat(catalog.entry("xxx-redis-spring-boot-starter").maintainer())
                .isEqualTo(Maintainer.INTERNAL);
    }

    @Test
    void realCatalog_internalRedisHasFullLinkages() throws IOException {
        StarterCatalog catalog = StarterCatalog.of(loadAll());
        LinkageSpec linkages = catalog.entry("xxx-redis-spring-boot-starter").linkages();
        assertThat(linkages.configPackage()).isEqualTo("{basePackage}.config");
        assertThat(linkages.templates()).hasSize(1);
        assertThat(linkages.ymlFragments()).hasSize(1);
        assertThat(linkages.abstractClasses()).hasSize(1);
        assertThat(linkages.abstractClasses().get(0).alias()).isEqualTo("redisSupport");
    }

    @Test
    void realCatalog_webOfficialHasEmptyLinkages() throws IOException {
        StarterCatalog catalog = StarterCatalog.of(loadAll());
        assertThat(catalog.entry("web").linkages().isEmpty()).isTrue();
        assertThat(catalog.entry("web").dependencies().get(0).artifactId())
                .isEqualTo("spring-boot-starter-web");
    }
}
