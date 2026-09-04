package com.wymix.initializer.app.starter;

import com.wymix.initializer.core.starter.Maintainer;
import com.wymix.initializer.core.starter.StarterCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StarterCatalogConfig} 装载测试：classpath 真实清单可全量装载且顺序确定。
 */
class StarterCatalogConfigTest {

    private final StarterCatalogConfig config = new StarterCatalogConfig();

    @Test
    void starterCatalog_loadsRealClasspathResources() {
        StarterCatalog catalog = config.starterCatalog();
        assertThat(catalog.size()).isGreaterThanOrEqualTo(2);
        assertThat(catalog.contains("web")).isTrue();
        assertThat(catalog.contains("xxx-redis-spring-boot-starter")).isTrue();
        assertThat(catalog.entry("web").maintainer()).isEqualTo(Maintainer.OFFICIAL);
        assertThat(catalog.entry("xxx-redis-spring-boot-starter").maintainer())
                .isEqualTo(Maintainer.INTERNAL);
    }

    @Test
    void starterCatalog_patternCoversClasspath() {
        assertThat(StarterCatalogConfig.STARTER_YML_PATTERN).isEqualTo("classpath*:catalog/starters/*.yml");
    }

    @Test
    void starterCatalog_loadedTwice_isDeterministic() {
        List<String> first = config.starterCatalog().all().stream()
                .map(com.wymix.initializer.core.starter.StarterCatalogEntry::id).toList();
        List<String> second = config.starterCatalog().all().stream()
                .map(com.wymix.initializer.core.starter.StarterCatalogEntry::id).toList();
        assertThat(first).isEqualTo(second);
    }
}
